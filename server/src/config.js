// Loads settings from .env (if present) and creates persistent secrets on first run.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const ROOT = path.join(__dirname, '..');
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, 'data');

try {
  if (fs.existsSync(path.join(ROOT, '.env'))) process.loadEnvFile(path.join(ROOT, '.env'));
} catch (e) {
  console.warn('Could not read .env:', e.message);
}

fs.mkdirSync(DATA_DIR, { recursive: true });

// Secrets: use values from .env, otherwise generate once and keep them in data/secrets.json
const secretsFile = path.join(DATA_DIR, 'secrets.json');
let saved = {};
if (fs.existsSync(secretsFile)) saved = JSON.parse(fs.readFileSync(secretsFile, 'utf8'));
function secret(name, bytes) {
  if (process.env[name]) return process.env[name];
  if (!saved[name]) {
    saved[name] = crypto.randomBytes(bytes).toString('hex');
    fs.writeFileSync(secretsFile, JSON.stringify(saved, null, 2));
  }
  return saved[name];
}

function hhmmToSeconds(s, fallback) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(s || '').trim());
  if (!m) return fallback;
  return Number(m[1]) * 3600 + Number(m[2]) * 60;
}

const config = {
  port: Number(process.env.PORT || 4000),
  dataDir: DATA_DIR,
  dbFile: path.join(DATA_DIR, 'attendance.db'),
  facultySignupCode: process.env.FACULTY_SIGNUP_CODE || 'CIT-FACULTY-2026',
  timeZone: process.env.TIME_ZONE || 'Asia/Kolkata',
  windowStartText: process.env.WINDOW_START || '07:20',
  windowEndText: process.env.WINDOW_END || '08:05',
  faceThreshold: Number(process.env.FACE_MATCH_THRESHOLD || 0.6),
  qrWindowSeconds: Number(process.env.QR_WINDOW_SECONDS || 15),
  jwtSecret: secret('JWT_SECRET', 32),
  qrSecret: secret('QR_SECRET', 32),
  faceKey: Buffer.from(secret('FACE_KEY', 32), 'hex').subarray(0, 32),
  tokenDays: 365,
};
config.windowStart = hhmmToSeconds(config.windowStartText, 7 * 3600 + 20 * 60);
config.windowEnd = hhmmToSeconds(config.windowEndText, 8 * 3600 + 5 * 60);
if (config.faceKey.length !== 32) config.faceKey = crypto.createHash('sha256').update(String(process.env.FACE_KEY)).digest();

module.exports = config;
