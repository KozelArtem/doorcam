import {HttpError, requireValue, telegramId, miniAppId, oidcId, authorizationUrl, randomToken, digest, cookie, equalSecret} from './auth.mjs';
import {handleCommand, deliverEvent, completeEvent, sendCaptureNotice} from './bot.mjs';

const SESSION = '__Host-doorcam';
const FLOW = '__Host-doorcam-flow';
const HOUR = 3600000;
const WEEK = 7*24*HOUR;
const DEVICE_TIMEOUT = 90_000;
const CAPTURE_GRACE = 120_000;
const captureReports = new Set(['ok','starting','failed','stopped']);
const MAX_PHOTO = 4*1024*1024;
const MAX_VIDEO = 5*1024*1024;
const MAX_COMPLETE_VIDEO = 24*1024*1024;
const baseHeaders = {'Cache-Control':'no-store','Referrer-Policy':'no-referrer','X-Content-Type-Options':'nosniff'};
const json = (data,status=200,headers={}) => Response.json(data,{status,headers:{...baseHeaders,...headers}});
const sessionCookie = (token,age) => SESSION+'='+token+'; Path=/; Secure; HttpOnly; SameSite=None; Partitioned; Max-Age='+age;
const flowCookie = (state,age) => FLOW+'='+state+'; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age='+age;
const eventId = value => typeof value==='string' && /^[a-zA-Z0-9_-]{1,100}$/.test(value);
const redirect = (location,cookies) => {
  const headers = new Headers({...baseHeaders,Location:location});
  for (const c of cookies) headers.append('Set-Cookie',c);
  return new Response(null,{status:302,headers});
};
async function boundedBody(request, limit) {
  requireValue(Number(request.headers.get('Content-Length'))<=limit,413,'Слишком большой запрос');
  const reader = request.body?.getReader();
  requireValue(reader,400,'Пустой запрос');
  let size = 0;
  const chunks = [];
  while (true) {
    const {value,done} = await reader.read();
    if (done) break;
    size+=value.byteLength;
    if (size>limit) {await reader.cancel(); throw new HttpError(413,'Слишком большой запрос');}
    chunks.push(value);
  }
  return new Response(new Blob(chunks),{headers:{'Content-Type':request.headers.get('Content-Type')||'application/octet-stream'}});
}
async function inputJson(request,limit) {
  const body = await boundedBody(request,limit);
  try {return await body.json();} catch {throw new HttpError(400,'Некорректный JSON');}
}

async function videoLayout(video) {
  const boxes = new DataView(await video.arrayBuffer());
  let metadata = -1, media = -1;
  for (let start=0; start<boxes.byteLength;) {
    requireValue(boxes.byteLength-start>=8,400,'Повреждённый MP4');
    let size=boxes.getUint32(start), header=8;
    if (size===1) {
      header=16;
      requireValue(boxes.byteLength-start>=header,400,'Повреждённый MP4');
      size=Number(boxes.getBigUint64(start+8));
    } else if (size===0) size=boxes.byteLength-start;
    requireValue(size>=header && size<=boxes.byteLength-start,400,'Повреждённый MP4');
    const type=boxes.getUint32(start+4);
    if (start===0) requireValue(type===0x66747970 && size>=header+8,400,'Неверный формат MP4'); // ftyp
    if (type===0x6d6f6f76 && size>header && metadata<0) metadata=start; // moov
    if (type===0x6d646174 && size>header && media<0) media=start; // mdat
    start+=size;
  }
  requireValue(metadata>=0 && media>=0,400,'MP4 не содержит видео');
  return metadata<media;
}

export default {
  async fetch(request,env) {
    const url = new URL(request.url);
    if (/^\/(api\/|auth\/|telegram\/)/.test(url.pathname)) {
      if (url.origin!==env.PUBLIC_ORIGIN || url.protocol!=='https:') return json({error:'Неверный адрес сервера'},403);
      return env.HUB.get(env.HUB.idFromName('front-door')).fetch(request);
    }
    return env.ASSETS.fetch(request);
  }
};

export class Doorcam {
  constructor(ctx,env) {
    this.ctx=ctx; this.env=env;
    if (ctx.setWebSocketAutoResponse) {
      ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('{"type":"ping"}','{"type":"pong"}'));
    }
    this.inFlight=new Map();
    this.approvals=new Map();
    this.alarmUpdates=Promise.resolve();
    for (const stmt of [
      'CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,started INTEGER DEFAULT 0,allowed INTEGER DEFAULT 0,last_command INTEGER DEFAULT 0)',
      'CREATE TABLE IF NOT EXISTS access_requests(id TEXT PRIMARY KEY)',
      'CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,id TEXT NOT NULL,expires INTEGER NOT NULL)',
      'CREATE TABLE IF NOT EXISTS flows(state TEXT PRIMARY KEY,verifier TEXT,nonce TEXT,event TEXT,expires INTEGER NOT NULL)',
      'CREATE TABLE IF NOT EXISTS capture_state(id INTEGER PRIMARY KEY, capture TEXT NOT NULL, seen INTEGER NOT NULL, since INTEGER NOT NULL, alerted INTEGER NOT NULL DEFAULT 0)',
      'CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,created INTEGER NOT NULL)',
      'CREATE TABLE IF NOT EXISTS recipients(event TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(event,id))',
      'CREATE TABLE IF NOT EXISTS event_messages(event TEXT NOT NULL,id TEXT NOT NULL,message INTEGER NOT NULL,expires INTEGER NOT NULL,PRIMARY KEY(event,id))',
      'CREATE INDEX IF NOT EXISTS event_messages_expiry ON event_messages(expires)'
    ]) this.run(stmt);
  }
  run(query,...args) {return this.ctx.storage.sql.exec(query,...args).toArray();}
  row(query,...args) {return this.run(query,...args)[0];}
  allowed(id) {return id===this.env.OWNER_ID || !!this.row('SELECT allowed FROM users WHERE id=?',id)?.allowed;}
  sockets(role) {return this.ctx.getWebSockets().filter(s=>s.deserializeAttachment()?.role===role);}
  send(socket, frame) {
    if (!socket) return;
    try {socket.send(JSON.stringify(frame));} catch {this.disconnect(socket);}
  }
  close(socket,code,reason) {
    this.disconnect(socket);
    try {socket.close(code,reason);} catch { /* Already closed. */ }
  }
  disconnect(socket) {
    const state=socket.deserializeAttachment();
    if (!state || state.role==='closed') return;
    socket.serializeAttachment({role:'closed'});
    if (state.role==='viewer') this.send(this.sockets('device')[0],{type:'leave',peer:state.peer});
    if (state.role==='device') {
      const stopped=this.captureStatus()==='stopped';
      for (const viewer of this.sockets('viewer')) {
        this.send(viewer,{type:'offline'});
        if (stopped) this.send(viewer,{type:'health',capture:'stopped'});
      }
    }
  }
  closeViewers(predicate) {
    for (const ws of this.sockets('viewer')) if (predicate(ws.deserializeAttachment())) this.close(ws,1008,'Session closed');
  }
  publishEvent(id) {
    for (const ws of this.sockets('viewer')) this.send(ws,{type:'event',id});
  }
  captureStatus(now=Date.now(), row=this.row('SELECT * FROM capture_state WHERE id=1')) {
    if (!row) return 'unknown';
    return row.capture!=='stopped' && now>=row.seen+DEVICE_TIMEOUT?'offline':row.capture;
  }
  async captureReport(capture, first) {
    const now=Date.now(), previous=this.row('SELECT * FROM capture_state WHERE id=1');
    const old=this.captureStatus(now,previous);
    const sameIncident=(old==='starting' || old==='failed') && (capture==='starting' || capture==='failed');
    const since=old===capture || sameIncident?previous.since:now;
    this.run(`INSERT INTO capture_state(id,capture,seen,since) VALUES(1,?,?,?)
      ON CONFLICT(id) DO UPDATE SET capture=excluded.capture,seen=excluded.seen,since=excluded.since,
      alerted=CASE WHEN excluded.capture='stopped' THEN 0 ELSE alerted END`,
      capture,now,since);
    if (first || old!==capture) for (const ws of this.sockets('viewer')) this.send(ws,{type:'health',capture});
    if (capture==='stopped') return;
    let deadline=now+DEVICE_TIMEOUT;
    if (capture==='ok' && previous?.alerted) deadline=now;
    if (capture!=='ok' && !previous?.alerted) deadline=Math.min(deadline,since+CAPTURE_GRACE);
    await this.schedule(deadline);
  }
  async checkCapture(now) {
    const row=this.row('SELECT * FROM capture_state WHERE id=1');
    const capture=this.captureStatus(now,row);
    if (capture==='unknown' || capture==='stopped') return Infinity;
    const deadline=capture==='offline'?row.seen+DEVICE_TIMEOUT:row.since+CAPTURE_GRACE;
    const bad=capture!=='ok' && now>=deadline;
    if (capture==='offline') for (const ws of this.sockets('viewer')) this.send(ws,{type:'health',capture});
    try {
      if (bad && !row.alerted) {
        await sendCaptureNotice(this,capture);
        this.run("UPDATE capture_state SET alerted=1 WHERE id=1 AND capture<>'stopped'");
      } else if (capture==='ok' && row.alerted) {
        await sendCaptureNotice(this,capture);
        this.run('UPDATE capture_state SET alerted=0 WHERE id=1');
      }
    } catch {
      return now+60_000;
    }
    if (capture==='offline') return Infinity;
    return Math.min(row.seen+DEVICE_TIMEOUT,capture==='ok' || bad || row.alerted?Infinity:deadline);
  }
  schedule(deadline) {
    // Preserve the earliest session or capture check when multiple events schedule an alarm.
    const update = this.alarmUpdates.then(async()=>{
      const old = await this.ctx.storage.getAlarm();
      if (old===null || old>deadline) await this.ctx.storage.setAlarm(deadline);
    });
    this.alarmUpdates=update.catch(()=>{});
    return update;
  }
  async alarm() {
    const now=Date.now();
    let next=Infinity;
    for(const ws of this.ctx.getWebSockets()) {
      const {role,expires}=ws.deserializeAttachment() || {};
      if(role!=='pending' && role!=='viewer') continue;
      if(expires<=now) this.close(ws,1008,'Expired');
      else next=Math.min(next,expires);
    }
    this.run('DELETE FROM sessions WHERE expires<=?',now);
    this.run('DELETE FROM flows WHERE expires<=?',now);
    next=Math.min(next,await this.checkCapture(now));
    if(Number.isFinite(next)) await this.schedule(next);
  }
  async session(request) {
    const raw=cookie(request,SESSION);
    const token=raw && await digest(raw);
    const user=token && this.row('SELECT id,expires FROM sessions WHERE token=? AND expires>?',token,Date.now());
    requireValue(user && this.allowed(user.id),401,'Требуется вход');
    return {...user,token};
  }
  async issue(id) {
    const raw=randomToken(), token=await digest(raw), expires=Date.now()+WEEK;
    requireValue(this.allowed(id),403,'Попросите владельца дать доступ вашему Telegram ID '+id);
    this.run('DELETE FROM sessions WHERE expires<=?',Date.now());
    this.run('INSERT INTO sessions(token,id,expires) VALUES(?,?,?)',token,id,expires);
    return sessionCookie(raw,WEEK/1000);
  }
  async deviceKey(request) {
    const auth=request.headers.get('Authorization') || '';
    return auth.startsWith('Bearer ') && await equalSecret(auth.slice(7),this.env.DEVICE_KEY);
  }
  async fetch(request) {
    try {return await this.route(request);} catch(error) {
      if (!(error instanceof HttpError)) console.error('Worker request failed', {
        path:new URL(request.url).pathname,
        name:error?.name || 'Error',
        message:String(error?.message || error).slice(0,240)
      });
      return json({error:error instanceof HttpError?error.message:'Сервис временно недоступен'},error instanceof HttpError?error.status:503);
    }
  }
  async route(request) {
    const {env}=this;
    const url=new URL(request.url), path=url.pathname, method=request.method;
    requireValue(env.BOT_TOKEN && telegramId(env.OWNER_ID)===env.OWNER_ID && env.OWNER_ID,503,'Заполните настройки сервера');
    const sameOrigin=()=>requireValue(request.headers.get('Origin')===env.PUBLIC_ORIGIN,403,'Недопустимый источник');
    if (path==='/api/login/telegram' && method==='POST') {
      sameOrigin();
      const data=await inputJson(request,32768);
      const id=await miniAppId(data?.initData,env.BOT_TOKEN);
      return json({id},200,{'Set-Cookie':await this.issue(id)});
    }
    if (path==='/auth/login' && method==='GET') {
      requireValue(env.OIDC_CLIENT_ID && env.OIDC_CLIENT_SECRET,503,'Не настроен Telegram Login');
      const state=randomToken(), verifier=randomToken(), nonce=randomToken(), event=url.searchParams.get('event');
      this.run('DELETE FROM flows WHERE expires<=?',Date.now());
      this.run('INSERT INTO flows VALUES(?,?,?,?,?)',state,verifier,nonce,eventId(event)?event:'',Date.now()+600000);
      return redirect(authorizationUrl(env,state,nonce,await digest(verifier)),[flowCookie(state,600)]);
    }
    if (path==='/auth/callback' && method==='GET') {
      const state=cookie(request,FLOW), code=url.searchParams.get('code');
      requireValue(state && state===url.searchParams.get('state') && code && code.length<=4096,401,'Запустите вход заново');
      const flow=this.row('DELETE FROM flows WHERE state=? AND expires>? RETURNING *',state,Date.now());
      requireValue(flow,401,'Ссылка входа устарела');
      const id=await oidcId(env,code,flow);
      return redirect(flow.event?'/?event='+flow.event:'/',[await this.issue(id),flowCookie('',0)]);
    }
    if (path==='/api/me' && method==='GET') return json({id:(await this.session(request)).id});
    if (path==='/api/logout' && method==='POST') {
      sameOrigin();
      const raw=cookie(request,SESSION);
      if (raw) {
        const token=await digest(raw);
        this.run('DELETE FROM sessions WHERE token=?',token);
        this.closeViewers(s=>s.token===token);
      }
      return json({ok:true},200,{'Set-Cookie':sessionCookie('',0)});
    }
    if (path==='/api/ice' && method==='GET') {
      if (!await this.deviceKey(request)) await this.session(request);
      if (!env.TURN_KEY_ID && !env.TURN_API_TOKEN) return json({iceServers:[{urls:['stun:stun.cloudflare.com:3478']}]});
      requireValue(env.TURN_KEY_ID && env.TURN_API_TOKEN,503,'Заполните настройки TURN');
      const result=await fetch('https://rtc.live.cloudflare.com/v1/turn/keys/'+encodeURIComponent(env.TURN_KEY_ID)+'/credentials/generate-ice-servers',{
        method:'POST',redirect:'manual',headers:{Authorization:'Bearer '+env.TURN_API_TOKEN,'Content-Type':'application/json'},body:JSON.stringify({ttl:3600})});
      requireValue(result.ok,503,'TURN временно недоступен');
      const {iceServers}=await result.json();
      requireValue(Array.isArray(iceServers) && iceServers.length,503,'Некорректный ответ TURN');
      return json({iceServers});
    }
    if (path==='/telegram/webhook' && method==='POST') {
      requireValue(await equalSecret(request.headers.get('X-Telegram-Bot-Api-Secret-Token'),env.WEBHOOK_SECRET),401,'Требуется секрет webhook');
      await handleCommand(this,await inputJson(request,131072));
      return json({ok:true});
    }
    if (path==='/api/event' && method==='POST') {
      requireValue(await this.deviceKey(request),401,'Требуется ключ устройства');
      const body=await boundedBody(request,MAX_COMPLETE_VIDEO+16384);
      let fields;
      try {fields=await body.formData();} catch {throw new HttpError(400,'Ожидается multipart/form-data');}
      const id=fields.get('id'), video=fields.get('video'), photo=fields.get('photo');
      const phase=fields.get('phase'), complete=phase==='complete';
      requireValue(phase===null || complete,400,'Некорректный этап события');
      const offsetText=fields.get('offset') ?? '0', offset=Number(offsetText);
      requireValue(eventId(id) && typeof offsetText==='string' && /^-?\d{1,3}$/.test(offsetText) && offset>=-720 && offset<=840,
        400,'Некорректные данные события');
      requireValue(complete ? /^\d{13}$/.test(id) && photo===null : (video===null)!==(photo===null),
        400,'Некорректное вложение события');
      let streaming=false;
      if (video!==null) {
        requireValue(/^\d{13}$/.test(id) && video instanceof Blob && video.type==='video/mp4' && video.size>0 &&
          video.size<=(complete?MAX_COMPLETE_VIDEO:MAX_VIDEO),
          400,'Ожидается MP4 с видео события');
        streaming=await videoLayout(video);
      } else if (photo!==null) {
        requireValue(photo instanceof Blob && photo.type==='image/jpeg' && photo.size>=4 && photo.size<=MAX_PHOTO,400,'Ожидается JPEG');
        const header=new Uint8Array(await photo.slice(0,3).arrayBuffer());
        requireValue(header[0]===255 && header[1]===216 && header[2]===255,400,'Неверный формат JPEG');
      }
      const key=complete?'complete/'+id:id;
      if (!this.inFlight.has(key)) {
        const delivery=complete?completeEvent(this,id,video,{offset,streaming}):deliverEvent(this,id,video ?? photo,{offset,streaming});
        this.inFlight.set(key,delivery.finally(()=>this.inFlight.delete(key)));
      }
      await this.inFlight.get(key);
      return json({ok:true});
    }
    if (method==='GET' && (path==='/api/device' || path==='/api/viewer')) {
      requireValue(request.headers.get('Upgrade')?.toLowerCase()==='websocket',426,'Требуется WebSocket');
      let info;
      if (path==='/api/viewer') {
        sameOrigin();
        info={role:'viewer',peer:randomToken(),...await this.session(request)};
      } else info={role:'pending',expires:Date.now()+15000};
      const [client,server]=Object.values(new WebSocketPair());
      this.ctx.acceptWebSocket(server);
      server.serializeAttachment(info);
      if(info.role==='viewer') {
        const connected=this.sockets('device').length>0;
        this.send(server,{type:connected?'ready':'offline'});
        const capture=this.captureStatus();
        if (capture==='stopped' || connected && capture!=='unknown') this.send(server,{type:'health',capture});
      }
      await this.schedule(info.expires);
      return new Response(null,{status:101,webSocket:client});
    }
    return json({error:'Неизвестный маршрут'},404);
  }
  async webSocketMessage(ws,raw) {
    const info=ws.deserializeAttachment();
    if (!info || info.role==='closed') return;
    if (typeof raw!=='string' || raw.length>96000) {this.close(ws,1009,'Frame too large'); return;}
    let msg;
    try {msg=JSON.parse(raw);} catch { /* Invalid frame below. */ }
    if (!msg || typeof msg!=='object') {this.close(ws,1008,'Invalid frame'); return;}
    if (info.role==='pending') {
      if (msg.type!=='auth' || info.expires<=Date.now() || !await equalSecret(msg.key,this.env.DEVICE_KEY)) {
        this.close(ws,1008,'Invalid device key'); return;
      }
      if(ws.deserializeAttachment()?.role!=='pending') return;
      for (const old of this.sockets('device')) this.close(old,1000,'Device reconnected');
      ws.serializeAttachment({role:'device'});
      this.send(ws,{type:'ready',health:true});
      for (const viewer of this.sockets('viewer')) this.send(viewer,{type:'ready'});
      return;
    }
    const viewer=info.role==='viewer';
    if (viewer && (info.expires<=Date.now() || !this.allowed(info.id))) {this.close(ws,1008,'Session expired'); return;}
    if (msg.type==='ping') {this.send(ws,{type:'pong'}); return;}
    if (!viewer && msg.type==='health' && captureReports.has(msg.capture)) {
      this.send(ws,{type:'pong'});
      if (!info.reported) ws.serializeAttachment({...info,reported:true});
      await this.captureReport(msg.capture,!info.reported);
      return;
    }
    let frame;
    if (msg.type===(viewer?'offer':'answer') && typeof msg.sdp==='string' && msg.sdp.length)
      frame={type:msg.type,sdp:msg.sdp};
    if (msg.type==='ice' && msg.candidate && typeof msg.candidate.candidate==='string')
      frame={type:'ice',candidate:msg.candidate};
    if (!frame) {this.close(ws,1008,'Invalid signal'); return;}
    if (viewer) {
      const phone=this.sockets('device')[0];
      if (phone) this.send(phone,{...frame,peer:info.peer});
      else this.send(ws,{type:'offline'});
    } else {
      const target=this.sockets('viewer').find(v=>v.deserializeAttachment().peer===msg.peer);
      if (target) this.send(target,frame);
    }
  }
  webSocketClose(ws) {this.close(ws,1000,'Closed');}
  webSocketError(ws) {this.close(ws,1011,'Connection lost');}
}
