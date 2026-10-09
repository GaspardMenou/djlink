package local.djlink;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.data.*;
import org.deepsymmetry.beatlink.dbserver.ConnectionManager;

import javax.sound.midi.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.LongSupplier;

public final class DjLink {
    static final Gson JSON = new GsonBuilder().serializeNulls().create();
    volatile String target = "";
    volatile String sourceGeneration = UUID.randomUUID().toString();
    final Object inputLock = new Object();
    volatile boolean closed;
    volatile boolean lightingMode;
    boolean listenersRegistered;
    static final long STALE_MS = 2000;
    final ConcurrentMap<Integer, CdjStatus> statuses = new ConcurrentHashMap<>();
    final ConcurrentMap<Integer, TrackMetadata> knownMetadata = new ConcurrentHashMap<>();
    record CachedWave(String generation, DataReference reference, Map<String, Object> payload) { }
    record WaveRetry(String generation, DataReference reference, long after) { }
    final Map<Integer, CachedWave> requestedWaves = new HashMap<>();
    final Map<Integer, WaveRetry> waveRetries = new HashMap<>();
    final Object waveformLock = new Object();
    final Function<DataReference, WaveformDetail> waveformFetch;
    final LongSupplier nanoTime;
    final ConcurrentMap<Integer, LoopWindow> loops = new ConcurrentHashMap<>();
    final ConcurrentMap<Integer, Beat> beats = new ConcurrentHashMap<>();
    final ConcurrentMap<Integer, Long> beatTimes = new ConcurrentHashMap<>();
    final Map<Integer, Long> beatNotBefore = new HashMap<>();
    final AtomicLong received = new AtomicLong();
    volatile String lightingError = "";
    volatile String linkMessage = "Recherche du XDJ-AZ…";
    final ConcurrentMap<Integer, NetworkStats> diagnostics = new ConcurrentHashMap<>();
    final ArrayBlockingQueue<Map<String, Object>> networkEvents = new ArrayBlockingQueue<>(1024);
    final AtomicLong captureDroppedPackets = new AtomicLong();
    final Path logDirectory;
    final Object logLock = new Object();
    List<Map<String, Object>> pendingLogBatch = List.of();
    long loggedCaptureDrops, pendingCaptureDrops, lastSummaryAt;
    LocalDate cleanedLogDate;
    volatile long captureUntil;
    volatile String logError = "";
    volatile String outputError = "";
    volatile Settings settings = new Settings();
    final DatagramSocket oscSocket;
    MidiDevice midiDevice;
    Receiver midiReceiver;
    boolean clockRunning;
    long sentClock, sentBeat, sentOsc;

    static class Settings {
        int deck = 0;
        int midi = -1;
        String midiMode = "clock";
        int channel = 1;
        int note = 60;
        boolean osc = false;
        String oscHost = "127.0.0.1";
        int oscPort = 9000;
    }

    DjLink() throws SocketException {
        this(Path.of(System.getProperty("djlink.dataDir", "data")), reference -> {
            WaveformFinder finder = WaveformFinder.getInstance();
            return finder.isRunning() ? finder.requestWaveformDetailFrom(reference) : null;
        }, System::nanoTime);
    }

    DjLink(Path logs, Function<DataReference, WaveformDetail> fetch, LongSupplier time) throws SocketException {
        logDirectory = logs; waveformFetch = fetch; nanoTime = time;
        oscSocket = new DatagramSocket();
    }

    static boolean fresh(DeviceUpdate update, long now) {
        return update != null && now - update.getTimestamp() < STALE_MS * 1_000_000L;
    }

    static boolean validTempo(double bpm) { return Double.isFinite(bpm) && bpm >= 20 && bpm <= 400; }

    void discover() {
        if (!DeviceFinder.getInstance().isRunning()) return;
        List<String> addresses = DeviceFinder.getInstance().getCurrentDevices().stream()
            .filter(d -> d.getDeviceName().equalsIgnoreCase("XDJ-AZ"))
            .map(d -> d.getAddress().getHostAddress()).distinct().sorted().toList();
        String current = target;
        selectTarget(addresses.contains(current) ? current : addresses.isEmpty() ? "" : addresses.get(0));
    }

    void selectTarget(String next) {
        synchronized (inputLock) {
            if (next.equals(target)) return;
            statuses.clear(); diagnostics.clear(); requestedWaves.clear(); waveRetries.clear();
            knownMetadata.clear(); loops.clear(); beats.clear(); beatTimes.clear(); beatNotBefore.clear();
            target = next; sourceGeneration = UUID.randomUUID().toString(); lightingMode = false;
        }
        stopClock();
    }

    static final class NetworkStats {
        long last, count, late, sequenceSkips, sequenceResets, sequence, unitSteps;
        double intervalMs, jitterMs, maxGapMs;
        long lastBeat, beatCount, lateBeats, phaseSkips;
        int previousBeat;
        double beatMaxGapMs;
        synchronized Map<String, Object> observe(long now, long counter) {
            Map<String, Object> event = null;
            if (last > 0) {
                double gap = (now - last) / 1_000_000.0;
                maxGapMs = Math.max(maxGapMs, gap);
                if (gap > Math.max(500, intervalMs * 2.5)) {
                    late++; event = new LinkedHashMap<>(); event.put("event", "status_gap"); event.put("gapMs", Math.round(gap));
                } else {
                    double old = intervalMs == 0 ? gap : intervalMs;
                    jitterMs += .1 * (Math.abs(gap - old) - jitterMs);
                    intervalMs = old + .1 * (gap - old);
                }
                long step = (counter - sequence) & 0xffffffffL;
                if (step == 1) unitSteps++;
                else if (step > 1 && step < 1024 && unitSteps >= 20) {
                    sequenceSkips += step - 1;
                    if (event == null) event = new LinkedHashMap<>();
                    event.put("event", "sequence_gap"); event.put("skipped", step - 1);
                } else if (step >= 1024) { sequenceResets++; unitSteps = 0; }
            }
            last = now; sequence = counter; count++;
            return event;
        }
        synchronized void pause() { lastBeat = 0; previousBeat = 0; }
        synchronized void observeBeat(long now, int beat, long expectedMs, boolean phaseReliable) {
            if (lastBeat > 0 && expectedMs > 0 && expectedMs < 3000) {
                double gap = (now - lastBeat) / 1_000_000.0;
                beatMaxGapMs = Math.max(beatMaxGapMs, gap);
                if (gap > expectedMs * 1.65) {
                    lateBeats++;
                    int steps = (int)Math.round(gap / expectedMs);
                    if (phaseReliable && steps >= 2 && steps <= 4 && ((beat - previousBeat + 4) % 4) == steps % 4) phaseSkips += steps - 1;
                }
            }
            lastBeat = now; previousBeat = beat; beatCount++;
        }
        synchronized Map<String, Object> snapshot(long now) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("statusCount", count); result.put("ageMs", last == 0 ? null : Math.round((now - last) / 1_000_000.0));
            result.put("intervalMs", Math.round(intervalMs)); result.put("jitterMs", Math.round(jitterMs));
            result.put("maxGapMs", Math.round(maxGapMs)); result.put("latePackets", late);
            result.put("sequenceAvailable", unitSteps >= 20); result.put("sequenceSkips", unitSteps >= 20 ? sequenceSkips : null);
            result.put("sequenceResets", sequenceResets);
            result.put("beatCount", beatCount); result.put("beatAgeMs", lastBeat == 0 ? null : Math.round((now - lastBeat) / 1_000_000.0));
            result.put("beatMaxGapMs", Math.round(beatMaxGapMs)); result.put("lateBeats", lateBeats);
            result.put("beatPhaseSkipsEstimate", phaseSkips); return result;
        }
    }

    void capture(DeviceUpdate update, String kind) {
        if (nanoTime.getAsLong() >= captureUntil) return;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", "packet"); record.put("kind", kind); record.put("deck", update.getDeviceNumber());
        record.put("receivedNanos", update.getTimestamp()); record.put("timestamp", System.currentTimeMillis());
        record.put("bytes", HexFormat.of().formatHex(update.getPacketBytes()));
        record.put("beat", update.getBeatWithinBar()); record.put("pitch", update.getPitch());
        if (update instanceof CdjStatus status) {
            record.put("sequence", status.getPacketNumber()); record.put("beatNumber", status.getBeatNumber());
            record.put("playing", status.isPlaying()); record.put("looping", status.isLooping());
            if (TimeFinder.getInstance().isRunning()) record.put("position", TimeFinder.getInstance().getTimeFor(update.getDeviceNumber()));
        }
        if (!networkEvents.offer(record)) captureDroppedPackets.incrementAndGet();
    }

    void diagnosticsTick() {
        diagnosticsTick(LocalDate.now(), nanoTime.getAsLong());
    }

    void pruneLogs(LocalDate today) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(logDirectory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !name.matches("network-\\d{4}-\\d{2}-\\d{2}(?:-previous)?\\.jsonl")) continue;
                LocalDate date;
                try { date = LocalDate.parse(name.substring(8, 18)); }
                catch (java.time.DateTimeException ignored) { continue; }
                if (date.isBefore(today.minusDays(29))) Files.delete(file);
            }
        }
    }

    void diagnosticsTick(LocalDate today, long now) {
        synchronized (logLock) {
            try {
                Files.createDirectories(logDirectory);
                String cleanupError = "";
                if (!today.equals(cleanedLogDate)) {
                    try { pruneLogs(today); cleanedLogDate = today; }
                    catch (IOException e) { cleanupError = "Nettoyage des logs : " + e.getMessage(); }
                }
                if (pendingLogBatch.isEmpty()) {
                    List<Map<String, Object>> entries = new ArrayList<>(); networkEvents.drainTo(entries);
                    pendingCaptureDrops = captureDroppedPackets.get();
                    if (pendingCaptureDrops > loggedCaptureDrops) entries.add(Map.of("event", "capture_drop",
                        "dropped", pendingCaptureDrops - loggedCaptureDrops, "total", pendingCaptureDrops,
                        "timestamp", System.currentTimeMillis()));
                    if (lastSummaryAt == 0 || now - lastSummaryAt >= 5_000_000_000L) {
                        lastSummaryAt = now;
                        for (int number = 1; number <= 4; number++) {
                            NetworkStats stats = diagnostics.get(number); if (stats == null) continue;
                            Map<String, Object> record = new LinkedHashMap<>(stats.snapshot(now));
                            record.put("event", "summary"); record.put("deck", number);
                            record.put("timestamp", System.currentTimeMillis()); entries.add(record);
                        }
                    }
                    pendingLogBatch = entries;
                }
                if (!pendingLogBatch.isEmpty()) {
                    Path log = logDirectory.resolve("network-" + today + ".jsonl");
                    if (Files.exists(log) && Files.size(log) > 10_000_000)
                        Files.move(log, logDirectory.resolve("network-" + today + "-previous.jsonl"), StandardCopyOption.REPLACE_EXISTING);
                    Files.writeString(log, pendingLogBatch.stream().map(JSON::toJson)
                        .collect(java.util.stream.Collectors.joining("\n", "", "\n")),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    loggedCaptureDrops = pendingCaptureDrops;
                    pendingLogBatch = List.of();
                }
                logError = cleanupError;
            } catch (IOException e) { logError = e.getMessage(); }
        }
    }

    // ponytail: whole-beat bounds inferred from two matching wraps; sub-beat/manual loops need exact hardware bounds.
    static final class LoopWindow {
        String track = "";
        int previous, maximum, candidateStart, candidateEnd, startBeat, endBeat;
        synchronized void observe(String key, boolean active, boolean reverse, int beat) {
            if (!key.equals(track) || !active || reverse || beat <= 0 || beat == 0xffff) {
                track = key; previous = maximum = candidateStart = candidateEnd = startBeat = endBeat = 0;
                return;
            }
            if (previous > 0 && beat > previous + 4) {
                candidateStart = candidateEnd = startBeat = endBeat = 0; maximum = beat;
            }
            if (previous > beat) {
                int end = maximum + 1;
                if (beat == candidateStart && end == candidateEnd) { startBeat = beat; endBeat = end; }
                else { startBeat = endBeat = 0; candidateStart = beat; candidateEnd = end; }
                maximum = beat;
            } else maximum = Math.max(maximum, beat);
            previous = beat;
        }
        synchronized boolean wholeBars() { return endBeat > startBeat && (endBeat - startBeat) % 4 == 0; }
        synchronized long[] bounds(BeatGrid grid) {
            if (grid == null || startBeat <= 0 || endBeat <= startBeat || endBeat > grid.beatCount) return null;
            return new long[]{grid.getTimeWithinTrack(startBeat), grid.getTimeWithinTrack(endBeat), endBeat - startBeat};
        }
    }

    static long wrapPosition(long position, long start, long end) {
        return end > start ? start + Math.floorMod(position - start, end - start) : position;
    }

    Map<String, Object> deck(int number, long now) {
        synchronized (inputLock) { return deckSnapshot(number, now); }
    }

    private Map<String, Object> deckSnapshot(int number, long now) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("number", number);
        NetworkStats stats = diagnostics.get(number);
        d.put("network", stats == null ? null : stats.snapshot(now));
        CdjStatus status = statuses.get(number);
        Beat beat = beats.get(number);
        boolean statusFresh = fresh(status, now);
        boolean beatFresh = fresh(beat, now);
        boolean online = DeviceFinder.getInstance().isRunning() && DeviceFinder.getInstance().getCurrentDevices().stream()
            .anyMatch(a -> a.getDeviceNumber() == number && a.getAddress().getHostAddress().equals(target));
        d.put("online", online);
        d.put("statusAvailable", statusFresh);
        d.put("master", statusFresh && status.isTempoMaster());
        d.put("onAir", statusFresh ? status.isOnAir() : null);
        // A beat alone does not prove the fader is open or the deck is playing.
        d.put("playing", statusFresh ? status.isPlaying() : null);
        d.put("ended", statusFresh && status.isAtEnd());
        d.put("synced", statusFresh ? status.isSynced() : null);
        double bpm = tempo(status, beat, now);
        d.put("bpm", validTempo(bpm) ? bpm : null);
        d.put("beat", beatFresh ? Integer.valueOf(beat.getBeatWithinBar()) : statusFresh ? Integer.valueOf(status.getBeatWithinBar()) : null);
        d.put("beatAt", beatFresh ? beat.getTimestamp() : null);
        d.put("trackId", statusFresh ? Integer.toUnsignedLong(status.getRekordboxId()) : null);
        d.put("trackType", statusFresh ? status.getTrackType().toString() : null);
        d.put("source", statusFresh ? status.getTrackSourceSlot().toString() : null);
        d.put("sourcePlayer", statusFresh ? status.getTrackSourcePlayer() : null);
        d.put("pitch", statusFresh ? Util.pitchToPercentage(status.getPitch()) : null);
        d.put("looping", statusFresh ? status.isLooping() : null);
        long[] loop = null;
        boolean inferred = false;
        if (statusFresh && status.isLooping()) {
            if (status.canReportLooping() && status.getLoopEnd() > status.getLoopStart()) {
                loop = new long[]{status.getLoopStart(), status.getLoopEnd(), status.getActiveLoopBeats()};
            } else if (BeatGridFinder.getInstance().isRunning()) {
                BeatGrid grid = BeatGridFinder.getInstance().getLatestBeatGridFor(status);
                LoopWindow window = loops.get(number);
                if (window != null) loop = window.bounds(grid);
                inferred = loop != null;
            }
        }
        d.put("loopStart", loop == null ? null : loop[0]);
        d.put("loopEnd", loop == null ? null : loop[1]);
        d.put("loopBeats", loop == null || loop[2] <= 0 ? null : loop[2]);
        d.put("loopInferred", inferred);
        d.put("reverse", statusFresh && status.isPlayingBackwards());
        d.put("beatNumber", statusFresh ? status.getBeatNumber() : null);
        d.put("position", null);
        d.put("positionPrecise", false);
        if (statusFresh && TimeFinder.getInstance().isRunning()) {
            TrackPositionUpdate p = TimeFinder.getInstance().getLatestPositionFor(number);
            if (p != null && now - p.timestamp < STALE_MS * 1_000_000L) {
                long position = TimeFinder.getInstance().getTimeFor(number);
                if (position >= 0) d.put("position", loop == null ? position : wrapPosition(position, loop[0], loop[1]));
                d.put("reverse", p.reverse);
                d.put("positionPrecise", p.precise);
            }
        }
        d.put("title", null);
        d.put("artist", null);
        d.put("duration", null);
        d.put("key", null);
        d.put("cues", List.of());
        if (statusFresh) {
            TrackMetadata latest = MetadataFinder.getInstance().isRunning() ? MetadataFinder.getInstance().getLatestMetadataFor(number) : null;
            TrackMetadata m = metadataFor(status, latest, knownMetadata.get(number));
            if (m != null) {
                d.put("title", m.getTitle());
                d.put("artist", m.getArtist() == null ? null : m.getArtist().label);
                d.put("duration", m.getDuration());
                d.put("key", m.getKey() == null ? null : m.getKey().label);
                if (m.getCueList() != null) d.put("cues", m.getCueList().entries.stream().map(c -> {
                    Map<String, Object> cue = new LinkedHashMap<>();
                    cue.put("hotCue", c.hotCueNumber); cue.put("time", c.cueTime);
                    cue.put("loopEnd", c.isLoop ? c.loopTime : null); cue.put("label", c.comment);
                    return cue;
                }).toList());
            }
        }
        return d;
    }

    static boolean matchesMetadata(CdjStatus status, TrackMetadata metadata) {
        return status != null && status.getRekordboxId() != 0 && metadata != null
            && metadata.getTitle() != null && !metadata.getTitle().isBlank()
            && metadata.trackReference.rekordboxId == status.getRekordboxId()
            && metadata.trackReference.player == status.getTrackSourcePlayer()
            && metadata.trackReference.slot == status.getTrackSourceSlot()
            && metadata.trackType == status.getTrackType();
    }

    static TrackMetadata metadataFor(CdjStatus status, TrackMetadata latest, TrackMetadata known) {
        return matchesMetadata(status, latest) ? latest : matchesMetadata(status, known) ? known : null;
    }

    void metadataTick() {
        MetadataFinder finder = MetadataFinder.getInstance();
        if (!finder.isRunning()) return;
        for (int number = 1; number <= 4; number++) {
            CdjStatus status; String generation;
            synchronized (inputLock) { status = statuses.get(number); generation = sourceGeneration; }
            if (!fresh(status, nanoTime.getAsLong()) || status.getRekordboxId() == 0) continue;
            try {
                TrackMetadata metadata = finder.getLatestMetadataFor(number);
                if (!matchesMetadata(status, metadata)
                    && ConnectionManager.getInstance().getPlayerDBServerPort(status.getTrackSourcePlayer()) > 0) {
                    // Empty replies are cached by Beat Link; retry on this worker, never on the UDP or HTTP thread.
                    metadata = finder.requestMetadataFrom(status);
                }
                synchronized (inputLock) {
                    if (generation.equals(sourceGeneration) && matchesMetadata(statuses.get(number), metadata))
                        knownMetadata.put(number, metadata);
                }
            } catch (Exception e) {
                networkEvents.offer(Map.of("event", "metadata_retry", "deck", number,
                    "timestamp", System.currentTimeMillis(), "error", e.getClass().getSimpleName()));
            }
        }
    }

    static DataReference trackReference(CdjStatus status) {
        return status == null || status.getRekordboxId() == 0 ? null : new DataReference(
            status.getTrackSourcePlayer(), status.getTrackSourceSlot(), status.getRekordboxId(), status.getTrackType());
    }

    static double tempo(CdjStatus status, Beat beat, long now) {
        double bpm = fresh(beat, now) ? beat.getEffectiveTempo() : fresh(status, now) ? status.getEffectiveTempo() : 0;
        return validTempo(bpm) ? bpm : 0;
    }

    int selectedDeck(long now) {
        synchronized (inputLock) { return selectedDeck(settings.deck, now); }
    }

    private int selectedDeck(int configured, long now) {
        if (configured > 0) return configured;
        return statuses.entrySet().stream()
            .filter(e -> fresh(e.getValue(), now) && e.getValue().isTempoMaster())
            .map(Map.Entry::getKey).min(Integer::compareTo).orElse(0);
    }

    record OutputSource(int deck, DataReference track, String generation, double bpm, long beatTime) { }

    OutputSource outputSource(long now, Settings cfg) {
        synchronized (inputLock) {
            int n = selectedDeck(cfg.deck, now);
            CdjStatus status = statuses.get(n);
            DataReference track = trackReference(status);
            double bpm = fresh(status, now) && status.isPlaying() && track != null ? tempo(status, beats.get(n), now) : 0;
            return new OutputSource(n, track, sourceGeneration, bpm, beatTimes.getOrDefault(n, 0L));
        }
    }

    double outputTempo(long now) { return outputSource(now, settings).bpm(); }

    Map<String, Object> state() {
        discover();
        long now = nanoTime.getAsLong();
        Settings cfg = settings;
        Map<String, Object> s = new LinkedHashMap<>();
        List<Map<String, Object>> decks = new ArrayList<>();
        synchronized (inputLock) {
            for (int i = 1; i <= 4; i++) decks.add(deckSnapshot(i, now));
            s.put("target", target);
            s.put("sourceGeneration", sourceGeneration);
            s.put("lightingMode", lightingMode);
            s.put("now", System.currentTimeMillis());
            s.put("decks", decks);
            OutputSource source = outputSource(now, cfg);
            s.put("selectedDeck", source.deck());
            s.put("outputBpm", source.bpm() > 0 ? source.bpm() : null);
        }
        s.put("connected", decks.stream().anyMatch(d -> Boolean.TRUE.equals(d.get("statusAvailable"))));
        s.put("linkMessage", linkMessage);
        s.put("lightingError", lightingError);
        s.put("received", received.get());
        s.put("settings", cfg);
        synchronized (this) {
            s.put("sentClock", sentClock); s.put("sentBeat", sentBeat); s.put("sentOsc", sentOsc);
            s.put("midiActive", midiReceiver != null);
            s.put("clockRunning", clockRunning);
        }
        s.put("outputError", outputError);
        s.put("logDirectory", logDirectory.toAbsolutePath().toString());
        s.put("logError", logError);
        s.put("captureRemaining", Math.max(0, (captureUntil - nanoTime.getAsLong()) / 1_000_000_000L));
        s.put("captureDroppedPackets", captureDroppedPackets.get());
        return s;
    }

    void acceptStatus(CdjStatus cdj) {
        if (!cdj.getAddress().getHostAddress().equals(target) || cdj.getDeviceNumber() < 1 || cdj.getDeviceNumber() > 4) return;
        capture(cdj, "status");
        synchronized (inputLock) {
            if (!cdj.getAddress().getHostAddress().equals(target)) return;
            int n = cdj.getDeviceNumber();
            boolean lighting = cdj.getPacketBytes()[10] == 0x10;
            if (lighting) lightingMode = true;
            if (lightingMode && !lighting) return;
            NetworkStats stats = diagnostics.computeIfAbsent(n, key -> new NetworkStats());
            Map<String, Object> event = stats.observe(cdj.getTimestamp(), cdj.getPacketNumber());
            if (!cdj.isPlaying()) stats.pause();
            if (event != null) {
                event.put("deck", n); event.put("timestamp", System.currentTimeMillis()); networkEvents.offer(event);
            }
            CdjStatus previous = statuses.get(n);
            DataReference reference = trackReference(cdj);
            if (reference == null || previous == null || !Objects.equals(reference, trackReference(previous))) {
                beats.remove(n); beatTimes.remove(n); beatNotBefore.put(n, cdj.getTimestamp());
                knownMetadata.remove(n); requestedWaves.remove(n); waveRetries.remove(n); loops.remove(n);
            }
            statuses.put(n, cdj);
            LoopWindow window = loops.computeIfAbsent(n, key -> new LoopWindow());
            String key = Objects.toString(reference, "");
            if (previous != null && !fresh(previous, cdj.getTimestamp())) window.observe(key, false, false, 0);
            window.observe(key, cdj.isLooping(), cdj.isPlayingBackwards(), cdj.getBeatNumber());
            received.incrementAndGet();
        }
    }

    static long beatInterval(Beat beat) {
        return validTempo(beat.getEffectiveTempo()) ? Math.round(60_000.0 / beat.getEffectiveTempo()) : 0;
    }

    void acceptBeat(Beat beat) {
        int n = beat.getDeviceNumber();
        if (!beat.getAddress().getHostAddress().equals(target) || n < 1 || n > 4) return;
        capture(beat, "beat");
        synchronized (inputLock) {
            if (!beat.getAddress().getHostAddress().equals(target)
                || beat.getTimestamp() < beatNotBefore.getOrDefault(n, Long.MIN_VALUE)) return;
            CdjStatus source = statuses.get(n);
            LoopWindow loop = loops.get(n);
            boolean phaseReliable = source != null && (!source.isLooping()
                || (source.canReportLooping() && source.getActiveLoopBeats() > 0 && source.getActiveLoopBeats() % 4 == 0)
                || (loop != null && loop.wholeBars()));
            diagnostics.computeIfAbsent(n, key -> new NetworkStats())
                .observeBeat(beat.getTimestamp(), beat.getBeatWithinBar(), beatInterval(beat), phaseReliable);
            beats.put(n, beat); beatTimes.put(n, beat.getTimestamp()); received.incrementAndGet();
        }
        synchronized (this) {
            OutputSource source = outputSource(nanoTime.getAsLong(), settings);
            if (n != source.deck() || source.bpm() <= 0) return;
            if (midiReceiver != null && !settings.midiMode.equals("clock")) {
                midi(ShortMessage.NOTE_ON, settings.channel - 1, settings.note, 127);
                midi(ShortMessage.NOTE_OFF, settings.channel - 1, settings.note, 0); sentBeat++;
            }
        }
        if (settings.osc) sendOsc("/djlink/beat", beat.getBeatWithinBar());
    }

    void connect() {
        try {
            DeviceFinder.getInstance().start();
            while (target.isEmpty()) { discover(); Thread.sleep(250); }
            if (!listenersRegistered) {
            MetadataFinder.getInstance().addTrackMetadataListener(update -> {
                synchronized (inputLock) {
                    if (matchesMetadata(statuses.get(update.player), update.metadata)) knownMetadata.put(update.player, update.metadata);
                }
            });
            BeatFinder.getInstance().addBeatListener(this::acceptBeat);
            VirtualCdj.getInstance().setDeviceName("DJ Link Monitor");
            Set<Integer> occupied = new HashSet<>();
            DeviceFinder.getInstance().getCurrentDevices().forEach(d -> occupied.add(d.getDeviceNumber()));
            if (!occupied.contains(5)) VirtualCdj.getInstance().setDeviceNumber((byte)5);
            else if (!occupied.contains(6)) VirtualCdj.getInstance().setDeviceNumber((byte)6);
            VirtualCdj.getInstance().addUpdateListener(update -> {
                if (update instanceof CdjStatus cdj) acceptStatus(cdj);
            });
            listenersRegistered = true;
            }
            BeatFinder.getInstance().start();
            while (true) {
                linkMessage = "Connexion PRO DJ LINK…";
                if (VirtualCdj.getInstance().start()) {
                    MetadataFinder.getInstance().start();
                    // AZ IDs are Device Library Plus IDs. DeviceSQL/NFS lookup would show unrelated tracks.
                    WaveformFinder.getInstance().setFindDetails(true);
                    WaveformFinder.getInstance().setPreferredStyle(WaveformFinder.WaveformStyle.RGB);
                    WaveformFinder.getInstance().start();
                    TimeFinder.getInstance().start();
                    Thread lighting = new Thread(this::lightingLoop, "az-lighting"); lighting.setDaemon(true); lighting.start();
                    linkMessage = "Écoute PRO DJ LINK active";
                    break;
                }
                linkMessage = "Aucune annonce reçue. Vérifie le réseau et le mode PRO DJ LINK.";
                Thread.sleep(3000);
            }
        } catch (Exception e) {
            if (VirtualCdj.getInstance().isRunning()) VirtualCdj.getInstance().stop();
            linkMessage = "Connexion impossible : " + e.getMessage()
                + ". Libère les ports 50000–50002 ; nouvelle tentative automatique dans 3 secondes.";
            System.err.println(linkMessage);
        }
    }

    void lightingLoop() {
        // Rekordbox Lighting handshake from Beat Link 8.0.0 VirtualRekordbox (EPL-2.0).
        try (DatagramSocket sender = new DatagramSocket(new InetSocketAddress(VirtualCdj.getInstance().getLocalAddress(), 0))) {
            sender.setBroadcast(true);
            int id = 0x17;
            Set<Integer> used = new HashSet<>();
            DeviceFinder.getInstance().getCurrentDevices().forEach(d -> used.add(d.getDeviceNumber()));
            while (used.contains(id) && id < 0x27) id++;
            if (used.contains(id)) throw new IOException("Aucun identifiant Lighting disponible.");
            byte[] hello = HexFormat.of().parseHex("5173707431576d4a4f4c060072656b6f7264626f780000000000000000000000010300361701183eefda5bcac0a8020b040100000408");
            hello[36] = (byte)id;
            NetworkInterface iface = NetworkInterface.getByInetAddress(VirtualCdj.getInstance().getLocalAddress());
            System.arraycopy(iface.getHardwareAddress(), 0, hello, 38, 6);
            System.arraycopy(VirtualCdj.getInstance().getLocalAddress().getAddress(), 0, hello, 44, 4);
            byte[] request = new byte[296];
            byte[] prefix = HexFormat.of().parseHex("5173707431576d4a4f4c1172656b6f7264626f780000000000000000000000010117010417010000006d006100630062006f006f006b002000700072006f0000");
            System.arraycopy(prefix, 0, request, 0, prefix.length);
            request[33] = (byte)id; request[36] = (byte)id;
            while (VirtualCdj.getInstance().isRunning()) {
                if (!target.isEmpty()) {
                    try {
                        sender.send(new DatagramPacket(hello, hello.length, VirtualCdj.getInstance().getBroadcastAddress(), 50000));
                        sender.send(new DatagramPacket(request, request.length, InetAddress.getByName(target), 50002));
                        lightingError = "";
                    } catch (IOException e) {
                        String error = Objects.toString(e.getMessage(), e.getClass().getSimpleName());
                        if (!error.equals(lightingError)) networkEvents.offer(Map.of("event", "lighting_io", "error", error, "timestamp", System.currentTimeMillis()));
                        lightingError = error;
                    }
                }
                Thread.sleep(1500);
            }
        } catch (Exception e) { lightingError = "Initialisation Lighting : " + e.getMessage(); }
    }

    List<Map<String, Object>> midiPorts() {
        List<Map<String, Object>> result = new ArrayList<>();
        MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
        for (int i = 0; i < infos.length; i++) {
            try {
                MidiDevice d = MidiSystem.getMidiDevice(infos[i]);
                if (d.getMaxReceivers() != 0 && !(d instanceof Synthesizer) && !(d instanceof Sequencer))
                    result.add(Map.of("id", i, "name", infos[i].getName(), "description", infos[i].getDescription()));
            } catch (MidiUnavailableException ignored) { }
        }
        return result;
    }

    synchronized void configure(Settings next) throws Exception {
        if (next == null || next.deck < 0 || next.deck > 4 || next.channel < 1 || next.channel > 16
            || next.note < 0 || next.note > 127 || next.midiMode == null || !Set.of("clock", "tap", "both").contains(next.midiMode)
            || next.oscPort < 1 || next.oscPort > 65535) throw new IllegalArgumentException("Paramètres invalides.");
        if (next.oscHost == null || !next.oscHost.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))
            throw new IllegalArgumentException("L’adresse OSC doit être une IPv4.");
        for (String part : next.oscHost.split("\\.")) if (Integer.parseInt(part) > 255)
            throw new IllegalArgumentException("Adresse OSC invalide.");
        boolean validPort = next.midi == -1 || midiPorts().stream().anyMatch(p -> p.get("id").equals(next.midi));
        if (!validPort) throw new IllegalArgumentException("Le port MIDI n’est plus disponible. Actualise la liste.");
        if (next.midi != settings.midi || (next.midi >= 0 && midiReceiver == null)) {
            MidiDevice replacement = null;
            Receiver receiver = null;
            if (next.midi >= 0) {
                replacement = MidiSystem.getMidiDevice(MidiSystem.getMidiDeviceInfo()[next.midi]);
                try { replacement.open(); receiver = replacement.getReceiver(); }
                catch (Exception e) { replacement.close(); throw e; }
            }
            stopClock();
            if (midiReceiver != null) midiReceiver.close();
            if (midiDevice != null) midiDevice.close();
            midiDevice = replacement; midiReceiver = receiver;
        }
        if (!next.midiMode.equals(settings.midiMode) || next.deck != settings.deck) stopClock();
        settings = next;
        outputError = "";
    }

    synchronized void midi(int command, int channel, int data1, int data2) {
        if (midiReceiver == null) return;
        try {
            ShortMessage m = new ShortMessage();
            if (command >= 0xf0) m.setMessage(command);
            else m.setMessage(command, channel, data1, data2);
            midiReceiver.send(m, -1);
        } catch (Exception e) { outputError = "MIDI : " + e.getMessage(); }
    }

    synchronized void stopClock() {
        if (clockRunning) midi(ShortMessage.STOP, 0, 0, 0);
        clockRunning = false;
    }

    synchronized long clockStep(ClockSchedule schedule, long now) {
        OutputSource source = outputSource(now, settings);
        if (source.bpm() <= 0 || midiReceiver == null || settings.midiMode.equals("tap")) {
            stopClock(); schedule.reset(); return 0;
        }
        OutputSource previous = schedule.source;
        if (!clockRunning || previous == null || source.deck() != previous.deck()
            || !source.generation().equals(previous.generation()) || !Objects.equals(source.track(), previous.track())) {
            stopClock(); schedule.reset(); midi(ShortMessage.START, 0, 0, 0); clockRunning = true;
        }
        schedule.source = source;
        if (schedule.due(now, source.bpm(), source.beatTime())) {
            midi(ShortMessage.TIMING_CLOCK, 0, 0, 0); sentClock++;
        }
        return schedule.next;
    }

    void clockLoop() {
        ClockSchedule schedule = new ClockSchedule();
        while (!closed && !Thread.currentThread().isInterrupted()) {
            long next = clockStep(schedule, nanoTime.getAsLong());
            LockSupport.parkNanos(next == 0 ? 5_000_000 : Math.min(5_000_000, Math.max(100_000, next - nanoTime.getAsLong())));
        }
    }

    static byte[] oscMessage(String address, Number value) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (String s : List.of(address, value instanceof Integer ? ",i" : ",f")) {
            byte[] data = s.getBytes(StandardCharsets.UTF_8);
            b.writeBytes(data);
            for (int i = 0; i < 4 - data.length % 4; i++) b.write(0);
        }
        b.writeBytes(value instanceof Integer ? ByteBuffer.allocate(4).putInt(value.intValue()).array()
            : ByteBuffer.allocate(4).putFloat(value.floatValue()).array());
        return b.toByteArray();
    }

    void sendOsc(String address, Number value) {
        Settings cfg = settings;
        if (!cfg.osc) return;
        try {
            byte[] data = oscMessage(address, value);
            oscSocket.send(new DatagramPacket(data, data.length, InetAddress.getByName(cfg.oscHost), cfg.oscPort));
            synchronized (this) { sentOsc++; }
        } catch (Exception e) { outputError = "OSC : " + e.getMessage(); }
    }

    void oscTick() {
        discover();
        if (!settings.osc) return;
        OutputSource source = outputSource(nanoTime.getAsLong(), settings);
        sendOsc("/djlink/playing", source.bpm() > 0 ? 1 : 0);
        sendOsc("/djlink/bpm", (float) source.bpm());
        sendOsc("/djlink/deck", source.deck());
    }

    void waveformTick() {
        synchronized (waveformLock) {
            if (closed) return;
            int number = 0; DataReference reference = null; String generation = "";
            synchronized (inputLock) {
                long now = nanoTime.getAsLong();
                for (int n = 1; n <= 4; n++) {
                    CdjStatus status = statuses.get(n);
                    DataReference track = trackReference(status);
                    if (!fresh(status, now) || track == null) continue;
                    CachedWave cache = requestedWaves.get(n);
                    if (cache != null && cache.generation().equals(sourceGeneration) && cache.reference().equals(track)) continue;
                    WaveRetry retry = waveRetries.get(n);
                    if (retry != null && retry.generation().equals(sourceGeneration) && retry.reference().equals(track) && now < retry.after()) continue;
                    number = n; reference = track; generation = sourceGeneration; break;
                }
            }
            if (number == 0) return;
            Map<String, Object> payload = null;
            try {
                WaveformDetail wave = waveformFetch.apply(reference);
                if (wave != null && wave.dataReference.equals(reference)) payload = wavePayload(wave, generation);
            } catch (RuntimeException e) {
                networkEvents.offer(Map.of("event", "waveform_retry", "deck", number,
                    "timestamp", System.currentTimeMillis(), "error", e.getClass().getSimpleName()));
            }
            synchronized (inputLock) {
                if (closed || !generation.equals(sourceGeneration) || !reference.equals(trackReference(statuses.get(number)))) return;
                if (payload != null) {
                    requestedWaves.put(number, new CachedWave(generation, reference, payload)); waveRetries.remove(number);
                } else waveRetries.put(number, new WaveRetry(generation, reference, nanoTime.getAsLong() + 3_000_000_000L));
            }
        }
    }

    Map<String, Object> waveform(int number) {
        synchronized (inputLock) {
            CdjStatus status = statuses.get(number);
            CachedWave wave = requestedWaves.get(number);
            if (fresh(status, nanoTime.getAsLong()) && wave != null && wave.generation().equals(sourceGeneration)
                && wave.reference().equals(trackReference(status))) return wave.payload();
            return Map.of("available", false, "sourceGeneration", sourceGeneration);
        }
    }

    static Map<String, Object> wavePayload(WaveformDetail w, String generation) {
        Map<String, Object> result = new LinkedHashMap<>();
        int frames = w.getFrameCount();
        List<int[]> samples = new ArrayList<>();
        // ponytail: cap the browser payload at 12,000 columns; request smaller windows if finer zoom is needed.
        int scale = Math.max(1, (int) Math.ceil(frames / 12000.0));
        for (int i = 0; i < frames; i += scale) {
            int height = 0;
            for (int j = i; j < Math.min(frames, i + scale); j++) {
                int h = w.style == WaveformFinder.WaveformStyle.THREE_BAND
                    ? w.segmentHeight(j, 1, WaveformFinder.ThreeBandLayer.LOW) : w.segmentHeight(j, 1);
                height = Math.max(height, h);
            }
            java.awt.Color color = w.segmentColor(i, 1);
            samples.add(new int[]{height, color.getRed(), color.getGreen(), color.getBlue()});
        }
        result.put("available", true); result.put("sourceGeneration", generation);
        result.put("trackId", Integer.toUnsignedLong(w.dataReference.rekordboxId));
        result.put("trackType", w.dataReference.trackType.toString());
        result.put("sourcePlayer", w.dataReference.player); result.put("sourceSlot", w.dataReference.slot.toString());
        result.put("duration", w.getTotalTime()); result.put("samples", samples);
        return Collections.unmodifiableMap(result);
    }

    static void reply(HttpExchange e, int code, String type, byte[] bytes) throws IOException {
        e.getResponseHeaders().set("Content-Type", type);
        e.getResponseHeaders().set("Cache-Control", "no-store");
        e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        e.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = e.getResponseBody()) { out.write(bytes); }
    }

    static void json(HttpExchange e, int code, Object value) throws IOException {
        reply(e, code, "application/json; charset=utf-8", JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    void http(HttpExchange e) throws IOException {
        try {
            String path = e.getRequestURI().getPath();
            if (e.getRequestMethod().equals("POST") && (path.equals("/api/settings") || path.equals("/api/capture"))) {
                String origin = e.getRequestHeaders().getFirst("Origin");
                String host = e.getRequestHeaders().getFirst("Host");
                if (!e.getRemoteAddress().getAddress().isLoopbackAddress() || host == null
                    || !(host.equals("localhost:8080") || host.equals("127.0.0.1:8080"))
                    || (origin != null && !origin.equals("http://" + host))) {
                    json(e, 403, Map.of("error", "Modifie les sorties depuis http://localhost:8080 sur le Mac qui héberge DJ Link."));
                    return;
                }
                if (!"application/json".equals(e.getRequestHeaders().getFirst("Content-Type"))) {
                    json(e, 415, Map.of("error", "Content-Type application/json requis.")); return;
                }
                byte[] body = e.getRequestBody().readNBytes(4097);
                if (body.length > 4096) { json(e, 413, Map.of("error", "Requête trop grande.")); return; }
                if (path.equals("/api/capture")) {
                    com.google.gson.JsonObject request = JSON.fromJson(new String(body, StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
                    int seconds = request.get("seconds").getAsInt();
                    if (seconds < 5 || seconds > 60) throw new IllegalArgumentException("Capture : 5 à 60 secondes.");
                    captureUntil = nanoTime.getAsLong() + seconds * 1_000_000_000L;
                    json(e, 200, Map.of("seconds", seconds)); return;
                }
                configure(JSON.fromJson(new String(body, StandardCharsets.UTF_8), Settings.class));
                json(e, 200, state()); return;
            }
            if (!e.getRequestMethod().equals("GET")) { json(e, 405, Map.of("error", "Méthode non autorisée.")); return; }
            if (path.matches("/api/waveform/[1-4]")) { json(e, 200, waveform(Integer.parseInt(path.substring(path.length() - 1)))); return; }
            switch (path) {
                case "/api/state" -> {
                    Map<String, Object> data = state();
                    if (!e.getRemoteAddress().getAddress().isLoopbackAddress()) { data.remove("logDirectory"); data.remove("logError"); data.remove("captureRemaining"); data.remove("captureDroppedPackets"); }
                    json(e, 200, data);
                }
                case "/api/midi" -> json(e, 200, midiPorts());
                case "/api/bpm" -> {
                    long now = nanoTime.getAsLong(); OutputSource source = outputSource(now, settings); double bpm = source.bpm();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("bpm", bpm > 0 ? bpm : null);
                    data.put("playing", bpm > 0); data.put("deck", source.deck()); data.put("timestamp", System.currentTimeMillis());
                    json(e, 200, data);
                }
                case "/", "/app.js", "/motion.js", "/waves.js", "/style.css" -> {
                    String asset = path.equals("/") ? "index.html" : path.substring(1);
                    try (InputStream in = getClass().getResourceAsStream("/web/" + asset)) {
                        if (in == null) { json(e, 404, Map.of("error", "Fichier absent.")); return; }
                        String type = asset.endsWith("js") ? "application/javascript" : asset.endsWith("css") ? "text/css" : "text/html";
                        reply(e, 200, type + "; charset=utf-8", in.readAllBytes());
                    }
                }
                default -> json(e, 404, Map.of("error", "Page introuvable."));
            }
        } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", "Paramètres invalides : " + ex.getMessage())); }
        catch (Exception ex) { json(e, 500, Map.of("error", "Impossible d’appliquer : " + ex.getMessage())); }
        finally { e.close(); }
    }

    public static void main(String[] args) throws Exception {
        DjLink app = new DjLink();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);
        server.createContext("/", app::http);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        Thread connection = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                app.connect();
                try {
                    while (VirtualCdj.getInstance().isRunning()) Thread.sleep(1000);
                    Thread.sleep(3000);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "pro-dj-link"); connection.setDaemon(true); connection.start();
        Thread clock = new Thread(app::clockLoop, "midi-clock"); clock.setDaemon(true); clock.setPriority(Thread.MAX_PRIORITY); clock.start();
        ScheduledExecutorService oscWorker = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService metadataWorker = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService waveWorker = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService logWorker = Executors.newSingleThreadScheduledExecutor();
        oscWorker.scheduleAtFixedRate(app::oscTick, 0, 200, TimeUnit.MILLISECONDS);
        metadataWorker.scheduleWithFixedDelay(app::metadataTick, 1, 3, TimeUnit.SECONDS);
        waveWorker.scheduleWithFixedDelay(app::waveformTick, 0, 250, TimeUnit.MILLISECONDS);
        logWorker.scheduleWithFixedDelay(app::diagnosticsTick, 0, 250, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.closed = true;
            app.stopClock();
            oscWorker.shutdownNow(); metadataWorker.shutdownNow(); waveWorker.shutdownNow();
            logWorker.execute(app::diagnosticsTick); logWorker.shutdown();
            try { logWorker.awaitTermination(500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            synchronized (app) {
                if (app.midiReceiver != null) app.midiReceiver.close();
                if (app.midiDevice != null) app.midiDevice.close();
            }
            app.oscSocket.close(); server.stop(0);
        }));
        System.out.println("DJ Link : http://localhost:8080 — détection automatique du XDJ-AZ");
    }
}
