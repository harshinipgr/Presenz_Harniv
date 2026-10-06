// SQLite database (built into Node.js 22.13+). The whole database is one file: data/attendance.db
const { DatabaseSync } = require('node:sqlite');
const config = require('./config');

const db = new DatabaseSync(config.dbFile);
db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');

db.exec(`
CREATE TABLE IF NOT EXISTS users (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  role            TEXT NOT NULL CHECK (role IN ('student','faculty')),
  name            TEXT NOT NULL,
  register_no     TEXT UNIQUE,          -- students log in with this
  email           TEXT UNIQUE,          -- faculty log in with this
  department      TEXT NOT NULL DEFAULT '',
  section         TEXT NOT NULL DEFAULT '',
  password_hash   TEXT NOT NULL,
  face_enc        TEXT,                 -- AES-256-GCM encrypted face embedding (never a photo)
  face_enrolled_at TEXT,
  device_hash     TEXT,
  created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS classrooms (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  name            TEXT NOT NULL,
  lat             REAL NOT NULL,
  lng             REAL NOT NULL,
  radius_m        REAL NOT NULL DEFAULT 40,
  max_accuracy_m  REAL NOT NULL DEFAULT 50,
  bssids          TEXT NOT NULL DEFAULT '[]',   -- JSON array of allowed Wi-Fi access point BSSIDs
  created_by      INTEGER REFERENCES users(id),
  created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS sessions (
  id              TEXT PRIMARY KEY,
  date            TEXT NOT NULL,
  classroom_id    INTEGER NOT NULL REFERENCES classrooms(id),
  section         TEXT NOT NULL,
  faculty_id      INTEGER NOT NULL REFERENCES users(id),
  started_at      TEXT NOT NULL,
  ended_at        TEXT
);

-- One row per student per day (UNIQUE) => only one attendance per day
CREATE TABLE IF NOT EXISTS attendance (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  student_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  date            TEXT NOT NULL,
  session_id      TEXT NOT NULL REFERENCES sessions(id),
  marked_at       TEXT NOT NULL,
  status          TEXT NOT NULL CHECK (status IN ('PRESENT','FLAGGED','REJECTED')),
  device_hash     TEXT,
  bssid           TEXT,
  ssid            TEXT,
  lat             REAL,
  lng             REAL,
  distance_m      REAL,
  accuracy_m      REAL,
  face_score      REAL,
  flag_reason     TEXT,
  other_student_id INTEGER,
  reviewed_by     INTEGER,
  reviewed_at     TEXT,
  UNIQUE (student_id, date)
);

-- Audit log of every attempt (accepted or rejected)
CREATE TABLE IF NOT EXISTS attempts (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  student_id      INTEGER,
  session_id      TEXT,
  at              TEXT NOT NULL,
  result          TEXT NOT NULL,
  reason          TEXT,
  device_hash     TEXT
);

CREATE INDEX IF NOT EXISTS idx_att_date ON attendance(date);
CREATE INDEX IF NOT EXISTS idx_att_device ON attendance(date, device_hash);
CREATE INDEX IF NOT EXISTS idx_sessions_date ON sessions(date);
`);

module.exports = db;
