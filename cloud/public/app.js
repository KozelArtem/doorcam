import { DoorPeer } from './peer.js';
import { dayKey, eventFromUrl, groupDays } from './library.mjs';

const el = id => document.getElementById(id);
const dateLabel = new Intl.DateTimeFormat('ru', {day:'numeric',month:'long',year:'numeric'});
const timeLabel = new Intl.DateTimeFormat('ru', {hour:'2-digit',minute:'2-digit',second:'2-digit'});
let peer, pendingEvent = eventFromUrl(location.search), events = [], selected = '', visible = 30;
let status = 'connecting', mode = 'idle', loadedArchive = false, generation = 0;
let observer, queue = [], inflight = 0;
const urls = new Set();

function gate(message, login = true, retry = false) {
  generation++;
  const old = peer;
  peer = null;
  old?.close();
  resetPhotos();
  events = [];
  selected = '';
  loadedArchive = false;
  el('recordings').replaceChildren();
  el('dashboard').hidden = true;
  el('gate').hidden = false;
  el('logout').hidden = true;
  el('login').hidden = !login;
  el('login').href = pendingEvent ? '/auth/login?event=' + encodeURIComponent(pendingEvent) : '/auth/login';
  el('retry').hidden = !retry;
  el('gate-message').textContent = message;
}
function resetPhotos() {
  observer?.disconnect();
  queue = [];
  for (const url of urls) URL.revokeObjectURL(url);
  urls.clear();
}
async function photos() {
  if (!peer || status !== 'online' || mode !== 'idle' || inflight) return;
  const target = queue.shift();
  if (!target) return;
  inflight++;
  const connection = peer;
  try {
    const blob = await connection.preview(target.dataset.id);
    if (peer === connection && target.isConnected && blob?.size) {
      const img = new Image();
      img.alt = '';
      img.src = URL.createObjectURL(blob);
      urls.add(img.src);
      target.replaceChildren(img);
    }
  } catch {
    if (peer === connection && mode !== 'idle' && target.isConnected) queue.unshift(target);
  }
  finally { inflight--; void photos(); }
}
function choose(id) {
  if (!peer || status !== 'online') return;
  selected = id;
  const list = el('recordings').querySelectorAll('.clip');
  for (const row of list) row.setAttribute('aria-current', String(row.dataset.id === id));
  peer.playEvent(id);
}
function list() {
  resetPhotos();
  const container = el('recordings');
  container.replaceChildren();
  el('more').hidden = events.length <= visible;
  if (!events.length) {
    const hint = document.createElement('p');
    hint.textContent = loadedArchive ? 'Записей пока нет.' : 'Ожидание архива на телефоне…';
    container.append(hint);
    return;
  }
  if ('IntersectionObserver' in window) {
    observer = new IntersectionObserver(items => {
      for (const item of items) if (item.isIntersecting) {
        observer.unobserve(item.target);
        queue.push(item.target);
      }
      void photos();
    });
  }
  for (const group of groupDays(events.slice(0, visible)).values()) {
    const date = document.createElement('h3');
    date.className = 'date';
    date.textContent = dayKey(group[0].time) === dayKey(Date.now()) ? 'Сегодня' : dateLabel.format(group[0].time);
    container.append(date);
    for (const event of group) {
      const id = String(event.id);
      const row = document.createElement('button');
      row.className = 'clip';
      row.dataset.id = id;
      row.setAttribute('aria-current', String(id === selected));
      row.onclick = () => choose(id);
      const thumb = document.createElement('span');
      thumb.className = 'thumb';
      thumb.textContent = '▷';
      thumb.dataset.id = id;
      thumb.setAttribute('aria-hidden','true');
      const time = document.createElement('time');
      time.dateTime = new Date(event.time).toISOString();
      time.textContent = timeLabel.format(event.time);
      row.setAttribute('aria-label', 'Посмотреть запись ' + dateLabel.format(event.time) + ', ' + time.textContent);
      row.append(thumb,time);
      container.append(row);
      observer?.observe(thumb);
    }
  }
}
function displayMode(next) {
  mode = next;
  el('stop').hidden = mode === 'idle';
  el('live').hidden = mode === 'live';
  el('screen-idle').hidden = mode !== 'idle';
  if (mode === 'archive' && selected) {
    const timestamp = events.find(e => String(e.id) === selected)?.time ?? Number(selected);
    el('screen-title').textContent = 'Запись · ' + timeLabel.format(timestamp);
    el('player-info').textContent = dateLabel.format(timestamp);
  } else {
    el('screen-title').textContent = 'Прямой эфир';
  }
  if (mode === 'live') el('player-info').textContent = 'Камера у двери';
  if (mode === 'idle') {
    el('player-info').textContent = '';
    void photos();
  }
}
function openCamera() {
  const run = ++generation;
  el('gate').hidden = true;
  el('logout').hidden = false;
  el('dashboard').hidden = false;
  const current = () => generation === run;
  displayMode('idle');
  list();
  peer = new DoorPeer({
    video: el('video'),
    bufferVideo: el('video-buffer'),
    onMode(next) { if (current()) displayMode(next); },
    onStatus(state, message) {
      if (!current()) return;
      status = state;
      const labels = {online:'Камера на связи', offline:'Камера вне сети', connecting:'Подключение…', error:'Ошибка связи'};
      el('status').textContent = labels[state] ?? 'Проверка доступа';
      el('status').dataset.state = state;
      el('refresh').disabled = el('live').disabled = state !== 'online';
      el('screen-hint').textContent = state === 'online' ? 'Камера готова к просмотру' : message || labels[state];
      if (message && state !== 'connecting') el('player-info').textContent = message;
      if (state === 'unauthorized') return gate('Для просмотра нужен действующий доступ. Открой чат с ботом или войди снова.');
      if (state === 'online' && pendingEvent) {
        const id = pendingEvent;
        pendingEvent = null;
        queueMicrotask(() => {
          if (current()) choose(id);
        });
      }
    },
    onArchive(incoming) {
      if (!current()) return;
      events = [...incoming].sort((a,b) => b.time-a.time);
      loadedArchive = true;
      list();
    }
  });
  status = 'connecting';
  peer.connect();
}
async function login() {
  gate('Проверяем доступ…', false);
  const attempt = generation;
  const tg = window.Telegram?.WebApp;
  tg?.ready();
  try {
    const initData = tg?.initData || new URLSearchParams(location.hash.slice(1)).get('tgWebAppData');
    const response = initData ? await fetch('/api/login/telegram', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body:JSON.stringify({initData})
    }) : await fetch('/api/me', {cache:'no-store'});
    if (generation !== attempt) return;
    if (response.ok) {
      if (initData) history.replaceState(null,'',location.pathname + location.search);
      return openCamera();
    }
    if (response.status === 403) return gate('Доступ к камере выдаёт владелец. Нажми «Старт» в личном чате с ботом и дождись одобрения.', !initData, true);
    if (response.status === 401) return gate(initData ? 'Открой камеру повторно из личного чата с ботом.' : 'Войди через свой аккаунт Telegram, чтобы посмотреть камеру и записи.');
    throw new Error('network');
  } catch {
    if (generation === attempt) gate('Не удалось подключиться. Проверь интернет и повтори попытку.',false,true);
  }
}
el('live').onclick = () => {
  selected = '';
  for (const row of el('recordings').querySelectorAll('[aria-current=true]')) row.setAttribute('aria-current','false');
  peer?.live();
};
el('stop').onclick = () => peer?.stop();
el('refresh').onclick = () => peer?.list();
el('more').onclick = () => { visible += 30; list(); };
el('retry').onclick = login;
el('logout').onclick = async () => {
  try {
    const response = await fetch('/api/logout',{method:'POST'});
    if (!response.ok && response.status !== 401) throw new Error();
    gate('Ты вышел из аккаунта.');
  } catch { el('player-info').textContent = 'Не удалось выйти. Проверь соединение.'; }
};
window.addEventListener('pagehide', () => { generation++; peer?.close(); resetPhotos(); });
window.addEventListener('pageshow', e => { if (e.persisted) void login(); });
void login();
