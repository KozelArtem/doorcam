/** One ordered DataChannel carries bounded JSON headers followed by <=16 KiB binary frames. */
export class FileBody {
  constructor(size, mime) {
    if (!Number.isSafeInteger(size) || size <= 0 || size > 32 * 1024 * 1024 ||
        !['video/mp4', 'image/jpeg'].includes(mime)) throw Error('Недопустимый размер или тип файла');
    this.remaining = size;
    this.mime = mime;
    this.parts = [];
  }

  append(frame) {
    if (!(frame instanceof ArrayBuffer) || !frame.byteLength ||
        frame.byteLength > 16384 || frame.byteLength > this.remaining) throw Error('Неверный размер блока');
    this.remaining -= frame.byteLength;
    this.parts.push(frame);
  }

  finish() {
    if (this.remaining) throw Error('Файл получен не полностью');
    return new Blob(this.parts, {type: this.mime});
  }
}

export class DoorPeer {
  constructor({video, bufferVideo, onStatus = () => {}, onMode = () => {}, onArchive = () => {}}) {
    this.video = video;
    this.bufferVideo = bufferVideo;
    this.activeVideo = video;
    this.onStatus = onStatus;
    this.onMode = onMode;
    this.onArchive = onArchive;
    this.pending = new Map();
    this.sequence = 0;
    this.connection = 0;
    this.media = 0;
    this.requested = {op: 'idle'};
    this.queue = [];
    this.delay = 1000;
    this.listVersion = 0;
    const failPlayback = () => {
      this.stop();
      this.onStatus('online', 'Браузер не смог воспроизвести этот видеофайл.');
    };
    for (const player of [video, bufferVideo].filter(Boolean)) {
      player.onended = () => {
        if (player === this.activeVideo) this.advance();
      };
      player.onerror = () => {
        if ((player === this.activeVideo && this.currentUrl) || player === this.staged?.player) {
          failPlayback();
        }
      };
    }
  }

  connect() {
    if (this.socket) return;
    this.closed = false;
    clearTimeout(this.retry);
    this.onStatus('connecting', 'Подключаемся к камере…');
    const url = new URL('/api/viewer', location.href);
    url.protocol = 'wss:';
    const ws = new WebSocket(url.href);
    this.socket = ws;
    ws.onmessage = e => {
      if (this.socket !== ws) return;
      let frame;
      try { frame = JSON.parse(e.data); } catch { return ws.close(); }
      if (frame.type === 'offline') {
        this.resetPeer();
        this.onStatus('offline', 'Телефон вне сети. Подключение восстановится автоматически.');
      } else if (frame.type === 'ready') {
        this.negotiate(ws);
      } else if (frame.type === 'answer' || frame.type === 'ice') {
        const pc = this.pc;
        if (!pc) return;
        // Signaling callbacks are asynchronous; apply the SDP before queued ICE candidates.
        this.signaling = this.signaling.then(async () => {
          if (pc !== this.pc) return;
          if (frame.type === 'answer') await pc.setRemoteDescription({type:'answer', sdp: frame.sdp});
          else await pc.addIceCandidate(frame.candidate);
        }).catch(() => { if (pc === this.pc) ws.close(); });
      }
    };
    ws.onerror = () => ws.close();
    ws.onclose = async event => {
      if (this.socket !== ws) return;
      this.socket = null;
      this.resetPeer();
      const token = this.connection;
      let denied = event.code === 1008;
      // Browsers mask the HTTP status of a failed upgrade. Recheck the session after a drop.
      if (event.code === 1006) {
        try {
          const response = await fetch('/api/me', {cache: 'no-store'});
          denied = response.status === 401 || response.status === 403;
        } catch { /* Network recovery is automatic. */ }
      }
      if (this.closed || token !== this.connection) return;
      if (denied) return this.onStatus('unauthorized', 'Сеанс завершён. Войди снова.');
      this.onStatus('offline', 'Связь прервалась. Пробуем подключиться снова.');
      this.retry = setTimeout(() => this.connect(), this.delay);
      this.delay = Math.min(30000, this.delay * 2);
    };
  }

  async negotiate(ws) {
    this.resetPeer();
    const token = this.connection;
    this.onStatus('connecting', 'Устанавливаем защищённое соединение с телефоном…');
    this.deadline = setTimeout(() => {
      if (ws === this.socket && token === this.connection) ws.close();
    }, 45000);
    try {
      const response = await fetch('/api/ice', {cache: 'no-store'});
      if (token !== this.connection) return;
      if (response.status === 401 || response.status === 403) {
        this.close();
        return this.onStatus('unauthorized', 'Нет доступа к камере.');
      }
      if (!response.ok) throw Error();
      const {iceServers} = await response.json();
      if (token !== this.connection) return;
      const pc = new RTCPeerConnection({iceServers});
      this.pc = pc;
      this.signaling = Promise.resolve();
      const ch = pc.createDataChannel('doorcam', {ordered: true});
      this.channel = ch;
      ch.binaryType = 'arraybuffer';
      const outbound = [];
      let offered = false;
      pc.onicecandidate = e => {
        if (!e.candidate || this.pc !== pc) return;
        const candidate = e.candidate.toJSON ? e.candidate.toJSON() : e.candidate;
        const frame = JSON.stringify({type:'ice', candidate});
        if (offered && ws.readyState === WebSocket.OPEN) ws.send(frame); else outbound.push(frame);
      };
      pc.oniceconnectionstatechange = () => {
        if (pc === this.pc && pc.iceConnectionState === 'failed') ws.close();
      };
      ch.onopen = () => {
        if (ch !== this.channel) return;
        clearTimeout(this.deadline);
        this.delay = 1000;
        this.onStatus('online', '');
        this.beginMedia();
        void this.list();
      };
      ch.onmessage = e => {
        if (ch !== this.channel) return;
        try { this.receive(e.data); } catch { ws.close(); }
      };
      ch.onclose = () => { if (ch === this.channel) ws.close(); };
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      if (this.pc !== pc || ws.readyState !== WebSocket.OPEN) return;
      ws.send(JSON.stringify({type:'offer', sdp:pc.localDescription.sdp}));
      offered = true;
      outbound.forEach(frame => ws.send(frame));
    } catch {
      if (token === this.connection) ws.close();
    }
  }

  resetPeer() {
    ++this.connection;
    ++this.listVersion;
    clearTimeout(this.deadline);
    this.clearMedia();
    const ch = this.channel, pc = this.pc;
    this.channel = this.pc = null;
    if (ch) ch.close();
    if (pc) pc.close();
    for (const p of this.pending.values()) {
      clearTimeout(p.timer);
      p.reject(Error('Соединение закрыто'));
    }
    this.pending.clear();
    this.incoming = null;
  }

  close() {
    this.closed = true;
    clearTimeout(this.retry);
    this.requested = {op:'idle'};
    const ws = this.socket;
    this.socket = null;
    if (ws) ws.close();
    this.resetPeer();
  }

  request(cmd, media = false) {
    const ch = this.channel;
    if (ch?.readyState !== 'open') return Promise.reject(Error('Камера вне сети'));
    const req = ++this.sequence;
    return new Promise((resolve, reject) => {
      const p = {resolve, reject, media, op: cmd.op, progress: Date.now()};
      const timeout = () => {
        const remaining = 90000 - (Date.now() - p.progress);
        if (remaining > 0) p.timer = setTimeout(timeout, remaining);
        else this.cancel(req);
      };
      p.timer = setTimeout(timeout, 90000);
      this.pending.set(req, p);
      try { ch.send(JSON.stringify({...cmd, req})); } catch { this.cancel(req); }
    });
  }

  cancel(req) {
    const p = this.pending.get(req);
    if (!p) return;
    clearTimeout(p.timer);
    this.pending.delete(req);
    if (this.channel?.readyState === 'open') {
      try { this.channel.send(JSON.stringify({op:'cancel', req})); } catch { /* Closing. */ }
    }
    p.reject(Error('Запрос отменён или истекло время ожидания'));
  }

  receive(data) {
    if (typeof data !== 'string') {
      const p = this.pending.get(this.incoming);
      if (p) {
        p.body.append(data);
        p.progress = Date.now();
      }
      return;
    }
    const msg = JSON.parse(data);
    if (msg.type === 'begin') this.incoming = msg.req;
    if (msg.type === 'end' || msg.type === 'error') this.incoming = null;
    const p = this.pending.get(msg.req);
    if (!p) return; // An earlier selection was cancelled while bytes were in flight.
    if (msg.type === 'begin') {
      p.body = new FileBody(msg.size, msg.mime);
      p.stamp = msg.stamp;
      p.progress = Date.now();
      return;
    }
    let value;
    if (msg.type === 'end') value = {blob:p.body.finish(), stamp:p.stamp};
    else if (msg.type === 'result') value = msg.value;
    else if (msg.type !== 'error') throw Error('Неизвестное сообщение');
    clearTimeout(p.timer);
    this.pending.delete(msg.req);
    if (msg.type === 'error') p.reject(Error(msg.message));
    else p.resolve(value);
  }

  async list() {
    const version = ++this.listVersion;
    const events = [];
    let before = '';
    try {
      do {
        const result = await this.request({op:'list', before});
        if (version !== this.listVersion) return;
        events.push(...result.events);
        if (result.next && before && Number(result.next) >= Number(before)) throw Error();
        before = result.next;
      } while (before);
      this.onArchive(events);
    } catch {
      if (version === this.listVersion) this.onStatus('online', 'Не удалось прочитать список записей.');
    }
  }

  async preview(id) {
    const received = await this.request({op:'preview', id});
    return received.blob;
  }

  live() { this.requested = {op:'live'}; this.beginMedia(); }
  playEvent(id) { this.requested = {op:'segment', id:String(id)}; this.beginMedia(); }
  stop() { this.requested = {op:'idle'}; this.clearMedia(); }

  clearMedia(notify = true) {
    ++this.media;
    if (this.subscribed && this.channel?.readyState === 'open') {
      try { this.channel.send(JSON.stringify({op:'watch', live:false})); } catch { /* Closing. */ }
    }
    this.subscribed = false;
    clearTimeout(this.nextPoll);
    this.nextPoll = null;
    for (const [id, p] of this.pending) if (p.media) this.cancel(id);
    this.fetching = false;
    this.done = false;
    this.after = '';
    this.queue = [];
    if (this.staged) {
      this.releaseVideo(this.staged.player, this.staged.url);
      this.staged = null;
    }
    if (this.displayed) {
      this.displayed = false;
      this.releaseVideo(this.activeVideo, this.currentUrl);
      this.currentUrl = null;
    }
    this.activeVideo = this.video;
    this.video.hidden = false;
    if (this.bufferVideo) this.bufferVideo.hidden = true;
    if (notify) this.onMode('idle');
  }

  beginMedia() {
    this.clearMedia(false);
    if (this.requested.op === 'idle' || this.channel?.readyState !== 'open') return;
    this.onMode(this.requested.op === 'live' ? 'live' : 'archive');
    for (const [id, p] of this.pending) if (p.op === 'preview') this.cancel(id);
    if (this.requested.op === 'live') {
      this.subscribed = true;
      this.channel.send(JSON.stringify({op:'watch', live:true}));
    }
    this.lastSegment = Date.now();
    void this.fill();
  }

  async fill() {
    if (this.fetching || this.queue.length || this.done || this.requested.op === 'idle') return;
    const token = this.media;
    this.fetching = true;
    try {
      const file = await this.request({...this.requested, after: this.after}, true);
      if (token !== this.media) return;
      this.fetching = false;
      if (!file || file.growing) {
        // A Telegram notification can arrive before the first MP4 has been finalized.
        if (this.requested.op === 'live' || ((file?.growing || !this.after) && Date.now() - this.lastSegment < 12000)) {
          const interval = this.requested.op === 'live' ? 300 : 700;
          this.nextPoll = setTimeout(() => { this.nextPoll = null; void this.fill(); }, interval);
        } else {
          this.done = true;
          if (!this.displayed) {
            this.stop();
            this.onStatus('online', 'В записи пока нет видео.');
          }
        }
        return;
      }
      this.lastSegment = Date.now();
      this.after = String(file.stamp);
      this.queue.push(file.blob);
      if (this.currentUrl) this.stageNext();
      if (!this.currentUrl || this.activeVideo.ended) this.advance();
    } catch (e) {
      if (token === this.media) {
        this.stop();
        this.onStatus('online', e.message || 'Запись недоступна.');
      }
    }
  }

  releaseVideo(player, url) {
    player.oncanplay = null;
    player.pause();
    player.removeAttribute('src');
    player.load();
    if (url) URL.revokeObjectURL(url);
  }

  stageNext() {
    if (!this.bufferVideo || this.staged || !this.queue.length) return;
    const player = this.activeVideo === this.video ? this.bufferVideo : this.video;
    const url = URL.createObjectURL(this.queue[0]);
    const staged = {player, url};
    this.staged = staged;
    player.oncanplay = () => {
      if (this.staged === staged && this.activeVideo.ended) this.advance();
    };
    player.src = url;
    player.load();
  }

  advance() {
    if (!this.queue.length) {
      if (!this.done && !this.fetching && !this.nextPoll) void this.fill();
      return;
    }
    if (this.staged && this.staged.player.readyState < 3) return;
    const next = this.queue.shift();
    const previousPlayer = this.activeVideo;
    const previousUrl = this.currentUrl;
    this.activeVideo = this.staged?.player ?? previousPlayer;
    this.currentUrl = this.staged?.url ?? URL.createObjectURL(next);
    this.activeVideo.oncanplay = null;
    if (!this.staged) this.activeVideo.src = this.currentUrl;
    this.staged = null;
    this.displayed = true;
    this.activeVideo.hidden = false;
    if (previousPlayer !== this.activeVideo) {
      previousPlayer.hidden = true;
      this.releaseVideo(previousPlayer, previousUrl);
    } else if (previousUrl) URL.revokeObjectURL(previousUrl);
    this.activeVideo.play().catch(e => {
      if (e.name === 'NotAllowedError') this.onStatus('online', 'Нажми ▶ на видео для воспроизведения.');
    });
    void this.fill();
  }
}
