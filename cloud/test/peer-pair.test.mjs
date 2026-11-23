import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import {DoorPeer} from '../public/peer.js';

const deviceScript = await readFile(new URL('../../android/app/src/main/assets/device.js', import.meta.url), 'utf8');
const originalFetch = globalThis.fetch;
async function until(predicate) {
  const end = performance.now() + 3500;
  while (!predicate()) {
    if (performance.now() > end) throw Error('Expected condition was not reached');
    await new Promise(resolve => setTimeout(resolve, 5));
  }
}
function pair(t, buffered = false) {
  let phone, viewer, viewerChannel, phoneChannel, phoneConnection, device, serial = 0;
  const held = new Map();
  const source = {
    live: false, present: false, growing: true, next: false, blocked: false,
    reads: 0, requests: [], releases: [], subscribed: [], pages: [], expired: false, eventIds: [200,100],
  };
  const archive = {
    live(value) { source.subscribed.push(value); source.live = value; },
    connection(value) { source.present = value === 'online'; },
    requestIce(ticket) { queueMicrotask(() => device.ice(ticket, [])); },
    page(before) {
      source.pages.push(before);
      const id = source.eventIds.find(value => !before || value < Number(before));
      return JSON.stringify({events:id ? [{id:String(id),time:id,segments:1}] : [],
        next:source.eventIds.some(value => value < id) ? String(id) : ''});
    },
    open(kind, id, after) {
      source.requests.push([kind, id, after]);
      let bytes, stamp;
      if (kind === 'preview') { bytes = [255,216,255,217]; stamp = id; }
      if (kind === 'segment') {
        if (source.expired) return JSON.stringify({expired:true});
        if (!after) { bytes = [11,12,13]; stamp = '10'; }
        else if (after === '10' && source.next) { bytes = [21,22]; stamp = '20'; }
        else return JSON.stringify({growing:source.growing});
      }
      if (kind === 'live') {
        if (after) return '{}';
        bytes = [31,32]; stamp = '30';
      }
      const token = ++serial;
      held.set(token, [...bytes]);
      return JSON.stringify({token, stamp, size: bytes.length});
    },
    read(token) {
      source.reads++;
      return Buffer.from(held.get(token).splice(0, 2)).toString('base64');
    },
    release(token) { source.releases.push(token); held.delete(token); }
  };
  function emit(ws, frame) {
    queueMicrotask(() => { if (ws?.readyState === 1) ws.onmessage?.({data:JSON.stringify(frame)}); });
  }
  class Socket {
    static OPEN = 1;
    constructor(url) {
      this.phone = String(url).endsWith('/api/device');
      if (this.phone) phone = this; else viewer = this;
      this.readyState = 0;
      queueMicrotask(() => {
        this.readyState = 1;
        this.onopen?.();
        if (!this.phone) emit(this, {type:source.present ? 'ready' : 'offline'});
      });
    }
    send(data) {
      const frame = JSON.parse(data);
      if (frame.type === 'auth') {
        assert.equal(frame.key, 'secret');
        emit(this, {type:'ready'});
      } else if (frame.type === 'ping') emit(this, {type:'pong'});
      else if (this.phone) emit(viewer, frame);
      else emit(phone, {...frame, peer:'peer1'});
    }
    close(code = 1000) {
      if (this.readyState === 3) return;
      this.readyState = 3;
      if (!this.phone) emit(phone, {type:'leave',peer:'peer1'});
      queueMicrotask(() => this.onclose?.({code}));
    }
  }
  function channel() {
    return {
      readyState:'connecting',label:'doorcam',bufferedAmount:0,
      send(data) {
        const target = this === viewerChannel ? phoneChannel : viewerChannel;
        queueMicrotask(() => { if (target.readyState === 'open') target.onmessage?.({data}); });
      },
      close() {
        if (this.readyState === 'closed') return;
        this.readyState = 'closed';
        queueMicrotask(() => this.onclose?.());
      }
    };
  }
  class PeerConnection {
    createDataChannel() { return (viewerChannel = channel()); }
    async createOffer() { return {type:'offer',sdp:'offer'}; }
    async createAnswer() { return {type:'answer',sdp:'answer'}; }
    async setLocalDescription(value) { this.localDescription = value; }
    async setRemoteDescription({type}) {
      if (type === 'offer') { phoneConnection = this; return; }
      phoneChannel = channel();
      phoneChannel.readyState = viewerChannel.readyState = 'open';
      phoneConnection.ondatachannel({channel:phoneChannel});
      queueMicrotask(() => viewerChannel.onopen());
    }
    close() { viewerChannel?.close(); phoneChannel?.close(); }
  }
  function replace(key, value) {
    const previous = Object.getOwnPropertyDescriptor(globalThis, key);
    Object.defineProperty(globalThis, key, {value,configurable:true});
    t.after(() => { if (previous) Object.defineProperty(globalThis,key,previous); else delete globalThis[key]; });
  }
  replace('WebSocket',Socket);
  replace('RTCPeerConnection',PeerConnection);
  replace('location',{href:'https://camera.example/'});
  replace('fetch', () => Promise.resolve(Response.json({iceServers:[]})));
  const window = {};
  vm.runInNewContext(deviceScript, {
    window, WebSocket:Socket, RTCPeerConnection:PeerConnection, Archive:archive, Uint8Array,
    atob, setTimeout, clearTimeout,
  });
  device = window.device;
  device.start('wss://camera.example/api/device','secret');
  const statuses = [], messages = [], archiveLists = [], modes = [], clips = [], health = [];
  const player = () => ({
    urls:[], ended:false, readyState:0,
    play() { this.ended = false; this.urls.push(this.src); return Promise.resolve(); },
    finish() { this.ended = true; this.onended(); },
    pause() {}, load() {}, removeAttribute() { delete this.src; }
  });
  const video = player();
  const bufferVideo = buffered ? player() : undefined;
  const peer = new DoorPeer({video, bufferVideo, onArchive:e => archiveLists.push(e),
    onStatus:state => statuses.push(state), onMode:mode => modes.push(mode),
    onPlayback:message => messages.push(message), onClip:clip => clips.push(clip),
    onHealth:capture => health.push(capture)});
  t.after(() => { peer.close(); phone.close(1008); });
  return {peer,source,video,bufferVideo,held,statuses,archiveLists,modes,messages,clips,health,
    block(flag) {phoneChannel.bufferedAmount = flag ? 500000 : 0;},
    revoke() {viewer.close(1008);},
    notify(frame) {emit(viewer, frame);}
  };
}

for (const buffered of [false, true]) {
  test(`paired clients play a late archive segment with ${buffered ? 'two players' : 'one player'}`, async t => {
    const {peer,source,video,bufferVideo,archiveLists,modes} = pair(t, buffered);
    await until(() => source.present);
    peer.connect();
    await until(() => archiveLists.length);
    assert.deepEqual(archiveLists[0].map(e => e.id), ['200']);
    assert.deepEqual([...new Uint8Array(await (await peer.preview('100')).arrayBuffer())], [255,216,255,217]);
    peer.playEvent('100');
    await until(() => source.requests.some(r => r[0] === 'segment' && r[2] === '10'));
    const contents = async url => [...new Uint8Array(await (await originalFetch(url)).arrayBuffer())];
    assert.deepEqual(await contents(video.urls[0]), [11,12,13]);
    video.finish();
    source.next = true;
    source.growing = buffered;
    const nextPlayer = bufferVideo ?? video;
    if (bufferVideo) {
      await until(() => bufferVideo.src);
      assert.equal(bufferVideo.urls.length, 0);
      bufferVideo.readyState = 3;
      bufferVideo.oncanplay();
    }
    await until(() => nextPlayer.urls.length === (buffered ? 1 : 2));
    assert.deepEqual(await contents(nextPlayer.urls.at(-1)), [21,22]);
    await until(() => source.requests.some(r => r[0] === 'segment' && r[2] === '20'));
    nextPlayer.finish();
    source.growing = false;
    await until(() => peer.done);
    assert.equal(modes.at(-1), 'archive');
    assert.ok(nextPlayer.src, 'keep the completed clip available to replay');
  });
}

test('the archive fetches later pages on demand and can play an event outside the first page', async t => {
  const {peer,source,video,archiveLists} = pair(t);
  await until(() => source.present);
  peer.connect();
  await until(() => archiveLists.length);
  assert.deepEqual(source.pages, ['']);
  peer.playEvent('100');
  await until(() => video.src);
  assert.deepEqual(source.pages, ['']);
  await peer.list('older');
  assert.deepEqual(archiveLists.at(-1).map(event => event.id), ['200','100']);
  assert.deepEqual(source.pages, ['','200']);
});

test('background archive refresh bridges new pages without discarding loaded history', async t => {
  const {peer,source,archiveLists} = pair(t);
  await until(() => source.present);
  peer.connect();
  await until(() => archiveLists.length);
  await peer.list('older');
  source.eventIds = [400,300,200,100];
  await peer.list('background');
  assert.deepEqual(archiveLists.at(-1).map(event => event.id), ['400','300','200','100']);
  assert.deepEqual(source.pages, ['','200','','400','300']);
  source.eventIds.pop();
  await peer.list('refresh');
  assert.deepEqual(archiveLists.at(-1).map(event => event.id), ['400','300','200']);
});

test('explicit growing state survives a long wait and keeps the displayed archive clip downloadable', async t => {
  const {peer,source,video,clips,messages} = pair(t);
  await until(() => source.present);
  peer.connect();
  await until(() => peer.channel?.readyState === 'open');
  peer.playEvent('100');
  await until(() => source.requests.some(r => r[2] === '10'));
  assert.deepEqual(clips.at(-1), {url:video.src, stamp:'10'});
  video.finish();
  t.mock.method(Date, 'now', () => Number.MAX_SAFE_INTEGER);
  await until(() => peer.done || messages.includes('Запись продолжается'));
  assert.equal(peer.done, false);
  assert.equal(messages.at(-1), 'Запись продолжается');
  source.next = true;
  source.growing = false;
  await until(() => video.urls.length === 2);
  assert.deepEqual(clips.at(-1), {url:video.src, stamp:'20'});
  peer.stop();
  assert.equal(clips.at(-1), null);
});

test('an expired archive event is distinct from a growing recording', async t => {
  const {peer,source,messages,archiveLists} = pair(t);
  source.expired = true;
  await until(() => source.present);
  peer.connect();
  await until(() => archiveLists.length);
  peer.playEvent('100');
  await until(() => source.requests.length);
  await until(() => messages.includes('Запись удалена из архива.'));
  assert.equal(source.requests.length, 1);
});

test('capture faults are reported separately from the viewer connection', async t => {
  const {peer,source,statuses,notify,health} = pair(t);
  await until(() => source.present);
  peer.connect();
  await until(() => statuses.at(-1) === 'online');
  notify({type:'health',capture:'failed'});
  await until(() => health.at(-1) === 'failed');
  assert.equal(statuses.at(-1), 'online');
});

test('changing selection cancels a backpressured file; a revoked live viewer releases capture', async t => {
  const {peer,source,held,video,statuses,archiveLists,block,revoke} = pair(t);
  await until(() => source.present);
  peer.connect();
  await until(() => archiveLists.length);
  block(true);
  peer.playEvent('100');
  await until(() => held.size === 1);
  const oldToken = [...held.keys()][0];
  const reads = source.reads;
  peer.live();
  await until(() => source.live && source.releases.includes(oldToken));
  assert.equal(source.reads, reads);
  block(false);
  await until(() => video.urls.length === 1);
  assert.deepEqual([...new Uint8Array(await (await originalFetch(video.urls[0])).arrayBuffer())], [31,32]);
  revoke();
  await until(() => statuses.includes('unauthorized') && !source.live);
  assert.equal(held.size, 0);
});

test('viewer can wait offline, then establish a new channel when the device returns', async t => {
  const {peer,source,statuses,archiveLists,notify} = pair(t);
  await until(() => source.present);
  peer.connect();
  await until(() => archiveLists.length);
  notify({type:'offline'});
  await until(() => statuses.at(-1) === 'offline');
  notify({type:'ready'});
  await until(() => archiveLists.length === 2);
  assert.equal(statuses.at(-1), 'online');
});
