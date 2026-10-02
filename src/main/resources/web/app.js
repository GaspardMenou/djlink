const $ = id => document.getElementById(id);
const colors = ['#00cee3', '#a5adff', '#dd87eb', '#6adbc1'];
let state, initialized = false, updating = false, receivedAt = performance.now(), transportOnline = false;
const motions = new Map(Array.from({length:4}, (_, i) => [i + 1, new DeckMotion()]));
const waves = new Map(), wavePending = new Set(), waveRetry = new Map();
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
const bpm = value => value == null ? '—' : value.toFixed(1);
const zoomSeconds = () => 32 / 2 ** Number($('wave-zoom').value);
for (let n = 1; n <= 4; n++) {
  const article = document.createElement('article');
  article.className = 'deck'; article.style.setProperty('--deck-color', colors[n - 1]);
  article.innerHTML = `
    <div class="deck-head">
      <div class="deck-number"><span>DECK</span><b>${String(n).padStart(2, '0')}</b></div>
      <div class="track-info"><div class="track-heading"><div class="track-title empty" id="title-${n}">Deck vide</div><span class="play-state" id="play-${n}">Inconnu</span></div><div class="artist" id="artist-${n}">Aucun morceau chargé</div></div>
      <div class="deck-data"><div class="deck-bpm"><span id="bpm-${n}">—</span><small>BPM</small></div><div class="beat-strip" id="beats-${n}" aria-label="Temps dans la mesure"><i></i><i></i><i></i><i></i></div></div>
      <div class="position-display"><span id="clock-${n}">--:--:--.---</span><small id="clock-label-${n}">Position du morceau</small></div>
    </div>
    <div class="wave-area">
      <div class="wave-head"><span class="badge" id="badge-${n}">Hors ligne</span><span id="pitch-${n}">Pitch —</span><span id="key-${n}">Tonalité —</span><div class="deck-signals"><span id="sync-${n}"></span><span class="onair" id="air-${n}">On air —</span><span class="loop-status" id="loop-${n}">Boucle —</span></div></div>
      <div class="wave-container"><canvas id="wave-${n}" class="wave-zoom" aria-label="Waveform détaillée du deck ${n}"></canvas><span class="wave-empty" id="wave-empty-${n}">Waveform en attente du matériel</span></div>
      <canvas id="overview-${n}" class="wave-overview" aria-label="Waveform complète du deck ${n}"></canvas>
      <div class="time-line"><span id="elapsed-${n}">--:--</span><span id="position-info-${n}">Position indisponible</span><div class="cue-list" id="cues-${n}"></div><span id="remaining-${n}">--:--</span></div>
    </div>`;
  $('decks').append(article);
}
function markBeat(id, beat, active) {
  [...$(id).children].forEach((el, i) => el.classList.toggle('current', active && beat === i + 1));
}
function hint() {
  const tap = $('midi-mode').value !== 'clock';
  $('note-settings').hidden = !tap;
  $('midi-hint').textContent = tap
    ? 'Dans grandMA2 : MIDI Remotes → même canal et note → CMD : Learn SpecialMaster 3.1.'
    : 'Dans Daslight : MIDI / Audio / BPM → MIDI Clock Sync. Active la même entrée MIDI.';
}
async function ports() {
  try {
    const response = await fetch('/api/midi'); if (!response.ok) throw new Error('Liste MIDI indisponible.');
    const devices = await response.json(); const current = $('midi-port').value;
    $('midi-port').innerHTML = '<option value="-1">Désactivé</option>' + devices.map(p => `<option value="${p.id}">${esc(p.name)}</option>`).join('');
    if ([...$('midi-port').options].some(o => o.value === current)) $('midi-port').value = current;
    if (!devices.length) $('midi-hint').textContent = 'Aucun port MIDI. Connecte une interface MIDI ou active un bus virtuel, puis actualise la liste.';
  } catch (e) { message(e.message, true); }
}
function message(text, error = false) {
  $('form-message').textContent = text; $('form-message').classList.toggle('error', error);
}
function render(s) {
  const previous = state;
  receivedAt = performance.now(); transportOnline = true;
  for (const d of s.decks) {
    const last = previous?.decks.find(p => p.number === d.number);
    if (!d.statusAvailable && last) for (const key of ['trackId','source','sourcePlayer','title','artist','duration','key','pitch','cues','loopStart','loopEnd','loopBeats','loopInferred','looping']) d[key] = last[key];
    motions.get(d.number).sample(d, receivedAt);
  }
  state = s;
  const stats = s.decks.map(d => d.network).filter(Boolean);
  const irregular = stats.some(n => n.ageMs > 500 || n.jitterMs > Math.max(40, n.intervalMs * .5));
  $('network-open').textContent = !s.connected ? 'Réseau · attente' : irregular ? 'Réseau · irrégulier' : 'Réseau · stable';
  $('network-open').classList.toggle('network-warning', irregular);
  $('network-summary').textContent = `${s.lightingError ? `Erreur d’envoi : ${s.lightingError} · ` : ''}${s.connected ? 'Signal reçu' : 'Signal en attente'} · lissage ${$('smoothing').selectedOptions[0].textContent} · extrapolation limitée à 1 seconde.`;
  $('network-rows').innerHTML = s.decks.map(d => { const n = d.network; return `<tr><th>${d.number}</th>${n ? `<td>${n.ageMs} ms</td><td>${n.intervalMs} ms</td><td>${n.jitterMs} ms</td><td>${n.maxGapMs} ms</td><td>${n.latePackets}</td><td>${n.sequenceAvailable ? n.sequenceSkips : 'Non disponible'}</td><td>${n.beatCount ? `${n.beatMaxGapMs} ms` : '—'}</td><td>${n.lateBeats}</td><td>${n.beatPhaseSkipsEstimate}</td>` : '<td colspan="9">Aucun état reçu</td>'}</tr>`; }).join('');
  $('network-log-path').textContent = s.logError ? `Erreur de log : ${s.logError}` : s.logDirectory ? `Logs locaux : ${s.logDirectory}` : 'Consulte les logs depuis l’application sur le PC serveur.';
  const online = s.decks.some(d => d.online);
  $('led').classList.toggle('online', s.connected);
  $('connection').textContent = s.connected ? 'XDJ-AZ connecté' : online ? 'XDJ-AZ détecté' : 'Recherche du XDJ-AZ';
  $('device').textContent = s.target ? s.target : 'Détection automatique';
  $('notice').hidden = s.connected;
  $('notice').textContent = s.lightingError ? `Accès réseau : ${s.lightingError}. Sur Mac, vérifie Confidentialité et sécurité → Réseau local → DJLink. Les requêtes sont retentées automatiquement.` : s.linkMessage.includes('impossible') ? s.linkMessage : online
    ? 'XDJ-AZ détecté. Attente des états de lecture ; les BPM de beat peuvent déjà apparaître.'
    : 'Branche le PC au même réseau que le XDJ-AZ. Ferme les autres apps PRO DJ LINK pour libérer les ports 50000–50002.';
  $('packet-count').textContent = `${s.received.toLocaleString('fr-FR')} paquets reçus`;
  $('network').textContent = s.target ? `PRO DJ LINK · ${s.target} · ${s.linkMessage}` : s.linkMessage;
  for (const d of s.decks) {
    const n = d.number;
    $(`title-${n}`).closest('.deck').classList.toggle('unloaded', !d.trackId || (d.ended && !d.title));
    $(`title-${n}`).closest('.deck').classList.toggle('looping', d.looping === true);
    $(`title-${n}`).closest('.deck').classList.toggle('stale', !d.statusAvailable);
    const title = d.title || (d.ended ? 'Deck à l’arrêt' : d.trackId ? `Morceau #${d.trackId}` : d.online ? 'Deck vide' : 'En attente du deck');
    $(`title-${n}`).textContent = title;
    $(`title-${n}`).classList.toggle('empty', !d.title);
    $(`artist-${n}`).textContent = d.artist || (d.title ? 'Artiste non renseigné' : d.trackId ? 'Métadonnées indisponibles pour ce morceau' : d.online ? 'Aucun morceau chargé' : 'Aucun morceau reçu');
    $(`bpm-${n}`).textContent = bpm(d.bpm);
    $(`badge-${n}`).textContent = d.master ? 'Master' : d.statusAvailable ? 'Connecté' : d.online ? 'Signal en attente' : 'Signal perdu';
    $(`badge-${n}`).classList.toggle('is-master', d.master);
    $(`play-${n}`).textContent = d.playing === true ? d.reverse ? '◀ Reverse' : '▶ Lecture' : d.playing === false ? d.ended ? 'Fin' : 'Pause' : 'État inconnu';
    $(`play-${n}`).classList.toggle('playing', d.playing === true);
    $(`sync-${n}`).textContent = d.synced ? 'Sync' : '';
    $(`air-${n}`).textContent = d.onAir === true ? 'On air' : d.onAir === false ? 'Hors air' : 'On air —';
    $(`air-${n}`).classList.toggle('active', d.onAir === true);
    $(`pitch-${n}`).textContent = d.pitch == null ? 'Pitch —' : `Pitch ${d.pitch >= 0 ? '+' : ''}${d.pitch.toFixed(2)} %`;
    $(`key-${n}`).textContent = d.key ? `Tonalité ${d.key}` : 'Tonalité —';
    $(`loop-${n}`).textContent = d.looping == null ? 'Boucle —' : d.looping ? `↺ ${d.loopBeats ? `${d.loopInferred ? '≈ ' : ''}${d.loopBeats} beats` : 'Boucle active · détection des bornes'}` : 'Pas de boucle';
    $(`loop-${n}`).classList.toggle('active', d.looping === true);
    $(`cues-${n}`).innerHTML = (d.cues || []).filter(c => c.hotCue > 0).map(c => `<span class="cue" title="${esc(c.label || (c.loopEnd != null ? 'Boucle mémorisée' : 'Hot cue'))}"><b>${esc(String.fromCharCode(64 + c.hotCue))}</b>${formatTime(c.time)}${c.loopEnd != null ? ' ↺' : ''}</span>`).join('');
    loadWave(d);
    markBeat(`beats-${n}`, d.beat, d.playing !== false && d.beatAt != null);
  }
  const source = s.decks.find(d => d.number === s.selectedDeck);
  $('master-bpm').textContent = bpm(s.outputBpm);
  $('master-label').textContent = !source ? 'En attente du tempo master' : `Deck ${source.number}${s.settings.deck === 0 ? ' · master automatique' : ' · source manuelle'}${s.outputBpm == null ? ' · sortie en attente' : ''}`;
  markBeat('master-beats', source?.beat, s.outputBpm != null && source?.beatAt != null);
  const enabled = s.midiActive || s.settings.osc;
  $('output-indicator').textContent = enabled ? s.outputBpm != null ? 'Sorties actives' : 'En attente' : 'Sorties coupées';
  $('output-indicator').classList.toggle('active', enabled && s.outputBpm != null);
  $('output-detail').textContent = s.outputError || (enabled ? `${s.sentClock.toLocaleString('fr-FR')} ticks MIDI · ${s.sentBeat.toLocaleString('fr-FR')} taps · ${s.sentOsc.toLocaleString('fr-FR')} messages OSC` : 'Les sorties s’arrêtent si la source est en pause ou perdue.');
  if (!initialized) {
    $('deck-select').value = s.settings.deck; $('midi-port').value = s.settings.midi;
    $('midi-mode').value = s.settings.midiMode; $('midi-channel').value = s.settings.channel;
    $('midi-note').value = s.settings.note; $('osc-enabled').checked = s.settings.osc;
    $('osc-host').value = s.settings.oscHost; $('osc-port').value = s.settings.oscPort;
    initialized = true; hint();
  }
}
async function poll() {
  if (updating) { setTimeout(poll, 120); return; }
  try {
    const response = await fetch('/api/state', {signal: AbortSignal.timeout(3000)});
    if (!response.ok) throw new Error('Serveur indisponible');
    render(await response.json());
  } catch {
    transportOnline = performance.now() - receivedAt < 2000;
    $('led').classList.remove('online'); $('connection').textContent = 'Connexion perdue';
    $('notice').hidden = false; $('notice').textContent = 'Le serveur DJ Link ne répond plus. Relance l’application DJ Link.';
    $('master-bpm').textContent = '—'; $('output-indicator').textContent = 'Connexion perdue';
    $('output-indicator').classList.remove('active');
    for (let n = 1; n <= 4; n++) { $(`bpm-${n}`).textContent = '—'; $(`badge-${n}`).textContent = 'Connexion perdue'; $(`badge-${n}`).classList.remove('is-master'); $(`play-${n}`).textContent = 'État inconnu'; $(`play-${n}`).classList.remove('playing'); $(`air-${n}`).textContent = 'On air —'; $(`air-${n}`).classList.remove('active'); markBeat(`beats-${n}`, 0, false); }
    markBeat('master-beats', 0, false);
  } finally { setTimeout(poll, 120); }
}
$('output-form').addEventListener('submit', async event => {
  event.preventDefault(); updating = true; $('apply').disabled = true;
  try {
    const response = await fetch('/api/settings', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({
      deck:Number($('deck-select').value), midi:Number($('midi-port').value), midiMode:$('midi-mode').value,
      channel:Number($('midi-channel').value), note:Number($('midi-note').value), osc:$('osc-enabled').checked,
      oscHost:$('osc-host').value.trim(), oscPort:Number($('osc-port').value)
    }), signal:AbortSignal.timeout(5000)});
    const data = await response.json(); if (!response.ok) throw new Error(data.error);
    render(data); message('Sorties appliquées.');
  } catch (e) { message(e.message, true); }
  finally { updating = false; $('apply').disabled = false; }
});
function updateZoom(delta = 0) {
  $('wave-zoom').value = Math.max(0, Math.min(6, Number($('wave-zoom').value) + delta));
  $('zoom-value').textContent = `${zoomSeconds().toFixed(zoomSeconds() < 2 ? 2 : 1).replace(/\.0$/, '')} s`;
  localStorage.setItem('djlink.zoom', $('wave-zoom').value);
}
$('wave-zoom').value = localStorage.getItem('djlink.zoom') || '2'; updateZoom();
$('wave-zoom').addEventListener('input', () => updateZoom());
$('zoom-in').addEventListener('click', () => updateZoom(.5));
$('zoom-out').addEventListener('click', () => updateZoom(-.5));
document.querySelectorAll('.wave-container').forEach(element => element.addEventListener('wheel', event => {
  if (!event.ctrlKey) return;
  event.preventDefault(); updateZoom(event.deltaY > 0 ? -.15 : .15);
}, {passive:false}));
$('network-open').addEventListener('click', () => $('network-dialog').showModal());
$('network-dialog').addEventListener('click', event => { if (event.target === $('network-dialog')) $('network-dialog').close(); });
$('routing-open').addEventListener('click', () => $('routing-dialog').showModal());
$('routing-dialog').addEventListener('click', event => { if (event.target === $('routing-dialog')) $('routing-dialog').close(); });
document.querySelectorAll('[data-deck]').forEach(button => button.addEventListener('click', () => {
  const visible = button.getAttribute('aria-pressed') !== 'true';
  button.setAttribute('aria-pressed', String(visible));
  $(`title-${button.dataset.deck}`).closest('.deck').hidden = !visible;
}));
$('show-empty').addEventListener('change', () => $('decks').classList.toggle('show-empty', $('show-empty').checked));
$('midi-mode').addEventListener('change', hint);
$('refresh-midi').addEventListener('click', ports);
$('fullscreen').addEventListener('click', async () => {
  try { if (document.fullscreenElement) await document.exitFullscreen(); else await document.documentElement.requestFullscreen(); }
  catch { message('Le plein écran n’est pas disponible dans ce navigateur.', true); }
});
document.addEventListener('fullscreenchange', () => $('fullscreen').textContent = document.fullscreenElement ? 'Quitter le plein écran' : 'Plein écran');
setInterval(() => $('time').textContent = new Date().toLocaleTimeString('fr-FR'), 1000);
if (!['localhost', '127.0.0.1'].includes(location.hostname)) {
  $('apply').disabled = true; message('Réglage des sorties depuis localhost:8080 sur le PC serveur.');
}
ports().then(poll);

function formatTime(ms) {
  if (ms == null || ms < 0) return '--:--';
  const seconds = Math.floor(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
function trackKey(d) { return `${d.trackId}:${d.sourcePlayer}:${d.source}`; }
async function loadWave(d) {
  const key = trackKey(d), previous = waves.get(d.number);
  if (!d.statusAvailable) return;
  if (!d.trackId) { waves.delete(d.number); return; }
  if (previous?.key === key || wavePending.has(d.number) || (waveRetry.get(`${d.number}:${key}`) || 0) > performance.now()) return;
  waves.delete(d.number); wavePending.add(d.number);
  try {
    const response = await fetch(`/api/waveform/${d.number}`, {signal:AbortSignal.timeout(5000)});
    if (!response.ok) throw new Error();
    const data = await response.json();
    const current = state?.decks.find(deck => deck.number === d.number);
    if (data.available && current && trackKey(current) === key && data.trackId === d.trackId && data.sourcePlayer === d.sourcePlayer && data.sourceSlot === d.source) waves.set(d.number, {...data, key});
    else waveRetry.set(`${d.number}:${key}`, performance.now() + 3000);
  } catch { waveRetry.set(`${d.number}:${key}`, performance.now() + 5000); }
  finally { wavePending.delete(d.number); }
}
function drawWave(canvas, wave, d, position, zoom) {
  const ratio = Math.min(devicePixelRatio || 1, 2), width = Math.round(canvas.clientWidth * ratio), height = Math.round(canvas.clientHeight * ratio);
  if (!width || !height) return;
  if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
  const ctx = canvas.getContext('2d'); ctx.clearRect(0, 0, width, height);
  ctx.strokeStyle = '#2a2d2d'; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(0, height / 2); ctx.lineTo(width, height / 2); ctx.stroke();
  if (!wave?.samples?.length) return;
  const duration = wave.duration;
  const span = zoom ? zoomSeconds() * 1000 : duration;
  const start = zoom && position != null ? position - span / 3 : 0;
  const xAt = time => (time - start) / span * width;
  const count = wave.samples.length;
  for (let x = 0; x < width; x += Math.max(1, ratio)) {
    const time = start + x / width * span;
    const index = Math.floor(time / duration * count);
    if (index < 0 || index >= count) continue;
    const end = Math.min(count, Math.max(index + 1, Math.floor((time + span / width * ratio) / duration * count)));
    let sample = wave.samples[index];
    for (let i = index + 1; i < end; i++) if (wave.samples[i][0] > sample[0]) sample = wave.samples[i];
    const h = Math.max(1, sample[0] / 31 * height * .42);
    ctx.fillStyle = `rgb(${sample[1]},${sample[2]},${sample[3]})`; ctx.fillRect(x, height / 2 - h, ratio, h * 2);
  }
  if (d.looping && d.loopStart != null && d.loopEnd != null) {
    const left = xAt(d.loopStart), right = xAt(d.loopEnd);
    ctx.fillStyle = '#edb95b28'; ctx.fillRect(left, 0, right - left, height);
    ctx.strokeStyle = '#edb95b'; ctx.lineWidth = 2 * ratio;
    ctx.setLineDash(d.loopInferred ? [5 * ratio, 3 * ratio] : []);
    ctx.strokeRect(left, ratio, right - left, height - 2 * ratio); ctx.setLineDash([]);
    if (zoom && right > 0 && left < width) {
      ctx.font = `600 ${14 * ratio}px sans-serif`;
      const text = `${d.loopInferred ? '≈ ' : ''}LOOP ${d.loopBeats || ''}`;
      const x = Math.max(4 * ratio, left + 5 * ratio);
      ctx.fillStyle = '#edb95b'; ctx.fillRect(x, 4 * ratio, ctx.measureText(text).width + 12 * ratio, 22 * ratio);
      ctx.fillStyle = '#171711'; ctx.fillText(text, x + 6 * ratio, 20 * ratio);
    }
  }
  for (const cue of d.cues || []) {
    const x = xAt(cue.time); if (x < 0 || x > width) continue;
    ctx.fillStyle = cue.hotCue ? '#87d7c6' : '#c6adf5'; ctx.fillRect(x, 0, ratio, height);
    if (zoom && cue.hotCue) { ctx.font = `${14 * ratio}px sans-serif`; ctx.fillText(String.fromCharCode(64 + cue.hotCue), x + 4 * ratio, 15 * ratio); }
  }
  if (position != null) { ctx.fillStyle = '#ffffff'; ctx.fillRect(xAt(position), 0, 2 * ratio, height); }
}
function animateWaves() {
  if (state && !document.hidden) for (const d of state.decks) {
    const wave = waves.get(d.number);
    let position = motions.get(d.number).position(performance.now(), Number($('smoothing').value));
    const duration = d.duration ? d.duration * 1000 : wave?.duration;
    if (position != null && duration) position = Math.min(duration, Math.max(0, position));
    $(`wave-empty-${d.number}`).hidden = !!wave;
    $(`wave-empty-${d.number}`).textContent = !transportOnline ? 'Connexion perdue' : d.trackId ? 'Waveform non transmise par le matériel' : 'Waveform en attente du matériel';
    $(`elapsed-${d.number}`).textContent = formatTime(position);
    $(`clock-${d.number}`).textContent = position == null ? '--:--:--.---' : `${String(Math.floor(position / 3600000)).padStart(2,'0')}:${String(Math.floor(position / 60000) % 60).padStart(2,'0')}:${String(Math.floor(position / 1000) % 60).padStart(2,'0')}.${String(Math.floor(position) % 1000).padStart(3,'0')}`;
    $(`clock-label-${d.number}`).textContent = position == null ? 'Position indisponible' : !transportOnline || !d.statusAvailable ? 'Signal perdu · position figée' : d.positionPrecise ? 'Position reçue' : 'Position estimée';
    $(`remaining-${d.number}`).textContent = position != null && duration ? `−${formatTime(Math.max(0, duration - position))}` : '--:--';
    $(`position-info-${d.number}`).textContent = position == null ? 'Position indisponible' : !transportOnline || !d.statusAvailable ? 'Signal perdu · position figée' : d.positionPrecise ? 'Position du lecteur' : 'Position estimée par la grille';
    drawWave($(`wave-${d.number}`), wave, d, position, true);
    drawWave($(`overview-${d.number}`), wave, d, position, false);
  }
  requestAnimationFrame(animateWaves);
}
requestAnimationFrame(animateWaves);
