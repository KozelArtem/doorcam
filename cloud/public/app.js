import { DoorPeer } from './peer.js';
import { dayKey, eventFromUrl, groupDays } from './library.mjs';

const el = id => document.getElementById(id);
const dateLabel = new Intl.DateTimeFormat('ru', {day:'numeric',month:'long',year:'numeric'});
const shortDate = new Intl.DateTimeFormat('ru', {day:'numeric',month:'long'});
const timeLabel = new Intl.DateTimeFormat('ru', {hour:'2-digit',minute:'2-digit',second:'2-digit'});
const plurals = new Intl.PluralRules('ru');
const mobile = matchMedia('(max-width: 760px)');
const pageSize = 30;
const linkedEvent = eventFromUrl(location.search);
let peer, pendingEvent = linkedEvent, events = [], selected = '';
let days = new Map(), archiveDay = '', visible = pageSize;
let status = 'connecting', mode = 'idle', loadedArchive = false, generation = 0;
let archiveCursor = '', archiveLoading = false, archiveRefresh;
let observer, queue = [], inflight = 0;
const previews = new Map();

function gate(message, login = true, retry = false) {
  generation++;
  const old = peer;
  peer = null;
  old?.close();
  clearTimeout(archiveRefresh);
  resetPhotoQueue();
  clearPreviews();
  events = [];
  days.clear();
  archiveDay = '';
  archiveCursor = '';
  archiveLoading = false;
  visible = pageSize;
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
function resetPhotoQueue() {
  observer?.disconnect();
  queue = [];
}
function clearPreviews() {
  for (const url of previews.values()) URL.revokeObjectURL(url);
  previews.clear();
}
function showPreview(target, url) {
  const img = new Image();
  img.alt = '';
  img.src = url;
  target.replaceChildren(img);
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
      if (previews.size >= 90) {
        const oldest = previews.keys().next().value;
        URL.revokeObjectURL(previews.get(oldest));
        previews.delete(oldest);
      }
      const url = URL.createObjectURL(blob);
      previews.set(target.dataset.id, url);
      showPreview(target, url);
    }
  } catch {
    if (peer === connection && mode !== 'idle' && target.isConnected) queue.unshift(target);
  }
  finally { inflight--; void photos(); }
}
function choose(id, fromLink = false) {
  if (!peer || status !== 'online') return;
  selected = id;
  if (fromLink && loadedArchive && revealSelectedDay()) list();
  for (const row of el('recordings').querySelectorAll('.clip')) markClip(row);
  peer.playEvent(id);
  if (!fromLink && mobile.matches) {
    el('screen-title').focus({preventScroll:true});
    document.querySelector('.monitor').scrollIntoView({block:'start'});
  }
}
function markClip(row) {
  const current = row.dataset.id === selected;
  row.setAttribute('aria-current', String(current));
  row.querySelector('.clip-current').hidden = !current;
}
function recentDays() {
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  return [dayKey(today), dayKey(yesterday)];
}
function revealSelectedDay() {
  const event = events.find(item => String(item.id) === selected);
  if (!event) return false;
  const day = dayKey(event.time);
  const index = days.get(day).indexOf(event);
  if (archiveDay === day && index < visible) return false;
  archiveDay = day;
  visible = pageSize * Math.ceil((index + 1) / pageSize);
  return true;
}
function showDay(day) {
  archiveDay = day;
  visible = pageSize;
  list();
  document.querySelector('.archive-list').scrollTop = 0;
}
function list() {
  resetPhotoQueue();
  const container = el('recordings');
  container.replaceChildren();
  const [today, yesterday] = recentDays();
  const day = archiveDay || (days.has(today) ? today : days.keys().next().value) || today;
  const group = days.get(day) ?? [];
  const picker = el('archive-date');
  const prompt = new Option('Выбрать дату', '');
  prompt.disabled = true;
  picker.replaceChildren(prompt);
  for (const [key, clips] of days) picker.add(new Option(dateLabel.format(clips[0].time), key));
  picker.value = day === today || day === yesterday || !days.has(day) ? '' : day;
  el('today').setAttribute('aria-pressed', String(day === today));
  el('yesterday').setAttribute('aria-pressed', String(day === yesterday));
  document.querySelector('.archive-filters').hidden = !loadedArchive;
  el('more').hidden = group.length <= visible;
  el('older').hidden = !archiveCursor;
  el('older').disabled = status !== 'online' || archiveLoading;
  const summary = el('archive-summary');
  summary.hidden = !group.length;
  const notice = el('archive-status');
  notice.textContent = !loadedArchive ? 'Ожидание архива на телефоне…' :
    !events.length ? 'Записей пока нет.' : !group.length ?
    (archiveCursor ? 'Среди загруженных записей за этот день ничего нет.' : 'За этот день записей нет.') : '';
  if (!group.length) return;
  const time = group[0].time;
  const label = day === today ? `Сегодня, ${shortDate.format(time)}` :
    day === yesterday ? `Вчера, ${shortDate.format(time)}` : dateLabel.format(time);
  const count = group.length;
  const noun = {one:'запись', few:'записи', many:'записей'}[plurals.select(count)];
  summary.textContent = `${label} · ${count} ${noun}`;
  if ('IntersectionObserver' in window) {
    observer = new IntersectionObserver(items => {
      for (const item of items) if (item.isIntersecting && item.target.isConnected) {
        observer.unobserve(item.target);
        queue.push(item.target);
      }
      void photos();
    });
  }
  for (const event of group.slice(0, visible)) {
    const id = String(event.id);
    const row = document.createElement('button');
    row.className = 'clip';
    row.dataset.id = id;
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
    const current = document.createElement('span');
    current.className = 'clip-current';
    current.textContent = 'Выбрана';
    row.append(thumb,time,current);
    markClip(row);
    container.append(row);
    const saved = previews.get(id);
    if (saved) showPreview(thumb, saved);
    else observer?.observe(thumb);
  }
}
async function loadArchive(mode) {
  if (!peer || status !== 'online' || archiveLoading) return;
  archiveLoading = true;
  el('refresh').disabled = el('older').disabled = true;
  const connection = peer;
  try {
    await connection.list(mode);
  } finally {
    if (peer === connection) {
      archiveLoading = false;
      el('refresh').disabled = el('older').disabled = status !== 'online';
    }
  }
}
function refreshLatest() {
  clearTimeout(archiveRefresh);
  if (document.hidden) return;
  archiveRefresh = setTimeout(() => {
    if (archiveLoading) refreshLatest();
    else void loadArchive('background');
  }, 300);
}
function showDownload(clip) {
  const link = el('download');
  link.hidden = !clip;
  if (clip) {
    link.href = clip.url;
    link.download = `doorcam-${selected}-${clip.stamp}.mp4`;
  } else link.removeAttribute('href');
}
function displayMode(next) {
  mode = next;
  document.querySelector('.screen').dataset.mode = mode;
  el('back-to-recordings').hidden = !selected;
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
  el('capture-status').textContent = el('playback-info').textContent = '';
  showDownload(null);
  displayMode('idle');
  list();
  peer = new DoorPeer({
    video: el('video'),
    bufferVideo: el('video-buffer'),
    onMode(next) { if (current()) displayMode(next); },
    onClip(clip) { if (current()) showDownload(clip); },
    onPlayback(message) {
      if (current() && el('playback-info').textContent !== message) el('playback-info').textContent = message;
    },
    onHealth(capture) {
      if (!current()) return;
      const labels = {ok:'Захват работает', starting:'Камера запускается', failed:'Ошибка захвата',
        stopped:'Наблюдение остановлено', offline:'Нет данных о захвате'};
      const message = Object.hasOwn(labels, capture) ? labels[capture] : '';
      if (el('capture-status').textContent !== message) el('capture-status').textContent = message;
    },
    onEvent() { if (current()) refreshLatest(); },
    onStatus(state, message) {
      if (!current()) return;
      status = state;
      const labels = {online:'Камера на связи', offline:'Камера вне сети', connecting:'Подключение…', error:'Ошибка связи'};
      el('status').textContent = labels[state] ?? 'Проверка доступа';
      el('status').dataset.state = state;
      el('live').disabled = state !== 'online';
      el('refresh').disabled = el('older').disabled = state !== 'online' || archiveLoading;
      el('screen-hint').textContent = state === 'online' ? 'Камера готова к просмотру' : message || labels[state];
      if (message && state !== 'connecting') el('player-info').textContent = message;
      if (state === 'unauthorized') return gate('Для просмотра нужен действующий доступ. Открой чат с ботом или войди снова.');
      if (state === 'online' && pendingEvent) {
        const id = pendingEvent;
        pendingEvent = null;
        queueMicrotask(() => {
          if (current()) choose(id, true);
        });
      }
    },
    onArchive(incoming, next) {
      if (!current()) return;
      events = incoming;
      days = groupDays(events);
      archiveCursor = next;
      loadedArchive = true;
      if (selected && linkedEvent === selected && !archiveDay) revealSelectedDay();
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
  for (const row of el('recordings').querySelectorAll('[aria-current=true]')) markClip(row);
  peer?.live();
};
el('stop').onclick = () => peer?.stop();
el('refresh').onclick = () => loadArchive();
el('older').onclick = () => loadArchive('older');
el('today').onclick = () => showDay(recentDays()[0]);
el('yesterday').onclick = () => showDay(recentDays()[1]);
el('archive-date').onchange = e => showDay(e.target.value);
el('back-to-recordings').onclick = () => {
  if (revealSelectedDay()) list();
  const row = el('recordings').querySelector('[aria-current=true]');
  const older = el('older');
  const target = row ?? (archiveCursor && !older.disabled ? older : el(loadedArchive ? 'today' : 'refresh'));
  target.scrollIntoView({block:'center'});
  target.focus({preventScroll:true});
};
el('more').onclick = () => {
  const firstNew = visible;
  visible += pageSize;
  list();
  el('recordings').children[firstNew]?.focus();
};
el('retry').onclick = login;
el('logout').onclick = async () => {
  try {
    const response = await fetch('/api/logout',{method:'POST'});
    if (!response.ok && response.status !== 401) throw new Error();
    gate('Ты вышел из аккаунта.');
  } catch { el('player-info').textContent = 'Не удалось выйти. Проверь соединение.'; }
};
document.addEventListener('visibilitychange', refreshLatest);
window.addEventListener('pagehide', () => {
  generation++;
  clearTimeout(archiveRefresh);
  peer?.close();
  resetPhotoQueue();
  clearPreviews();
});
window.addEventListener('pageshow', e => { if (e.persisted) void login(); });
void login();
