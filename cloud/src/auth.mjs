const encoder = new TextEncoder();
const issuer = 'https://oauth.telegram.org';
const rs256 = {name:'RSASSA-PKCS1-v1_5', hash:'SHA-256'};

export class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
export function requireValue(condition, status, message) {
  if (!condition) throw new HttpError(status, message);
}
export function telegramId(id) {
  const text = String(id);
  return /^[1-9]\d{0,15}$/.test(text) && Number.isSafeInteger(Number(text)) ? text : null;
}
export function base64url(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
}
export function randomToken() { return base64url(crypto.getRandomValues(new Uint8Array(32))); }
export async function digest(text) { return base64url(await crypto.subtle.digest('SHA-256', encoder.encode(text))); }
export function cookie(request, name) {
  for (const entry of (request.headers.get('Cookie') || '').split(';')) {
    const [key,value] = entry.trim().split('=');
    if (key === name && /^[A-Za-z0-9_-]{43}$/.test(value)) return value;
  }
  return null;
}
export async function equalSecret(value, expected) {
  if (!expected || typeof value !== 'string' || value.length > 1024) return false;
  const hash = async str => new Uint8Array(await crypto.subtle.digest('SHA-256',encoder.encode(str)));
  const [a,b] = await Promise.all([hash(value),hash(expected)]);
  let diff = 0;
  for(let i=0;i<a.length;i++) diff |= a[i]^b[i];
  return diff === 0;
}
export async function miniAppId(raw, token) {
  const deny = () => {throw new HttpError(401,'Недействительные данные Telegram');};
  if (!token || typeof raw !== 'string' || raw.length > 16000) deny();
  const fields = new URLSearchParams(raw);
  const hash = fields.get('hash');
  if (!/^[a-f0-9]{64}$/.test(hash) || new Set(fields.keys()).size !== [...fields].length) deny();
  fields.delete('hash');
  fields.sort();
  const check = [...fields].map(([key,val]) => key+'='+val).join('\n');
  const hmac = {name:'HMAC',hash:'SHA-256'};
  const webKey = await crypto.subtle.importKey('raw',encoder.encode('WebAppData'),hmac,false,['sign']);
  const secret = await crypto.subtle.sign('HMAC', webKey, encoder.encode(token));
  const key = await crypto.subtle.importKey('raw',secret,hmac,false,['verify']);
  const signature = Uint8Array.from(hash.match(/../g), h => parseInt(h,16));
  if (!await crypto.subtle.verify('HMAC',key,signature,encoder.encode(check))) deny();
  const age = Date.now()/1000 - Number(fields.get('auth_date'));
  if (!Number.isFinite(age) || age < -30 || age > 300) deny();
  let user;
  try { user = JSON.parse(fields.get('user')); } catch { deny(); }
  return telegramId(user?.id) || deny();
}
export function authorizationUrl(env, state, nonce, challenge) {
  const url = new URL(issuer+'/auth');
  url.search = new URLSearchParams({client_id:env.OIDC_CLIENT_ID, redirect_uri:env.PUBLIC_ORIGIN+'/auth/callback',
    response_type:'code', scope:'openid profile',state,nonce,code_challenge:challenge,code_challenge_method:'S256'});
  return url.href;
}
function decodeBytes(text) {
  if (!/^[A-Za-z0-9_-]+$/.test(text)) throw new Error('invalid encoding');
  return Uint8Array.from(atob(text.replace(/-/g,'+').replace(/_/g,'/')), c => c.charCodeAt(0));
}
export async function oidcId(env, code, flow) {
  const token = await fetch(issuer+'/token',{method:'POST',redirect:'manual',
    headers:{Authorization:'Basic '+btoa(env.OIDC_CLIENT_ID+':'+env.OIDC_CLIENT_SECRET)},
    body:new URLSearchParams({grant_type:'authorization_code',code,redirect_uri:env.PUBLIC_ORIGIN+'/auth/callback',
      client_id:env.OIDC_CLIENT_ID,code_verifier:flow.verifier})});
  requireValue(token.type!=='opaqueredirect' && token.status<300,
    token.status>=500?503:401,'Telegram не подтвердил вход');
  const value = (await token.json()).id_token;
  requireValue(typeof value==='string' && value.length<32768,401,'Некорректный токен Telegram');
  const parts = value.split('.');
  let header, claims, signature;
  try {
    const decodeJson = str => JSON.parse(new TextDecoder().decode(decodeBytes(str)));
    header = decodeJson(parts[0]); claims = decodeJson(parts[1]); signature = decodeBytes(parts[2]);
  } catch { throw new HttpError(401,'Некорректный токен Telegram'); }
  requireValue(parts.length===3 && header?.alg==='RS256' && typeof header.kid==='string' && !header.crit,401,'Неподдерживаемая подпись');
  const keys = await fetch(issuer+'/.well-known/jwks.json',{redirect:'manual',cf:{cacheTtl:300,cacheEverything:true}});
  requireValue(keys.ok,503,'Недоступны ключи Telegram');
  const jwk = (await keys.json()).keys.find(k=>k.kid===header.kid && k.kty==='RSA' && (!k.alg || k.alg==='RS256') && (!k.use || k.use==='sig'));
  requireValue(jwk,401,'Неизвестный ключ Telegram');
  const key = await crypto.subtle.importKey('jwk',jwk,rs256,false,['verify']);
  requireValue(await crypto.subtle.verify(rs256,key,signature,encoder.encode(parts[0]+'.'+parts[1])),401,'Неверная подпись Telegram');
  const now = Date.now()/1000;
  const aud = claims?.aud;
  const validAudience = Array.isArray(aud) ? aud.includes(env.OIDC_CLIENT_ID) && (aud.length===1 || claims.azp===env.OIDC_CLIENT_ID) : aud===env.OIDC_CLIENT_ID;
  requireValue(claims?.iss===issuer && validAudience && (!claims.azp || claims.azp===env.OIDC_CLIENT_ID)
    && typeof claims.sub==='string' && claims.sub.length>0 && claims.nonce===flow.nonce
    && Number.isSafeInteger(claims.exp) && claims.exp>now && Number.isSafeInteger(claims.iat)
    && claims.iat<=now+30 && claims.iat>=now-660 && (claims.nbf===undefined || (Number.isSafeInteger(claims.nbf) && claims.nbf<=now+30))
    && telegramId(claims.id),401,'Недействительные данные входа');
  return telegramId(claims.id);
}
