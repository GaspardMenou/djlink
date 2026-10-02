const assert = require('node:assert/strict');
const Motion = require('../main/resources/web/motion.js');
const m = new Motion();
const deck = {statusAvailable:true,trackId:1,sourcePlayer:1,source:'USB',position:1000,beatNumber:3,playing:true,reverse:false,pitch:0};
m.sample(deck, 100);
assert.equal(m.position(200), 1100);
m.sample({...deck, position:1080}, 200); // 20 ms of packet jitter should ease, not jump.
assert.equal(m.position(200), 1100);
const eased = m.position(216); assert(eased >= 1113.6 && eased < 1116);
m.sample({...deck, position:100,beatNumber:1}, 250); assert.equal(m.position(250), 100); // seek back
m.sample({...deck,position:300,reverse:true}, 300); assert.equal(m.position(400), 200);
m.sample({...deck,position:1990,looping:true,loopStart:1000,loopEnd:2000}, 500);
assert.equal(m.position(520), 1010); // loop must wrap immediately
m.sample({...deck,position:1990,looping:true,loopStart:1000,loopEnd:2000,reverse:true}, 550);
assert.equal(m.position(560), 1980);
m.sample({...deck,position:1500,playing:true}, 600);
const frozen = m.position(2000); assert.equal(m.position(3000), frozen); // bounded extrapolation
m.sample({...deck,statusAvailable:false,position:null}, 3100); assert.equal(m.position(3200), frozen);
m.sample({...deck,trackId:2,position:4000}, 3300); assert.equal(m.position(3300), 4000);
const jitter = new Motion(); jitter.sample(deck, 100); jitter.position(200); jitter.sample({...deck,position:1200}, 200);
assert(jitter.position(216) <= 1118.4, 'Network corrections must not exceed 15% of frame speed');
m.sample({...deck,trackId:3,position:null}, 3400); assert.equal(m.position(3400), null);
console.log('OK — jitter smoothing, seek/reverse/loop snaps, dropout freeze, track reset.');
