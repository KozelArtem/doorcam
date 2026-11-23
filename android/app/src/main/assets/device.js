/* This script and its native interface exist only in the service's bundled page. */
'use strict';
window.device = (() => {
  const peers = new Map();
  const iceRequests = new Map();
  let ticket = 0;
  let socket, url, key, retry, lastReply, permanentFailure;
  let delay = 1000;

  function signal(frame) {
    if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(frame));
  }

  function leave(id) {
    const p = peers.get(id);
    if (!p) return;
    peers.delete(id);
    if (p.live) updateLive();
    p.closed = true;
    if (p.ticket) ice(p.ticket, null);
    if (p.pc) p.pc.close();
  }

  function updateLive() {
    Archive.live(Array.from(peers.values()).some(p => p.live));
  }

  function ice(n, servers) {
    const finish = iceRequests.get(n);
    if (finish) {
      iceRequests.delete(n);
      finish(servers);
    }
  }

  function pause(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }

  async function transfer(p, cmd) {
    const ch = p.channel;
    const reply = data => ch.send(JSON.stringify(Object.assign({req: cmd.req}, data)));
    if (cmd.op === 'list') {
      const page = JSON.parse(Archive.page(cmd.before || ''));
      reply({type: 'result', value: page});
      return;
    }
    if (['live', 'segment', 'preview'].indexOf(cmd.op) < 0) throw Error('Invalid operation');
    const raw = Archive.open(cmd.op, String(cmd.id || ''), String(cmd.after || ''));
    if (!raw) throw Error('Файл недоступен');
    const file = JSON.parse(raw);
    if (!file.token) {
      reply({type: 'result', value: file.growing ? {growing:true} : null});
      return;
    }
    try {
      reply({type: 'begin', size: file.size, stamp: file.stamp,
        mime: cmd.op === 'preview' ? 'image/jpeg' : 'video/mp4'});
      let total = 0;
      while (total < file.size) {
        if (p.closed || cmd.cancelled || ch.readyState !== 'open') return;
        // Reserve SCTP buffers for control messages and let the WebView service other peers.
        if (ch.bufferedAmount > 256 * 1024) {
          await pause(20);
          continue;
        }
        const text = atob(Archive.read(file.token));
        if (!text.length || total + text.length > file.size) throw Error('Файл изменился');
        const bytes = new Uint8Array(text.length);
        for (let i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
        ch.send(bytes.buffer);
        total += bytes.length;
        if (total % (256 * 1024) === 0) await pause(0);
      }
      reply({type: 'end'});
    } finally {
      Archive.release(file.token);
    }
  }

  async function drain(p) {
    if (p.busy) return;
    p.busy = true;
    try {
      while (!p.closed && p.queue.length) {
        const cmd = p.queue.shift();
        if (cmd.cancelled) continue;
        p.active = cmd;
        try {
          await transfer(p, cmd);
        } catch (e) {
          if (!p.closed && p.channel.readyState === 'open') {
            p.channel.send(JSON.stringify({req: cmd.req, type: 'error', message: 'Запись недоступна'}));
          }
        } finally { p.active = null; }
      }
    } finally { p.busy = false; }
  }

  function receive(p, event) {
    if (typeof event.data !== 'string' || event.data.length > 1024) return leave(p.id);
    try {
      const cmd = JSON.parse(event.data);
      if (cmd.op === 'watch' && typeof cmd.live === 'boolean') {
        p.live = cmd.live;
        updateLive();
        return;
      }
      if (!Number.isSafeInteger(cmd.req) || cmd.req < 1) throw Error();
      if (cmd.op === 'cancel') {
        if (p.active && p.active.req === cmd.req) p.active.cancelled = true;
        for (const queued of p.queue) if (queued.req === cmd.req) queued.cancelled = true;
      } else {
        if (p.queue.length >= 32) throw Error();
        p.queue.push(cmd);
        drain(p).catch(() => leave(p.id));
      }
    } catch (_) { leave(p.id); }
  }

  async function offer(msg) {
    leave(msg.peer);
    const p = {id: msg.peer, pendingIce: [], queue: [], ticket: ++ticket};
    peers.set(p.id, p);
    try {
      const servers = await new Promise(resolve => {
        iceRequests.set(p.ticket, resolve);
        Archive.requestIce(p.ticket);
      });
      if (p.closed) return;
      if (!servers) throw Error('ICE configuration unavailable');
      const pc = new RTCPeerConnection({iceServers: servers});
      p.pc = pc;
      const outbound = [];
      let answered = false;
      pc.onicecandidate = e => {
        if (!e.candidate || p.closed) return;
        const frame = {type: 'ice', peer: p.id, candidate: e.candidate.toJSON ? e.candidate.toJSON() : e.candidate};
        if (answered) signal(frame); else outbound.push(frame);
      };
      pc.oniceconnectionstatechange = () => {
        if (pc.iceConnectionState === 'failed' && peers.get(p.id) === p) leave(p.id);
      };
      pc.ondatachannel = e => {
        if (p.channel || e.channel.label !== 'doorcam' || p.closed) return e.channel.close();
        p.channel = e.channel;
        p.channel.onmessage = ev => receive(p, ev);
        p.channel.onclose = () => { if (peers.get(p.id) === p) leave(p.id); };
      };
      await pc.setRemoteDescription({type: 'offer', sdp: msg.sdp});
      if (p.closed) return;
      for (const candidate of p.pendingIce) await pc.addIceCandidate(candidate);
      p.pendingIce = null;
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      if (p.closed) return;
      signal({type: 'answer', peer: p.id, sdp: pc.localDescription.sdp});
      answered = true;
      outbound.forEach(signal);
    } catch (_) {
      if (peers.get(p.id) === p) leave(p.id);
    }
  }

  function openSocket() {
    clearTimeout(retry);
    if (permanentFailure || socket) return;
    Archive.connection('connecting');
    const ws = new WebSocket(url);
    socket = ws;
    lastReply = Date.now();
    ws.onopen = () => { if (socket === ws) signal({type: 'auth', key: key}); };
    ws.onmessage = e => {
      if (socket !== ws) return;
      try {
        const msg = JSON.parse(e.data);
        lastReply = Date.now();
        if (msg.type === 'ready') { delay = 1000; Archive.connection('online'); }
        if (msg.type === 'offer') offer(msg);
        if (msg.type === 'leave') leave(msg.peer);
        if (msg.type === 'ice') {
          const p = peers.get(msg.peer);
          if (!p || !msg.candidate) return;
          if (p.pendingIce) {
            if (p.pendingIce.length < 256) p.pendingIce.push(msg.candidate);
          } else p.pc.addIceCandidate(msg.candidate).catch(() => {});
        }
      } catch (_) { ws.close(); }
    };
    ws.onclose = e => {
      if (socket !== ws) return;
      socket = null;
      Array.from(peers.keys()).forEach(leave);
      Archive.connection(e.code === 1008 ? 'unauthorized' : 'offline');
      if (e.code === 1008) permanentFailure = true;
      else {
        retry = setTimeout(openSocket, delay);
        delay = Math.min(delay * 2, 30000);
      }
    };
    ws.onerror = () => ws.close();
  }

  return {
    start: (endpoint, secret) => { url = endpoint; key = secret; openSocket(); },
    ice,
    tick: () => {
      if (!url || permanentFailure) return;
      if (!socket) return openSocket();
      if (Date.now() - lastReply > 90000) socket.close();
      else signal({type: 'ping'});
    }
  };
})();
