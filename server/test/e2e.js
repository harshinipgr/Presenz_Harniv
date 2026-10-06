// End-to-end test: starts two servers (window open / window closed) in temp folders and exercises every API.
// Run: npm test
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert');

function hhmm(d) { return d.toTimeString().slice(0, 5); }
function tzNowParts(tz) {
  const f = new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  const [h, m] = f.format(new Date()).split(':').map(Number);
  return h * 60 + m;
}
const pad = (n) => String(n).padStart(2, '0');
const toHHMM = (min) => `${pad(Math.floor(((min + 1440) % 1440) / 60))}:${pad(((min + 1440) % 1440) % 60)}`;

function startServer(port, env) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'att-'));
  const p = spawn(process.execPath, ['--no-warnings=ExperimentalWarning', path.join(__dirname, '..', 'server.js')], {
    env: { ...process.env, PORT: String(port), DATA_DIR: dir, FACULTY_SIGNUP_CODE: 'TEST-CODE', ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  p.stderr.on('data', (d) => process.stderr.write('[server] ' + d));
  return new Promise((resolve) => p.stdout.on('data', (d) => { if (String(d).includes('running')) resolve(p); }));
}

function api(port) {
  return async (method, url, body, token) => {
    const res = await fetch(`http://127.0.0.1:${port}${url}`, {
      method,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    const ct = res.headers.get('content-type') || '';
    return { status: res.status, data: ct.includes('json') ? await res.json() : Buffer.from(await res.arrayBuffer()), headers: res.headers };
  };
}

function randomEmbedding(seed) {
  let x = seed;
  return Array.from({ length: 192 }, () => { x = (x * 9301 + 49297) % 233280; return x / 233280 - 0.5; });
}
function noisy(v, amt, seed) {
  const n = randomEmbedding(seed);
  return v.map((x, i) => x + n[i] * amt);
}

let passed = 0;
function ok(cond, msg) { assert.ok(cond, msg); passed++; console.log('  ✓ ' + msg); }

(async () => {
  const tz = 'Asia/Kolkata';
  const nowMin = tzNowParts(tz);
  const openEnv = { TIME_ZONE: tz, WINDOW_START: toHHMM(nowMin - 5), WINDOW_END: toHHMM(nowMin + 30) };
  const closedEnv = { TIME_ZONE: tz, WINDOW_START: toHHMM(nowMin + 60), WINDOW_END: toHHMM(nowMin + 90) };
  if (nowMin < 10 || nowMin > 1300) { console.log('Run the test between 00:10 and 21:40 local time.'); process.exit(0); }

  const s1 = await startServer(4101, openEnv);
  const s2 = await startServer(4102, closedEnv);
  const A = api(4101), C = api(4102);
  try {
    console.log('Window OPEN server');
    let r = await A('GET', '/api/health');
    ok(r.status === 200 && r.data.window.open === true, 'health reports window open');

    r = await A('POST', '/api/auth/signup/faculty', { name: 'Dr Priya', email: 'priya@cit.edu', department: 'CSE', password: 'secret1', facultyCode: 'WRONG' });
    ok(r.status === 403, 'faculty signup rejected with wrong code');
    r = await A('POST', '/api/auth/signup/faculty', { name: 'Dr Priya', email: 'priya@cit.edu', department: 'CSE', password: 'secret1', facultyCode: 'TEST-CODE', deviceHash: 'fac-dev' });
    ok(r.status === 200 && r.data.token, 'faculty signup ok');
    const F = r.data.token;

    const e1 = randomEmbedding(1), e2 = randomEmbedding(2), e3 = randomEmbedding(3);
    r = await A('POST', '/api/auth/signup/student', { name: 'Nivya', registerNo: '24cs0616', department: 'cse', section: 'l', password: 'pass12', faceEmbeddings: [noisy(e1, 0.05, 11), noisy(e1, 0.05, 12), e1], deviceHash: 'dev-1' });
    ok(r.status === 200 && r.data.user.registerNo === '24CS0616' && r.data.user.section === 'L', 'student 1 signup ok (normalised upper case)');
    const T1 = r.data.token;
    r = await A('POST', '/api/auth/signup/student', { name: 'Harshini', registerNo: '24CS0697', department: 'CSE', section: 'L', password: 'pass12', faceEmbeddings: [e2], deviceHash: 'dev-2' });
    const T2 = r.data.token;
    r = await A('POST', '/api/auth/signup/student', { name: 'Other', registerNo: '24CS0700', department: 'CSE', section: 'M', password: 'pass12', faceEmbeddings: [e3], deviceHash: 'dev-3' });
    const T3 = r.data.token;
    r = await A('POST', '/api/auth/signup/student', { name: 'Dup', registerNo: '24CS0616', department: 'CSE', section: 'L', password: 'pass12', faceEmbeddings: [e3] });
    ok(r.status === 409, 'duplicate register number rejected');
    r = await A('POST', '/api/auth/login', { role: 'student', loginId: '24cs0616', password: 'bad' });
    ok(r.status === 401, 'wrong password rejected');
    r = await A('POST', '/api/auth/login', { role: 'student', loginId: '24cs0616', password: 'pass12', deviceHash: 'dev-1' });
    ok(r.status === 200 && r.data.user.role === 'student', 'student login ok');
    r = await A('GET', '/api/faculty/dashboard', null, T1);
    ok(r.status === 403, 'student cannot call faculty API');

    // Class-wise faculty: a faculty of section M sees only section M students
    r = await A('POST', '/api/auth/signup/faculty', { name: 'Dr M', email: 'm@cit.edu', department: 'CSE', section: 'm', password: 'secret1', facultyCode: 'TEST-CODE' });
    const FM = r.data.token;
    ok(r.status === 200 && r.data.user.section === 'M', 'faculty signup with section M');
    r = await A('GET', '/api/faculty/dashboard', null, FM);
    ok(r.data.counts.students === 1, 'section-M faculty dashboard counts only 1 student (not 3)');
    r = await A('GET', '/api/faculty/students?section=ALL', null, FM);
    ok(r.data.students.length === 1 && r.data.students[0].section === 'M', 'section-M faculty student list shows only section M');
    r = await A('GET', '/api/faculty/sections', null, FM);
    ok(r.data.sections.length === 1 && r.data.sections[0] === 'M', 'section-M faculty sees only its own section');
    r = await A('GET', '/api/faculty/dashboard', null, FM);
    ok(/Section M/.test(r.data.user.department), 'dashboard header shows the faculty class: ' + r.data.user.department);
    r = await A('GET', '/api/faculty/dashboard', null, F);
    ok(r.data.counts.students === 3, 'faculty without a section still sees all 3 students');

    r = await A('POST', '/api/faculty/classrooms', { name: 'Room 301', lat: 13.0, lng: 80.0, radiusM: 40, maxAccuracyM: 50, bssids: ['AA:BB:CC:DD:EE:FF', '02:00:00:00:00:00', 'junk'] }, F);
    ok(r.status === 200 && r.data.classroom.bssids.length === 1 && r.data.classroom.bssids[0] === 'aa:bb:cc:dd:ee:ff', 'classroom created, BSSIDs cleaned');
    const room = r.data.classroom;

    r = await A('POST', '/api/faculty/sessions', { classroomId: room.id, section: 'l' }, F);
    ok(r.status === 200 && r.data.session.active && r.data.session.section === 'L', 'session started for section L');
    const sess = r.data.session;
    r = await A('POST', '/api/faculty/sessions', { classroomId: room.id, section: 'L' }, F);
    ok(r.data.resumed === true && r.data.session.id === sess.id, 'starting again resumes same session');

    r = await A('GET', `/api/faculty/sessions/${sess.id}/qr`, null, F);
    ok(r.status === 200 && r.data.token.startsWith('ATT1.') && r.data.secondsLeft <= 15, 'QR token issued');
    const qr = r.data.token;

    // Full student flow: precheck (QR + Wi-Fi + GPS) -> ticket -> final submit (face + device)
    const mark = async (payload, token) => {
      const fresh = payload.qrToken === qr ? (await A('GET', `/api/faculty/sessions/${sess.id}/qr`, null, F)).data.token : payload.qrToken;
      const pre = await A('POST', '/api/student/precheck', { ...payload, qrToken: fresh }, token);
      if (pre.data.status !== 'OK') return pre;
      const { qrToken, ...rest } = payload;
      return A('POST', '/api/student/attendance', { ...rest, ticket: pre.data.ticket }, token);
    };
    const good = { qrToken: qr, bssid: 'AA:BB:CC:DD:EE:FF', ssid: 'CIT', lat: 13.0001, lng: 80.0001, accuracy: 12, isMock: false, liveness: { passed: true }, faceEmbedding: noisy(e1, 0.08, 99), deviceHash: 'dev-1' };
    r = await mark({ ...good, bssid: '11:22:33:44:55:66' }, T1);
    ok(r.data.status === 'REJECTED' && /Wi-Fi/.test(r.data.reason), 'wrong Wi-Fi rejected');
    r = await mark({ ...good, lat: 13.01 }, T1);
    ok(r.data.status === 'REJECTED' && /away/.test(r.data.reason), 'outside geofence rejected: ' + r.data.reason);
    r = await mark({ ...good, isMock: true }, T1);
    ok(r.data.status === 'REJECTED' && /Mock/.test(r.data.reason), 'mock location rejected');
    r = await mark({ ...good, accuracy: 300 }, T1);
    ok(r.data.status === 'REJECTED' && /accurate/.test(r.data.reason), 'poor GPS accuracy rejected');
    r = await mark({ ...good, liveness: { passed: false } }, T1);
    ok(r.data.status === 'REJECTED' && /Liveness/.test(r.data.reason), 'liveness failure rejected');
    r = await mark({ ...good, faceEmbedding: e2 }, T1);
    ok(r.data.status === 'REJECTED' && /Face did not match/.test(r.data.reason), 'different face rejected: ' + r.data.reason);
    const [, sid, w, sig] = qr.split('.');
    r = await mark({ ...good, qrToken: `ATT1.${sid}.${Number(w) - 2}.${sig}` }, T1);
    ok(r.data.status === 'REJECTED' && /expired/.test(r.data.reason), 'expired QR rejected');
    r = await mark({ ...good, qrToken: `ATT1.${sid}.${w}.forged` }, T1);
    ok(r.data.status === 'REJECTED' && /Invalid QR/.test(r.data.reason), 'forged QR rejected');
    r = await A('POST', '/api/student/attendance', { ...good, ticket: 'x.1.y' }, T1);
    ok(r.data.status === 'REJECTED' && /Invalid check-in/.test(r.data.reason), 'forged ticket rejected');
    const preOther = await A('POST', '/api/student/precheck', { ...good, qrToken: (await A('GET', `/api/faculty/sessions/${sess.id}/qr`, null, F)).data.token }, T2);
    ok(preOther.data.status === 'OK' && preOther.data.classroom === 'Room 301', 'precheck OK returns ticket + classroom');
    r = await A('POST', '/api/student/attendance', { ...good, ticket: preOther.data.ticket }, T1);
    ok(r.data.status === 'REJECTED' && /Invalid check-in/.test(r.data.reason), 'ticket of another student rejected');
    r = await mark(good, T1);
    ok(r.data.status === 'PRESENT' && r.data.checks.face && r.data.checks.device, 'valid attempt -> PRESENT (face score ' + r.data.faceScore + ')');
    r = await mark(good, T1);
    ok(r.data.status === 'REJECTED' && /already recorded/.test(r.data.reason), 'second attempt same day rejected');
    r = await mark({ ...good, faceEmbedding: e2, deviceHash: 'dev-1' }, T2);
    ok(r.data.status === 'FLAGGED', 'same phone used for second student -> FLAGGED');
    r = await mark({ ...good, faceEmbedding: e3, deviceHash: 'dev-3' }, T3);
    ok(r.data.status === 'REJECTED' && /section/.test(r.data.reason), 'student of another section rejected');

    r = await A('GET', `/api/faculty/sessions/${sess.id}/live`, null, F);
    ok(r.data.records.length === 2 && r.data.session.present === 1 && r.data.session.flagged === 1, 'live list shows 1 present + 1 flagged');
    r = await A('GET', '/api/faculty/flags', null, F);
    ok(r.data.flags.length === 1 && r.data.flags[0].otherRegisterNo === '24CS0616', 'flag lists the other account');
    const flagId = r.data.flags[0].attendanceId;
    r = await A('POST', `/api/faculty/flags/${flagId}`, { decision: 'approve' }, F);
    ok(r.data.status === 'PRESENT', 'faculty approved flag');
    r = await A('GET', '/api/faculty/attendance?section=L', null, F);
    ok(r.data.present.length === 2 && r.data.absent.length === 0, 'records for section L: 2 present');
    r = await A('GET', '/api/faculty/attendance?section=ALL', null, F);
    ok(r.data.present.length === 2 && r.data.absent.length === 0 && r.data.rejected.length === 0, 'section M not counted absent (no session for M)');
    r = await A('GET', '/api/faculty/students', null, F);
    ok(r.data.students.length === 3 && r.data.students.find((s) => s.registerNo === '24CS0616').percent === 100, 'students list with 100%');
    r = await A('GET', '/api/student/dashboard', null, T1);
    ok(r.data.today.marked && r.data.stats.present === 1 && r.data.stats.workingDays === 1 && r.data.history.length === 1, 'student dashboard shows today marked');
    r = await A('GET', '/api/faculty/dashboard', null, F);
    ok(r.data.counts.presentToday === 2 && r.data.sessions.length === 1, 'faculty dashboard counts');

    r = await A('GET', '/api/faculty/export?section=ALL', null, F);
    ok(r.status === 200 && r.data.length > 1000 && r.data[0] === 0x50 && r.data[1] === 0x4b, 'xlsx export downloaded (' + r.data.length + ' bytes)');
    const out = path.join(__dirname, 'sample_export.xlsx');
    fs.writeFileSync(out, r.data);

    const otherId = (await A('GET', '/api/faculty/students', null, F)).data.students.find((s) => s.registerNo === '24CS0700').id;
    r = await A('POST', `/api/faculty/students/${otherId}/reset-face`, null, F);
    ok(r.data.ok, 'faculty reset a student face');
    r = await A('POST', '/api/student/face', { faceEmbeddings: [e3] }, T3);
    ok(r.data.ok && r.data.user.faceEnrolled, 'student re-enrolled face');

    r = await A('POST', `/api/faculty/sessions/${sess.id}/end`, null, F);
    ok(r.data.session.active === false, 'session ended');
    r = await A('GET', `/api/faculty/sessions/${sess.id}/qr`, null, F);
    ok(r.status === 409, 'no QR after session ended');

    console.log('Window CLOSED server');
    r = await C('POST', '/api/auth/signup/faculty', { name: 'Dr X', email: 'x@cit.edu', department: 'CSE', password: 'secret1', facultyCode: 'TEST-CODE' });
    const F2 = r.data.token;
    r = await C('POST', '/api/faculty/classrooms', { name: 'Lab', lat: 13, lng: 80, bssids: ['aa:bb:cc:dd:ee:ff'] }, F2);
    r = await C('POST', '/api/faculty/sessions', { classroomId: r.data.classroom.id, section: 'L' }, F2);
    ok(r.status === 409 && /only between/.test(r.data.error), 'cannot start session outside window');
    r = await C('POST', '/api/auth/signup/student', { name: 'Late', registerNo: '24CS0999', department: 'CSE', section: 'L', password: 'pass12', faceEmbeddings: [e1] });
    r = await C('POST', '/api/student/attendance', good, r.data.token);
    ok(r.data.status === 'REJECTED' && /only between/.test(r.data.reason), 'cannot mark attendance outside window');

    console.log(`\nAll ${passed} checks passed. Sample export: ${out}`);
  } finally {
    s1.kill(); s2.kill();
  }
})().catch((e) => { console.error(e); process.exit(1); });
