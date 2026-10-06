# Smart Attendance – Backend API

Node.js REST API + SQLite database for the Multi-Factor Mobile Attendance Management System.
**No `npm install` needed** – it uses only features built into Node.js 22.13+ (HTTP server, SQLite, crypto).

## Run

```bash
cd server
cp .env.example .env        # Windows: copy .env.example .env
npm start
```

Health check: open `http://localhost:4000/api/health` in a browser.
Automated test (42 checks, uses temporary databases): `npm test`

## What the server enforces

| Rule | Where |
|---|---|
| Attendance only between `WINDOW_START` and `WINDOW_END` (default 07:20–08:05, Asia/Kolkata), using the **server clock** | `presenceChecks()` in `server.js` |
| Only **one** attendance per student per day | `UNIQUE(student_id, date)` in the `attendance` table + check before insert |
| Dynamic QR, HMAC‑SHA256 signed, new code every 15 s (current + 1 previous window accepted) | `src/security.js` → `makeQrToken / checkQrToken` |
| Wi‑Fi BSSID must be one of the classroom's registered access points | `presenceChecks()` step 4 |
| GPS inside classroom radius, accuracy limit, mock location rejected | `presenceChecks()` step 5 |
| Face embedding must match the enrolled template (cosine ≥ `FACE_MATCH_THRESHOLD`) | `/api/student/attendance` |
| Same phone used for a second student on the same day → **FLAGGED** for faculty review | `/api/student/attendance` step 7 |
| Face templates stored encrypted (AES‑256‑GCM), never photos | `encryptEmbedding()` |
| Passwords hashed with scrypt; login token (JWT HS256) valid 365 days | `src/security.js` |

## API

| Method & path | Who | Purpose |
|---|---|---|
| `GET /api/health` | anyone | server status + attendance window |
| `POST /api/auth/signup/student` | student | name, registerNo, department, section, password, faceEmbeddings[] |
| `POST /api/auth/signup/faculty` | faculty | name, email, department, password, facultyCode |
| `POST /api/auth/login` | both | role, loginId, password |
| `GET /api/me` | both | current user |
| `GET /api/student/dashboard` | student | today status, %, history |
| `POST /api/student/precheck` | student | QR + Wi‑Fi + GPS → 120 s check-in ticket |
| `POST /api/student/attendance` | student | ticket + Wi‑Fi + GPS + face → PRESENT / FLAGGED / REJECTED |
| `POST /api/student/face` | student | re-enrol face after faculty reset |
| `GET /api/faculty/dashboard` | faculty | window, today's sessions, counts |
| `GET/POST/PUT/DELETE /api/faculty/classrooms` | faculty | classroom geofence + Wi‑Fi APs |
| `POST /api/faculty/sessions` | faculty | start today's session (window must be open) |
| `GET /api/faculty/sessions/:id/qr` | faculty | current signed QR token |
| `GET /api/faculty/sessions/:id/live` | faculty | students checked in |
| `POST /api/faculty/sessions/:id/end` | faculty | end session |
| `GET /api/faculty/attendance?date=&section=` | faculty | present / absent / flagged lists |
| `GET /api/faculty/flags`, `POST /api/faculty/flags/:id` | faculty | review flagged check-ins |
| `GET /api/faculty/students?section=` | faculty | students with attendance % |
| `POST /api/faculty/students/:id/reset-face` | faculty | force face re-enrolment |
| `GET /api/faculty/export?from=&to=&section=` | faculty | **Excel (.xlsx) download** |

## Files

```
server/
├── server.js          routes + attendance rules + Excel report
├── src/config.js      reads .env, creates secrets on first run
├── src/db.js          SQLite schema (data/attendance.db)
├── src/security.js    passwords, JWT, dynamic QR, ticket, face encryption/matching, distance
├── src/time.js        server-clock time window (time zone aware)
├── src/xlsx.js        dependency-free Excel writer
└── test/e2e.js        end-to-end test of every rule
```

Back up `data/` (database + secrets). Deleting `data/secrets.json` logs everyone out and makes stored face templates unreadable.
