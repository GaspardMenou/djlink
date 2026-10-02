// Display smoothing only: MIDI/OSC keep following the actual incoming beats.
class DeckMotion {
  value = null; anchor = null; at = 0; drawnAt = 0; key = ''; data = {};
  wrap(value, d = this.data) {
    const length = d.loopEnd - d.loopStart;
    return d.looping && length > 0 ? d.loopStart + ((value - d.loopStart) % length + length) % length : Math.max(0, value);
  }
  speed(d = this.data) { return d.playing === true ? (d.reverse ? -1 : 1) * (1 + (d.pitch || 0) / 100) : 0; }
  target(now) { return this.anchor == null ? null : this.wrap(this.anchor + Math.min(1000, Math.max(0, now - this.at)) * this.speed()); }
  sample(d, now) {
    const key = `${d.trackId}:${d.sourcePlayer}:${d.source}`;
    if (d.statusAvailable && key !== this.key) { this.value = this.anchor = null; this.key = key; }
    if (!d.statusAvailable || d.position == null) {
      this.anchor = this.value; this.at = now; this.data = {...this.data, playing:false}; return;
    }
    const predicted = this.target(now), delta = predicted == null ? 0 : d.position - predicted;
    const seek = d.beatNumber < this.data.beatNumber && !d.looping && !d.reverse;
    const snap = key !== this.key || this.value == null || d.reverse !== this.data.reverse
      || d.playing !== this.data.playing || !d.playing || seek || (!d.looping && Math.abs(delta) > 700)
      || d.loopStart !== this.data.loopStart || d.loopEnd !== this.data.loopEnd;
    this.data = {...d}; this.key = key; this.anchor = d.position; this.at = now;
    if (snap) { this.value = this.wrap(d.position); this.drawnAt = now; }
  }
  position(now, smooth = 120) {
    const target = this.target(now); if (target == null) return null;
    if (!smooth || !this.drawnAt || now - this.at >= 1000) { this.value = target; this.drawnAt = now; return target; }
    const dt = Math.max(0, now - this.drawnAt); this.drawnAt = now;
    // A short interruption is extrapolated for at most one second, then the cursor freezes.
    this.value = this.wrap(this.value + Math.min(dt, Math.max(0, 1000 - (now - this.at - dt))) * this.speed());
    let error = target - this.value;
    const length = this.data.loopEnd - this.data.loopStart;
    if (this.data.looping && length > 0) error -= Math.round(error / length) * length;
    // Limit speed corrections so bursty packets cannot jerk the wave back and forth.
    const limit = dt * (smooth >= 250 ? .08 : .15);
    const correction = Math.max(-limit, Math.min(limit, error * (1 - Math.exp(-dt / smooth))));
    this.value = this.wrap(this.value + correction);
    return this.value;
  }
}
if (typeof module !== 'undefined') module.exports = DeckMotion;
