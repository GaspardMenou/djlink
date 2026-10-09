const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const WaveCache = require('../main/resources/web/waves.js');
const deck = {number:1,statusAvailable:true,trackId:88,sourcePlayer:1,source:'USB_2_SLOT',trackType:'REKORDBOX'};
function deferred() { let resolve; const promise = new Promise(r => resolve = r); return {promise, resolve}; }
function response(generation, trackId = 88, sample = 1) {
  return {ok:true,json:async () => ({available:true,sourceGeneration:generation,trackId,sourcePlayer:1,
    sourceSlot:deck.source,trackType:deck.trackType,samples:[[sample,0,0,0]],duration:1000})};
}
async function cacheChecks() {
  const requests = []; let now = 0;
  const cache = new WaveCache((url, options) => { const request = deferred(); requests.push({...request,options}); return request.promise; }, () => now);
  cache.setGeneration('A1');
  const old = cache.load(deck); const oldRequest = cache.pending.get(1);
  await cache.load(deck); assert.equal(requests.length, 1, 'Repeated polls do not create concurrent fetches');
  cache.setGeneration('B1'); assert.equal(oldRequest.controller.signal.aborted, true);
  const current = cache.load(deck);
  requests[0].resolve(response('A1')); await old;
  assert.equal(cache.waves.size, 0);
  assert.equal(cache.pending.size, 1, 'Old cleanup must not remove the new request');
  requests[1].resolve(response('B1', 88, 2)); await current;
  assert.equal(cache.waves.get(1).samples[0][0], 2);
  cache.setGeneration('A2'); assert.equal(cache.waves.size, 0);
  const back = cache.load(deck); requests[2].resolve(response('A2', 88, 3)); await back;
  assert.equal(cache.waves.get(1).samples[0][0], 3, 'A to B to A uses a fresh generation');
  cache.setGeneration('restart'); const restarted = cache.load(deck);
  requests[3].resolve(response('A2')); await restarted;
  assert.equal(cache.waves.size, 0, 'A response from before engine restart is discarded');
  now = 2999; await cache.load(deck); assert.equal(requests.length, 4);
  now = 3000; const retry = cache.load(deck); requests[4].resolve(response('restart')); await retry;
  assert.equal(cache.waves.size, 1);
  const changing = cache.load({...deck,trackId:89});
  const replacement = cache.load({...deck,trackId:90});
  assert.equal(requests[5].options.signal.aborted, true);
  requests[6].resolve(response('restart', 90, 9)); await replacement;
  requests[5].resolve(response('restart', 89, 8)); await changing;
  assert.equal(cache.waves.get(1).trackId, 90);
  await cache.load({...deck,statusAvailable:false}); assert.equal(cache.waves.get(1).trackId, 90, 'A short dropout retains the current wave');
  await cache.load({...deck,trackId:0}); assert.equal(cache.waves.size, 0);
}
async function renderChecks() {
  const elements = new Map();
  function element(id) {
    if (!elements.has(id)) elements.set(id, {value:id === 'smoothing' ? '120' : '',textContent:'',children:[],options:[],
      selectedOptions:[{textContent:'Normal'}],classList:{toggle(){},add(){},remove(){}},style:{setProperty(){}},
      append(child){this.children.push(child);},closest(){return this;},addEventListener(){}});
    return elements.get(id);
  }
  const context = vm.createContext({console,AbortController,performance:{now:()=>100},location:{hostname:'localhost'},
    document:{getElementById:element,createElement:()=>element(`created-${elements.size}`),querySelectorAll:()=>[],addEventListener(){}},
    localStorage:{getItem:()=>null,setItem(){}},requestAnimationFrame(){},setInterval(){},setTimeout(){return 1;},clearTimeout(){},
    fetch:url => url === '/api/midi' ? Promise.resolve({ok:true,json:async()=>[]}) : new Promise(()=>{})});
  for (const file of ['motion.js','waves.js','app.js']) vm.runInContext(fs.readFileSync(`src/main/resources/web/${file}`, 'utf8'), context);
  const data = generation => ({sourceGeneration:generation,target:'127.0.0.1',connected:true,lightingError:'',linkMessage:'Active',
    received:0,selectedDeck:1,outputBpm:120,settings:{deck:0,midi:-1,midiMode:'clock',channel:1,note:60,osc:false,oscHost:'127.0.0.1',oscPort:9000},
    sentClock:0,sentBeat:0,sentOsc:0,decks:[{...deck,bpm:120,title:'Old title',artist:'Old artist',playing:true,pitch:0,position:1000,beatNumber:1,cues:[]}]});
  context.sample = data('A1'); vm.runInContext('render(sample)', context);
  const firstMotion = vm.runInContext('motions.get(1)', context);
  const changed = data('B1'); changed.decks[0] = {...changed.decks[0],statusAvailable:false,trackId:null,title:null,artist:null,position:null};
  context.sample = changed; vm.runInContext('render(sample)', context);
  assert.equal(changed.decks[0].title, null, 'Device switches must not copy old titles into empty states');
  assert.notEqual(vm.runInContext('motions.get(1)', context), firstMotion);
  assert.equal(vm.runInContext('motions.get(1).value', context), null);
  context.sample = data('A2'); vm.runInContext('render(sample)', context);
  const sameSourceMotion = vm.runInContext('motions.get(1)', context);
  const dropout = data('A2'); dropout.decks[0] = {...dropout.decks[0],statusAvailable:false,title:null};
  context.sample = dropout; vm.runInContext('render(sample)', context);
  assert.equal(dropout.decks[0].title, 'Old title', 'Same-device dropouts still retain track information');
  assert.equal(vm.runInContext('motions.get(1)', context), sameSourceMotion);
  context.sample = data('restart'); vm.runInContext('render(sample)', context);
  assert.notEqual(vm.runInContext('motions.get(1)', context), sameSourceMotion);
}
(async () => {
  await cacheChecks(); await renderChecks();
  console.log('OK — waveform retries, device switches, engine restart, old-response rejection and motion reset.');
})().catch(error => { console.error(error); process.exitCode = 1; });
