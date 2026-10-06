package com.cit.smartattendance

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject

/** STUDENT DASHBOARD (blue theme) */
class StudentHomeActivity : BaseActivity() {
    private lateinit var swipe: SwipeRefreshLayout

    private val enrolLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val json = result.data?.getStringExtra(FaceCaptureActivity.EXTRA_EMBEDDINGS)
        if (result.resultCode == Activity.RESULT_OK && json != null) {
            launchApi {
                Api.post("/api/student/face", JSONObject().put("faceEmbeddings", JSONArray(json)))
                toast("Face enrolled")
                load()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_home)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setupHomeMenu(toolbar) { load() }
        swipe = findViewById(R.id.swipe)
        swipe.setOnRefreshListener { load() }
        findViewById<TextView>(R.id.nameText).text = "Hello, ${Prefs.userName}"
        findViewById<MaterialButton>(R.id.btnMark).setOnClickListener { goTo(MarkAttendanceActivity::class.java) }
        findViewById<MaterialButton>(R.id.btnEnrol).setOnClickListener {
            enrolLauncher.launch(Intent(this, FaceCaptureActivity::class.java).putExtra(FaceCaptureActivity.EXTRA_MODE, FaceCaptureActivity.MODE_ENROLL))
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        swipe.isRefreshing = true
        launchApi {
            try {
                render(Api.get("/api/student/dashboard"))
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun render(d: JSONObject) {
        val user = d.getJSONObject("user")
        val w = d.getJSONObject("window")
        val today = d.getJSONObject("today")
        val stats = d.getJSONObject("stats")
        Prefs.userName = user.str("name")
        findViewById<TextView>(R.id.nameText).text = "Hello, ${user.str("name")}"
        findViewById<TextView>(R.id.infoText).text = "${user.str("registerNo")}  ·  ${user.str("department")}  ·  Section ${user.str("section")}"
        findViewById<MaterialCardView>(R.id.faceCard).isVisible = !user.optBoolean("faceEnrolled")
        findViewById<TextView>(R.id.todayDate).text = "TODAY  ·  ${prettyDate(w.str("today")).uppercase()}"

        val icon = findViewById<ImageView>(R.id.statusIcon)
        val statusText = findViewById<TextView>(R.id.statusText)
        val detail = findViewById<TextView>(R.id.statusDetail)
        val btn = findViewById<MaterialButton>(R.id.btnMark)
        val windowText = "Attendance window: ${w.str("start")} - ${w.str("end")} (server time ${w.str("serverTime").take(5)})"

        fun setStatus(iconRes: Int, colorRes: Int, text: String) {
            icon.setImageResource(iconRes)
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color(colorRes)))
            statusText.text = text
        }
        if (today.optBoolean("marked")) {
            when (today.str("status")) {
                "PRESENT" -> setStatus(R.drawable.ic_check_circle, R.color.status_present, "Present - marked at ${today.str("time").take(5)}")
                "FLAGGED" -> setStatus(R.drawable.ic_flag, R.color.status_flagged, "Waiting for faculty review")
                else -> setStatus(R.drawable.ic_cancel, R.color.status_absent, "Rejected by faculty")
            }
            detail.text = "Only one attendance is recorded per day."
            btn.isEnabled = false
            btn.text = "Already recorded today"
        } else {
            when (w.str("state")) {
                "OPEN" -> {
                    setStatus(R.drawable.ic_schedule, R.color.status_info, "Not marked yet")
                    detail.text = "$windowText\nCloses in ${formatDuration(w.optInt("secondsToEnd"))}."
                    btn.isEnabled = user.optBoolean("faceEnrolled")
                    btn.text = "Mark attendance"
                }
                "NOT_STARTED" -> {
                    setStatus(R.drawable.ic_schedule, R.color.status_neutral, "Window not open yet")
                    detail.text = "$windowText\nOpens in ${formatDuration(w.optInt("secondsToStart"))}."
                    btn.isEnabled = false
                    btn.text = "Opens at ${w.str("start")}"
                }
                else -> {
                    setStatus(R.drawable.ic_cancel, R.color.status_absent, "Absent / window closed")
                    detail.text = "$windowText\nAttendance can no longer be marked today."
                    btn.isEnabled = false
                    btn.text = "Closed for today"
                }
            }
        }

        findViewById<TextView>(R.id.percentText).text = "${stats.optDouble("percent", 0.0)}%"
        findViewById<TextView>(R.id.presentText).text = stats.optInt("present").toString()
        findViewById<TextView>(R.id.workingText).text = stats.optInt("workingDays").toString()

        val list = findViewById<LinearLayout>(R.id.historyContainer)
        list.removeAllViews()
        val history = d.getJSONArray("history").objects()
        findViewById<TextView>(R.id.emptyHistory).isVisible = history.isEmpty()
        for (h in history) {
            val status = h.str("status")
            addRow(list, prettyDate(h.str("date")),
                if (h.str("time").isNotBlank()) "Marked at ${h.str("time").take(5)}" else null,
                when (status) { "PRESENT" -> "PRESENT"; "FLAGGED" -> "REVIEW"; "REJECTED" -> "REJECTED"; else -> "ABSENT" },
                statusColor(status))
        }
    }
}

/**
 * GUIDED CHECK-IN: Wi-Fi -> GPS -> scan QR (server pre-check) -> face + liveness -> submit.
 * The server re-verifies everything; the phone only collects the evidence.
 */
class MarkAttendanceActivity : BaseActivity() {
    private enum class Step { WIFI, GPS, QR, FACE }
    private data class StepViews(val detail: TextView, val progress: ProgressBar, val icon: ImageView)

    private lateinit var views: Map<Step, StepViews>
    private lateinit var btnStart: MaterialButton
    private var wifi: WifiResult? = null
    private var location: Location? = null
    private var ticket: String? = null
    private var busy = false

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) runLocalChecks()
        else {
            fail(Step.WIFI, "Precise location permission is required (Android needs it for Wi-Fi and GPS checks).")
        }
    }

    private val qrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val token = r.data?.getStringExtra(QrScanActivity.EXTRA_QR)
        if (r.resultCode == Activity.RESULT_OK && token != null) precheck(token) else fail(Step.QR, "QR scan cancelled")
    }

    private val faceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val json = r.data?.getStringExtra(FaceCaptureActivity.EXTRA_EMBEDDINGS)
        if (r.resultCode == Activity.RESULT_OK && json != null) submit(JSONArray(json).getJSONArray(0)) else fail(Step.FACE, "Face check cancelled")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mark_attendance)
        setupToolbar("Mark attendance")
        views = mapOf(
            Step.WIFI to StepViews(findViewById(R.id.stepWifiDetail), findViewById(R.id.stepWifiProgress), findViewById(R.id.stepWifiIcon)),
            Step.GPS to StepViews(findViewById(R.id.stepGpsDetail), findViewById(R.id.stepGpsProgress), findViewById(R.id.stepGpsIcon)),
            Step.QR to StepViews(findViewById(R.id.stepQrDetail), findViewById(R.id.stepQrProgress), findViewById(R.id.stepQrIcon)),
            Step.FACE to StepViews(findViewById(R.id.stepFaceDetail), findViewById(R.id.stepFaceProgress), findViewById(R.id.stepFaceIcon)),
        )
        btnStart = findViewById(R.id.btnStart)
        btnStart.setOnClickListener { start() }
        findViewById<MaterialButton>(R.id.btnDone).setOnClickListener { finish() }
    }

    private fun start() {
        if (busy) return
        Step.values().forEach { setState(it, "Waiting", null) }
        findViewById<View>(R.id.resultCard).isVisible = false
        ticket = null
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine) runLocalChecks()
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // ---------- UI state per step ----------
    private fun setState(step: Step, text: String, ok: Boolean?, running: Boolean = false) {
        val v = views.getValue(step)
        v.detail.text = text
        v.progress.isVisible = running
        v.icon.isVisible = !running
        val (iconRes, colorRes) = when (ok) {
            true -> R.drawable.ic_check_circle to R.color.status_present
            false -> R.drawable.ic_cancel to R.color.status_absent
            null -> R.drawable.ic_schedule to R.color.status_neutral
        }
        v.icon.setImageResource(iconRes)
        ImageViewCompat.setImageTintList(v.icon, ColorStateList.valueOf(color(colorRes)))
        v.detail.setTextColor(color(if (ok == false) R.color.status_absent else R.color.text_secondary))
    }

    private fun running(step: Step, text: String) = setState(step, text, null, running = true)

    private fun fail(step: Step, reason: String) {
        setState(step, reason, false)
        showResult("REJECTED", reason)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        btnStart.isEnabled = !b
    }

    // ---------- Step 1 + 2: Wi-Fi and GPS on the phone ----------
    private fun runLocalChecks() {
        setBusy(true)
        launchApi(onError = { e -> fail(Step.WIFI, e.message ?: "Error"); setBusy(false) }) {
            // Quick server check: window open and not already marked
            val d = Api.get("/api/student/dashboard")
            val w = d.getJSONObject("window")
            if (d.getJSONObject("today").optBoolean("marked")) {
                showResult("REJECTED", "Attendance is already recorded for today."); setBusy(false); return@launchApi
            }
            if (!w.optBoolean("open")) {
                showResult("REJECTED", "Attendance can be marked only between ${w.str("start")} and ${w.str("end")}. Server time now: ${w.str("serverTime").take(5)}.")
                setBusy(false); return@launchApi
            }

            running(Step.WIFI, "Reading Wi-Fi...")
            val wr = WifiChecker.read(this)
            if (!wr.ok) { fail(Step.WIFI, wr.message); setBusy(false); return@launchApi }
            wifi = wr
            setState(Step.WIFI, "${wr.message} (${wr.bssid})", true)

            running(Step.GPS, "Getting GPS location (stand near a window)...")
            if (!LocationHelper.isLocationOn(this)) { fail(Step.GPS, "Turn ON Location (GPS) and try again."); setBusy(false); return@launchApi }
            val loc = LocationHelper.current(this)
            if (loc == null) { fail(Step.GPS, "Could not get your location. Try again near a window."); setBusy(false); return@launchApi }
            if (LocationHelper.isMock(loc)) { fail(Step.GPS, "Fake GPS detected. Turn off mock location apps."); setBusy(false); return@launchApi }
            location = loc
            setState(Step.GPS, "Location found (accuracy ±${loc.accuracy.toInt()} m)", true)

            running(Step.QR, "Scan the QR code on the faculty's screen")
            qrLauncher.launch(Intent(this, QrScanActivity::class.java))
        }
    }

    private fun evidence(): JSONObject {
        val w = wifi!!
        val l = location!!
        return JSONObject()
            .put("bssid", w.bssid).put("ssid", w.ssid)
            .put("lat", l.latitude).put("lng", l.longitude).put("accuracy", l.accuracy.toDouble())
            .put("isMock", LocationHelper.isMock(l))
            .put("deviceHash", DeviceId.get(this))
    }

    // ---------- Step 3: QR + server pre-check (QR, Wi-Fi, geofence) ----------
    private fun precheck(qrToken: String) {
        running(Step.QR, "Verifying with server...")
        launchApi(onError = { e -> fail(Step.QR, e.message ?: "Error"); setBusy(false) }) {
            val res = Api.post("/api/student/precheck", evidence().put("qrToken", qrToken))
            if (res.str("status") == "OK") {
                ticket = res.str("ticket")
                setState(Step.QR, "Verified: ${res.str("classroom")} (${res.optInt("distance")} m from centre)", true)
                running(Step.FACE, "Look at the camera, blink and turn your head")
                faceLauncher.launch(Intent(this, FaceCaptureActivity::class.java).putExtra(FaceCaptureActivity.EXTRA_MODE, FaceCaptureActivity.MODE_VERIFY))
            } else {
                applyServerFailure(res)
                setBusy(false)
            }
        }
    }

    // ---------- Step 4: face + final submission ----------
    private fun submit(embedding: JSONArray) {
        running(Step.FACE, "Matching face on server...")
        launchApi(onError = { e -> fail(Step.FACE, e.message ?: "Error"); setBusy(false) }) {
            try {
                val res = Api.post("/api/student/attendance", evidence()
                    .put("ticket", ticket)
                    .put("faceEmbedding", embedding)
                    .put("liveness", JSONObject().put("passed", true).put("method", "blink+head-turn")))
                when (res.str("status")) {
                    "PRESENT" -> {
                        setState(Step.FACE, "Face matched (score ${res.optDouble("faceScore")})", true)
                        showResult("PRESENT", "Recorded at ${res.str("time").take(5)} in ${res.str("classroom")}.")
                    }
                    "FLAGGED" -> {
                        setState(Step.FACE, "Face matched", true)
                        showResult("FLAGGED", res.str("reason"))
                    }
                    else -> applyServerFailure(res)
                }
            } finally {
                setBusy(false)
            }
        }
    }

    /** Marks the first failed server check on the matching step and shows the reason. */
    private fun applyServerFailure(res: JSONObject) {
        val c = res.optJSONObject("checks") ?: JSONObject()
        val reason = res.str("reason").ifBlank { "Attendance not recorded." }
        when {
            !c.optBoolean("time") -> showResult("REJECTED", reason)
            !c.optBoolean("qr") -> fail(Step.QR, reason)
            !c.optBoolean("wifi") -> fail(Step.WIFI, reason)
            !c.optBoolean("location") -> fail(Step.GPS, reason)
            else -> fail(Step.FACE, reason)
        }
    }

    private fun showResult(status: String, detail: String) {
        findViewById<View>(R.id.resultCard).isVisible = true
        val icon = findViewById<ImageView>(R.id.resultIcon)
        val title = findViewById<TextView>(R.id.resultTitle)
        val (iconRes, colorRes, text) = when (status) {
            "PRESENT" -> Triple(R.drawable.ic_check_circle, R.color.status_present, "Attendance marked!")
            "FLAGGED" -> Triple(R.drawable.ic_flag, R.color.status_flagged, "Sent for faculty review")
            else -> Triple(R.drawable.ic_cancel, R.color.status_absent, "Not marked")
        }
        icon.setImageResource(iconRes)
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color(colorRes)))
        title.text = text
        title.setTextColor(color(colorRes))
        findViewById<TextView>(R.id.resultDetail).text = detail
        findViewById<View>(R.id.btnDone).isVisible = true
        if (status == "REJECTED") btnStart.text = "Try again" else btnStart.isVisible = false
    }
}
