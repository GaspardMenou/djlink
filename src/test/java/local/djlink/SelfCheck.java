package local.djlink;

import org.deepsymmetry.beatlink.CdjStatus;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

public final class SelfCheck {
    static CdjStatus status(int number, boolean playing, boolean master) throws Exception {
        byte[] p = new byte[512];
        System.arraycopy(HexFormat.of().parseHex("5173707431576d4a4f4c10"), 0, p, 0, 11);
        System.arraycopy("XDJ-AZ".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, p, 11, 6);
        p[0x21] = (byte)number; p[0x24] = (byte)number;
        p[0x28] = 1; p[0x29] = 7; p[0x2a] = 1;
        ByteBuffer.wrap(p).putInt(0x2c, 88).putInt(0x8c, 0x100000).putShort(0x92, (short)14000);
        p[0x7b] = playing ? (byte)3 : (byte)5;
        p[0x89] = (byte)(0x80 | (playing ? 0x40 : 0) | (master ? 0x20 : 0));
        p[0x9d] = 9; p[0x9e] = master ? (byte)1 : (byte)0;
        return new CdjStatus(new DatagramPacket(p, p.length, InetAddress.getLoopbackAddress(), 50002));
    }

    public static void main(String[] args) throws Exception {
        CdjStatus player = status(1, true, true);
        assert player.getTrackSourceSlot().protocolValue == 7 : "USB 2 of the four-deck AZ must preserve slot 7";
        assert player.getRekordboxId() == 88;
        assert player.isPlaying() && player.isTempoMaster();
        assert Math.abs(player.getEffectiveTempo() - 140) < 0.001;
        assert DjLink.fresh(player, player.getTimestamp() + 1_999_000_000L);
        assert !DjLink.fresh(player, player.getTimestamp() + 2_000_000_000L) : "Lost sources must stop output";
        assert !DjLink.validTempo(Double.NaN) && !DjLink.validTempo(655.35);
        assert Arrays.equals(DjLink.oscMessage("/bpm", 140.0f), HexFormat.of().parseHex("2f62706d000000002c660000430c0000"));
        assert Arrays.equals(DjLink.oscMessage("/beat", 4), HexFormat.of().parseHex("2f626561740000002c69000000000004"));
        DjLink.NetworkStats network = new DjLink.NetworkStats();
        long t = 1_000_000_000L;
        for (int i = 0; i <= 21; i++) network.observe(t + i * 200_000_000L, i);
        assert network.snapshot(t + 4_200_000_000L).get("sequenceAvailable").equals(true);
        assert network.observe(t + 5_000_000_000L, 24).get("skipped").equals(2L);
        assert network.snapshot(t + 5_000_000_000L).get("latePackets").equals(1L);
        network.observeBeat(t, 1, 400, true);
        network.observeBeat(t + 800_000_000L, 3, 400, true);
        assert network.snapshot(t + 800_000_000L).get("lateBeats").equals(1L);
        assert network.snapshot(t + 800_000_000L).get("beatPhaseSkipsEstimate").equals(1L);
        network.observeBeat(t + 1_600_000_000L, 1, 400, false);
        assert network.snapshot(t + 1_600_000_000L).get("beatPhaseSkipsEstimate").equals(1L) : "Do not infer beat loss across an unconfirmed loop";
        network.pause();
        network.observeBeat(t + 50_000_000_000L, 1, 400, true);
        assert network.snapshot(t + 50_000_000_000L).get("lateBeats").equals(2L) : "A pause is not network loss";
        DjLink.NetworkStats unsupported = new DjLink.NetworkStats();
        for (int i = 0; i < 25; i++) unsupported.observe(t + i * 200_000_000L, 0);
        assert unsupported.snapshot(t + 5_000_000_000L).get("sequenceSkips") == null : "Do not invent loss without a sequence counter";
        DjLink.LoopWindow loop = new DjLink.LoopWindow();
        org.deepsymmetry.beatlink.data.BeatGrid grid = new org.deepsymmetry.beatlink.data.BeatGrid(
            new org.deepsymmetry.beatlink.data.DataReference(1, CdjStatus.TrackSourceSlot.USB_SLOT, 88),
            new int[20], new int[20], java.util.stream.LongStream.range(0, 20).map(i -> i * 500).toArray());
        for (int beat : new int[]{5,6,7,8,5,6,7,8}) loop.observe("a", true, false, beat);
        assert loop.bounds(grid) == null : "A single wrap is not proof of loop bounds";
        loop.observe("a", true, false, 5);
        assert Arrays.equals(loop.bounds(grid), new long[]{2000, 4000, 4});
        assert DjLink.wrapPosition(4200, 2000, 4000) == 2200;
        assert DjLink.wrapPosition(1900, 2000, 4000) == 3900 : "Reverse wraps must use positive modulo";
        loop.observe("a", true, true, 4);
        assert loop.bounds(grid) == null : "Reverse/scratch is not a loop wrap";
        loop.observe("b", true, false, 5);
        assert loop.bounds(grid) == null : "Track changes clear loop bounds";
        loop.observe("b", false, false, 5);
        assert loop.bounds(grid) == null;
        DjLink app = new DjLink();
        try {
            app.statuses.put(1, player);
            assert app.selectedDeck(System.nanoTime()) == 1;
            assert Math.abs(app.outputTempo(System.nanoTime()) - 140) < 0.001;
            app.statuses.put(1, status(1, false, true));
            assert app.outputTempo(System.nanoTime()) == 0 : "Paused decks must not emit clock";
            app.statuses.clear();
            assert app.selectedDeck(System.nanoTime()) == 0 : "Do not invent a master";
            DjLink.Settings invalid = new DjLink.Settings(); invalid.note = 128;
            try { app.configure(invalid); throw new AssertionError("Invalid note accepted"); }
            catch (IllegalArgumentException expected) { }
            invalid = new DjLink.Settings(); invalid.oscHost = "127.0.0.999";
            try { app.configure(invalid); throw new AssertionError("Invalid address accepted"); }
            catch (IllegalArgumentException expected) { }
            invalid = new DjLink.Settings(); invalid.midiMode = null;
            try { app.configure(invalid); throw new AssertionError("Null format accepted"); }
            catch (IllegalArgumentException expected) { }
            assert app.deck(4, System.nanoTime()).get("bpm") == null : "Unseen deck must be empty";
        } finally { app.oscSocket.close(); }
        System.out.println("OK — AZ slot 7, BPM, stale/pause handling, OSC encoding, input validation.");
    }
}
