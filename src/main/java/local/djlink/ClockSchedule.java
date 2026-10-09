package local.djlink;

/** Tick deadlines stay on the beat grid, even when the operating system wakes us late. */
final class ClockSchedule {
    long next, anchor, period, lastDue, lastSent;
    boolean initialized, emitted;
    DjLink.OutputSource source;

    void reset() { initialized = emitted = false; anchor = 0; source = null; }

    boolean due(long now, double bpm, long beatTime) {
        long tick = Math.round(60_000_000_000.0 / bpm / 24);
        if (!initialized) { next = now; initialized = true; }
        if (beatTime != 0 && beatTime != anchor && now >= beatTime && now - beatTime < tick * 24) {
            anchor = beatTime;
            next = beatTime + (now - beatTime) / tick * tick;
            // A tick sent just before the beat callback may already represent this boundary.
            if (emitted && Math.abs(lastSent - next) <= tick / 2) next += tick;
        } else if (emitted && tick != period) next = lastDue + tick;
        period = tick;
        if (now < next) return false;
        lastDue = next + (now - next) / tick * tick;
        lastSent = now; emitted = true; next = lastDue + tick;
        return true;
    }
}
