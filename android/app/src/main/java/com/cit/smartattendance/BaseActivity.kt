package com.cit.smartattendance

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Parent of every screen:
 *  - draws edge-to-edge and pads the coloured top bar under the status bar,
 *  - offers helpers for API calls with a loading indicator and friendly error dialogs.
 */
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyInsets()
    }

    private fun applyInsets() {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: return
        val topBar: View? = findViewById(R.id.topBar)
        val rootPad = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        val topPad = topBar?.paddingTop ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(
                rootPad[0] + bars.left,
                rootPad[1] + if (topBar == null) bars.top else 0,
                rootPad[2] + bars.right,
                rootPad[3] + bars.bottom
            )
            topBar?.setPadding(topBar.paddingLeft, topPad + bars.top, topBar.paddingRight, topBar.paddingBottom)
            insets
        }
    }

    /** Sets up the toolbar inside the included top bar, with a back arrow. */
    protected fun setupToolbar(title: String, back: Boolean = true): MaterialToolbar {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = title
        if (back) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            toolbar.navigationIcon?.setTint(Color.WHITE)
            toolbar.setNavigationOnClickListener { finish() }
        }
        return toolbar
    }

    /**
     * Runs an API call in the background. Shows [progress] while running,
     * shows an error dialog on failure and sends the user to the Welcome screen if their login expired.
     */
    protected fun launchApi(progress: View? = null, onError: ((ApiException) -> Unit)? = null, block: suspend () -> Unit): Job =
        lifecycleScope.launch {
            progress?.isVisible = true
            try {
                block()
            } catch (e: ApiException) {
                if (e.code == 401) {
                    Prefs.logout()
                    toast("Please log in again.")
                    goTo(WelcomeActivity::class.java, clearTask = true)
                } else if (onError != null) {
                    onError(e)
                } else {
                    showMessage("Something went wrong", e.message ?: "Error")
                }
            } finally {
                progress?.isVisible = false
            }
        }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    fun showMessage(title: String, message: String, onOk: (() -> Unit)? = null) {
        if (isFinishing) return
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onOk?.invoke() }
            .show()
    }

    fun confirm(title: String, message: String, yes: String = "Yes", onYes: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .show()
    }

    fun goTo(cls: Class<*>, clearTask: Boolean = false, extras: (Intent.() -> Unit)? = null) {
        val i = Intent(this, cls)
        if (clearTask) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        extras?.invoke(i)
        startActivity(i)
        if (clearTask) finish()
    }

    /** Lets a logged-in user change the server address (e.g. when the tunnel link changes). */
    fun editServerAddress() {
        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.setText(Prefs.serverUrl)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        val box = android.widget.FrameLayout(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle("Server address")
            .setMessage("Example: http://192.168.1.10:4000 or https://xxxx.trycloudflare.com")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                var url = input.text?.toString()?.trim().orEmpty()
                if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
                if (url.isNotEmpty()) {
                    Prefs.serverUrl = url
                    toast("Server address saved. Pull down to refresh.")
                }
            }
            .show()
    }

    /** Standard dashboard menu: refresh, log out, server address. */
    protected fun setupHomeMenu(toolbar: MaterialToolbar, onRefresh: () -> Unit) {
        toolbar.inflateMenu(R.menu.menu_home)
        toolbar.overflowIcon?.setTint(Color.WHITE)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_refresh -> onRefresh()
                R.id.action_logout -> logoutWithConfirm()
                R.id.action_server -> editServerAddress()
            }
            true
        }
    }

    fun logoutWithConfirm() {
        confirm("Log out?", "You will need to log in again on this phone.", "Log out") {
            Prefs.logout()
            goTo(WelcomeActivity::class.java, clearTask = true)
        }
    }

    // ---------- list rows (used by all list screens) ----------
    fun color(@ColorRes id: Int) = ContextCompat.getColor(this, id)

    /** Adds a row (title, subtitle, coloured badge) to [container]. Returns the row so callers can add buttons. */
    fun addRow(
        container: LinearLayout,
        title: String,
        subtitle: String? = null,
        badge: String? = null,
        @ColorRes badgeColor: Int = R.color.status_neutral,
        onClick: (() -> Unit)? = null
    ): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_row, container, false)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowSubtitle).apply {
            text = subtitle ?: ""
            isVisible = !subtitle.isNullOrBlank()
        }
        row.findViewById<TextView>(R.id.rowBadge).apply {
            text = badge ?: ""
            isVisible = !badge.isNullOrBlank()
            backgroundTintList = ColorStateList.valueOf(color(badgeColor))
        }
        if (onClick != null) {
            row.isClickable = true
            row.setOnClickListener { onClick() }
        }
        container.addView(row)
        return row
    }

    fun statusColor(status: String): Int = when (status) {
        "PRESENT" -> R.color.status_present
        "FLAGGED" -> R.color.status_flagged
        "ABSENT", "REJECTED" -> R.color.status_absent
        else -> R.color.status_neutral
    }
}

/** Formats "2026-09-29" as "Tue, 29 Sep 2026". */
fun prettyDate(iso: String): String = try {
    val inFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val outFmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy", java.util.Locale.US)
    outFmt.format(inFmt.parse(iso)!!)
} catch (e: Exception) { iso }

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
