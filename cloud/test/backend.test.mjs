import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { createHmac } from 'node:crypto';
import { Doorcam } from '../src/worker.mjs';

const origin = 'https://door.test';
const env = {PUBLIC_ORIGIN: origin, OWNER_ID:'41', BOT_TOKEN:'demo:token', DEVICE_KEY:'local-device-key-1234567890123456',
  WEBHOOK_SECRET:'webhook-secret-for-test', OIDC_CLIENT_ID:'72', OIDC_CLIENT_SECRET:'client-secret'};

function fixture() {
  const db = new DatabaseSync(':memory:');
  const sockets = [];
  const sql = { exec(query, ...params) {
    const rows = db.prepare(query).all(...params);
    return Object.assign(rows, { toArray: () => rows });
  }};
  const storage = {sql, transactionSync: (fn) => fn(), getAlarm: async()=>null, setAlarm: async()=>{}};
  const hub = new Doorcam({ storage, getWebSockets: () => sockets }, env);
  function request(path, options = {}) {
    return hub.fetch(new Request(origin + path, options));
  }
  function webhook(from, text, message_id = 1, chatType = 'private') {
    return request('/telegram/webhook', {method:'POST', headers:{'X-Telegram-Bot-Api-Secret-Token':env.WEBHOOK_SECRET},
      body:JSON.stringify({update_id:message_id, message:{message_id,date:Math.floor(Date.now()/1000),from:{id:from}, chat:{type:chatType,id:from},text}})});
  }
  async function login(id) {
    return request('/api/login/telegram', {method:'POST',headers:{Origin:origin,'Content-Type':'application/json'},body:JSON.stringify({initData: signed(id)})});
  }
  return {request,webhook,login,sockets,hub};
}

function signed(id, age = 0) {
  const fields = new URLSearchParams({auth_date:String(Math.floor(Date.now()/1000)-age),user:JSON.stringify({id}), signature:'test_signature'});
  fields.sort();
  const data = [...fields].map(([k,v]) => k+'='+v).join('\n');
  const key = createHmac('sha256','WebAppData').update(env.BOT_TOKEN).digest();
  fields.append('hash', createHmac('sha256', key).update(data).digest('hex'));
  return fields.toString();
}
function botStub(t, action = () => Response.json({ok:true,result:true})) {
  return t.mock.method(globalThis,'fetch',action);
}
function socket(info) {
  return {info, frames:[], deserializeAttachment(){return this.info;},serializeAttachment(data){this.info=data;},
    send(raw){this.frames.push(JSON.parse(raw));},close(code){this.code=code;}};
}

test('Mini App validation rejects spoofed, expired and unauthorized identities',async()=>{
  const {request,login} = fixture();
  for (const initData of [signed(41).replace('test_signature','edited'), signed(41,1000), signed(41)+'&user=%7B%22id%22%3A41%7D']) {
    const result = await request('/api/login/telegram',{method:'POST',headers:{Origin:origin,'Content-Type':'application/json'},body:JSON.stringify({initData})});
    assert.equal(result.status,401);
  }
  assert.equal((await login(888)).status,403);
  const good = await login(41);
  assert.equal(good.status,200);
  assert.match(good.headers.get('set-cookie'),/HttpOnly/);
  assert.equal((await (await request('/api/me',{headers:{Cookie:good.headers.get('set-cookie').split(';')[0]}})).json()).id,'41');
});

test('private owner commands grant and revoke with immediate peer shutdown',async(t)=>{
  botStub(t);
  const {request,login,webhook,hub,sockets} = fixture();
  await webhook(999,'/allow 52');
  await webhook(41,'/allow 52',2,'group');
  assert.equal((await login(52)).status,403);
  assert.equal((await webhook(41,'/allow 52',3)).status,200);
  const response = await login(52);
  const cookie = response.headers.get('set-cookie').split(';')[0];
  const phone = socket({role:'device'});
  const viewer = socket({role:'viewer',peer:'peer-a',id:'52',expires:Date.now()+100000});
  sockets.push(phone,viewer);
  await hub.webSocketMessage(viewer,JSON.stringify({type:'offer',sdp:'v=0',peer:'spoof'}));
  assert.deepEqual(phone.frames.pop(),{type:'offer',sdp:'v=0',peer:'peer-a'});
  await hub.webSocketMessage(phone,JSON.stringify({type:'answer',sdp:'answer',peer:'peer-a'}));
  assert.deepEqual(viewer.frames.pop(),{type:'answer',sdp:'answer'});
  assert.equal((await webhook(41,'/revoke 52',4)).status,200);
  assert.equal(viewer.code,1008);
  assert.deepEqual(phone.frames.pop(),{type:'leave',peer:'peer-a'});
  assert.equal((await request('/api/me',{headers:{Cookie:cookie}})).status,401);
  await webhook(41,'/allow 52',3);
  assert.equal((await login(52)).status,403);
});

test('per-recipient event retries resume partial sends and deduplicate completion',async(t)=>{
  const {request,webhook} = fixture();
  const attempts = [];
  let offline = true;
  botStub(t, async(url,init)=>{
    if(String(url).endsWith('/sendPhoto')) {
      const id = init.body.get('chat_id'); attempts.push(id);
      assert.equal(JSON.parse(init.body.get('reply_markup')).inline_keyboard[0][0].web_app.url,origin+'/?event=e-001');
      if(id==='52' && offline) return Response.json({ok:false,error_code:429});
    }
    return Response.json({ok:true,result:true});
  });
  await webhook(41,'/start');
  await webhook(52,'/start');
  await webhook(41,'/allow 52',2);
  function upload(key) {
    const form = new FormData(); form.set('id','e-001'); form.set('photo',new Blob([Uint8Array.of(255,216,255,217)],{type:'image/jpeg'}),'photo.jpg');
    return request('/api/event',{method:'POST',headers:{Authorization:'Bearer '+key},body:form});
  }
  assert.equal((await upload('wrong')).status,401);
  assert.equal((await upload(env.DEVICE_KEY)).status,503);
  offline = false;
  assert.equal((await upload(env.DEVICE_KEY)).status,200);
  assert.equal((await upload(env.DEVICE_KEY)).status,200);
  assert.deepEqual(attempts,['41','52','52']);
});

test('phone must authenticate before receiving offers and replaced socket goes offline',async()=>{
  const {hub,sockets}=fixture();
  const join = () => {
    const phone=socket({role:'pending',expires:Date.now()+15000});
    sockets.push(phone); return phone;
  };
  const viewer=socket({role:'viewer',peer:'one',id:'41',expires:Date.now()+100000});
  sockets.push(viewer);
  const wrong=join();
  await hub.webSocketMessage(wrong,'{"type":"auth","key":"bad"}');
  assert.equal(wrong.code,1008);
  const phone=join();
  await hub.webSocketMessage(phone,JSON.stringify({type:'auth',key:env.DEVICE_KEY}));
  assert.deepEqual(phone.frames,[{type:'ready'}]);
  await hub.webSocketMessage(phone,'{"type":"ping"}');
  assert.deepEqual(phone.frames.pop(),{type:'pong'});
  assert.deepEqual(viewer.frames,[{type:'ready'}]);
  const replacement=join();
  await hub.webSocketMessage(replacement,JSON.stringify({type:'auth',key:env.DEVICE_KEY}));
  assert.equal(phone.code,1000);
  assert.deepEqual(viewer.frames.slice(-2),[{type:'offline'},{type:'ready'}]);
  hub.webSocketClose(phone);
  assert.equal(viewer.frames.length,3);
  hub.webSocketClose(viewer);
  assert.deepEqual(replacement.frames.pop(),{type:'leave',peer:'one'});
});

test('logout rejects cross-origin POST and closes only the matching session',async()=>{
  const {request,login,hub,sockets}=fixture();
  const response=await login(41);
  const cookie=response.headers.get('set-cookie').split(';')[0];
  const {token,expires}=await hub.session(new Request(origin,{headers:{Cookie:cookie}}));
  const socketA=socket({role:'viewer',peer:'a',id:'41',token,expires});
  const socketB=socket({role:'viewer',peer:'b',id:'41',token:'other',expires});
  sockets.push(socketA,socketB);
  const logout=(o)=>request('/api/logout',{method:'POST',headers:{Cookie:cookie,Origin:o}});
  assert.equal((await logout('https://other.test')).status,403);
  assert.equal((await logout(origin)).status,200);
  assert.equal(socketA.code,1008);
  assert.equal(socketB.code,undefined);
  assert.equal((await request('/api/me',{headers:{Cookie:cookie}})).status,401);
});

test('OIDC binds browser state and PKCE, validates signed profile ID, nonce and claims',async(t)=>{
  const {generateKeyPairSync, sign, createHash}=await import('node:crypto');
  const {privateKey,publicKey}=generateKeyPairSync('rsa',{modulusLength:2048});
  const jwk={...publicKey.export({format:'jwk'}),kid:'official',alg:'RS256'};
  const {request}=fixture();
  let authorization,claimsOverride={},fakeKey=false,exchanges=0;
  botStub(t,async(url,options)=>{
    if(url.endsWith('/jwks.json')) return Response.json({keys:[jwk]});
    assert.equal(url,'https://oauth.telegram.org/token');
    exchanges++;
    assert.equal(options.headers.Authorization,'Basic '+btoa(env.OIDC_CLIENT_ID+':'+env.OIDC_CLIENT_SECRET));
    assert.equal(createHash('sha256').update(options.body.get('code_verifier')).digest('base64url'),authorization.get('code_challenge'));
    const now=Math.floor(Date.now()/1000);
    const claims={iss:'https://oauth.telegram.org',aud:'72',iat:now,exp:now+300,nonce:authorization.get('nonce'),id:41,sub:'unrelated-subject',...claimsOverride};
    const encode=obj=>Buffer.from(JSON.stringify(obj)).toString('base64url');
    const input=encode({alg:'RS256',kid:'official'})+'.'+encode(claims);
    const signature=sign('RSA-SHA256',Buffer.from(input),privateKey);
    if(fakeKey) signature[0]^=1;
    return Response.json({id_token:input+'.'+signature.toString('base64url')});
  });
  async function flow() {
    const start=await request('/auth/login?event=e1');
    assert.equal(start.status,302);
    authorization=new URL(start.headers.get('Location')).searchParams;
    assert.equal(authorization.get('scope'),'openid profile');
    const cookie=start.headers.get('Set-Cookie').split(';')[0];
    const path='/auth/callback?code=code&state='+authorization.get('state');
    return {path,cookie};
  }
  const first=await flow();
  const exchange=({path,cookie})=>request(path,{headers:{Cookie:cookie}});
  assert.equal((await request(first.path)).status,401);
  assert.equal(exchanges,0);
  const valid=await exchange(first);
  assert.equal(valid.status,302);
  assert.equal(valid.headers.get('Location'),'/?event=e1');
  assert.match(valid.headers.get('Set-Cookie'),/__Host-doorcam=/);
  assert.equal((await exchange(first)).status,401);
  for(const patch of [{exp:1},{iss:'invalid'},{aud:'wrong'},{nonce:'wrong'},{iat:0}]) {
    claimsOverride=patch;
    assert.equal((await exchange(await flow())).status,401);
  }
  claimsOverride={id:55,sub:'41'};
  assert.equal((await exchange(await flow())).status,403);
  claimsOverride={}; fakeKey=true;
  assert.equal((await exchange(await flow())).status,401);
});

test('TURN API credentials stay server-side and require viewer or device authorization',async(t)=>{
  const {request,hub}=fixture();
  hub.env={...env,TURN_KEY_ID:'key-id',TURN_API_TOKEN:'server-token'};
  const stub=botStub(t,async(url,options)=>{
    assert.equal(url,'https://rtc.live.cloudflare.com/v1/turn/keys/key-id/credentials/generate-ice-servers');
    assert.equal(options.headers.Authorization,'Bearer server-token');
    assert.equal(JSON.parse(options.body).ttl,3600);
    return Response.json({iceServers:[{urls:['turns:relay.test:443'],username:'short',credential:'issued'}]});
  });
  assert.equal((await request('/api/ice')).status,401);
  assert.equal(stub.mock.callCount(),0);
  const result=await request('/api/ice',{headers:{Authorization:'Bearer '+env.DEVICE_KEY}});
  assert.equal(result.status,200);
  assert.equal((await result.json()).iceServers[0].credential,'issued');
});

test('owner approves requests and revokes from /list with Telegram inline buttons',async(t)=>{
  const {request,login,webhook}=fixture();
  const calls=[];
  botStub(t,(url,opts)=>{calls.push({method:url.split('/').pop(),...JSON.parse(opts.body)});return Response.json({ok:true});});
  async function tap(user,data,messageNumber,chatType='private') {
    return request('/telegram/webhook',{method:'POST',headers:{'X-Telegram-Bot-Api-Secret-Token':env.WEBHOOK_SECRET},
      body:JSON.stringify({callback_query:{id:'callback-'+messageNumber,from:{id:user},message:{message_id:messageNumber,chat:{type:chatType,id:user}},data}})});
  }
  const button = (method,action) => calls.filter(c=>c.method===method && c.chat_id==='41')
    .flatMap(c=>c.reply_markup?.inline_keyboard.flat() || []).find(b=>b.callback_data?.startsWith(action));
  await webhook(52,'/start');
  const grant=button('sendMessage','allow');
  assert.ok(grant);
  await tap(99,grant.callback_data,2);
  await tap(41,grant.callback_data,2,'group');
  assert.equal((await login(52)).status,403);
  await tap(41,grant.callback_data,2);
  assert.equal((await login(52)).status,200);
  assert.ok(calls.some(c=>c.method==='answerCallbackQuery'));
  calls.length=0;
  await webhook(41,'/list',3);
  const revoke=button('sendMessage','revoke');
  assert.ok(revoke);
  await tap(41,revoke.callback_data,4);
  assert.equal((await login(52)).status,403);
  await tap(41,grant.callback_data,2);
  assert.equal((await login(52)).status,403);
});
