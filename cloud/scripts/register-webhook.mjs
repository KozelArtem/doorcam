// Run with node --env-file=.dev.vars scripts/register-webhook.mjs https://your-worker.workers.dev
const {BOT_TOKEN, WEBHOOK_SECRET} = process.env;
const base = new URL(process.argv[2] ?? 'about:blank');
if (!BOT_TOKEN || !/^[A-Za-z0-9_-]{1,256}$/.test(WEBHOOK_SECRET ?? '') || base.protocol !== 'https:' || base.href !== base.origin + '/') {
  console.error('Укажите HTTPS-адрес Worker, BOT_TOKEN и WEBHOOK_SECRET.');
  process.exit(1);
}
try {
  const response = await fetch('https://api.telegram.org/bot' + BOT_TOKEN + '/setWebhook', {
    method:'POST',
    headers:{'Content-Type':'application/json'},
    body: JSON.stringify({
      url:base.origin + '/telegram/webhook',
      secret_token:WEBHOOK_SECRET,
      allowed_updates:['message','callback_query']
    })
  });
  const result = await response.json();
  if (!response.ok || !result.ok) throw new Error();
  console.log('Webhook зарегистрирован для ' + base.hostname);
} catch {
  console.error('Telegram не подтвердил регистрацию webhook. Проверьте параметры и соединение.');
  process.exitCode = 1;
}
