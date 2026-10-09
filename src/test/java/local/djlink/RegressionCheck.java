package local.djlink;

import com.sun.net.httpserver.*;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.data.*;
import javax.sound.midi.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class RegressionCheck {
    static CdjStatus status(int deck, int id, int bpm, boolean playing, int player, int slot, int type, long now) throws Exception {
        byte[] p = SelfCheck.status(deck, playing, deck == 1).getPacketBytes();
        p[0x28] = (byte)player; p[0x29] = (byte)slot; p[0x2a] = (byte)type;
        ByteBuffer.wrap(p).putInt(0x2c, id).putShort(0x92, (short)(bpm * 100));
        return new CdjStatus(new DatagramPacket(p, p.length, InetAddress.getByName("127.0.0.1"), 50002)) {
            @Override public long getTimestamp() { return now; }
        };
    }
    static CdjStatus status(int id, int bpm, long now) throws Exception { return status(1, id, bpm, true, 1, 7, 1, now); }
    static CdjStatus master(CdjStatus original, boolean master) {
        return new CdjStatus(new DatagramPacket(original.getPacketBytes(), original.getPacketBytes().length,
            original.getAddress(), 50002)) {
            @Override public long getTimestamp() { return original.getTimestamp(); }
            @Override public boolean isTempoMaster() { return master; }
        };
    }
    static Beat beat(int deck, int bpm, double pitch, int phase, long now) throws Exception {
        byte[] p = new byte[96];
        System.arraycopy(HexFormat.of().parseHex("5173707431576d4a4f4c28"), 0, p, 0, 11);
        p[0x21] = (byte)deck; p[0x5c] = (byte)phase;
        ByteBuffer.wrap(p).putInt(0x54, (int)Math.round(0x100000 * (1 + pitch / 100)))
            .putShort(0x5a, (short)(bpm * 100)).putInt(0x24, 60_000 / bpm);
        return new Beat(new DatagramPacket(p, p.length, InetAddress.getByName("127.0.0.1"), 50001)) {
            @Override public long getTimestamp() { return now; }
        };
    }
    static DjLink app(Path logs, AtomicLong now) throws Exception { return new DjLink(logs, ref -> null, now::get); }
    static void close(DjLink app) { app.closed = true; app.oscSocket.close(); }
    static WaveformDetail wave(DataReference ref) {
        return new WaveformDetail(ref, ByteBuffer.wrap(new byte[25_001]), WaveformFinder.WaveformStyle.BLUE);
    }
    static void run() throws Exception {
        Path root = Files.createTempDirectory("djlink-check-");
        try {
            clock(root); tracks(root); waveforms(root); diagnostics(); capturesAndLogs(root); http(root);
        } finally {
            try (var files = Files.walk(root)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        System.out.println("OK — clock cadence, track resets, background waves, capture loss, retention and HTTP authorization.");
    }

    static void clock(Path root) throws Exception {
        long start = 10_000_000_000L, period = Math.round(60_000_000_000.0 / 120 / 24);
        AtomicLong now = new AtomicLong(start);
        DjLink app = app(root.resolve("clock"), now);
        List<Integer> messages = new ArrayList<>();
        app.midiReceiver = new Receiver() {
            public void send(MidiMessage message, long timestamp) { messages.add(message.getStatus()); }
            public void close() { }
        };
        app.selectTarget("127.0.0.1");
        ClockSchedule schedule = new ClockSchedule();
        try {
            for (int barBeat = 0; barBeat < 12; barBeat++) {
                long anchor = start + barBeat * 500_000_000L;
                now.set(anchor); app.acceptStatus(status(88, 120, anchor));
                app.acceptBeat(beat(1, 120, 0, barBeat % 4 + 1, anchor));
                long before = app.sentClock;
                for (int tick = 0; tick < 24; tick++) {
                    now.set(anchor + tick * period + 100_000);
                    app.clockStep(schedule, now.get());
                }
                assert app.sentClock - before == 24 : "Every complete beat must emit 24 clocks despite small wake delays";
            }
            long boundary = start + 12 * 500_000_000L;
            now.set(boundary + 100_000); app.acceptStatus(status(88, 120, boundary));
            app.clockStep(schedule, now.get());
            long before = app.sentClock;
            app.acceptBeat(beat(1, 120, 0, 1, boundary));
            app.clockStep(schedule, now.get() + 1000);
            assert app.sentClock == before : "A beat callback must not duplicate an already sent boundary tick";
            now.addAndGet(100_000_000); app.clockStep(schedule, now.get());
            assert app.sentClock == before + 1 : "A long stall emits one tick, never a catch-up burst";
            app.clockStep(schedule, now.get());
            assert app.sentClock == before + 1;
            now.addAndGet(200_000_000); app.acceptStatus(status(88, 150, now.get()));
            app.acceptBeat(beat(1, 150, 0, 2, now.get())); app.clockStep(schedule, now.get() + 100_000);
            assert schedule.period == Math.round(60_000_000_000.0 / 150 / 24);
            app.acceptStatus(status(1, 88, 150, false, 1, 7, 1, now.get()));
            assert app.clockStep(schedule, now.get()) == 0 && !app.clockRunning;
            assert messages.get(messages.size() - 1) == ShortMessage.STOP;
            app.acceptStatus(status(88, 120, now.get())); app.clockStep(schedule, now.get());
            now.addAndGet(2_000_000_000L);
            assert app.clockStep(schedule, now.get()) == 0 && !app.clockRunning : "Stale sources stop the clock";
            app.acceptStatus(status(88, 120, now.get())); app.clockStep(schedule, now.get());
            app.settings.midiMode = "tap";
            assert app.clockStep(schedule, now.get()) == 0;
            app.settings.midiMode = "clock"; app.clockStep(schedule, now.get());
            int starts = Collections.frequency(messages, ShortMessage.START);
            app.acceptStatus(status(2, 99, 130, true, 1, 7, 1, now.get())); app.settings.deck = 2;
            app.clockStep(schedule, now.get());
            assert schedule.source.deck() == 2 && Collections.frequency(messages, ShortMessage.START) == starts + 1;
            app.settings.deck = 0;
            app.acceptStatus(master(status(1, 88, 120, true, 1, 7, 1, now.get()), true));
            app.acceptStatus(master(status(2, 99, 130, true, 1, 7, 1, now.get()), false));
            app.clockStep(schedule, now.get()); assert schedule.source.deck() == 1;
            starts = Collections.frequency(messages, ShortMessage.START);
            app.acceptStatus(master(status(1, 88, 120, true, 1, 7, 1, now.get()), false));
            app.acceptStatus(master(status(2, 99, 130, true, 1, 7, 1, now.get()), true));
            app.clockStep(schedule, now.get());
            assert schedule.source.deck() == 2 && Collections.frequency(messages, ShortMessage.START) == starts + 1;
            app.midiReceiver = null;
            assert app.clockStep(schedule, now.get()) == 0 && !app.clockRunning;
            app.selectTarget(""); assert !app.clockRunning;
            assert app.clockStep(schedule, now.get()) == 0;
        } finally { close(app); }
    }

    static void tracks(Path root) throws Exception {
        AtomicLong now = new AtomicLong(10_000_000_000L);
        DjLink app = app(root.resolve("tracks"), now);
        app.selectTarget("127.0.0.1");
        try {
            app.acceptStatus(status(88, 120, now.get()));
            now.addAndGet(1000); Beat old = beat(1, 120, 0, 1, now.get()); app.acceptBeat(old);
            assert app.outputTempo(now.get()) == 120;
            int[][] changes = {{89,1,7,1},{89,1,3,1},{89,2,3,1},{89,2,3,2},{0,2,3,2}};
            for (int[] c : changes) {
                CdjStatus prior = app.statuses.get(1);
                app.knownMetadata.put(1, SelfCheck.metadata(prior, "Known", prior.getRekordboxId()));
                var reference = DjLink.trackReference(prior);
                app.requestedWaves.put(1, new DjLink.CachedWave(app.sourceGeneration, reference, Map.of("available", true)));
                DjLink.LoopWindow loop = new DjLink.LoopWindow(); app.loops.put(1, loop);
                now.addAndGet(1000);
                CdjStatus next = status(1, c[0], 140, true, c[1], c[2], c[3], now.get()); app.acceptStatus(next);
                assert !app.beats.containsKey(1) && !app.beatTimes.containsKey(1);
                assert !app.knownMetadata.containsKey(1) && !app.requestedWaves.containsKey(1);
                assert app.loops.get(1) != loop;
                assert app.outputTempo(now.get()) == (c[0] == 0 ? 0 : 140);
                assert app.deck(1, now.get()).get("bpm").equals(140.0);
                app.acceptBeat(old);
                assert !app.beats.containsKey(1) : "A callback received before the track change cannot restore old beats";
                now.addAndGet(1000); app.acceptBeat(beat(1, 140, 0, 1, now.get()));
            }
            now.addAndGet(1000); app.acceptStatus(status(99, 140, now.get()));
            now.addAndGet(1000); Beat same = beat(1, 140, 0, 1, now.get()); app.acceptBeat(same);
            now.addAndGet(1000); app.acceptStatus(status(99, 140, now.get()));
            assert app.beats.get(1) == same : "An ordinary status update retains the current track's beat";
            String generation = app.sourceGeneration; app.selectTarget(""); app.selectTarget("127.0.0.1");
            assert !generation.equals(app.sourceGeneration) && app.statuses.isEmpty();
        } finally { close(app); }
    }

    static void waveforms(Path root) throws Exception {
        AtomicLong now = new AtomicLong(10_000_000_000L);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        DjLink app = new DjLink(root.resolve("waves"), ref -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fetch was not released"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            return wave(ref);
        }, now::get);
        ExecutorService worker = Executors.newSingleThreadExecutor(), requests = Executors.newFixedThreadPool(8);
        try {
            app.selectTarget("127.0.0.1"); app.acceptStatus(status(88, 120, now.get()));
            Future<?> fetching = worker.submit(app::waveformTick);
            assert entered.await(1, TimeUnit.SECONDS);
            List<Future<Integer>> responses = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String path = i % 3 == 0 ? "/api/state" : i % 3 == 1 ? "/api/settings" : "/api/waveform/1";
                responses.add(requests.submit(() -> {
                    Exchange e = new Exchange(path, path.equals("/api/settings") ? "POST" : "GET", "{}", "127.0.0.1");
                    app.http(e); return e.code;
                }));
            }
            for (Future<Integer> response : responses) assert response.get(1, TimeUnit.SECONDS) == 200;
            assert calls.get() == 1 && app.waveform(1).get("available").equals(false);
            now.addAndGet(1000); app.acceptStatus(status(89, 120, now.get()));
            release.countDown(); fetching.get(1, TimeUnit.SECONDS);
            assert app.waveform(1).get("available").equals(false) : "Old track fetch is discarded";
            app.waveformTick();
            assert app.waveform(1).get("trackId").equals(89L);
            assert ((List<?>)app.waveform(1).get("samples")).size() <= 12_000;
            app.waveformTick(); assert calls.get() == 2 : "Cached data does not refetch";
            String generation = app.sourceGeneration;
            app.selectTarget(""); app.selectTarget("127.0.0.1"); app.acceptStatus(status(89, 120, now.get()));
            assert app.waveform(1).get("available").equals(false);
            app.waveformTick();
            assert !generation.equals(app.waveform(1).get("sourceGeneration"));
            assert calls.get() == 3;
            close(app); app.waveformTick(); assert calls.get() == 3;
        } finally { release.countDown(); worker.shutdownNow(); requests.shutdownNow(); close(app); }
        AtomicInteger failed = new AtomicInteger();
        DjLink retry = new DjLink(root.resolve("retry"), ref -> { failed.incrementAndGet(); return null; }, now::get);
        try {
            retry.selectTarget("127.0.0.1"); retry.acceptStatus(status(88, 120, now.get())); retry.waveformTick();
            long first = now.get(); now.set(first + 2_999_999_999L);
            retry.acceptStatus(status(88, 120, now.get())); retry.waveformTick(); assert failed.get() == 1;
            now.set(first + 3_000_000_000L); retry.waveformTick(); assert failed.get() == 2;
        } finally { close(retry); }
        CountDownLatch waiting = new CountDownLatch(1), finish = new CountDownLatch(1);
        DjLink switching = new DjLink(root.resolve("switch"), ref -> {
            waiting.countDown();
            try { finish.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return wave(ref);
        }, now::get);
        ExecutorService switchWorker = Executors.newSingleThreadExecutor();
        try {
            switching.selectTarget("127.0.0.1"); switching.acceptStatus(status(88, 120, now.get()));
            Future<?> fetching = switchWorker.submit(switching::waveformTick);
            assert waiting.await(1, TimeUnit.SECONDS);
            switching.selectTarget("192.0.2.1"); switching.selectTarget("127.0.0.1");
            switching.acceptStatus(status(88, 120, now.get())); finish.countDown(); fetching.get(1, TimeUnit.SECONDS);
            assert switching.waveform(1).get("available").equals(false) : "A to B to A rejects the old device generation";
        } finally { finish.countDown(); switchWorker.shutdownNow(); close(switching); }
    }

    static void diagnostics() throws Exception {
        for (double pitch : new double[]{-50, 0, 50}) {
            Beat beat = beat(1, 120, pitch, 1, 10_000_000_000L);
            long interval = DjLink.beatInterval(beat);
            assert interval == Math.round(60_000 / beat.getEffectiveTempo());
            DjLink.NetworkStats stats = new DjLink.NetworkStats();
            stats.observeBeat(10_000_000_000L, 1, interval, true);
            stats.observeBeat(10_000_000_000L + interval * 1_000_000L, 2, interval, true);
            assert stats.lateBeats == 0;
            stats.observeBeat(10_000_000_000L + interval * 3_000_000L, 4, interval, true);
            assert stats.lateBeats == 1 && stats.phaseSkips == 1;
        }
        assert DjLink.beatInterval(beat(1, 120, -100, 1, 10_000_000_000L)) == 0;
    }

    static void capturesAndLogs(Path root) throws Exception {
        AtomicLong now = new AtomicLong(10_000_000_000L);
        Path directory = root.resolve("logs"); LocalDate today = LocalDate.of(2026, 10, 9);
        DjLink app = app(directory, now);
        try {
            app.captureUntil = now.get() + 60_000_000_000L;
            CdjStatus status = status(88, 120, now.get());
            for (int i = 0; i < 300; i++) app.capture(status, "status");
            assert app.networkEvents.size() == 300 && app.captureDroppedPackets.get() == 0;
            app.diagnosticsTick(today, now.get());
            Path log = directory.resolve("network-" + today + ".jsonl");
            assert Files.readAllLines(log).size() == 300;
            for (int i = 0; i < 1100; i++) app.capture(status, "status");
            assert app.networkEvents.size() == 1024 && app.captureDroppedPackets.get() == 76;
            app.diagnosticsTick(today, now.get());
            var records = Files.readAllLines(log).stream().map(line -> DjLink.JSON.fromJson(line, Map.class)).toList();
            var dropped = records.stream().filter(r -> "capture_drop".equals(r.get("event"))).findFirst().orElseThrow();
            assert dropped.get("dropped").equals(76.0) && dropped.get("total").equals(76.0);
            app.capture(status, "status"); assert app.networkEvents.size() == 1 : "Capture continues after overflow";
            app.diagnosticsTick(today, now.get());
            int lines = Files.readAllLines(log).size();
            app.diagnostics.put(1, new DjLink.NetworkStats());
            app.diagnosticsTick(today, now.get()); assert Files.readAllLines(log).size() == lines;
            app.diagnosticsTick(today, now.addAndGet(5_000_000_000L)); assert Files.readAllLines(log).size() == lines + 1;
            try (RandomAccessFile large = new RandomAccessFile(log.toFile(), "rw")) { large.setLength(10_000_001); }
            app.networkEvents.offer(Map.of("event", "rotation")); app.diagnosticsTick(today, now.get());
            assert Files.size(directory.resolve("network-" + today + "-previous.jsonl")) == 10_000_001;
            assert Files.readString(log).contains("rotation");
            Path kept = directory.resolve("network-" + today.minusDays(29) + ".jsonl"); Files.writeString(kept, "keep");
            Path old = directory.resolve("network-" + today.minusDays(30) + ".jsonl"); Files.writeString(old, "old");
            Path archive = directory.resolve("network-" + today.minusDays(30) + "-previous.jsonl"); Files.writeString(archive, "old");
            Path future = directory.resolve("network-" + today.plusDays(1) + ".jsonl"); Files.writeString(future, "future");
            Path unrelated = directory.resolve("notes.jsonl"); Files.writeString(unrelated, "private");
            Path invalid = directory.resolve("network-2026-02-30.jsonl"); Files.writeString(invalid, "invalid date");
            Path folder = directory.resolve("network-2020-01-01.jsonl"); Files.createDirectory(folder);
            Path symlink = directory.resolve("network-2020-01-02.jsonl");
            boolean linked = false;
            try { Files.createSymbolicLink(symlink, unrelated); linked = true; }
            catch (UnsupportedOperationException | IOException ignored) { }
            app.cleanedLogDate = null; app.diagnosticsTick(today, now.get());
            assert Files.exists(kept) && !Files.exists(old) && !Files.exists(archive);
            assert Files.exists(future) && Files.exists(unrelated) && Files.exists(invalid) && Files.isDirectory(folder);
            if (linked) assert Files.isSymbolicLink(symlink);
            app.networkEvents.offer(Map.of("event", "midnight")); app.diagnosticsTick(today.plusDays(1), now.get());
            assert !Files.exists(kept);
            assert Files.readString(future).contains("midnight");
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                var permissions = Files.getPosixFilePermissions(directory);
                try {
                    Files.setPosixFilePermissions(directory, Set.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
                    app.cleanedLogDate = null; app.diagnosticsTick(today, now.get());
                    assert !app.logError.isEmpty() : "Retention errors must remain visible";
                } finally { Files.setPosixFilePermissions(directory, permissions); }
                app.diagnosticsTick(today, now.get()); assert app.logError.isEmpty();
            }
        } finally { close(app); }
        Path failedPath = root.resolve("failed-logs"); Files.createDirectory(failedPath);
        Path blocked = failedPath.resolve("network-" + today + ".jsonl"); Files.createDirectory(blocked);
        DjLink failing = app(failedPath, now);
        try {
            failing.networkEvents.offer(Map.of("event", "retry_batch")); failing.diagnosticsTick(today, now.get());
            assert !failing.logError.isEmpty() && failing.pendingLogBatch.size() == 1;
            failing.networkEvents.offer(Map.of("event", "next_batch")); failing.diagnosticsTick(today, now.get());
            assert failing.pendingLogBatch.size() == 1 && failing.networkEvents.size() == 1;
            Files.delete(blocked); failing.diagnosticsTick(today, now.get());
            assert failing.pendingLogBatch.isEmpty() && failing.logError.isEmpty();
            assert Files.readString(blocked).contains("retry_batch");
            failing.diagnosticsTick(today, now.get()); assert Files.readString(blocked).contains("next_batch");
        } finally { close(failing); }
    }

    static void http(Path root) throws Exception {
        AtomicLong now = new AtomicLong(10_000_000_000L); DjLink app = app(root.resolve("http"), now);
        try {
            for (String endpoint : List.of("/api/settings", "/api/capture")) {
                String body = endpoint.endsWith("settings") ? "{\"deck\":2}" : "{\"seconds\":5}";
                for (int bad = 0; bad < 3; bad++) {
                    Exchange e = new Exchange(endpoint, "POST", body, bad == 0 ? "192.0.2.1" : "127.0.0.1");
                    if (bad == 1) e.request.set("Host", "attacker.example:8080");
                    if (bad == 2) e.request.set("Origin", "http://attacker.example");
                    DjLink.Settings previous = app.settings; long capture = app.captureUntil;
                    app.http(e); assert e.code == 403 && app.settings == previous && app.captureUntil == capture;
                }
                for (String host : List.of("", "localhost:9090", "localhost:8080.attacker.example")) {
                    Exchange e = new Exchange(endpoint, "POST", body, "127.0.0.1");
                    if (host.isEmpty()) e.request.remove("Host"); else e.request.set("Host", host);
                    DjLink.Settings previous = app.settings; long capture = app.captureUntil;
                    app.http(e); assert e.code == 403 && app.settings == previous && app.captureUntil == capture;
                }
                for (String host : List.of("localhost:8080", "127.0.0.1:8080")) {
                    for (boolean origin : new boolean[]{true, false}) {
                        Exchange e = new Exchange(endpoint, "POST", body, "127.0.0.1");
                        e.request.set("Host", host); if (origin) e.request.set("Origin", "http://" + host);
                        app.http(e); assert e.code == 200;
                    }
                }
                Exchange oversized = new Exchange(endpoint, "POST", "x".repeat(4097), "127.0.0.1");
                app.http(oversized); assert oversized.code == 413;
                Exchange content = new Exchange(endpoint, "POST", body, "127.0.0.1"); content.request.set("Content-Type", "text/plain");
                app.http(content); assert content.code == 415;
            }
            for (String remote : List.of("127.0.0.1", "192.0.2.1")) {
                Exchange e = new Exchange("/api/state", "GET", "", remote); app.http(e); assert e.code == 200;
                var json = DjLink.JSON.fromJson(e.output.toString(StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
                assert json.has("sourceGeneration");
                for (String field : List.of("logDirectory", "logError", "captureRemaining", "captureDroppedPackets"))
                    assert json.has(field) == remote.equals("127.0.0.1");
            }
            Exchange invalid = new Exchange("/api/settings", "POST", "{\"note\":128}", "127.0.0.1");
            app.http(invalid); assert invalid.code == 400;
            Exchange asset = new Exchange("/waves.js", "GET", "", "127.0.0.1"); app.http(asset); assert asset.code == 200;
        } finally { close(app); }
    }

    static final class Exchange extends HttpExchange {
        final Headers request = new Headers(), response = new Headers();
        final URI uri; final String method; final InetSocketAddress remote;
        final InputStream input; final ByteArrayOutputStream output = new ByteArrayOutputStream(); int code;
        Exchange(String path, String method, String body, String remote) {
            uri = URI.create(path); this.method = method; this.remote = new InetSocketAddress(remote, 12345);
            input = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            request.set("Host", "localhost:8080"); request.set("Content-Type", "application/json");
        }
        public Headers getRequestHeaders() { return request; }
        public Headers getResponseHeaders() { return response; }
        public URI getRequestURI() { return uri; }
        public String getRequestMethod() { return method; }
        public HttpContext getHttpContext() { return null; }
        public void close() { }
        public InputStream getRequestBody() { return input; }
        public OutputStream getResponseBody() { return output; }
        public void sendResponseHeaders(int code, long length) { this.code = code; }
        public InetSocketAddress getRemoteAddress() { return remote; }
        public int getResponseCode() { return code; }
        public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        public String getProtocol() { return "HTTP/1.1"; }
        public Object getAttribute(String name) { return null; }
        public void setAttribute(String name, Object value) { }
        public void setStreams(InputStream input, OutputStream output) { }
        public HttpPrincipal getPrincipal() { return null; }
    }
}
