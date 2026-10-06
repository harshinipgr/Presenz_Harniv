package com.cit.smartattendance

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Application class: initialises the saved settings once. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}

/**
 * Saved on the phone after the first login/sign-up, so the user never has to log in again
 * (until they press "Log out" or uninstall the app).
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("smart_attendance", Context.MODE_PRIVATE)
    }

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()

    var role: String?
        get() = sp.getString("role", null)
        set(v) = sp.edit().putString("role", v).apply()

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v).apply()

    val isLoggedIn: Boolean get() = !token.isNullOrEmpty() && !role.isNullOrEmpty()

    fun saveLogin(response: JSONObject) {
        val user = response.getJSONObject("user")
        token = response.getString("token")
        role = user.getString("role")
        userName = user.optString("name")
    }

    fun logout() {
        sp.edit().remove("token").remove("role").remove("user_name").apply()
    }
}

class ApiException(message: String, val code: Int = 0) : Exception(message)

/** Small REST client for the backend (JSON over HTTP/HTTPS). */
object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .build()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun get(path: String): JSONObject = call("GET", path, null)
    suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject = call("POST", path, body)
    suspend fun put(path: String, body: JSONObject): JSONObject = call("PUT", path, body)
    suspend fun delete(path: String): JSONObject = call("DELETE", path, null)

    private fun request(path: String, baseUrl: String = Prefs.serverUrl): Request.Builder {
        if (baseUrl.isBlank()) throw ApiException("Set the server address first (Welcome screen).")
        val builder = try {
            Request.Builder().url(baseUrl.trimEnd('/') + path)
        } catch (e: IllegalArgumentException) {
            throw ApiException("Invalid server address: $baseUrl")
        }
        Prefs.token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private suspend fun call(method: String, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val builder = request(path)
        val rb = (body ?: JSONObject()).toString().toRequestBody(JSON_TYPE)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(rb)
            "PUT" -> builder.put(rb)
            "DELETE" -> builder.delete()
        }
        execute(builder.build())
    }

    private fun execute(req: Request): JSONObject {
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (!resp.isSuccessful) {
                    throw ApiException(json.optString("error", "Server error (${resp.code})"), resp.code)
                }
                return json
            }
        } catch (e: IOException) {
            throw ApiException("Cannot reach the server. Check your internet and the server address (dashboard menu ⋮ › Server address).")
        }
    }

    /** Used on the Welcome screen to test a server address before saving it. */
    suspend fun health(baseUrl: String): JSONObject = withContext(Dispatchers.IO) {
        execute(request("/api/health", baseUrl).get().build())
    }

    /** Downloads a file (Excel export). Returns file name + bytes. */
    suspend fun download(path: String): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request(path).get().build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) {
                    val msg = try { JSONObject(String(bytes)).optString("error") } catch (e: Exception) { "" }
                    throw ApiException(msg.ifBlank { "Download failed (${resp.code})" }, resp.code)
                }
                val name = resp.header("X-File-Name") ?: "Attendance.xlsx"
                name to bytes
            }
        } catch (e: IOException) {
            throw ApiException("Cannot reach the server. Check your connection.")
        }
    }
}

object DeviceId {
    /** A stable, anonymous ID for this phone + app (SHA-256 of ANDROID_ID). Used to detect one phone marking for many students. */
    @SuppressLint("HardwareIds")
    fun get(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val digest = MessageDigest.getInstance("SHA-256").digest("smart-attendance:$androidId".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

// ---------- small JSON helpers ----------
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun FloatArray.toJson(): JSONArray = JSONArray().also { arr -> forEach { arr.put(it.toDouble()) } }
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")
