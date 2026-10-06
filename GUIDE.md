# Smart Attendance – Complete Build Guide

Multi-Factor Mobile Attendance Management System for Proxy Prevention
(Android app + Node.js backend + SQLite database)

---

## 0. What you are building

```
 Student phone (blue app)                 Faculty phone (green app)
 ┌─────────────────────────┐              ┌──────────────────────────┐
 │ Wi-Fi BSSID  ─┐         │              │ Start session            │
 │ GPS + mock    ├─ evidence│              │ Live QR (new every 15 s) │
 │ Scan QR       │         │              │ Live check-in list       │
 │ Face+liveness ┘         │              │ Records / Flags / Export │
 └──────────┬──────────────┘              └────────────┬─────────────┘
            │  HTTPS / HTTP (JSON REST API)             │
            ▼                                           ▼
     ┌──────────────────────────────────────────────────────┐
     │ Node.js server (server.js)                           │
     │  • time window 07:20–08:05 on SERVER clock           │
     │  • one attendance per student per day                │
     │  • HMAC-signed QR, Wi-Fi, geofence, face match       │
     │  • same-phone-two-students → FLAGGED                 │
     │  • Excel (.xlsx) export                              │
     │ SQLite database (data/attendance.db)                 │
     └──────────────────────────────────────────────────────┘
```

Every check is real. The phone collects the evidence; the **server** decides. A modified app can't write attendance directly.

| Feature from the report | How it works in this build |
|---|---|
| Campus Wi-Fi verification | App reads the connected access point's BSSID; server checks it is in the classroom's list |
| GPS geofence | Fresh high-accuracy fix; mock location rejected; server checks distance ≤ radius and accuracy ≤ limit |
| Dynamic QR (15 s) | Server signs `sessionId + classroomId + 15-second window` with HMAC-SHA256; faculty phone fetches a new one every 15 s |
| Face recognition + liveness | ML Kit face detection (blink + head turn in random order) + MobileFaceNet (TensorFlow Lite) 192-number face embedding; server compares with the enrolled template (encrypted, never a photo) |
| Single-device monitoring | A phone already used by another student today → attendance is **FLAGGED** for faculty review |
| Faculty dashboard | Live QR, live list, records by date, students %, flagged review, Excel export |
| Time rule | Sessions can be started and attendance marked **only 07:20–08:05** (server time, Asia/Kolkata) |
| One per day | Database constraint `UNIQUE(student_id, date)` |
| One-time login | Login token saved on the phone (valid 365 days); app opens straight to the right dashboard |
| Themes | Student = **blue**, Faculty = **green**, Welcome = navy |

---

## 1. Install the tools (once)

| Tool | Version | Why |
|---|---|---|
| **Node.js** | 22.13 or newer (the current LTS is fine) – nodejs.org | Runs the backend. Check with `node -v` |
| **Android Studio** | Latest stable – developer.android.com/studio | Builds the Android app. It includes the JDK and Android SDK |
| A text editor | VS Code / Notepad++ | Editing `.env` |
| (Optional) **cloudflared** | Latest – developers.cloudflare.com | Gives the server a public HTTPS link so phones can reach it from anywhere |

During the Android Studio setup wizard choose **Standard** so it downloads the SDK, platform tools and an emulator.

---

## 2. Project structure

Unzip `SmartAttendance.zip`. You get:

```
SmartAttendance/
├── GUIDE.md                  ← this guide
├── server/                   ← BACKEND (Node.js)
│   ├── server.js             routes, attendance rules, Excel report
│   ├── src/config.js         settings from .env, auto-generated secrets
│   ├── src/db.js             SQLite tables
│   ├── src/security.js       passwords, login tokens, QR signing, face encryption & matching
│   ├── src/time.js           07:20–08:05 window on the server clock
│   ├── src/xlsx.js           Excel file writer
│   ├── test/e2e.js           automated test (42 checks)
│   ├── .env.example          settings template
│   └── package.json
└── android/                  ← FRONTEND (Android Studio project, Kotlin)
    ├── settings.gradle.kts, build.gradle.kts, gradle.properties
    └── app/
        ├── build.gradle.kts              dependencies
        └── src/main/
            ├── AndroidManifest.xml       permissions, screens, themes per screen
            ├── assets/MobileFaceNet.tflite   face recognition model (MIT licence)
            ├── java/com/cit/smartattendance/
            │   ├── Core.kt               App, saved login (Prefs), REST client (Api), device ID
            │   ├── BaseActivity.kt       common screen helpers, list rows, dialogs
            │   ├── AuthActivities.kt     Splash, Welcome, Login, Student/Faculty sign-up
            │   ├── Verification.kt       Wi-Fi BSSID reader, GPS + mock detection
            │   ├── CameraActivities.kt   CameraX, face liveness + embedding, QR scanner
            │   ├── StudentActivities.kt  Student dashboard, guided Mark Attendance
            │   └── FacultyActivities.kt  Faculty dashboard, live QR session, classrooms,
            │                             records, students, flags, Excel export
            └── res/                      layouts, colours, themes, icons
```

---

## 3. Backend – set up and run

### 3.1 Configure

```bash
cd SmartAttendance/server
copy .env.example .env      # Windows
# cp .env.example .env      # macOS / Linux
```

Open `.env` and set at least:

```ini
FACULTY_SIGNUP_CODE=CIT-FACULTY-2026     # change this; faculty need it to sign up
TIME_ZONE=Asia/Kolkata
WINDOW_START=07:20
WINDOW_END=08:05
```

Secrets (`JWT_SECRET`, `QR_SECRET`, `FACE_KEY`) can stay empty – they are generated once and saved in `data/secrets.json`.

### 3.2 Run the automated test (optional but recommended)

```bash
npm test
```

You should see `All 42 checks passed.` It checks wrong Wi-Fi, outside the geofence, fake GPS, poor accuracy, failed liveness, a different face, expired and forged QR, a second attempt on the same day, the same phone for two students (flagged), the wrong section, the Excel export, and that nothing works outside the window.

### 3.3 Start the server

```bash
npm start
```

Output:

```
  Smart Attendance API is running
  Local:    http://localhost:4000
  Network:  http://192.168.1.23:4000   <- use this in the app (same Wi-Fi)
  Attendance window: 07:20 - 08:05 (Asia/Kolkata). Server time now: 2026-09-30 07:12:05
  Faculty signup code: CIT-FACULTY-2026
```

Open `http://localhost:4000/api/health` in a browser – you should see `{"ok":true,...}`.
Windows may ask about the firewall – click **Allow** (Private networks).

Keep this window open while the app is in use. The database is the single file `server/data/attendance.db`.

### 3.4 Make the server reachable from phones

Pick one:

**Option A – Same Wi-Fi (simplest for testing).** Laptop and phones on the same Wi-Fi. In the app, use the `Network:` address, e.g. `http://192.168.1.23:4000`.
*Campus Wi-Fi often blocks phone-to-laptop traffic ("client isolation"). If the app says "Cannot reach the server", use option B.*

**Option B – Public HTTPS link with Cloudflare Tunnel (works on any network, free, no account).** In a second terminal:

```bash
cloudflared tunnel --url http://localhost:4000
```

It prints a link like `https://blue-river-1234.trycloudflare.com`. Use that link in the app. The link changes each time you restart cloudflared; update it on the Welcome screen of each phone when it changes. (A named Cloudflare tunnel or an ngrok free static domain gives a permanent link.)

**Emulator:** the emulator reaches your laptop at `http://10.0.2.2:4000`.

**Changing the address later:** logged-in phones skip the Welcome screen, so use the dashboard menu **⋮ › Server address** (for example when the tunnel link changes).

### 3.5 Practising outside 07:20–08:05 (screenshots, demo, viva)

The app enforces the window for real, so outside it the Start / Mark buttons are disabled. For a rehearsal only, change `.env` (for example `WINDOW_START=14:00`, `WINDOW_END=14:45`), restart `npm start`, and **change it back to 07:20 / 08:05 afterwards**. The one-attendance-per-day rule still applies. For a fresh demo you can stop the server and delete `server/data/attendance.db`. This removes all data, so do it only while testing.

---

## 4. Android app – open, understand, build

### 4.1 Open the project

1. Android Studio → **File › Open** → select the `SmartAttendance/android` folder → **Trust Project**.
2. Wait for **Gradle sync** (first time: 5–15 minutes, it downloads the libraries).
3. If Studio offers an **Android Gradle Plugin upgrade**, you can click *Don't remind me* (the project is pinned to AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.9, which is known to work together).
4. If sync reports a problem with the Gradle wrapper: create any new *Empty Views Activity* project in Studio, copy its `gradle/wrapper/` folder and `gradlew`, `gradlew.bat` into `SmartAttendance/android/`, then **File › Sync Project with Gradle Files**.
5. **Build › Make Project** → it should end with *BUILD SUCCESSFUL*. If you see a red error, copy the message from the Build panel and send it to me.

### 4.2 How the project was created from scratch (for your report)

1. **New Project › Empty Views Activity**
   * Name: `SmartAttendance` · Package: `com.cit.smartattendance`
   * Language: **Kotlin** · Minimum SDK: **API 26 (Android 8.0)** · Build config: **Kotlin DSL**
2. Added dependencies in `app/build.gradle.kts`: Material 3, CameraX, ML Kit barcode scanning, ML Kit face detection, TensorFlow Lite, Play Services Location, OkHttp, ZXing, Coroutines.
3. Added permissions in `AndroidManifest.xml`: Internet, network/Wi-Fi state, fine/coarse location, camera.
4. Put `MobileFaceNet.tflite` in `app/src/main/assets/` (right-click `main` › New › Folder › Assets Folder).
5. Created the themes (`res/values/themes.xml`): `Theme.SmartAttendance.Student` (blue #1E5AA8) and `Theme.SmartAttendance.Faculty` (green #0F7B5F), assigned per screen in the manifest.
6. Created the layouts in `res/layout/` and the Kotlin files listed in section 2.

### 4.3 Screen flow

```
Splash ──(already logged in?)──► Student Dashboard  or  Faculty Dashboard
   │ no
   ▼
Welcome (server address, Student / Faculty)
   ├─► Login (blue) ──► Student sign-up ──► Face enrolment (blink + turn, 3 samples)
   └─► Login (green) ─► Faculty sign-up (needs faculty code)

Student Dashboard ─► Mark Attendance: Wi-Fi ✓ → GPS ✓ → Scan QR ✓ (server pre-check) → Face + liveness ✓ → PRESENT / FLAGGED / REJECTED
Faculty Dashboard ─► Start session (classroom + section) → Live QR + live list → End
                  ├─► Classrooms (use current GPS + add current Wi-Fi)
                  ├─► Attendance records (date, section, Present / Absent / Review)
                  ├─► Students (% per student, reset face)
                  ├─► Flagged (approve / reject)
                  └─► Export Excel (from–to, section) → saved in Downloads → Open / Share
```

---

## 5. Run on the emulator (for screenshots)

### 5.1 Create the virtual device

1. **Tools › Device Manager › + › Create Virtual Device**.
2. Phone: **Pixel 7** → Next.
3. System image: **API 34** with **Google Play** (needed for location services) → Download → Next.
4. **Show Advanced Settings** → Camera: **Front = Webcam0**, **Back = Webcam0** (uses your laptop camera for the face and QR steps) → Finish.

### 5.2 Run

1. Start the server (`npm start`).
2. Select the Pixel 7 device and press **Run ▶**.
3. On the Welcome screen enter `http://10.0.2.2:4000` → **Save & test** → it shows *Connected*.

### 5.3 Give the emulator a location

Emulator toolbar **⋯ (Extended controls) › Location** → search your college or drop a pin → **Set location**. Use the same point when the faculty sets up the classroom and when the student marks attendance.

### 5.4 Wi-Fi in the emulator

The emulator is connected to a virtual Wi-Fi called `AndroidWifi`. When the faculty (on the emulator) taps **Add the Wi-Fi I am connected to**, that access point is registered, so students on the emulator pass the Wi-Fi check. Real phones need the real classroom access point added from a real phone inside the classroom.

### 5.5 Demo order for a full set of screenshots

To scan a QR you need two screens. Easiest: **Faculty = emulator** (QR shown on the laptop screen), **Student = your real phone** (or the other way round: faculty on a phone, student on the emulator pointing the webcam at the phone).

| # | Screen | How to get there |
|---|---|---|
| 1 | Welcome | first launch |
| 2 | Faculty sign-up (green) | I am Faculty › Create an account |
| 3 | Faculty dashboard | after sign-up |
| 4 | Classroom setup | Classrooms › Add classroom › Use my current location › Add the Wi-Fi I am connected to › Save |
| 5 | Student sign-up (blue) | on the student device |
| 6 | Face enrolment (liveness) | Capture my face |
| 7 | Student dashboard | after sign-up |
| 8 | Live QR session | Faculty › Start attendance session |
| 9 | Mark attendance – steps | Student › Mark attendance › Start verification |
| 10 | Attendance marked (success) | after the face step |
| 11 | Live list with the student | faculty session screen |
| 12 | Rejected example | e.g. turn Wi-Fi off, or try again (one per day) |
| 13 | Records / Students / Flagged | faculty tiles |
| 14 | Export + Excel file | Export Excel › Download › Open |

**Taking a screenshot:** click the **camera icon** in the emulator side toolbar (saved to your Desktop), or in Android Studio's **Running Devices** panel use the screenshot button. On a real phone press **Power + Volume Down**.

---

## 6. Build the APK

### 6.1 Release APK (recommended for installing on phones)

1. **Build › Generate Signed App Bundle / APK…** → choose **APK** → Next.
2. **Key store path › Create new…**
   * Path: e.g. `Documents/smartattendance.jks`
   * Password, key alias `smartattendance`, key password, validity 25 years, your name/college.
3. Next → **release** → **Create / Finish**.
4. When the build finishes, click **locate**. The file is `android/app/release/app-release.apk`.

**Keep the `.jks` file and passwords safe.** Updates must be signed with the same key.

### 6.2 Quick debug APK (for testing)

**Build › Build App Bundle(s) / APK(s) › Build APK(s)** → `android/app/build/outputs/apk/debug/app-debug.apk`.

---

## 7. Install on a mobile phone

**Method 1 – copy the file**
1. Send `app-release.apk` to the phone (USB cable, Google Drive, Telegram/WhatsApp as a document).
2. Tap the file → allow **Install unknown apps** for the app you opened it from → **Install**.
3. If Play Protect warns, choose **More details › Install anyway** (it is your own app, not from the Play Store).

**Method 2 – USB from Android Studio**
1. Phone: **Settings › About phone** → tap **Build number** 7 times → **Developer options › USB debugging ON**.
2. Connect USB → allow the prompt → select the phone in Android Studio → **Run ▶**.
3. Or from a terminal: `adb install -r app-release.apk`.

**First launch on each phone:** enter the server address → Save & test → Student or Faculty → sign up once. From then on the app opens directly to the dashboard. When asked, allow **Camera** and **Precise location** (location is needed by Android to read the Wi-Fi BSSID).

---

## 8. Real-classroom setup (day 0) and daily use

**Day 0 (any time)**
1. Start the server (and the tunnel if used).
2. Faculty installs the app → signs up with the faculty code.
3. Faculty stands **inside the classroom, connected to the classroom Wi-Fi** → Classrooms › Add classroom → *Use my current location* (wait for ±10–20 m) → *Add the Wi-Fi I am connected to* → set radius (30–50 m) → Save. For a large hall with several access points, walk near each one and tap *Add the Wi-Fi…* again.
4. Students install the app → sign up (register number, section) → face enrolment in good light.

**Every morning**
1. **07:20** – faculty opens the app → *Start attendance session* → choose classroom + section → the QR appears and refreshes every 15 s (screen stays on at full brightness). Project it if needed.
2. Students (inside the room, on the classroom Wi-Fi, Location ON) → *Mark attendance* → Start verification → scan QR → blink/turn → **Present**.
3. **08:05** – the window closes automatically: the QR stops and marking is refused.
4. Faculty reviews **Flagged** check-ins (approve/reject) and exports Excel when needed.

---

## 9. Troubleshooting

| Problem | Fix |
|---|---|
| "Cannot reach the server" | Server running? Correct address (`http://` + IP + `:4000`)? Same Wi-Fi? Firewall allowed? On campus Wi-Fi use the Cloudflare tunnel link |
| Start / Mark buttons disabled | Outside 07:20–08:05 on the **server** clock; check the time shown on the dashboard and the laptop's time zone/clock |
| "Could not read the Wi-Fi access point" | Allow **Precise** location, turn Location ON, be connected to Wi-Fi (not mobile data) |
| "Connected Wi-Fi is not the authorised network" | That access point isn't registered – faculty adds it in Classrooms (large rooms may have several) |
| "You are X m away" | Increase the radius slightly, or re-capture the classroom centre with a better GPS fix |
| "Location not accurate enough" | Move near a window / wait a few seconds and retry; or raise *Max GPS error* in the classroom |
| "Face did not match" | Good front lighting, no mask/cap; if it keeps failing, faculty › Students › tap student › Reset face, then re-enrol. You can also lower `FACE_MATCH_THRESHOLD` (e.g. 0.55) in `.env` |
| Liveness times out | Blink slowly, turn the head clearly (~30°), then look straight |
| Flagged "same phone" | A student used a friend's phone – faculty decides in Flagged |
| Emulator QR/face camera black | AVD settings → Camera Front/Back = Webcam0; close other apps using the webcam |
| Gradle sync fails | Check internet; **File › Invalidate Caches / Restart**; see 4.1 step 4 |
| Excel won't open on phone | Install Excel / Google Sheets / WPS, or use Share |

---

## 10. Notes for your report

The report says Firebase (Auth, Firestore, Cloud Functions). This build uses **your own Node.js REST backend with SQLite** instead. The logic is the same (the server issues signed QR tokens, validates every factor and is the only thing that writes attendance), but you don't need a paid Firebase Blaze plan and everything runs on your own laptop. Update Chapter 6.4/6.5 and Table 7.3 to match:

* **Database:** SQLite tables `users, classrooms, sessions, attendance (UNIQUE student+date), attempts (audit log)`.
* **API:** see `server/README.md` (`/api/student/precheck`, `/api/student/attendance`, `/api/faculty/sessions/:id/qr`, `/api/faculty/export`, …).
* **Export:** Excel `.xlsx` (sheet 1: P/A matrix with totals and %; sheet 2: detailed log with distance, BSSID and face score).
* **Extra rules:** 07:20–08:05 server-time window and one attendance per student per day.
* **Test evidence:** the output of `npm test` (42 automated checks) can go into Chapter 9 test cases.
