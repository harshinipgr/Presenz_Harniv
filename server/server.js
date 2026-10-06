// Smart Attendance - backend API server
// Run:  npm start      (requires Node.js 22.13 or newer, no "npm install" needed)
const http = require('node:http');
const crypto = require('node:crypto');
const os = require('node:os');
const config = require('./src/config');
const db = require('./src/db');
const sec = require('./src/security');
const { localParts, windowStatus, isValidDate, addDays } = require('./src/time');
const { buildXlsx, S } = require('./src/xlsx');

// ------------------------------------------------------------------ helpers
class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
const bad = (msg) => new HttpError(400, msg);

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body), 'Cache-Control': 'no-store' });
  res.end(body);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > 1_000_000) { reject(new HttpError(413, 'Request too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch { reject(bad('Invalid JSON body')); }
    });
    req.on('error', reject);
  });
}

const str = (v, max = 100) => String(v ?? '').trim().slice(0, max);
const upper = (v, max = 30) => str(v, max).toUpperCase();
const nowIso = () => new Date().toISOString();
const localNow = () => localParts();

function publicUser(u) {
  return {
    id: u.id, role: u.role, name: u.name,
    registerNo: u.register_no, email: u.email,
    department: u.department, section: u.section,
    faceEnrolled: !!u.face_enc,
  };
}

function getUser(id) {
  return db.prepare('SELECT * FROM users WHERE id = ?').get(id);
}

function parseEmbeddings(list) {
  if (!Array.isArray(list) || list.length < 1 || list.length > 5) throw bad('Face data missing. Please capture your face again.');
  for (const e of list) if (!sec.validEmbedding(e)) throw bad('Face data invalid. Please capture your face again.');
  const len = list[0].length;
  if (!list.every((e) => e.length === len)) throw bad('Face data invalid.');
  return sec.averageEmbeddings(list);
}

// Convert a UTC ISO timestamp to local "HH:MM:SS"
const localTime = (iso) => (iso ? localParts(new Date(iso)).time : '');
const signupDate = (u) => localParts(new Date(u.created_at.replace(' ', 'T') + 'Z')).date;

// ------------------------------------------------------------------ router
const routes = [];
function route(method, path, role, handler) {
  const keys = [];
  const regex = new RegExp('^' + path.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)'; }) + '/?$');
  routes.push({ method, regex, keys, role, handler });
}

function authenticate(req, role) {
  const h = req.headers.authorization || '';
  const token = h.startsWith('Bearer ') ? h.slice(7) : '';
  const payload = sec.verifyToken(token);
  if (!payload) throw new HttpError(401, 'Please log in again.');
  const user = getUser(payload.uid);
  if (!user) throw new HttpError(401, 'Account not found. Please sign up again.');
  if (role && user.role !== role) throw new HttpError(403, 'Not allowed for this account type.');
  return user;
}

// ------------------------------------------------------------------ public routes
route('GET', '/', null, () => ({ ok: true, app: 'Smart Attendance API', window: windowStatus() }));
route('GET', '/api/health', null, () => ({ ok: true, app: 'Smart Attendance API', window: windowStatus() }));

route('POST', '/api/auth/signup/student', null, async (req, body) => {
  const name = str(body.name, 80);
  const registerNo = upper(body.registerNo, 20);
  const department = upper(body.department, 40);
  const section = upper(body.section, 20);
  const password = String(body.password || '');
  if (name.length < 2) throw bad('Enter your full name.');
  if (!/^[A-Z0-9]{3,20}$/.test(registerNo)) throw bad('Register number must be 3-20 letters/digits.');
  if (!department) throw bad('Enter your department.');
  if (!section) throw bad('Enter your section.');
  if (password.length < 6) throw bad('Password must be at least 6 characters.');
  const face = parseEmbeddings(body.faceEmbeddings);
  if (db.prepare('SELECT id FROM users WHERE register_no = ?').get(registerNo)) throw new HttpError(409, 'This register number is already registered. Please log in.');
  const r = db.prepare(`INSERT INTO users (role, name, register_no, department, section, password_hash, face_enc, face_enrolled_at, device_hash)
    VALUES ('student', ?, ?, ?, ?, ?, ?, ?, ?)`).run(name, registerNo, department, section, sec.hashPassword(password), sec.encryptEmbedding(face), nowIso(), str(body.deviceHash, 128));
  const user = getUser(Number(r.lastInsertRowid));
  return { token: sec.signToken({ uid: user.id, role: user.role }), user: publicUser(user) };
});

route('POST', '/api/auth/signup/faculty', null, async (req, body) => {
  const name = str(body.name, 80);
  const email = str(body.email, 120).toLowerCase();
  const department = upper(body.department, 40);
  const section = upper(body.section, 20) === 'ALL' ? '' : upper(body.section, 20); // the class this faculty handles ('' = all classes)
  const password = String(body.password || '');
  if (str(body.facultyCode, 100) !== config.facultySignupCode) throw new HttpError(403, 'Invalid faculty signup code. Ask the administrator.');
  if (name.length < 2) throw bad('Enter your full name.');
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw bad('Enter a valid email address.');
  if (!department) throw bad('Enter your department.');
  if (password.length < 6) throw bad('Password must be at least 6 characters.');
  if (db.prepare('SELECT id FROM users WHERE email = ?').get(email)) throw new HttpError(409, 'This email is already registered. Please log in.');
  const r = db.prepare(`INSERT INTO users (role, name, email, department, section, password_hash, device_hash) VALUES ('faculty', ?, ?, ?, ?, ?, ?)`)
    .run(name, email, department, section, sec.hashPassword(password), str(body.deviceHash, 128));
  const user = getUser(Number(r.lastInsertRowid));
  return { token: sec.signToken({ uid: user.id, role: user.role }), user: publicUser(user) };
});

route('POST', '/api/auth/login', null, async (req, body) => {
  const role = body.role === 'faculty' ? 'faculty' : 'student';
  const loginId = str(body.loginId, 120);
  const user = role === 'faculty'
    ? db.prepare("SELECT * FROM users WHERE role = 'faculty' AND email = ?").get(loginId.toLowerCase())
    : db.prepare("SELECT * FROM users WHERE role = 'student' AND register_no = ?").get(loginId.toUpperCase());
  if (!user || !sec.verifyPassword(String(body.password || ''), user.password_hash)) throw new HttpError(401, 'Wrong login ID or password.');
  if (body.deviceHash) db.prepare('UPDATE users SET device_hash = ? WHERE id = ?').run(str(body.deviceHash, 128), user.id);
  return { token: sec.signToken({ uid: user.id, role: user.role }), user: publicUser(user) };
});

route('GET', '/api/me', 'any', (req, body, user) => ({ user: publicUser(user), window: windowStatus() }));

// ------------------------------------------------------------------ student routes
route('POST', '/api/student/face', 'student', (req, body, user) => {
  if (user.face_enc) throw new HttpError(409, 'Face is already enrolled. Ask your faculty to reset it if needed.');
  const face = parseEmbeddings(body.faceEmbeddings);
  db.prepare('UPDATE users SET face_enc = ?, face_enrolled_at = ? WHERE id = ?').run(sec.encryptEmbedding(face), nowIso(), user.id);
  return { ok: true, user: publicUser(getUser(user.id)) };
});

function studentStats(user) {
  const from = signupDate(user);
  const workingDays = db.prepare('SELECT COUNT(DISTINCT date) AS n FROM sessions WHERE (section = ? OR section = \'ALL\') AND date >= ?').get(user.section, from).n;
  const present = db.prepare("SELECT COUNT(*) AS n FROM attendance WHERE student_id = ? AND status = 'PRESENT'").get(user.id).n;
  const percent = workingDays ? Math.round((present / workingDays) * 1000) / 10 : 0;
  return { present, workingDays, absent: Math.max(0, workingDays - present), percent };
}

route('GET', '/api/student/dashboard', 'student', (req, body, user) => {
  const w = windowStatus();
  const today = db.prepare('SELECT * FROM attendance WHERE student_id = ? AND date = ?').get(user.id, w.today);
  const dates = db.prepare(`SELECT DISTINCT date FROM sessions WHERE (section = ? OR section = 'ALL') AND date >= ? ORDER BY date DESC LIMIT 30`).all(user.section, signupDate(user)).map((r) => r.date);
  const rows = db.prepare('SELECT date, status, marked_at FROM attendance WHERE student_id = ? ORDER BY date DESC LIMIT 60').all(user.id);
  const byDate = new Map(rows.map((r) => [r.date, r]));
  const history = dates.map((d) => {
    const a = byDate.get(d);
    return { date: d, status: a ? a.status : 'ABSENT', time: a ? localTime(a.marked_at) : '' };
  });
  return {
    user: publicUser(user),
    window: w,
    today: today ? { marked: true, status: today.status, time: localTime(today.marked_at) } : { marked: false },
    stats: studentStats(user),
    history,
  };
});

// Shared checks used by both the precheck (after QR scan) and the final submission.
// mode 'qr'     -> body.qrToken is the freshly scanned dynamic QR (valid 15 s + one grace window)
// mode 'ticket' -> body.ticket is the short-lived ticket returned by precheck (valid 120 s, bound to this student)
function presenceChecks(user, body, mode) {
  const w = windowStatus();
  const deviceHash = str(body.deviceHash, 128);
  const ctx = { w, deviceHash, sessionId: null, checks: { time: false, qr: false, wifi: false, location: false, liveness: false, face: false, device: false } };
  const log = (result, reason) => db.prepare('INSERT INTO attempts (student_id, session_id, at, result, reason, device_hash) VALUES (?, ?, ?, ?, ?, ?)')
    .run(user.id, ctx.sessionId, nowIso(), result, reason || null, deviceHash);
  ctx.finish = (result, reason, extra = {}) => { log(result, reason); return { status: result, reason: reason || '', checks: ctx.checks, ...extra }; };
  const fail = (reason) => { ctx.error = ctx.finish('REJECTED', reason); return ctx; };

  // 1. Time window (server clock)
  if (!w.open) return fail(`Attendance can be marked only between ${w.start} and ${w.end}. Server time now: ${w.serverTime.slice(0, 5)}.`);
  ctx.checks.time = true;

  // 2. One attendance per day
  const existing = db.prepare('SELECT * FROM attendance WHERE student_id = ? AND date = ?').get(user.id, w.today);
  if (existing) return fail(`Attendance already recorded today at ${localTime(existing.marked_at).slice(0, 5)} (${existing.status}).`);

  // 3. Dynamic QR (or the ticket issued after a valid QR scan)
  let session;
  if (mode === 'qr') {
    const parsed = sec.parseQrToken(body.qrToken);
    if (!parsed) return fail('This is not a valid attendance QR code.');
    session = db.prepare('SELECT * FROM sessions WHERE id = ?').get(parsed.sessionId);
    if (session) ctx.sessionId = session.id;
    if (!session || session.date !== w.today || session.ended_at) return fail('This attendance session is not active.');
    const qr = sec.checkQrToken(parsed, session.classroom_id);
    if (!qr.ok) return fail(qr.reason);
  } else {
    const t = sec.checkTicket(body.ticket, user.id);
    if (!t.ok) return fail(t.reason);
    session = db.prepare('SELECT * FROM sessions WHERE id = ?').get(t.sessionId);
    if (session) ctx.sessionId = session.id;
    if (!session || session.date !== w.today || session.ended_at) return fail('This attendance session is not active.');
  }
  if (session.section !== 'ALL' && session.section !== user.section) return fail(`This session is for section ${session.section}. Your section is ${user.section}.`);
  const room = db.prepare('SELECT * FROM classrooms WHERE id = ?').get(session.classroom_id);
  ctx.session = session;
  ctx.room = room;
  ctx.checks.qr = true;

  // 4. Campus Wi-Fi (access point BSSID)
  const allowed = JSON.parse(room.bssids || '[]').map(sec.normalizeBssid);
  const bssid = sec.normalizeBssid(body.bssid);
  if (!bssid || bssid === '02:00:00:00:00:00') return fail('Could not read Wi-Fi details. Connect to the classroom Wi-Fi and turn on Location.');
  if (!allowed.includes(bssid)) return fail(`Connected Wi-Fi is not the authorised network for ${room.name}.`);
  ctx.bssid = bssid;
  ctx.checks.wifi = true;

  // 5. GPS geofence
  const lat = Number(body.lat), lng = Number(body.lng), accuracy = Number(body.accuracy);
  if (body.isMock === true) return fail('Mock (fake) location detected. Turn off fake GPS apps.');
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return fail('Location not available.');
  if (!Number.isFinite(accuracy) || accuracy > room.max_accuracy_m) return fail(`Location not accurate enough (±${Math.round(accuracy) || '?'} m). Move near a window and retry.`);
  const distance = sec.distanceMeters(lat, lng, room.lat, room.lng);
  if (distance > room.radius_m) return fail(`You are ${Math.round(distance)} m away from ${room.name}. Allowed: ${room.radius_m} m.`);
  Object.assign(ctx, { lat, lng, accuracy, distance });
  ctx.checks.location = true;
  return ctx;
}

// Step 1 (right after scanning the QR): verify time, QR, Wi-Fi and location. Returns a 120-second ticket.
route('POST', '/api/student/precheck', 'student', (req, body, user) => {
  const ctx = presenceChecks(user, body, 'qr');
  if (ctx.error) return ctx.error;
  return {
    status: 'OK', reason: '', checks: ctx.checks,
    ticket: sec.makeTicket(user.id, ctx.session.id),
    classroom: ctx.room.name, section: ctx.session.section, distance: Math.round(ctx.distance),
  };
});

// Step 2 (after the face + liveness step): re-verify everything, match the face, check the device, record attendance.
route('POST', '/api/student/attendance', 'student', (req, body, user) => {
  const ctx = presenceChecks(user, body, 'ticket');
  if (ctx.error) return ctx.error;
  const { w, deviceHash, checks, session, room, bssid, lat, lng, accuracy, distance } = ctx;
  const reject = (reason) => ctx.finish('REJECTED', reason);
  const finish = ctx.finish;

  // 6. Liveness + face match
  if (!body.liveness || body.liveness.passed !== true) return reject('Liveness check not completed.');
  checks.liveness = true;
  if (!user.face_enc) return reject('Your face is not enrolled. Open the app to enrol your face.');
  if (!sec.validEmbedding(body.faceEmbedding)) return reject('Face data missing. Please retry.');
  const enrolled = sec.decryptEmbedding(user.face_enc);
  const score = sec.cosine(enrolled, body.faceEmbedding);
  if (score < config.faceThreshold) return reject(`Face did not match the enrolled student (score ${score.toFixed(2)}).`);
  checks.face = true;

  // 7. Single-device monitoring: same phone already used today by another student -> FLAG for review
  let status = 'PRESENT', flagReason = null, otherId = null;
  if (deviceHash) {
    const other = db.prepare('SELECT a.student_id, u.register_no FROM attendance a JOIN users u ON u.id = a.student_id WHERE a.date = ? AND a.device_hash = ? AND a.student_id != ? LIMIT 1')
      .get(w.today, deviceHash, user.id);
    if (other) { status = 'FLAGGED'; otherId = other.student_id; flagReason = `Same phone already used today by ${other.register_no}`; }
  } else {
    status = 'FLAGGED'; flagReason = 'Device ID missing';
  }
  checks.device = status === 'PRESENT';

  const markedAt = nowIso();
  try {
    db.prepare(`INSERT INTO attendance (student_id, date, session_id, marked_at, status, device_hash, bssid, ssid, lat, lng, distance_m, accuracy_m, face_score, flag_reason, other_student_id)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
      .run(user.id, w.today, session.id, markedAt, status, deviceHash, bssid, str(body.ssid, 64), lat, lng, Math.round(distance * 10) / 10, accuracy, Math.round(score * 1000) / 1000, flagReason, otherId);
  } catch (e) {
    if (String(e.message).includes('UNIQUE')) return reject('Attendance already recorded today.');
    throw e;
  }
  return finish(status, status === 'FLAGGED' ? `${flagReason}. Your attendance is waiting for faculty review.` : '', {
    time: localTime(markedAt), classroom: room.name, distance: Math.round(distance), faceScore: Math.round(score * 100) / 100,
  });
});

// ------------------------------------------------------------------ faculty routes
function roomOut(r) {
  return { id: r.id, name: r.name, lat: r.lat, lng: r.lng, radiusM: r.radius_m, maxAccuracyM: r.max_accuracy_m, bssids: JSON.parse(r.bssids || '[]') };
}

function roomInput(body) {
  const name = str(body.name, 60);
  const lat = Number(body.lat), lng = Number(body.lng);
  const radiusM = Number(body.radiusM ?? 40), maxAccuracyM = Number(body.maxAccuracyM ?? 50);
  if (!name) throw bad('Enter a classroom name.');
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180 || (lat === 0 && lng === 0)) throw bad('Set the classroom location (tap "Use my current location").');
  if (!(radiusM >= 5 && radiusM <= 1000)) throw bad('Radius must be between 5 and 1000 metres.');
  if (!(maxAccuracyM >= 5 && maxAccuracyM <= 500)) throw bad('GPS accuracy limit must be between 5 and 500 metres.');
  const list = Array.isArray(body.bssids) ? body.bssids : [];
  const bssids = [...new Set(list.map(sec.normalizeBssid).filter((b) => /^([0-9a-f]{2}:){5}[0-9a-f]{2}$/.test(b) && b !== '02:00:00:00:00:00'))];
  return { name, lat, lng, radiusM, maxAccuracyM, bssids };
}

route('GET', '/api/faculty/classrooms', 'faculty', () => ({ classrooms: db.prepare('SELECT * FROM classrooms ORDER BY name').all().map(roomOut) }));

route('POST', '/api/faculty/classrooms', 'faculty', (req, body, user) => {
  const r = roomInput(body);
  const res = db.prepare('INSERT INTO classrooms (name, lat, lng, radius_m, max_accuracy_m, bssids, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)')
    .run(r.name, r.lat, r.lng, r.radiusM, r.maxAccuracyM, JSON.stringify(r.bssids), user.id);
  return { classroom: roomOut(db.prepare('SELECT * FROM classrooms WHERE id = ?').get(Number(res.lastInsertRowid))) };
});

route('PUT', '/api/faculty/classrooms/:id', 'faculty', (req, body, user, p) => {
  const r = roomInput(body);
  const res = db.prepare('UPDATE classrooms SET name = ?, lat = ?, lng = ?, radius_m = ?, max_accuracy_m = ?, bssids = ? WHERE id = ?')
    .run(r.name, r.lat, r.lng, r.radiusM, r.maxAccuracyM, JSON.stringify(r.bssids), Number(p.id));
  if (!res.changes) throw new HttpError(404, 'Classroom not found.');
  return { classroom: roomOut(db.prepare('SELECT * FROM classrooms WHERE id = ?').get(Number(p.id))) };
});

route('DELETE', '/api/faculty/classrooms/:id', 'faculty', (req, body, user, p) => {
  if (db.prepare('SELECT 1 FROM sessions WHERE classroom_id = ? LIMIT 1').get(Number(p.id))) throw new HttpError(409, 'This classroom already has attendance sessions, so it cannot be deleted. You can edit it instead.');
  db.prepare('DELETE FROM classrooms WHERE id = ?').run(Number(p.id));
  return { ok: true };
});

// ---- Class-wise scoping: a faculty with a section sees ONLY students of that section ----
// facultySection(user) = 'L' for a class faculty, or null for a faculty without a section (sees all classes)
const facultySection = (user) => (user && user.role === 'faculty' && user.section ? user.section : null);
const scopeSection = (user, requested) => facultySection(user) || upper(requested || 'ALL') || 'ALL';
function assertStudentInScope(user, studentId) {
  const fs = facultySection(user);
  if (!fs) return;
  const st = db.prepare('SELECT section FROM users WHERE id = ?').get(studentId);
  if (!st || st.section !== fs) throw new HttpError(403, `This student is not in your class (${fs}).`);
}

route('GET', '/api/faculty/sections', 'faculty', (req, body, user) => ({
  sections: facultySection(user)
    ? [facultySection(user)]
    : db.prepare("SELECT DISTINCT section FROM users WHERE role = 'student' AND section != '' ORDER BY section").all().map((r) => r.section),
}));

function sessionOut(s) {
  const room = db.prepare('SELECT name FROM classrooms WHERE id = ?').get(s.classroom_id);
  const counts = db.prepare("SELECT SUM(status = 'PRESENT') AS present, SUM(status = 'FLAGGED') AS flagged FROM attendance WHERE session_id = ?").get(s.id);
  const total = s.section === 'ALL'
    ? db.prepare("SELECT COUNT(*) AS n FROM users WHERE role = 'student'").get().n
    : db.prepare("SELECT COUNT(*) AS n FROM users WHERE role = 'student' AND section = ?").get(s.section).n;
  const w = windowStatus();
  return {
    id: s.id, date: s.date, classroomId: s.classroom_id, classroom: room ? room.name : '?', section: s.section,
    startedAt: localTime(s.started_at), endedAt: s.ended_at ? localTime(s.ended_at) : null,
    active: !s.ended_at && s.date === w.today && w.open,
    present: counts.present || 0, flagged: counts.flagged || 0, totalStudents: total,
  };
}

route('GET', '/api/faculty/dashboard', 'faculty', (req, body, user) => {
  const w = windowStatus();
  const sessions = db.prepare('SELECT * FROM sessions WHERE faculty_id = ? AND date = ? ORDER BY started_at DESC').all(user.id, w.today).map(sessionOut);
  // The dashboard header shows "email · department"; add the class here so it is visible without an app update
  const shown = publicUser(user);
  shown.department = `${user.department}  ·  ${facultySection(user) ? 'Class: Section ' + facultySection(user) : 'All sections'}`;
  return {
    user: shown,
    window: w,
    sessions,
    counts: (() => {
      const fs = facultySection(user);
      const sec = fs ? ' AND u.section = ?' : '';
      const args = fs ? [fs] : [];
      return {
        students: db.prepare(`SELECT COUNT(*) AS n FROM users u WHERE u.role = 'student'${sec}`).get(...args).n,
        presentToday: db.prepare(`SELECT COUNT(*) AS n FROM attendance a JOIN users u ON u.id = a.student_id WHERE a.date = ? AND a.status = 'PRESENT'${sec}`).get(w.today, ...args).n,
        pendingFlags: db.prepare(`SELECT COUNT(*) AS n FROM attendance a JOIN users u ON u.id = a.student_id WHERE a.status = 'FLAGGED'${sec}`).get(...args).n,
        classrooms: db.prepare('SELECT COUNT(*) AS n FROM classrooms').get().n,
      };
    })(),
  };
});

route('POST', '/api/faculty/sessions', 'faculty', (req, body, user) => {
  const w = windowStatus();
  if (!w.open) throw new HttpError(409, `Sessions can be started only between ${w.start} and ${w.end}. Server time now: ${w.serverTime.slice(0, 5)}.`);
  const room = db.prepare('SELECT * FROM classrooms WHERE id = ?').get(Number(body.classroomId));
  if (!room) throw bad('Choose a classroom.');
  if (JSON.parse(room.bssids || '[]').length === 0) throw bad('Add at least one classroom Wi-Fi access point before starting.');
  const section = facultySection(user) || upper(body.section, 20) || 'ALL';
  const existing = db.prepare('SELECT * FROM sessions WHERE date = ? AND classroom_id = ? AND section = ? AND ended_at IS NULL').get(w.today, room.id, section);
  if (existing) return { session: sessionOut(existing), resumed: true };
  const id = crypto.randomBytes(9).toString('base64url');
  db.prepare('INSERT INTO sessions (id, date, classroom_id, section, faculty_id, started_at) VALUES (?, ?, ?, ?, ?, ?)').run(id, w.today, room.id, section, user.id, nowIso());
  return { session: sessionOut(db.prepare('SELECT * FROM sessions WHERE id = ?').get(id)), resumed: false };
});

function ownSession(user, id) {
  const s = db.prepare('SELECT * FROM sessions WHERE id = ?').get(id);
  if (!s) throw new HttpError(404, 'Session not found.');
  if (s.faculty_id !== user.id) throw new HttpError(403, 'This session belongs to another faculty member.');
  return s;
}

route('GET', '/api/faculty/sessions/:id/qr', 'faculty', (req, body, user, p) => {
  const s = ownSession(user, p.id);
  const w = windowStatus();
  if (s.ended_at) throw new HttpError(409, 'Session has ended.');
  if (s.date !== w.today || !w.open) throw new HttpError(409, `Attendance window is closed (${w.start}-${w.end}).`);
  const q = sec.makeQrToken(s.id, s.classroom_id);
  return { token: q.token, secondsLeft: q.secondsLeft, refreshSeconds: config.qrWindowSeconds, window: w };
});

route('GET', '/api/faculty/sessions/:id/live', 'faculty', (req, body, user, p) => {
  const s = ownSession(user, p.id);
  const rows = db.prepare(`SELECT a.*, u.name, u.register_no FROM attendance a JOIN users u ON u.id = a.student_id WHERE a.session_id = ? ORDER BY a.marked_at DESC`).all(s.id);
  return {
    session: sessionOut(s),
    records: rows.map((r) => ({ id: r.id, name: r.name, registerNo: r.register_no, status: r.status, time: localTime(r.marked_at), reason: r.flag_reason })),
  };
});

route('POST', '/api/faculty/sessions/:id/end', 'faculty', (req, body, user, p) => {
  const s = ownSession(user, p.id);
  if (!s.ended_at) db.prepare('UPDATE sessions SET ended_at = ? WHERE id = ?').run(nowIso(), s.id);
  return { session: sessionOut(db.prepare('SELECT * FROM sessions WHERE id = ?').get(s.id)) };
});

function studentsIn(section) {
  return section && section !== 'ALL'
    ? db.prepare("SELECT * FROM users WHERE role = 'student' AND section = ? ORDER BY register_no").all(section)
    : db.prepare("SELECT * FROM users WHERE role = 'student' ORDER BY section, register_no").all();
}

route('GET', '/api/faculty/attendance', 'faculty', (req, body, user, p, q) => {
  const date = q.get('date') || windowStatus().today;
  if (!isValidDate(date)) throw bad('Invalid date.');
  const section = scopeSection(user, q.get('section'));
  const students = studentsIn(section);
  const att = new Map(db.prepare('SELECT * FROM attendance WHERE date = ?').all(date).map((a) => [a.student_id, a]));
  const sessionSections = new Set(db.prepare('SELECT DISTINCT section FROM sessions WHERE date = ?').all(date).map((r) => r.section));
  const out = { date, section, sessionHeld: false, present: [], flagged: [], absent: [], rejected: [] };
  for (const st of students) {
    const held = sessionSections.has('ALL') || sessionSections.has(st.section);
    if (held) out.sessionHeld = true;
    const a = att.get(st.id);
    const item = { id: st.id, attendanceId: a ? a.id : null, name: st.name, registerNo: st.register_no, section: st.section, time: a ? localTime(a.marked_at) : '', reason: a ? a.flag_reason : null };
    if (a && a.status === 'PRESENT') out.present.push(item);
    else if (a && a.status === 'FLAGGED') out.flagged.push(item);
    else if (a && a.status === 'REJECTED') out.rejected.push(item);
    else if (held) out.absent.push(item);
  }
  return out;
});

route('GET', '/api/faculty/flags', 'faculty', (req, body, user) => {
  const fs = facultySection(user);
  const rows = db.prepare(`SELECT a.*, u.name, u.register_no, u.section, o.register_no AS other_reg, o.name AS other_name
    FROM attendance a JOIN users u ON u.id = a.student_id LEFT JOIN users o ON o.id = a.other_student_id
    WHERE a.status = 'FLAGGED'${fs ? ' AND u.section = ?' : ''} ORDER BY a.date DESC, a.marked_at DESC`).all(...(fs ? [fs] : []));
  return {
    flags: rows.map((r) => ({ attendanceId: r.id, date: r.date, time: localTime(r.marked_at), name: r.name, registerNo: r.register_no, section: r.section, reason: r.flag_reason, otherRegisterNo: r.other_reg, otherName: r.other_name })),
  };
});

route('POST', '/api/faculty/flags/:id', 'faculty', (req, body, user, p) => {
  const decision = body.decision === 'approve' ? 'PRESENT' : body.decision === 'reject' ? 'REJECTED' : null;
  if (!decision) throw bad('Decision must be approve or reject.');
  const flagged = db.prepare('SELECT student_id FROM attendance WHERE id = ?').get(Number(p.id));
  if (flagged) assertStudentInScope(user, flagged.student_id);
  const r = db.prepare("UPDATE attendance SET status = ?, reviewed_by = ?, reviewed_at = ? WHERE id = ? AND status = 'FLAGGED'").run(decision, user.id, nowIso(), Number(p.id));
  if (!r.changes) throw new HttpError(404, 'Flag not found or already reviewed.');
  return { ok: true, status: decision };
});

function workingDaysBySection() {
  const map = new Map();
  for (const r of db.prepare('SELECT DISTINCT date, section FROM sessions').all()) {
    if (!map.has(r.section)) map.set(r.section, new Set());
    map.get(r.section).add(r.date);
  }
  return map;
}

route('GET', '/api/faculty/students', 'faculty', (req, body, user, p, q) => {
  const section = scopeSection(user, q.get('section'));
  const wd = workingDaysBySection();
  const presentCounts = new Map(db.prepare("SELECT student_id, COUNT(*) AS n FROM attendance WHERE status = 'PRESENT' GROUP BY student_id").all().map((r) => [r.student_id, r.n]));
  const students = studentsIn(section).map((st) => {
    const from = signupDate(st);
    const days = new Set([...(wd.get(st.section) || []), ...(wd.get('ALL') || [])].filter((d) => d >= from)).size;
    const present = presentCounts.get(st.id) || 0;
    return { id: st.id, name: st.name, registerNo: st.register_no, department: st.department, section: st.section, faceEnrolled: !!st.face_enc, present, workingDays: days, percent: days ? Math.round((present / days) * 1000) / 10 : 0 };
  });
  return { section, students };
});

route('POST', '/api/faculty/students/:id/reset-face', 'faculty', (req, body, user, p) => {
  assertStudentInScope(user, Number(p.id));
  const r = db.prepare("UPDATE users SET face_enc = NULL, face_enrolled_at = NULL WHERE id = ? AND role = 'student'").run(Number(p.id));
  if (!r.changes) throw new HttpError(404, 'Student not found.');
  return { ok: true };
});

// ---- Excel export ----
function buildReport(from, to, section, facultyName) {
  const students = studentsIn(section);
  const sessionRows = section === 'ALL'
    ? db.prepare('SELECT DISTINCT date, section FROM sessions WHERE date BETWEEN ? AND ? ORDER BY date').all(from, to)
    : db.prepare("SELECT DISTINCT date, section FROM sessions WHERE date BETWEEN ? AND ? AND (section = ? OR section = 'ALL') ORDER BY date").all(from, to, section);
  const dates = [...new Set(sessionRows.map((r) => r.date))].sort();
  const heldFor = new Map(); // date -> Set(section)
  for (const r of sessionRows) { if (!heldFor.has(r.date)) heldFor.set(r.date, new Set()); heldFor.get(r.date).add(r.section); }
  const att = db.prepare(`SELECT a.*, u.name, u.register_no, u.section AS u_section, c.name AS room
      FROM attendance a JOIN users u ON u.id = a.student_id JOIN sessions s ON s.id = a.session_id JOIN classrooms c ON c.id = s.classroom_id
      WHERE a.date BETWEEN ? AND ? ORDER BY a.date, a.marked_at`).all(from, to);
  const key = (sid, d) => sid + '|' + d;
  const attMap = new Map(att.map((a) => [key(a.student_id, a.date), a]));

  const fmtDate = (d) => { const [y, m, dd] = d.split('-'); return `${dd}-${['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][Number(m) - 1]}-${y.slice(2)}`; };
  const header = ['S.No', 'Register No', 'Name', 'Section', ...dates.map(fmtDate), 'Present', 'Working Days', 'Attendance %'];
  const lastCol = header.length - 1;
  const { colName } = require('./src/xlsx');
  const rows = [
    [{ v: 'Attendance Report - Multi-Factor Attendance System', s: S.title }],
    [null, { v: 'Section:', s: S.bold }, section === 'ALL' ? 'All sections' : section],
    [null, { v: 'Period:', s: S.bold }, `${fmtDate(from)} to ${fmtDate(to)}`],
    [null, { v: 'Generated:', s: S.bold }, `${localNow().date} ${localNow().time.slice(0, 5)} by ${facultyName}`],
    [null, { v: 'Legend:', s: S.bold }, 'P = Present,  A = Absent,  F = Flagged (pending review),  - = No session for that section'],
    [],
    header.map((h) => ({ v: h, s: S.header })),
  ];
  students.forEach((st, i) => {
    let present = 0, working = 0;
    const cells = dates.map((d) => {
      const held = heldFor.get(d);
      if (!held || !(held.has('ALL') || held.has(st.section))) return { v: '-', s: S.center };
      working++;
      const a = attMap.get(key(st.id, d));
      if (a && a.status === 'PRESENT') { present++; return { v: 'P', s: S.present }; }
      if (a && a.status === 'FLAGGED') return { v: 'F', s: S.flagged };
      return { v: 'A', s: S.absent };
    });
    rows.push([
      { v: i + 1, s: S.center }, { v: st.register_no, s: S.text }, { v: st.name, s: S.text }, { v: st.section, s: S.center },
      ...cells,
      { v: present, s: S.center }, { v: working, s: S.center }, { v: working ? Math.round((present / working) * 1000) / 10 : 0, s: S.number1 },
    ]);
  });
  if (!students.length) rows.push([{ v: 'No students found for this section.', s: S.text }]);

  const logRows = [
    [{ v: 'Attendance Log (every verified check-in)', s: S.title }],
    [],
    ['Date', 'Time', 'Register No', 'Name', 'Section', 'Status', 'Classroom', 'Distance (m)', 'GPS accuracy (m)', 'Wi-Fi BSSID', 'Face score', 'Note'].map((h) => ({ v: h, s: S.header })),
  ];
  for (const a of att) {
    if (section !== 'ALL' && a.u_section !== section) continue;
    const st = a.status === 'PRESENT' ? S.present : a.status === 'FLAGGED' ? S.flagged : S.absent;
    logRows.push([
      { v: fmtDate(a.date), s: S.center }, { v: localTime(a.marked_at), s: S.center }, { v: a.register_no, s: S.text }, { v: a.name, s: S.text },
      { v: a.u_section, s: S.center }, { v: a.status, s: st }, { v: a.room, s: S.text }, { v: a.distance_m, s: S.center },
      { v: a.accuracy_m, s: S.center }, { v: a.bssid, s: S.text }, { v: a.face_score, s: S.center }, { v: a.flag_reason || '', s: S.text },
    ]);
  }

  return buildXlsx([
    { name: 'Attendance', rows, cols: [6, 14, 26, 9, ...dates.map(() => 10), 9, 9, 12], freeze: { row: 7, col: 4 }, merges: [`A1:${colName(Math.max(lastCol, 8))}1`] },
    { name: 'Log', rows: logRows, cols: [11, 10, 14, 26, 9, 11, 16, 12, 14, 20, 10, 36], freeze: { row: 3, col: 0 }, merges: ['A1:L1'] },
  ]);
}

route('GET', '/api/faculty/export', 'faculty', (req, body, user, p, q, res) => {
  const today = windowStatus().today;
  const to = q.get('to') || today;
  const from = q.get('from') || addDays(to, -30);
  if (!isValidDate(from) || !isValidDate(to) || from > to) throw bad('Choose a valid date range.');
  if ((Date.parse(to) - Date.parse(from)) / 86400000 > 366) throw bad('Date range can be at most one year.');
  const section = scopeSection(user, q.get('section'));
  const buf = buildReport(from, to, section, user.name);
  const fileName = `Attendance_${section}_${from}_to_${to}.xlsx`;
  res.writeHead(200, {
    'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'Content-Disposition': `attachment; filename="${fileName}"`,
    'Content-Length': buf.length,
    'X-File-Name': fileName,
    'Cache-Control': 'no-store',
  });
  res.end(buf);
  return undefined; // response already sent
});

// ------------------------------------------------------------------ server
const server = http.createServer(async (req, res) => {
  const started = Date.now();
  const url = new URL(req.url, 'http://localhost');
  try {
    const r = routes.find((x) => x.method === req.method && x.regex.test(url.pathname));
    if (!r) throw new HttpError(404, 'Not found');
    const m = r.regex.exec(url.pathname);
    const params = {};
    r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
    const user = r.role ? authenticate(req, r.role === 'any' ? null : r.role) : null;
    const body = req.method === 'POST' || req.method === 'PUT' ? await readJson(req) : {};
    const result = await r.handler(req, body, user, params, url.searchParams, res);
    if (result !== undefined) sendJson(res, 200, result);
  } catch (e) {
    const status = e.status || 500;
    if (status === 500) console.error(e);
    if (!res.headersSent) sendJson(res, status, { error: status === 500 ? 'Server error. Please try again.' : e.message });
  } finally {
    console.log(`${new Date().toISOString()} ${req.method} ${url.pathname} ${res.statusCode} ${Date.now() - started}ms`);
  }
});

if (require.main === module) {
  server.listen(config.port, '0.0.0.0', () => {
    const w = windowStatus();
    console.log('\n  Smart Attendance API is running');
    console.log(`  Local:    http://localhost:${config.port}`);
    for (const list of Object.values(os.networkInterfaces())) {
      for (const n of list || []) if (n.family === 'IPv4' && !n.internal) console.log(`  Network:  http://${n.address}:${config.port}   <- use this in the app (same Wi-Fi)`);
    }
    console.log(`  Attendance window: ${w.start} - ${w.end} (${w.timeZone}). Server time now: ${w.today} ${w.serverTime}`);
    console.log(`  Faculty signup code: ${config.facultySignupCode}\n`);
  });
}

module.exports = server;
