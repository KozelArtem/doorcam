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
  requireValue(response.type!=='opaqueredirect' && response.status<300 && reply.ok,
    503,'Telegram временно не принял сообщение');
  return true;
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

// All changes originate in the owner's private chat. Ignore old commands and buttons,
// including redelivered updates, after the owner makes a newer decision about this viewer.
async function changeAccess(hub, target, allow, messageNumber) {
  const {env} = hub;
  const current = hub.row('SELECT last_command,started FROM users WHERE id=?', target);
  if (current?.last_command >= messageNumber) return false;
  hub.run('INSERT INTO users(id,allowed,last_command) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET allowed=excluded.allowed,last_command=excluded.last_command',
    target, Number(allow), messageNumber);
  if (!allow) {
    hub.run('DELETE FROM sessions WHERE id=?', target);
    hub.run('DELETE FROM recipients WHERE id=?', target);
    hub.closeViewers(viewer => viewer.id === target);
  }
  await say(env, env.OWNER_ID, (allow ? 'Открыт доступ для ' : 'Отозван доступ для ') + target, {
    inline_keyboard: [[accessButton(target, !allow)]]
  });
  if (allow && current?.started) await say(env, target, 'Владелец открыл вам доступ.', botButton(env));
  return true;
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
  const help = '/allow ID — дать доступ\n/revoke ID — отозвать\n/list — управление зрителями';
  if (command === '/start') {
    const existing = hub.row('SELECT started FROM users WHERE id=?', id);
    hub.run('INSERT INTO users(id,started) VALUES(?,1) ON CONFLICT(id) DO UPDATE SET started=1', id);
    if (hub.allowed(id)) {
      await say(env, id, 'Доступ открыт. Ваш Telegram ID ' + id + (owner ? '\n' + help : ''), botButton(env));
    } else {
      await say(env, id, 'Ваш Telegram ID ' + id + '. Доступ подтверждает владелец.');
      if (!existing?.started) {
        const name = [msg.from.first_name, msg.from.last_name].filter(Boolean).join(' ');
        await say(env, env.OWNER_ID, 'Запрос доступа от ' + (name ? name + ', ' : '') + 'Telegram ID ' + id, {
          inline_keyboard: [[accessButton(id, true)]]
        });
      }
    }
    return;
  }
  if (!owner) {
    await say(env, id, 'Ваш Telegram ID ' + id + '. Для входа отправьте /start.');
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

export async function deliverEvent(hub, id, photo) {
  // Store only delivery metadata. The phone keeps the JPEG and retries with the same event id.
  if (!hub.row('SELECT id FROM events WHERE id=?',id)) {
    hub.ctx.storage.transactionSync(()=>{
      hub.run('INSERT INTO events(id,created) VALUES(?,?)',id,Date.now());
      hub.run('INSERT INTO recipients(event,id) SELECT ?,id FROM users WHERE started=1 AND (allowed=1 OR id=?)',id,hub.env.OWNER_ID);
    });
  }
  const pending = hub.run('SELECT id FROM recipients WHERE event=? ORDER BY id',id);
  let failed = false;
  for (const {id:viewer} of pending) {
    if (!hub.row('SELECT id FROM recipients WHERE event=? AND id=?',id,viewer)) continue;
    const form = new FormData();
    form.set('chat_id',viewer); form.set('photo',photo,'motion.jpg'); form.set('caption','Движение у двери');
    form.set('reply_markup',JSON.stringify(botButton(hub.env,id)));
    try {
      const sent = await callBot(hub.env,'sendPhoto',form);
      if (!sent) {
        hub.run('UPDATE users SET started=0 WHERE id=?',viewer);
        hub.run('DELETE FROM recipients WHERE id=?',viewer);
      } else hub.run('DELETE FROM recipients WHERE event=? AND id=?',id,viewer);
    } catch { failed = true; }
  }
  if (failed) throw new HttpError(503,'Доставка ожидает повтора');
}
