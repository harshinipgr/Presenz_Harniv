package com.cit.smartattendance

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Result of one verification step: pass/fail with a message the student can understand. */
data class WifiResult(val ok: Boolean, val bssid: String, val ssid: String, val message: String)

/**
 * Wi-Fi verification: reads the BSSID (MAC address of the access point) of the connected Wi-Fi.
 * The server compares it with the classroom's registered access points.
 * Android requires ACCESS_FINE_LOCATION permission AND Location turned on to read the BSSID.
 */
object WifiChecker {
    suspend fun read(context: Context): WifiResult {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return WifiResult(false, "", "", "Not connected to Wi-Fi. Connect to the classroom Wi-Fi.")
        }
        if (!LocationHelper.isLocationOn(context)) {
            return WifiResult(false, "", "", "Turn ON Location (GPS). Android needs it to read Wi-Fi details.")
        }
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) readWifiInfoS(cm) ?: legacyInfo(context) else legacyInfo(context)
        val bssid = info?.bssid?.lowercase().orEmpty()
        val ssid = info?.ssid?.trim('"').orEmpty()
        if (bssid.isEmpty() || bssid == "02:00:00:00:00:00") {
            return WifiResult(false, "", ssid, "Could not read the Wi-Fi access point. Allow 'Precise location' for this app and turn Location ON.")
        }
        return WifiResult(true, bssid, ssid, "Connected to ${if (ssid.isBlank() || ssid == "<unknown ssid>") "Wi-Fi" else ssid}")
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun legacyInfo(context: Context): WifiInfo? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wm.connectionInfo
    }

    /** Android 12+: WifiInfo with location details comes from a network callback with FLAG_INCLUDE_LOCATION_INFO. */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun readWifiInfoS(cm: ConnectivityManager): WifiInfo? = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
                    if (wifiInfo != null && cont.isActive) {
                        cont.resume(wifiInfo)
                        try { cm.unregisterNetworkCallback(this) } catch (_: Exception) { }
                    }
                }
            }
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            cm.registerNetworkCallback(request, callback)
            cont.invokeOnCancellation { try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) { } }
        }
    }
}

/** GPS: one fresh high-accuracy location fix, with mock (fake GPS) detection. */
object LocationHelper {
    fun isLocationOn(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        return try {
            withTimeoutOrNull(20_000) {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
            }
        } finally {
            cts.cancel()
        }
    }

    @Suppress("DEPRECATION")
    fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
}
