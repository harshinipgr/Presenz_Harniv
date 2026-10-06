package com.cit.smartattendance

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ======================================================================= shared helpers

/** Wraps a Material exposed dropdown and remembers the selected index. */
class Dropdown(private val view: MaterialAutoCompleteTextView) {
    var items: List<String> = emptyList(); private set
    var selectedIndex = -1; private set
    var onChange: ((Int) -> Unit)? = null
    val selected: String? get() = items.getOrNull(selectedIndex)

    init {
        view.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            onChange?.invoke(position)
        }
    }

    fun setItems(labels: List<String>, select: Int = 0) {
        items = labels
        view.setSimpleItems(labels.toTypedArray())
        if (labels.isNotEmpty()) {
            selectedIndex = select.coerceIn(0, labels.size - 1)
            view.setText(labels[selectedIndex], false)
        } else {
            selectedIndex = -1
            view.setText("", false)
        }
    }
}

private val isoDate get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
fun todayIso(): String = isoDate.format(Date())
fun firstOfMonthIso(): String = isoDate.format(Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.time)

fun BaseActivity.pickDate(title: String, currentIso: String, onPicked: (String) -> Unit) {
    val utc = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val selection = runCatching { utc.parse(currentIso)!!.time }.getOrDefault(MaterialDatePicker.todayInUtcMilliseconds())
    val picker = MaterialDatePicker.Builder.datePicker().setTitleText(title).setSelection(selection).build()
    picker.addOnPositiveButtonClickListener { millis -> onPicked(utc.format(Date(millis))) }
    picker.show(supportFragmentManager, "date")
}

fun BaseActivity.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

/** Adds a small outlined action button (Approve / Reject / Remove) to a list row. */
fun BaseActivity.addAction(row: View, label: String, colorRes: Int, onClick: () -> Unit) {
    val actions = row.findViewById<LinearLayout>(R.id.rowActions)
    actions.isVisible = true
    val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    b.text = label
    b.setTextColor(color(colorRes))
    b.strokeColor = ColorStateList.valueOf(color(colorRes))
    b.setOnClickListener { onClick() }
    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    lp.marginStart = dp(8)
    actions.addView(b, lp)
}

suspend fun loadSections(): List<String> =
    listOf("ALL") + Api.get("/api/faculty/sections").getJSONArray("sections").strings()

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

fun makeQrBitmap(text: String, size: Int = 800): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

// ======================================================================= FACULTY DASHBOARD (green theme)

class FacultyHomeActivity : BaseActivity() {
    private lateinit var swipe: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faculty_home)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setupHomeMenu(toolbar) { load() }
        swipe = findViewById(R.id.swipe)
        swipe.setOnRefreshListener { load() }
        findViewById<TextView>(R.id.nameText).text = "Welcome, ${Prefs.userName}"
        findViewById<MaterialButton>(R.id.btnStartSession).setOnClickListener { goTo(SessionActivity::class.java) }
        findViewById<View>(R.id.tileRecords).setOnClickListener { goTo(RecordsActivity::class.java) }
        findViewById<View>(R.id.tileExport).setOnClickListener { goTo(ExportActivity::class.java) }
        findViewById<View>(R.id.tileStudents).setOnClickListener { goTo(StudentsActivity::class.java) }
        findViewById<View>(R.id.tileFlags).setOnClickListener { goTo(FlagsActivity::class.java) }
        findViewById<View>(R.id.tileClassrooms).setOnClickListener { goTo(ClassroomsActivity::class.java) }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        swipe.isRefreshing = true
        launchApi {
            try {
                render(Api.get("/api/faculty/dashboard"))
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun render(d: JSONObject) {
        val user = d.getJSONObject("user")
        val w = d.getJSONObject("window")
        val counts = d.getJSONObject("counts")
        Prefs.userName = user.str("name")
        findViewById<TextView>(R.id.nameText).text = "Welcome, ${user.str("name")}"
        // The server adds the class to "department", e.g. "CSE  ·  Class: Section L"
        findViewById<TextView>(R.id.infoText).text = "${user.str("email")}  ·  ${user.str("department")}"
        findViewById<TextView>(R.id.todayDate).text = "TODAY  ·  ${prettyDate(w.str("today")).uppercase()}"
        val status = findViewById<TextView>(R.id.windowStatus)
        val detail = findViewById<TextView>(R.id.windowDetail)
        val btn = findViewById<MaterialButton>(R.id.btnStartSession)
        val windowText = "Window ${w.str("start")} - ${w.str("end")}  ·  server time ${w.str("serverTime").take(5)}"
        when (w.str("state")) {
            "OPEN" -> {
                status.text = "Attendance window is OPEN"
                status.setTextColor(color(R.color.status_present))
                detail.text = "$windowText\nCloses in ${formatDuration(w.optInt("secondsToEnd"))}"
                btn.isEnabled = true
                btn.text = "Start attendance session"
            }
            "NOT_STARTED" -> {
                status.text = "Window not open yet"
                status.setTextColor(color(R.color.text_primary))
                detail.text = "$windowText\nOpens in ${formatDuration(w.optInt("secondsToStart"))}"
                btn.isEnabled = false
                btn.text = "Opens at ${w.str("start")}"
            }
            else -> {
                status.text = "Window closed for today"
                status.setTextColor(color(R.color.status_absent))
                detail.text = windowText
                btn.isEnabled = false
                btn.text = "Closed for today"
            }
        }

        val container = findViewById<LinearLayout>(R.id.sessionsContainer)
        container.removeAllViews()
        for (s in d.getJSONArray("sessions").objects()) {
            val active = s.optBoolean("active")
            addRow(container,
                "${s.str("classroom")}  ·  Section ${s.str("section")}",
                "Started ${s.str("startedAt").take(5)}  ·  ${s.optInt("present")} / ${s.optInt("totalStudents")} present" +
                    if (s.optInt("flagged") > 0) "  ·  ${s.optInt("flagged")} to review" else "",
                if (active) "LIVE" else "ENDED",
                if (active) R.color.status_present else R.color.status_neutral
            ) { goTo(SessionActivity::class.java) { putExtra("sessionId", s.str("id")) } }
        }

        findViewById<TextView>(R.id.statStudents).text = counts.optInt("students").toString()
        findViewById<TextView>(R.id.statPresent).text = counts.optInt("presentToday").toString()
        findViewById<TextView>(R.id.statFlags).text = counts.optInt("pendingFlags").toString()
        findViewById<TextView>(R.id.tileFlagsSub).text =
            if (counts.optInt("pendingFlags") > 0) "${counts.optInt("pendingFlags")} waiting for review" else "Review suspicious attempts"
        findViewById<TextView>(R.id.tileClassroomsSub).text = "${counts.optInt("classrooms")} configured"
    }
}

// ======================================================================= SESSION: start + live QR

class SessionActivity : BaseActivity() {
    private var sessionId: String? = null
    private var classrooms: List<JSONObject> = emptyList()
    private lateinit var classroomDd: Dropdown
    private lateinit var sectionDd: Dropdown

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session)
        setupToolbar("Attendance session")
        classroomDd = Dropdown(findViewById(R.id.classroomDropdown))
        sectionDd = Dropdown(findViewById(R.id.sectionDropdown))
        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener { startSession() }
        findViewById<MaterialButton>(R.id.btnEnd).setOnClickListener {
            confirm("End session?", "Students will no longer be able to mark attendance in this session.", "End session") { endSession() }
        }
        sessionId = intent.getStringExtra("sessionId")
        if (sessionId != null) showLive() else loadStartForm()
    }

    private fun loadStartForm() {
        launchApi {
            classrooms = Api.get("/api/faculty/classrooms").getJSONArray("classrooms").objects()
            classroomDd.setItems(classrooms.map { "${it.str("name")} (${it.getJSONArray("bssids").length()} Wi-Fi AP)" })
            sectionDd.setItems(loadSections())
            findViewById<TextView>(R.id.startHint).text = if (classrooms.isEmpty())
                "No classroom yet. Go back and open Classrooms to add one (GPS + Wi-Fi) first."
            else "Choose ALL to allow every section. The QR code changes every 15 seconds."
        }
    }

    private fun startSession() {
        val room = classrooms.getOrNull(classroomDd.selectedIndex) ?: return toast("Choose a classroom")
        val section = sectionDd.selected ?: "ALL"
        launchApi {
            val res = Api.post("/api/faculty/sessions", JSONObject().put("classroomId", room.getInt("id")).put("section", section))
            sessionId = res.getJSONObject("session").str("id")
            if (res.optBoolean("resumed")) toast("Resumed the session already running for this classroom and section")
            showLive()
        }
    }

    private fun showLive() {
        findViewById<View>(R.id.startCard).isVisible = false
        findViewById<View>(R.id.liveArea).isVisible = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f } // bright screen for scanning
        val id = sessionId ?: return
        val qrImage = findViewById<ImageView>(R.id.qrImage)
        val countdown = findViewById<TextView>(R.id.qrCountdown)
        val qrProgress = findViewById<LinearProgressIndicator>(R.id.qrProgress)

        // Loop 1: fetch a fresh signed QR token every 15 seconds
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    try {
                        val q = Api.get("/api/faculty/sessions/$id/qr")
                        qrImage.setImageBitmap(makeQrBitmap(q.str("token")))
                        val total = q.optInt("refreshSeconds", 15)
                        var left = q.optInt("secondsLeft", total)
                        qrProgress.max = total
                        while (left > 0 && isActive) {
                            countdown.text = "New QR code in ${left}s"
                            qrProgress.setProgressCompat(left, true)
                            delay(1000)
                            left--
                        }
                        delay(200)
                    } catch (e: ApiException) {
                        if (e.code == 401) { Prefs.logout(); goTo(WelcomeActivity::class.java, clearTask = true); return@repeatOnLifecycle }
                        if (e.code == 409 || e.code == 403 || e.code == 404) {
                            qrImage.setImageBitmap(null)
                            countdown.text = e.message
                            findViewById<View>(R.id.btnEnd).isVisible = false
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            return@repeatOnLifecycle
                        }
                        countdown.text = "Reconnecting... (${e.message})"
                        delay(3000)
                    }
                }
            }
        }

        // Loop 2: live list of students who checked in
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    try {
                        renderLive(Api.get("/api/faculty/sessions/$id/live"))
                    } catch (e: ApiException) {
                        if (e.code == 401) return@repeatOnLifecycle
                    }
                    delay(4000)
                }
            }
        }
    }

    private fun renderLive(d: JSONObject) {
        val s = d.getJSONObject("session")
        findViewById<TextView>(R.id.sessionTitle).text = "${s.str("classroom")}  ·  Section ${s.str("section")}"
        findViewById<TextView>(R.id.presentCount).text =
            "${s.optInt("present")} / ${s.optInt("totalStudents")} present" + if (s.optInt("flagged") > 0) "  ·  ${s.optInt("flagged")} flagged" else ""
        if (!s.optBoolean("active")) findViewById<View>(R.id.btnEnd).isVisible = false
        val list = findViewById<LinearLayout>(R.id.liveList)
        list.removeAllViews()
        val records = d.getJSONArray("records").objects()
        findViewById<View>(R.id.liveEmpty).isVisible = records.isEmpty()
        for (r in records) {
            addRow(list, "${r.str("name")} (${r.str("registerNo")})",
                "Checked in at ${r.str("time").take(5)}" + if (r.str("reason").isNotBlank()) "  ·  ${r.str("reason")}" else "",
                if (r.str("status") == "FLAGGED") "REVIEW" else r.str("status"), statusColor(r.str("status")))
        }
    }

    private fun endSession() {
        val id = sessionId ?: return
        launchApi {
            Api.post("/api/faculty/sessions/$id/end")
            toast("Session ended")
            finish()
        }
    }
}

// ======================================================================= CLASSROOMS

class ClassroomsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setupToolbar("Classrooms")
        findViewById<TextView>(R.id.listHint).apply {
            isVisible = true
            text = "Each classroom has a GPS point + radius and its authorised Wi-Fi access points."
        }
        findViewById<MaterialButton>(R.id.btnBottom).apply {
            isVisible = true
            text = "Add classroom"
            setOnClickListener { goTo(ClassroomEditActivity::class.java) }
        }
        findViewById<SwipeRefreshLayout>(R.id.swipe).setOnRefreshListener { load() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe)
        swipe.isRefreshing = true
        launchApi {
            try {
                val rooms = Api.get("/api/faculty/classrooms").getJSONArray("classrooms").objects()
                val list = findViewById<LinearLayout>(R.id.listContainer)
                list.removeAllViews()
                findViewById<TextView>(R.id.emptyText).apply { isVisible = rooms.isEmpty(); text = "No classrooms yet. Tap \"Add classroom\"." }
                for (r in rooms) {
                    val aps = r.getJSONArray("bssids").length()
                    addRow(list, r.str("name"),
                        "Radius ${r.optInt("radiusM")} m  ·  GPS error limit ${r.optInt("maxAccuracyM")} m\n${"%.5f".format(r.optDouble("lat"))}, ${"%.5f".format(r.optDouble("lng"))}",
                        "$aps Wi-Fi", if (aps > 0) R.color.status_present else R.color.status_absent
                    ) { goTo(ClassroomEditActivity::class.java) { putExtra("classroom", r.toString()) } }
                }
            } finally {
                swipe.isRefreshing = false
            }
        }
    }
}

class ClassroomEditActivity : BaseActivity() {
    private var roomId: Int? = null
    private var lat: Double? = null
    private var lng: Double? = null
    private val bssids = mutableListOf<String>()
    private var pendingAction: (() -> Unit)? = null

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) pendingAction?.invoke()
        else showMessage("Permission needed", "Allow precise location to read GPS and Wi-Fi details.")
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_classroom_edit)
        val json = intent.getStringExtra("classroom")
        setupToolbar(if (json == null) "Add classroom" else "Edit classroom")
        if (json != null) {
            val r = JSONObject(json)
            roomId = r.getInt("id")
            lat = r.getDouble("lat"); lng = r.getDouble("lng")
            findViewById<TextInputEditText>(R.id.nameInput).setText(r.str("name"))
            findViewById<TextInputEditText>(R.id.radiusInput).setText(r.optInt("radiusM", 40).toString())
            findViewById<TextInputEditText>(R.id.accuracyInput).setText(r.optInt("maxAccuracyM", 50).toString())
            bssids.addAll(r.getJSONArray("bssids").strings())
            findViewById<View>(R.id.btnDelete).isVisible = true
        }
        renderLocation(null)
        renderBssids()

        findViewById<MaterialButton>(R.id.btnLocation).setOnClickListener { withLocationPermission { captureLocation() } }
        findViewById<MaterialButton>(R.id.btnAddWifi).setOnClickListener { withLocationPermission { addCurrentWifi() } }
        findViewById<MaterialButton>(R.id.btnAddBssid).setOnClickListener {
            val input = findViewById<TextInputEditText>(R.id.bssidInput)
            val b = input.text?.toString()?.trim()?.lowercase().orEmpty()
            if (!Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$").matches(b)) return@setOnClickListener toast("Format must be aa:bb:cc:dd:ee:ff")
            if (b !in bssids) bssids.add(b)
            input.setText("")
            renderBssids()
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener {
            confirm("Delete classroom?", "This cannot be undone.", "Delete") {
                launchApi(findViewById(R.id.progress)) {
                    Api.delete("/api/faculty/classrooms/$roomId")
                    toast("Classroom deleted")
                    finish()
                }
            }
        }
    }

    private fun withLocationPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) action()
        else {
            pendingAction = action
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun renderLocation(accuracy: Float?) {
        val t = findViewById<TextView>(R.id.locationText)
        val la = lat; val ln = lng
        t.text = if (la == null || ln == null) "Location not set" else
            "Centre: ${"%.6f".format(la)}, ${"%.6f".format(ln)}" + (accuracy?.let { "  (±${it.toInt()} m)" } ?: "")
    }

    private fun captureLocation() {
        if (!LocationHelper.isLocationOn(this)) return showMessage("Location is off", "Turn ON Location (GPS) first.")
        findViewById<TextView>(R.id.locationText).text = "Getting location..."
        launchApi(findViewById(R.id.progress)) {
            val loc = LocationHelper.current(this)
            if (loc == null) { renderLocation(null); toast("Could not get location. Try again."); return@launchApi }
            lat = loc.latitude; lng = loc.longitude
            renderLocation(loc.accuracy)
            if (loc.accuracy > 30) toast("GPS accuracy is ±${loc.accuracy.toInt()} m. Tap again near a window for a better fix.")
        }
    }

    private fun addCurrentWifi() {
        launchApi(findViewById(R.id.progress)) {
            val w = WifiChecker.read(this)
            if (!w.ok) return@launchApi showMessage("Wi-Fi", w.message)
            if (w.bssid !in bssids) bssids.add(w.bssid)
            renderBssids()
            toast("Added ${w.ssid.ifBlank { "Wi-Fi" }} (${w.bssid})")
        }
    }

    private fun renderBssids() {
        val list = findViewById<LinearLayout>(R.id.bssidList)
        list.removeAllViews()
        if (bssids.isEmpty()) {
            addRow(list, "No access point added", "Students must be connected to one of these to mark attendance.", null)
            return
        }
        for (b in bssids.toList()) {
            val row = addRow(list, b, "Access point BSSID", null)
            addAction(row, "Remove", R.color.status_absent) { bssids.remove(b); renderBssids() }
        }
    }

    private fun save() {
        val name = findViewById<TextInputEditText>(R.id.nameInput).text?.toString()?.trim().orEmpty()
        val radius = findViewById<TextInputEditText>(R.id.radiusInput).text?.toString()?.toDoubleOrNull()
        val acc = findViewById<TextInputEditText>(R.id.accuracyInput).text?.toString()?.toDoubleOrNull()
        when {
            name.isEmpty() -> return toast("Enter the classroom name")
            lat == null || lng == null -> return toast("Tap \"Use my current location\" first")
            radius == null -> return toast("Enter the radius")
            acc == null -> return toast("Enter the GPS error limit")
            bssids.isEmpty() -> return toast("Add at least one Wi-Fi access point")
        }
        val body = JSONObject().put("name", name).put("lat", lat).put("lng", lng)
            .put("radiusM", radius).put("maxAccuracyM", acc).put("bssids", JSONArray(bssids))
        launchApi(findViewById(R.id.progress)) {
            if (roomId == null) Api.post("/api/faculty/classrooms", body) else Api.put("/api/faculty/classrooms/$roomId", body)
            toast("Classroom saved")
            finish()
        }
    }
}

// ======================================================================= RECORDS (present / absent by date)

class RecordsActivity : BaseActivity() {
    private var date = todayIso()
    private lateinit var sectionDd: Dropdown
    private var data: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_records)
        setupToolbar("Attendance records")
        sectionDd = Dropdown(findViewById(R.id.sectionDropdown))
        sectionDd.onChange = { load() }
        val btnDate = findViewById<MaterialButton>(R.id.btnDate)
        btnDate.text = prettyDate(date)
        btnDate.setOnClickListener {
            pickDate("Choose date", date) { picked -> date = picked; btnDate.text = prettyDate(date); load() }
        }
        findViewById<MaterialButtonToggleGroup>(R.id.toggle).addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) render() }
        findViewById<SwipeRefreshLayout>(R.id.swipe).setOnRefreshListener { load() }
        launchApi {
            sectionDd.setItems(loadSections())
            load()
        }
    }

    private fun load() {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe)
        swipe.isRefreshing = true
        launchApi {
            try {
                data = Api.get("/api/faculty/attendance?date=$date&section=${enc(sectionDd.selected ?: "ALL")}")
                render()
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun render() {
        val d = data ?: return
        val present = d.getJSONArray("present").objects()
        val absent = d.getJSONArray("absent").objects()
        val flagged = d.getJSONArray("flagged").objects() + d.getJSONArray("rejected").objects()
        findViewById<MaterialButton>(R.id.btnPresent).text = "Present (${present.size})"
        findViewById<MaterialButton>(R.id.btnAbsent).text = "Absent (${absent.size})"
        findViewById<MaterialButton>(R.id.btnFlagged).text = "Review (${flagged.size})"
        val total = present.size + absent.size + flagged.size
        findViewById<TextView>(R.id.summaryText).text = if (!d.optBoolean("sessionHeld")) "No attendance session was held on this date for this section."
        else "${present.size} of $total students present" + if (total > 0) " (${present.size * 100 / total}%)" else ""

        val checked = findViewById<MaterialButtonToggleGroup>(R.id.toggle).checkedButtonId
        val (items, status) = when (checked) {
            R.id.btnAbsent -> absent to "ABSENT"
            R.id.btnFlagged -> flagged to "FLAGGED"
            else -> present to "PRESENT"
        }
        val list = findViewById<LinearLayout>(R.id.listContainer)
        list.removeAllViews()
        findViewById<TextView>(R.id.emptyText).apply { isVisible = items.isEmpty(); text = "No students in this list." }
        for (s in items) {
            val sub = buildString {
                append("${s.str("registerNo")}  ·  Section ${s.str("section")}")
                if (s.str("time").isNotBlank()) append("  ·  ${s.str("time").take(5)}")
                if (s.str("reason").isNotBlank()) append("\n${s.str("reason")}")
            }
            val row = addRow(list, s.str("name"), sub, if (status == "FLAGGED") "REVIEW" else status, statusColor(status))
            if (status == "FLAGGED" && !s.isNull("attendanceId")) {
                val attId = s.getInt("attendanceId")
                addAction(row, "Reject", R.color.status_absent) { decide(attId, "reject") }
                addAction(row, "Approve", R.color.status_present) { decide(attId, "approve") }
            }
        }
    }

    private fun decide(id: Int, decision: String) {
        launchApi {
            Api.post("/api/faculty/flags/$id", JSONObject().put("decision", decision))
            toast(if (decision == "approve") "Marked present" else "Rejected")
            load()
        }
    }
}

// ======================================================================= STUDENTS

class StudentsActivity : BaseActivity() {
    private lateinit var sectionDd: Dropdown

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setupToolbar("Students")
        findViewById<TextInputLayout>(R.id.sectionLayout).isVisible = true
        findViewById<TextView>(R.id.listHint).apply { isVisible = true; text = "Tap a student for options (e.g. reset face enrolment)." }
        sectionDd = Dropdown(findViewById(R.id.sectionDropdown))
        sectionDd.onChange = { load() }
        findViewById<SwipeRefreshLayout>(R.id.swipe).setOnRefreshListener { load() }
        launchApi {
            sectionDd.setItems(loadSections())
            load()
        }
    }

    private fun load() {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe)
        swipe.isRefreshing = true
        launchApi {
            try {
                val students = Api.get("/api/faculty/students?section=${enc(sectionDd.selected ?: "ALL")}").getJSONArray("students").objects()
                val list = findViewById<LinearLayout>(R.id.listContainer)
                list.removeAllViews()
                findViewById<TextView>(R.id.emptyText).apply { isVisible = students.isEmpty(); text = "No students have signed up yet." }
                for (s in students) {
                    val pct = s.optDouble("percent", 0.0)
                    val colorRes = when {
                        s.optInt("workingDays") == 0 -> R.color.status_neutral
                        pct >= 75 -> R.color.status_present
                        pct >= 60 -> R.color.status_flagged
                        else -> R.color.status_absent
                    }
                    addRow(list, s.str("name"),
                        "${s.str("registerNo")}  ·  ${s.str("department")}  ·  Section ${s.str("section")}\n" +
                            "Present ${s.optInt("present")} of ${s.optInt("workingDays")} days" + if (!s.optBoolean("faceEnrolled")) "  ·  face not enrolled" else "",
                        "$pct%", colorRes
                    ) { options(s) }
                }
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun options(s: JSONObject) {
        MaterialAlertDialogBuilder(this)
            .setTitle("${s.str("name")} (${s.str("registerNo")})")
            .setItems(arrayOf("Reset face enrolment")) { _, _ ->
                confirm("Reset face?", "The student must enrol their face again in the app before marking attendance.", "Reset") {
                    launchApi {
                        Api.post("/api/faculty/students/${s.getInt("id")}/reset-face")
                        toast("Face enrolment reset")
                        load()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}

// ======================================================================= FLAGGED ATTEMPTS

class FlagsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setupToolbar("Flagged for review")
        findViewById<TextView>(R.id.listHint).apply {
            isVisible = true
            text = "These check-ins passed all checks but were made from a phone already used by another student today."
        }
        findViewById<SwipeRefreshLayout>(R.id.swipe).setOnRefreshListener { load() }
        load()
    }

    private fun load() {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe)
        swipe.isRefreshing = true
        launchApi {
            try {
                val flags = Api.get("/api/faculty/flags").getJSONArray("flags").objects()
                val list = findViewById<LinearLayout>(R.id.listContainer)
                list.removeAllViews()
                findViewById<TextView>(R.id.emptyText).apply { isVisible = flags.isEmpty(); text = "Nothing to review." }
                for (f in flags) {
                    val row = addRow(list, "${f.str("name")} (${f.str("registerNo")})",
                        "${prettyDate(f.str("date"))} at ${f.str("time").take(5)}  ·  Section ${f.str("section")}\n${f.str("reason")}" +
                            if (f.str("otherName").isNotBlank()) " (${f.str("otherName")})" else "",
                        "REVIEW", R.color.status_flagged)
                    addAction(row, "Reject", R.color.status_absent) { decide(f.getInt("attendanceId"), "reject") }
                    addAction(row, "Approve", R.color.status_present) { decide(f.getInt("attendanceId"), "approve") }
                }
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun decide(id: Int, decision: String) {
        launchApi {
            Api.post("/api/faculty/flags/$id", JSONObject().put("decision", decision))
            toast(if (decision == "approve") "Marked present" else "Rejected")
            load()
        }
    }
}

// ======================================================================= EXPORT EXCEL

class ExportActivity : BaseActivity() {
    companion object { const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }

    private var from = firstOfMonthIso()
    private var to = todayIso()
    private lateinit var sectionDd: Dropdown
    private var pendingBytes: ByteArray? = null
    private var savedUri: Uri? = null

    // Android 8/9: let the user choose where to save (no storage permission needed)
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument(XLSX)) { uri ->
        val bytes = pendingBytes
        if (uri != null && bytes != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            onSaved(uri, "Saved")
        }
        pendingBytes = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)
        setupToolbar("Export Excel")
        sectionDd = Dropdown(findViewById(R.id.sectionDropdown))
        val btnFrom = findViewById<MaterialButton>(R.id.btnFrom)
        val btnTo = findViewById<MaterialButton>(R.id.btnTo)
        btnFrom.text = prettyDate(from)
        btnTo.text = prettyDate(to)
        btnFrom.setOnClickListener { pickDate("From date", from) { from = it; btnFrom.text = prettyDate(it) } }
        btnTo.setOnClickListener { pickDate("To date", to) { to = it; btnTo.text = prettyDate(it) } }
        findViewById<MaterialButton>(R.id.btnDownload).setOnClickListener { download() }
        findViewById<MaterialButton>(R.id.btnOpen).setOnClickListener { open() }
        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener { share() }
        launchApi { sectionDd.setItems(loadSections()) }
    }

    private fun download() {
        if (from > to) return toast("From date must be before To date")
        val section = sectionDd.selected ?: "ALL"
        val btn = findViewById<MaterialButton>(R.id.btnDownload)
        btn.isEnabled = false
        launchApi(findViewById(R.id.progress)) {
            try {
                val (name, bytes) = Api.download("/api/faculty/export?from=$from&to=$to&section=${enc(section)}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = saveToDownloads(name, bytes)
                    onSaved(uri, "Saved to Downloads/$name")
                } else {
                    pendingBytes = bytes
                    createDoc.launch(name)
                }
            } finally {
                btn.isEnabled = true
            }
        }
    }

    /** Android 10+: save straight into the phone's Downloads folder using MediaStore. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloads(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, XLSX)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw ApiException("Could not create the file in Downloads")
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw ApiException("Could not write the file")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun onSaved(uri: Uri, message: String) {
        savedUri = uri
        findViewById<MaterialCardView>(R.id.savedCard).isVisible = true
        findViewById<TextView>(R.id.savedText).text = message
        toast("Excel file downloaded")
    }

    private fun open() {
        val uri = savedUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, XLSX).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            showMessage("No app to open Excel files", "Install Microsoft Excel, Google Sheets or WPS Office, or use Share to send the file.")
        }
    }

    private fun share() {
        val uri = savedUri ?: return
        val send = Intent(Intent.ACTION_SEND).setType(XLSX).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share attendance report"))
    }
}
