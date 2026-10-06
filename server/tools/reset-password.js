// Reset a forgotten password (run on the server laptop).
//   node tools/reset-password.js 24CS0616 NewPass123          -> student (by register number)
//   node tools/reset-password.js faculty@cit.edu NewPass123   -> faculty (by email)
// The person then logs in with the new password and can keep using the app as before.
const db = require('../src/db');
const sec = require('../src/security');

const [id, password] = process.argv.slice(2);
if (!id || !password) {
  console.log('Usage: node tools/reset-password.js <register number or faculty email> <new password>');
  process.exit(1);
}
if (password.length < 6) { console.log('The new password must be at least 6 characters.'); process.exit(1); }
const loginId = id.trim();
const user = loginId.includes('@')
  ? db.prepare("SELECT * FROM users WHERE role = 'faculty' AND email = ?").get(loginId.toLowerCase())
  : db.prepare("SELECT * FROM users WHERE role = 'student' AND register_no = ?").get(loginId.toUpperCase());
if (!user) { console.log('No account found for ' + loginId); process.exit(1); }
db.prepare('UPDATE users SET password_hash = ? WHERE id = ?').run(sec.hashPassword(password), user.id);
console.log(`Done: password reset for ${user.name} (${user.role}, ${user.register_no || user.email}). They can log in with the new password now.`);
