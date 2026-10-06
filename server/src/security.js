// Passwords (scrypt), login tokens (HS256 JWT), dynamic QR tokens (HMAC-SHA256),
// face-template encryption (AES-256-GCM) and face matching (cosine similarity).
const crypto = require('node:crypto');
const config = require('./config');

// ---------- Passwords ----------
function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(String(password), salt, 64);
  return `scrypt$${salt.toString('hex')}$${hash.toString('hex')}`;
}
function verifyPassword(password, stored) {
  const [alg, saltHex, hashHex] = String(stored || '').split('$');
  if (alg !== 'scrypt') return false;
  const hash = crypto.scryptSync(String(password), Buffer.from(saltHex, 'hex'), 64);
  return crypto.timingSafeEqual(hash, Buffer.from(hashHex, 'hex'));
}

// ---------- Login tokens (JWT, HS256) ----------
const b64url = (buf) => Buffer.from(buf).toString('base64url');
function signToken(payload) {
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const now = Math.floor(Date.now() / 1000);
  const body = b64url(JSON.stringify({ ...payload, iat: now, exp: now + config.tokenDays * 86400 }));
  const sig = crypto.createHmac('sha256', config.jwtSecret).update(`${header}.${body}`).digest('base64url');
  return `${header}.${body}.${sig}`;
}
function verifyToken(token) {
  const parts = String(token || '').split('.');
  if (parts.length !== 3) return null;
  const expected = crypto.createHmac('sha256', config.jwtSecret).update(`${parts[0]}.${parts[1]}`).digest();
  const given = Buffer.from(parts[2], 'base64url');
  if (given.length !== expected.length || !crypto.timingSafeEqual(given, expected)) return null;
  try {
    const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
    if (payload.exp && payload.exp < Math.floor(Date.now() / 1000)) return null;
    return payload;
  } catch {
    return null;
  }
}

// ---------- Dynamic QR tokens ----------
// Token = ATT1.<sessionId>.<window>.<signature>; signature = HMAC(sessionId|classroomId|window)
function qrWindow(now = Date.now()) {
  return Math.floor(now / 1000 / config.qrWindowSeconds);
}
function qrSignature(sessionId, classroomId, w) {
  return crypto.createHmac('sha256', config.qrSecret).update(`${sessionId}|${classroomId}|${w}`).digest('base64url');
}
function makeQrToken(sessionId, classroomId, now = Date.now()) {
  const w = qrWindow(now);
  const secondsLeft = config.qrWindowSeconds - (Math.floor(now / 1000) % config.qrWindowSeconds);
  return { token: `ATT1.${sessionId}.${w}.${qrSignature(sessionId, classroomId, w)}`, secondsLeft };
}
function parseQrToken(token) {
  const parts = String(token || '').trim().split('.');
  if (parts.length !== 4 || parts[0] !== 'ATT1') return null;
  const w = Number(parts[2]);
  if (!Number.isInteger(w)) return null;
  return { sessionId: parts[1], window: w, signature: parts[3] };
}
// Accept the current window and one previous window (grace period for scanning delay)
function checkQrToken(parsed, classroomId, now = Date.now()) {
  const cur = qrWindow(now);
  if (parsed.window !== cur && parsed.window !== cur - 1) return { ok: false, reason: 'QR code expired. Scan the live QR again.' };
  const expected = Buffer.from(qrSignature(parsed.sessionId, classroomId, parsed.window));
  const given = Buffer.from(parsed.signature);
  if (expected.length !== given.length || !crypto.timingSafeEqual(expected, given)) return { ok: false, reason: 'Invalid QR code.' };
  return { ok: true };
}

// ---------- Check-in ticket (issued by precheck, valid 120 s, bound to one student + session) ----------
const TICKET_SECONDS = 120;
function ticketSig(uid, sessionId, issued) {
  return crypto.createHmac('sha256', config.qrSecret).update(`ticket|${uid}|${sessionId}|${issued}`).digest('base64url');
}
function makeTicket(uid, sessionId, now = Date.now()) {
  return `${sessionId}.${now}.${ticketSig(uid, sessionId, now)}`;
}
function checkTicket(ticket, uid, now = Date.now()) {
  const parts = String(ticket || '').split('.');
  if (parts.length !== 3) return { ok: false, reason: 'Scan the QR code first.' };
  const [sessionId, issuedStr, sig] = parts;
  const issued = Number(issuedStr);
  const expected = Buffer.from(ticketSig(uid, sessionId, issued));
  const given = Buffer.from(sig);
  if (!Number.isFinite(issued) || expected.length !== given.length || !crypto.timingSafeEqual(expected, given)) return { ok: false, reason: 'Invalid check-in. Scan the QR code again.' };
  if (now - issued > TICKET_SECONDS * 1000 || issued - now > 5000) return { ok: false, reason: 'Check-in took too long. Scan the QR code again.' };
  return { ok: true, sessionId };
}

// ---------- Face templates ----------
function validEmbedding(v) {
  return Array.isArray(v) && v.length >= 64 && v.length <= 1024 && v.every((x) => typeof x === 'number' && Number.isFinite(x));
}
function normalize(v) {
  const n = Math.sqrt(v.reduce((s, x) => s + x * x, 0)) || 1;
  return v.map((x) => x / n);
}
function averageEmbeddings(list) {
  const len = list[0].length;
  const sum = new Array(len).fill(0);
  for (const v of list) {
    const nv = normalize(v);
    for (let i = 0; i < len; i++) sum[i] += nv[i];
  }
  return normalize(sum);
}
function cosine(a, b) {
  if (a.length !== b.length) return -1;
  const na = normalize(a), nb = normalize(b);
  let s = 0;
  for (let i = 0; i < na.length; i++) s += na[i] * nb[i];
  return s;
}
function encryptEmbedding(vec) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', config.faceKey, iv);
  const enc = Buffer.concat([cipher.update(JSON.stringify(vec), 'utf8'), cipher.final()]);
  return `${iv.toString('base64')}.${cipher.getAuthTag().toString('base64')}.${enc.toString('base64')}`;
}
function decryptEmbedding(text) {
  const [iv, tag, data] = String(text).split('.');
  const decipher = crypto.createDecipheriv('aes-256-gcm', config.faceKey, Buffer.from(iv, 'base64'));
  decipher.setAuthTag(Buffer.from(tag, 'base64'));
  return JSON.parse(Buffer.concat([decipher.update(Buffer.from(data, 'base64')), decipher.final()]).toString('utf8'));
}

// ---------- Geo ----------
function distanceMeters(lat1, lng1, lat2, lng2) {
  const R = 6371000;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

function normalizeBssid(b) {
  return String(b || '').trim().toLowerCase();
}

module.exports = {
  hashPassword, verifyPassword, signToken, verifyToken,
  makeQrToken, parseQrToken, checkQrToken, makeTicket, checkTicket,
  validEmbedding, averageEmbeddings, cosine, encryptEmbedding, decryptEmbedding,
  distanceMeters, normalizeBssid,
};
