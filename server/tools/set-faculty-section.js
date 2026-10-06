// Assign (or change) the class/section of an existing faculty account.
//   node tools/set-faculty-section.js                       -> list all faculty accounts
//   node tools/set-faculty-section.js faculty@cit.edu L     -> faculty@cit.edu now sees only section L
//   node tools/set-faculty-section.js faculty@cit.edu ALL   -> sees all sections again
const db = require('../src/db');

const [email, section] = process.argv.slice(2);
if (!email) {
  const rows = db.prepare("SELECT name, email, department, section FROM users WHERE role = 'faculty' ORDER BY name").all();
  if (!rows.length) console.log('No faculty accounts yet.');
  for (const r of rows) console.log(`${r.email.padEnd(32)} ${r.name.padEnd(24)} ${r.department.padEnd(8)} section: ${r.section || 'ALL (not set)'}`);
  console.log('\nUsage: node tools/set-faculty-section.js <faculty email> <SECTION or ALL>');
  process.exit(0);
}
if (!section) { console.log('Give a section, e.g.: node tools/set-faculty-section.js ' + email + ' L'); process.exit(1); }
const value = section.trim().toUpperCase() === 'ALL' ? '' : section.trim().toUpperCase();
const r = db.prepare("UPDATE users SET section = ? WHERE role = 'faculty' AND email = ?").run(value, email.trim().toLowerCase());
if (!r.changes) { console.log('No faculty account found with email ' + email); process.exit(1); }
console.log(`Done: ${email} -> section ${value || 'ALL'}. Pull down to refresh the faculty dashboard.`);
