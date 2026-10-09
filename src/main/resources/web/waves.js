// A device generation also changes on engine restart, even if track IDs are reused.
class WaveCache {
  generation = null; waves = new Map(); retries = new Map(); pending = new Map(); current = new Map();
  constructor(fetchWave = (...args) => fetch(...args), now = () => performance.now()) {
    this.fetchWave = fetchWave; this.now = now;
  }
  setGeneration(generation) {
    if (generation === this.generation) return false;
    for (const request of this.pending.values()) request.controller.abort();
    this.waves.clear(); this.retries.clear(); this.pending.clear(); this.current.clear();
    this.generation = generation;
    return true;
  }
  async load(d) {
    if (!d.statusAvailable) return;
    const n = d.number, generation = this.generation;
    const key = `${generation}:${d.trackId}:${d.sourcePlayer}:${d.source}:${d.trackType}`;
    this.current.set(n, key);
    const old = this.pending.get(n);
    if (old && old.key !== key) { old.controller.abort(); this.pending.delete(n); }
    if (!d.trackId) { this.waves.delete(n); return; }
    if (this.waves.get(n)?.key === key) return;
    this.waves.delete(n);
    const retry = this.retries.get(n);
    if (this.pending.has(n) || (retry?.key === key && retry.after > this.now())) return;
    const request = {key, controller:new AbortController()};
    this.pending.set(n, request);
    const timer = setTimeout(() => request.controller.abort(), 5000);
    const active = () => this.generation === generation && this.current.get(n) === key && this.pending.get(n) === request;
    try {
      const response = await this.fetchWave(`/api/waveform/${n}`, {signal:request.controller.signal});
      if (!response.ok) throw new Error('Waveform indisponible');
      const data = await response.json();
      if (!active()) return;
      if (data.available && data.sourceGeneration === generation && data.trackId === d.trackId
        && data.sourcePlayer === d.sourcePlayer && data.sourceSlot === d.source && data.trackType === d.trackType)
        this.waves.set(n, {...data, key});
      else this.retries.set(n, {key, after:this.now() + 3000});
    } catch {
      if (active()) this.retries.set(n, {key, after:this.now() + 5000});
    } finally {
      clearTimeout(timer);
      if (this.pending.get(n) === request) this.pending.delete(n);
    }
  }
}
if (typeof module !== 'undefined') module.exports = WaveCache;
