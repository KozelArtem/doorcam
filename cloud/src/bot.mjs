import {HttpError, requireValue, telegramId} from './auth.mjs';

export function botButton(env, event) {
  const url = new URL('/',env.PUBLIC_ORIGIN);
  if (event) url.searchParams.set('event',event);
  return {inline_keyboard:[[{text:event?'Посмотреть запись':'Открыть глазок',web_app:{url:url.href}}]]};
}

async function callBot(env, method, body) {
  const multipart = body instanceof FormData;
  const response = await fetch('https://api.telegram.org/bot'+env.BOT_TOKEN+'/'+method,{
    method:'POST',redirect:'manual',headers:multipart?{}:{'Content-Type':'application/json'},
    body:multipart?body:JSON.stringify(body)});
  const reply = await response.json();
  if (method === 'answerCallbackQuery' && reply.error_code === 400) return false; // Telegram has expired the callback.
  if (!reply.ok && reply.error_code===403) return false; // Recipient has blocked the bot.
  if ((multipart || method==='editMessageCaption') && !reply.ok && reply.error_code===400 && response.status===400) return null;
  requireValue(response.type!=='opaqueredirect' && response.status<300 && reply.ok,
    503,'Telegram временно не принял сообщение');
  return reply.result ?? true;
}

function accessButton(id, allow) {
  return {
    text: (allow ? 'Разрешить ' : 'Отозвать ') + id,
    callback_data: (allow ? 'allow:' : 'revoke:') + id
  };
}

function say(env, id, text, keyboard) {
  return callBot(env, 'sendMessage', {chat_id: id, text, reply_markup: keyboard});
}

const captureLabels = {
  ok:'Камера получает кадры и записывает видео.',
  starting:'Камера запускается.',
  failed:'Камера не записывает. Откройте Doorcam на телефоне и проверьте наблюдение.',
  stopped:'Наблюдение остановлено.',
  offline:'Телефон с камерой вне сети. Проверьте питание и Wi-Fi, затем откройте Doorcam.',
  unknown:'Телефон ещё не сообщил состояние записи.'
};

export function sendCaptureNotice(hub, capture) {
  const {env} = hub;
  return say(env, env.OWNER_ID, capture === 'ok' ? 'Запись Doorcam восстановлена.' :
    captureLabels[capture === 'starting' ? 'failed' : capture], botButton(env));
}

// All changes originate in the owner's private chat. Ignore old commands and buttons,
// including redelivered updates, after the owner makes a newer decision about this viewer.
async function changeAccess(hub, target, allow, messageNumber) {
  const {env} = hub;
  const current = hub.row('SELECT last_command,started FROM users WHERE id=?', target);
  if (current?.last_command >= messageNumber) return false;
  hub.run('INSERT INTO users(id,allowed,last_command) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET allowed=excluded.allowed,last_command=excluded.last_command',
    target, Number(allow), messageNumber);
  if (!allow) {
    hub.run('DELETE FROM access_requests WHERE id=?',target);
    hub.run('DELETE FROM sessions WHERE id=?', target);
    hub.run('DELETE FROM recipients WHERE id=?', target);
    hub.run('DELETE FROM event_messages WHERE id=?', target);
    hub.closeViewers(viewer => viewer.id === target);
  }
  await say(env, env.OWNER_ID, (allow ? 'Открыт доступ для ' : 'Отозван доступ для ') + target, {
    inline_keyboard: [[accessButton(target, !allow)]]
  });
  if (allow && current?.started) await say(env, target, 'Владелец открыл вам доступ.', botButton(env));
  return true;
}

async function requestAccess(hub, msg, id) {
  if (hub.row('SELECT id FROM access_requests WHERE id=?',id)) return;
  const {env} = hub;
  if (!hub.approvals.has(id)) {
    const decision=hub.row('SELECT last_command FROM users WHERE id=?',id).last_command;
    const name = [msg.from.first_name, msg.from.last_name].filter(Boolean).join(' ');
    const send = say(env, env.OWNER_ID, 'Запрос доступа от ' + (name ? name + ', ' : '') + 'Telegram ID ' + id, {
      inline_keyboard: [[accessButton(id, true)]]
    }).then(sent => {
      if (sent) hub.run('INSERT OR IGNORE INTO access_requests(id) SELECT id FROM users WHERE id=? AND allowed=0 AND last_command=?',
        id,decision);
    }).finally(() => hub.approvals.delete(id));
    hub.approvals.set(id,send);
  }
  await hub.approvals.get(id);
}

async function handleCallback(hub, query) {
  const {env} = hub;
  const message = query.message;
  if (typeof query.id !== 'string') return;
  let result = 'Действие недоступно';
  const command = /^(allow|revoke):([1-9]\d{0,15})$/.exec(query.data);
  if (telegramId(query.from?.id) === env.OWNER_ID && message?.chat?.type === 'private' &&
      String(message.chat.id) === env.OWNER_ID && Number.isSafeInteger(message.message_id) &&
      command && telegramId(command[2]) && command[2] !== env.OWNER_ID) {
    const applied = await changeAccess(hub, command[2], command[1] === 'allow', message.message_id);
    result = applied ? 'Готово' : 'Кнопка устарела. Откройте /list.';
  }
  await callBot(env, 'answerCallbackQuery', {callback_query_id: query.id, text: result});
}

export async function handleCommand(hub, update) {
  const {env} = hub;
  if (update?.callback_query) return handleCallback(hub, update.callback_query);
  const msg = update?.message;
  const id = telegramId(msg?.from?.id);
  if (!id || msg.chat?.type !== 'private' || String(msg.chat.id) !== id ||
      typeof msg.text !== 'string' || !Number.isSafeInteger(msg.message_id)) return;
  const [cmd, arg, extra] = msg.text.trim().split(/\s+/);
  const command = cmd.split('@')[0];
  const owner = id === env.OWNER_ID;
  const help = '/allow ID — дать доступ\n/revoke ID — отозвать\n/list — управление зрителями\n/status — состояние камеры';
  if (command === '/start') {
    hub.run('INSERT INTO users(id,started) VALUES(?,1) ON CONFLICT(id) DO UPDATE SET started=1', id);
    if (hub.allowed(id)) {
      await say(env, id, 'Доступ открыт. Ваш Telegram ID ' + id + (owner ? '\n' + help : ''), botButton(env));
    } else {
      await say(env, id, 'Ваш Telegram ID ' + id + '. Доступ подтверждает владелец.');
      if (!hub.allowed(id)) await requestAccess(hub,msg,id);
    }
    return;
  }
  if (!owner) {
    await say(env, id, 'Ваш Telegram ID ' + id + '. Для входа отправьте /start.');
    return;
  }
  if (command === '/status') {
    await say(env, id, captureLabels[hub.captureStatus()], botButton(env));
    return;
  }
  if (command === '/list') {
    const users = hub.run('SELECT id,allowed FROM users WHERE id<>? AND (allowed=1 OR started=1) ORDER BY allowed DESC,id', id);
    if (!users.length) await say(env, id, 'Владелец ' + id + '. Других зрителей и запросов пока нет.');
    for (let i = 0; i < users.length; i += 20) {
      const buttons = users.slice(i, i + 20).map(user => [accessButton(user.id, !user.allowed)]);
      await say(env, id, 'Управление доступом к глазку', {inline_keyboard: buttons});
    }
    return;
  }
  const target = telegramId(arg);
  if (!['/allow', '/revoke'].includes(command) || !target || extra || target === id) {
    await say(env, id, help);
    return;
  }
  await changeAccess(hub, target, command === '/allow', msg.message_id);
}

const eventTime = new Intl.DateTimeFormat('ru-RU',{
  timeZone:'UTC',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'
});

function eventCaption(id, offset) {
  return 'Движение у двери' + (/^\d{13}$/.test(id) ? '\n' + eventTime.format(Number(id)+offset*60000) : '');
}

export async function deliverEvent(hub, id, attachment, {offset,streaming}) {
  hub.run('DELETE FROM event_messages WHERE expires<=?',Date.now());
  // Store only delivery metadata. The phone keeps the file and retries with the same event id.
  if (!hub.row('SELECT id FROM events WHERE id=?',id)) {
    hub.ctx.storage.transactionSync(()=>{
      hub.run('INSERT INTO events(id,created) VALUES(?,?)',id,Date.now());
      hub.run('INSERT INTO recipients(event,id) SELECT ?,id FROM users WHERE started=1 AND (allowed=1 OR id=?)',id,hub.env.OWNER_ID);
    });
    hub.publishEvent(id);
  }
  const pending = hub.run('SELECT id FROM recipients WHERE event=? ORDER BY id',id);
  const video = attachment.type==='video/mp4';
  const caption = eventCaption(id,offset);
  let failed = false;
  for (const {id:viewer} of pending) {
    if (!hub.row('SELECT id FROM recipients WHERE event=? AND id=?',id,viewer)) continue;
    const form = new FormData();
    form.set('chat_id',viewer);
    form.set(video?'video':'photo',attachment,video?'motion.mp4':'motion.jpg');
    form.set('caption',caption);
    if (streaming) form.set('supports_streaming','true');
    form.set('reply_markup',JSON.stringify(botButton(hub.env,id)));
    try {
      const sent = await callBot(hub.env,video?'sendVideo':'sendPhoto',form);
      if (sent===false) {
        hub.run('UPDATE users SET started=0 WHERE id=?',viewer);
        hub.run('DELETE FROM recipients WHERE id=?',viewer);
        hub.run('DELETE FROM event_messages WHERE id=?',viewer);
      } else hub.ctx.storage.transactionSync(()=>{
        if (video && Number.isSafeInteger(sent?.message_id) &&
          hub.row('SELECT id FROM recipients WHERE event=? AND id=?',id,viewer))
          hub.run('INSERT OR REPLACE INTO event_messages VALUES(?,?,?,?)',id,viewer,sent.message_id,Date.now()+7*86400000);
        hub.run('DELETE FROM recipients WHERE event=? AND id=?',id,viewer);
      });
    } catch { failed = true; }
  }
  if (failed) throw new HttpError(503,'Доставка ожидает повтора');
}

export async function completeEvent(hub,id,video,{offset,streaming}) {
  requireValue(!hub.row('SELECT id FROM recipients WHERE event=? LIMIT 1',id),503,'Первое видео ожидает отправки');
  const caption=eventCaption(id,offset);
  const reply_markup=botButton(hub.env,id);
  let failed=false;
  for (const {id:viewer,message} of hub.run('SELECT id,message FROM event_messages WHERE event=? AND expires>?',id,Date.now())) {
    if (!hub.row('SELECT id FROM event_messages WHERE event=? AND id=?',id,viewer)) continue;
    try {
      let edited=null;
      if (video) {
        const form=new FormData();
        form.set('chat_id',viewer);
        form.set('message_id',String(message));
        form.set('media',JSON.stringify({type:'video',media:'attach://video',caption,supports_streaming:streaming}));
        form.set('reply_markup',JSON.stringify(reply_markup));
        form.set('video',video,'motion-full.mp4');
        edited=await callBot(hub.env,'editMessageMedia',form);
      }
      if (edited===null && hub.allowed(viewer)) await callBot(hub.env,'editMessageCaption',{
        chat_id:viewer,message_id:message,caption:caption+'\nПолная запись доступна по кнопке ниже.',reply_markup
      });
      hub.run('DELETE FROM event_messages WHERE event=? AND id=?',id,viewer);
    } catch { failed=true; }
  }
  if (failed) throw new HttpError(503,'Обновление видео ожидает повтора');
}
