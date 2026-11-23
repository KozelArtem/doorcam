'use strict';

const stage = document.querySelector('#stage');
const live = document.querySelector('#live');
const toggle = document.querySelector('#toggle-live');
const liveStatus = document.querySelector('#live-status');
let liveRequest, liveUrl, rotation = 0;

function fitLive() {
  if (!live.videoWidth || !live.videoHeight) return;
  const sideways = rotation % 180 !== 0;
  const scale = Math.min(stage.clientWidth / (sideways ? live.videoHeight : live.videoWidth),
    stage.clientHeight / (sideways ? live.videoWidth : live.videoHeight));
  live.style.width = Math.floor(live.videoWidth * scale) + 'px';
  live.style.height = Math.floor(live.videoHeight * scale) + 'px';
  live.style.transform = 'translate(-50%, -50%) rotate(' + rotation + 'deg)';
}
window.addEventListener('resize', fitLive);
live.addEventListener('resize', fitLive);
live.addEventListener('playing', () => { liveStatus.textContent = ''; });
live.addEventListener('error', () => {
  if (liveRequest && live.error) stopLive('Не удалось воспроизвести эфир.');
});

function stopLive(message = '') {
  if (liveRequest) liveRequest.abort();
  liveRequest = null;
  live.removeAttribute('src');
  live.load();
  if (liveUrl) URL.revokeObjectURL(liveUrl);
  liveUrl = null;
  stage.hidden = true;
  toggle.textContent = 'Смотреть эфир';
  liveStatus.textContent = message;
}

function mediaOperation(target, eventName, signal, start) {
  return new Promise((resolve, reject) => {
    function finish(error) {
      target.removeEventListener(eventName, ready);
      target.removeEventListener('error', failed);
      signal.removeEventListener('abort', cancelled);
      if (error) reject(error);
      else resolve();
    }
    const ready = () => finish();
    const failed = () => finish(Error('Не удалось воспроизвести видео.'));
    const cancelled = () => finish(Error('Просмотр остановлен.'));
    if (signal.aborted) return cancelled();
    target.addEventListener(eventName, ready, { once: true });
    target.addEventListener('error', failed, { once: true });
    signal.addEventListener('abort', cancelled, { once: true });
    try { start(); } catch (error) { finish(error); }
  });
}

async function startLive() {
  if (!window.MediaSource) {
    liveStatus.textContent = 'Этот браузер не поддерживает Media Source Extensions.';
    return;
  }
  const request = new AbortController();
  const { signal } = request;
  liveRequest = request;
  toggle.textContent = 'Остановить эфир';
  liveStatus.textContent = 'Подключение к камере…';
  try {
    const response = await fetch('/live.mp4', { signal });
    if (signal.aborted) return;
    if (!response.ok) throw Error('Эфир недоступен. Попробуйте снова.');
    const mime = 'video/mp4; codecs="' + response.headers.get('X-Video-Codec') + '"';
    if (!MediaSource.isTypeSupported(mime) || !response.body) throw Error('Этот браузер не поддерживает формат эфира.');
    rotation = Number(response.headers.get('X-Video-Rotation')) || 0;
    const source = new MediaSource();
    stage.hidden = false;
    await mediaOperation(source, 'sourceopen', signal, () => {
      liveUrl = URL.createObjectURL(source);
      live.src = liveUrl;
    });
    const buffer = source.addSourceBuffer(mime);
    const reader = response.body.getReader();
    let started = false;
    try {
      while (!signal.aborted) {
        const { value, done } = await reader.read();
        if (done) throw Error('Соединение с камерой прервалось.');
        await mediaOperation(buffer, 'updateend', signal, () => buffer.appendBuffer(value));
        if (!buffer.buffered.length) continue;
        const start = buffer.buffered.start(0);
        const end = buffer.buffered.end(buffer.buffered.length - 1);
        if (!started && end - start >= 0.20) {
          started = true;
          if (start > 0) live.currentTime = start;
          live.play().catch(() => {
            if (liveRequest === request) stopLive('Браузер остановил воспроизведение. Нажмите «Смотреть эфир».');
          });
        }
        if (started && end - live.currentTime > 2) live.currentTime = Math.max(start, end - 0.35);
        if (live.currentTime - start > 8)
          await mediaOperation(buffer, 'updateend', signal, () => buffer.remove(0, live.currentTime - 4));
      }
    } finally {
      reader.releaseLock();
    }
  } catch (error) {
    if (liveRequest === request) stopLive(error.message);
  }
}

toggle.addEventListener('click', () => { if (liveRequest) stopLive(); else startLive(); });
window.addEventListener('pagehide', () => { if (liveRequest) stopLive(); });

const events = document.querySelector('#events');
const clips = document.querySelector('#clips');
const clipRow = document.querySelector('#clip-row');
const archiveStatus = document.querySelector('#archive-status');
const recording = document.querySelector('#recording');
const download = document.querySelector('#download');
const refresh = document.querySelector('#refresh');
const date = new Intl.DateTimeFormat('ru', { dateStyle: 'medium', timeStyle: 'medium' });
const day = new Intl.DateTimeFormat('ru', { dateStyle: 'medium' });
const time = new Intl.DateTimeFormat('ru', { timeStyle: 'medium' });
let archiveRequest;

async function list(path, signal) {
  const response = await fetch(path, { signal });
  if (!response.ok) throw Error('Запись недоступна. Обновите архив.');
  return response.json();
}

function clearRecording() {
  clipRow.hidden = true;
  clips.replaceChildren();
  download.hidden = true;
  download.removeAttribute('href');
  recording.hidden = true;
  recording.removeAttribute('src');
  recording.load();
}

async function loadClips() {
  if (archiveRequest) archiveRequest.abort();
  const request = new AbortController();
  archiveRequest = request;
  clearRecording();
  const event = events.value;
  if (!event) return;
  try {
    const entries = await list('/archive/' + event, request.signal);
    if (archiveRequest !== request) return;
    clips.replaceChildren(...entries.map(id => new Option(date.format(id), id)));
    clipRow.hidden = entries.length < 2;
    archiveStatus.textContent = entries.length ? '' : 'Для этого события пока нет видео.';
    if (entries.length) showClip();
  } catch (error) {
    if (!request.signal.aborted) archiveStatus.textContent = error.message;
  }
}

function groupEvents(entries) {
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);
  const labels = new Map([[today.toDateString(), 'Сегодня'], [yesterday.toDateString(), 'Вчера']]);
  const groups = new Map();
  for (const id of entries) {
    const key = new Date(id).toDateString();
    if (!groups.has(key)) {
      const group = document.createElement('optgroup');
      group.label = labels.get(key) || day.format(id);
      groups.set(key, group);
    }
    groups.get(key).append(new Option(time.format(id), id));
  }
  return groups.values();
}

async function loadEvents() {
  if (archiveRequest) archiveRequest.abort();
  const request = new AbortController();
  archiveRequest = request;
  const selected = events.value;
  events.disabled = true;
  refresh.disabled = true;
  archiveStatus.textContent = 'Загрузка записей…';
  try {
    const entries = await list('/events', request.signal);
    events.replaceChildren(new Option('Выберите событие', ''), ...groupEvents(entries.reverse()));
    events.value = entries.includes(Number(selected)) ? selected : '';
    events.disabled = entries.length === 0;
    archiveStatus.textContent = entries.length ? '' : 'Записей пока нет.';
    if (events.value) await loadClips();
    else clearRecording();
  } catch (error) {
    archiveStatus.textContent = error.message;
    events.disabled = events.options.length === 0;
  } finally {
    refresh.disabled = false;
  }
}

function showClip(play = false) {
  const path = '/archive/' + events.value + '/' + clips.value + '.mp4';
  recording.src = path;
  recording.hidden = false;
  download.href = path;
  download.download = 'doorcam-' + clips.value + '.mp4';
  download.hidden = false;
  if (play) recording.play().catch(() => {});
}

events.addEventListener('change', () => {
  archiveStatus.textContent = '';
  loadClips();
});
clips.addEventListener('change', () => showClip());
refresh.addEventListener('click', loadEvents);
recording.addEventListener('ended', () => {
  if (clips.selectedIndex + 1 < clips.options.length) {
    clips.selectedIndex++;
    showClip(true);
  }
});
recording.addEventListener('error', () => {
  if (!recording.hidden) archiveStatus.textContent = 'Не удалось открыть запись. Обновите архив.';
});
loadEvents();
