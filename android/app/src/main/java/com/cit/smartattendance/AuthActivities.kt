package com.cit.smartattendance

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject

/** Launcher: if the user already logged in once, go straight to their dashboard. */
class SplashActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = when {
            !Prefs.isLoggedIn -> WelcomeActivity::class.java
            Prefs.role == "faculty" -> FacultyHomeActivity::class.java
            else -> StudentHomeActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}

/** First screen after install: set server address, then choose Student or Faculty. */
class WelcomeActivity : BaseActivity() {
    private lateinit var serverInput: TextInputEditText
    private lateinit var serverStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        serverInput = findViewById(R.id.serverInput)
        serverStatus = findViewById(R.id.serverStatus)
        serverInput.setText(Prefs.serverUrl)
        if (Prefs.serverUrl.isNotBlank()) serverStatus.text = "Saved: ${Prefs.serverUrl}"

        findViewById<MaterialButton>(R.id.btnTestServer).setOnClickListener { testServer(null) }
        findViewById<MaterialButton>(R.id.btnStudent).setOnClickListener { testServer("student") }
        findViewById<MaterialButton>(R.id.btnFaculty).setOnClickListener { testServer("faculty") }
    }

    /** Saves and tests the server address; if [thenRole] is given, continues to the login screen. */
    private fun testServer(thenRole: String?) {
        var url = serverInput.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) {
            serverStatus.text = "Enter the server address first"
            serverStatus.setTextColor(color(R.color.status_absent))
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        serverInput.setText(url)
        val progress = findViewById<ProgressBar>(R.id.serverProgress)
        launchApi(progress, onError = { e ->
            serverStatus.text = "Not reachable: ${e.message}"
            serverStatus.setTextColor(color(R.color.status_absent))
        }) {
            val res = Api.health(url)
            Prefs.serverUrl = url
            val w = res.getJSONObject("window")
            serverStatus.text = "Connected. Server time ${w.str("serverTime").take(5)}, attendance window ${w.str("start")}-${w.str("end")}"
            serverStatus.setTextColor(color(R.color.status_present))
            if (thenRole != null) goTo(LoginActivity::class.java) { putExtra("role", thenRole) }
        }
    }
}

/** Login for both roles. The colour theme changes with the role (blue = student, green = faculty). */
class LoginActivity : BaseActivity() {
    private val role by lazy { intent.getStringExtra("role") ?: "student" }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (role == "faculty") R.style.Theme_SmartAttendance_Faculty else R.style.Theme_SmartAttendance_Student)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        val isFaculty = role == "faculty"
        setupToolbar(if (isFaculty) "Faculty" else "Student")
        findViewById<TextView>(R.id.loginHeading).text = if (isFaculty) "Faculty login" else "Student login"
        findViewById<ImageView>(R.id.loginIcon).setImageResource(if (isFaculty) R.drawable.ic_school else R.drawable.ic_person)
        val idLayout = findViewById<TextInputLayout>(R.id.loginIdLayout)
        val idInput = findViewById<TextInputEditText>(R.id.loginIdInput)
        idLayout.hint = if (isFaculty) "Email" else "Register number"
        idInput.inputType = if (isFaculty) android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or android.text.InputType.TYPE_CLASS_TEXT
        else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        val passwordInput = findViewById<TextInputEditText>(R.id.passwordInput)
        val progress = findViewById<android.view.View>(R.id.progress)
        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)

        btnLogin.setOnClickListener {
            val loginId = idInput.text?.toString()?.trim().orEmpty()
            val password = passwordInput.text?.toString().orEmpty()
            if (loginId.isEmpty() || password.isEmpty()) { toast("Enter your login ID and password"); return@setOnClickListener }
            btnLogin.isEnabled = false
            launchApi(progress) {
                try {
                    val res = Api.post("/api/auth/login", JSONObject()
                        .put("role", role).put("loginId", loginId).put("password", password)
                        .put("deviceHash", DeviceId.get(this)))
                    Prefs.saveLogin(res)
                    goHome()
                } finally {
                    btnLogin.isEnabled = true
                }
            }
        }
        findViewById<MaterialButton>(R.id.btnSignup).setOnClickListener {
            goTo(if (isFaculty) FacultySignupActivity::class.java else StudentSignupActivity::class.java)
        }
    }

    private fun goHome() {
        goTo(if (Prefs.role == "faculty") FacultyHomeActivity::class.java else StudentHomeActivity::class.java, clearTask = true)
    }
}

/** Student sign-up: details + face enrolment (with liveness). */
class StudentSignupActivity : BaseActivity() {
    private var faceEmbeddings: JSONArray? = null

    private val faceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(FaceCaptureActivity.EXTRA_EMBEDDINGS)
            if (json != null) {
                faceEmbeddings = JSONArray(json)
                findViewById<TextView>(R.id.faceStatus).apply {
                    text = "Face captured successfully. You can now create your account."
                    setTextColor(ContextCompat.getColor(this@StudentSignupActivity, R.color.status_present))
                }
                findViewById<MaterialButton>(R.id.btnCaptureFace).text = "Capture again"
                findViewById<MaterialButton>(R.id.btnCreate).isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_signup)
        setupToolbar("Student sign up")
        findViewById<MaterialButton>(R.id.btnCaptureFace).setOnClickListener {
            faceLauncher.launch(Intent(this, FaceCaptureActivity::class.java).putExtra(FaceCaptureActivity.EXTRA_MODE, FaceCaptureActivity.MODE_ENROLL))
        }
        findViewById<MaterialButton>(R.id.btnCreate).setOnClickListener { submit() }
    }

    private fun text(id: Int) = findViewById<TextInputEditText>(id).text?.toString()?.trim().orEmpty()

    private fun submit() {
        val name = text(R.id.nameInput)
        val reg = text(R.id.regInput)
        val dept = text(R.id.deptInput)
        val section = text(R.id.sectionInput)
        val pass = findViewById<TextInputEditText>(R.id.passwordInput).text?.toString().orEmpty()
        val confirm = findViewById<TextInputEditText>(R.id.confirmInput).text?.toString().orEmpty()
        when {
            name.length < 2 -> return toast("Enter your full name")
            reg.length < 3 -> return toast("Enter your register number")
            dept.isEmpty() -> return toast("Enter your department")
            section.isEmpty() -> return toast("Enter your section")
            pass.length < 6 -> return toast("Password must be at least 6 characters")
            pass != confirm -> return toast("Passwords do not match")
            faceEmbeddings == null -> return toast("Capture your face first")
        }
        val btn = findViewById<MaterialButton>(R.id.btnCreate)
        btn.isEnabled = false
        launchApi(findViewById(R.id.progress)) {
            try {
                val res = Api.post("/api/auth/signup/student", JSONObject()
                    .put("name", name).put("registerNo", reg).put("department", dept).put("section", section)
                    .put("password", pass).put("faceEmbeddings", faceEmbeddings)
                    .put("deviceHash", DeviceId.get(this)))
                Prefs.saveLogin(res)
                goTo(StudentHomeActivity::class.java, clearTask = true)
            } finally {
                btn.isEnabled = true
            }
        }
    }
}

/** Faculty sign-up (needs the faculty signup code configured on the server). */
class FacultySignupActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faculty_signup)
        setupToolbar("Faculty sign up")
        findViewById<MaterialButton>(R.id.btnCreate).setOnClickListener { submit() }
    }

    private fun text(id: Int) = findViewById<TextInputEditText>(id).text?.toString()?.trim().orEmpty()

    private fun submit() {
        val name = text(R.id.nameInput)
        val email = text(R.id.emailInput)
        val dept = text(R.id.deptInput)
        val section = text(R.id.sectionInput)
        val code = text(R.id.codeInput)
        val pass = findViewById<TextInputEditText>(R.id.passwordInput).text?.toString().orEmpty()
        val confirm = findViewById<TextInputEditText>(R.id.confirmInput).text?.toString().orEmpty()
        when {
            name.length < 2 -> return toast("Enter your full name")
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> return toast("Enter a valid email")
            dept.isEmpty() -> return toast("Enter your department")
            section.isEmpty() -> return toast("Enter the class / section you handle")
            pass.length < 6 -> return toast("Password must be at least 6 characters")
            pass != confirm -> return toast("Passwords do not match")
            code.isEmpty() -> return toast("Enter the faculty signup code")
        }
        val btn = findViewById<MaterialButton>(R.id.btnCreate)
        btn.isEnabled = false
        launchApi(findViewById(R.id.progress)) {
            try {
                val res = Api.post("/api/auth/signup/faculty", JSONObject()
                    .put("name", name).put("email", email).put("department", dept).put("section", section)
                    .put("password", pass).put("facultyCode", code)
                    .put("deviceHash", DeviceId.get(this)))
                Prefs.saveLogin(res)
                goTo(FacultyHomeActivity::class.java, clearTask = true)
            } finally {
                btn.isEnabled = true
            }
        }
    }
}
