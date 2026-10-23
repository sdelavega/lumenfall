// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Fetches the device's current location via the framework [LocationManager].
 *
 * Why not FusedLocationProvider (play-services-location): Lumenfall ships no
 * Play Services dependency today (the app is 3.5 MB and runs on any
 * Android 13+ device, not just ones with Play). The framework
 * `getCurrentLocation` (API 30+, our minSdk is 33) gives a one-shot fix
 * without a persistent client or service binding, which is exactly what a
 * wallpaper that occasionally asks "where am I" needs. Adding Play Services
 * just for this would double the APK and break the no-GMS posture for no
 * gain on a Pixel Tablet, which always has Play anyway.
 *
 * No reverse geocoding: Lumenfall never asks Android's geocoder (which on most
 * devices is a network request to Google) for a place name, so `pos.label` is
 * not provided and the app makes no network requests of its own.
 *
 * Results are delivered on the main thread so callers can `evaluateJavascript`
 * straight back into the WebView without an extra hop.
 *
 * Permission is the caller's responsibility (see [GeolocationPolicy]); this
 * provider still re-checks at call time and returns [Result.Denied] if the
 * user revoked location since the policy snapshot was taken — a live wallpaper
 * runs in the background and can't host a permission dialog, so a revoke
 * mid-session must surface as a clean rejection, not a crash.
 */
class GeolocationProvider(private val context: Context) {

    sealed interface Result {
        data class Fix(val json: String) : Result
        data class Denied(val reason: String) : Result
        data class Unavailable(val reason: String) : Result
        data class Timeout(val reason: String) : Result
    }

    private val main = Handler(Looper.getMainLooper())
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * One-shot current location. [enableHighAccuracy] selects GPS over NETWORK
     * when available; [timeoutMs] bounds the wait. Resolves on the main thread.
     */
    fun current(enableHighAccuracy: Boolean, timeoutMs: Int, onResult: (Result) -> Unit) {
        if (!hasLocationPermission(context)) {
            main.post { onResult(Result.Denied("location permission not granted")) }
            return
        }
        val manager = lm
        if (manager == null) {
            main.post { onResult(Result.Unavailable("no LocationManager service")) }
            return
        }

        // Pick the best provider that's actually present and enabled. A WiFi-only
        // tablet (like the Pixel Tablet) often has no GPS_PROVIDER at all —
        // isProviderEnabled returns false for a nonexistent provider, so we fall
        // through to NETWORK_PROVIDER (WiFi/cell-based fix) rather than handing
        // getCurrentLocation a provider string it will reject with
        // "provider X does not exist".
        // GPS_PROVIDER requires ACCESS_FINE_LOCATION; with approximate-only
        // permission it is skipped and NETWORK_PROVIDER is used.
        val gpsAllowed = isFineLocationGranted(context)
        val provider = when {
            gpsAllowed && enableHighAccuracy &&
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            gpsAllowed && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            else -> {
                main.post { onResult(Result.Unavailable("location providers are off")) }
                return
            }
        }

        val signal = CancellationSignal()
        val startedAt = SystemClock.elapsedRealtime()
        val resolved = java.util.concurrent.atomic.AtomicBoolean(false)

        val timeout = Runnable {
            if (resolved.compareAndSet(false, true)) {
                signal.cancel()
                onResult(Result.Timeout("no fix within ${timeoutMs}ms"))
            }
        }
        main.postDelayed(timeout, timeoutMs.toLong())

        try {
            manager.getCurrentLocation(
                provider,
                signal,
                ContextCompat.getMainExecutor(context)
            ) { loc -> onLocation(loc, startedAt, resolved, timeout, onResult) }
        } catch (t: SecurityException) {
            main.removeCallbacks(timeout)
            if (resolved.compareAndSet(false, true)) {
                main.post { onResult(Result.Denied("location permission revoked")) }
            }
        } catch (t: Throwable) {
            main.removeCallbacks(timeout)
            if (resolved.compareAndSet(false, true)) {
                main.post { onResult(Result.Unavailable(t.message ?: "location fetch failed")) }
            }
        }
    }

    private fun onLocation(
        location: Location?,
        startedAt: Long,
        resolved: java.util.concurrent.atomic.AtomicBoolean,
        timeout: Runnable,
        onResult: (Result) -> Unit
    ) {
        if (!resolved.compareAndSet(false, true)) return
        main.removeCallbacks(timeout)
        if (location == null) {
            onResult(Result.Unavailable("provider returned no fix"))
            return
        }
        onResult(Result.Fix(toJson(location).toString()))
    }

    private fun toJson(loc: Location): JSONObject = JSONObject().apply {
        put("latitude", loc.latitude)
        put("longitude", loc.longitude)
        put("accuracy", loc.accuracy.toDouble())
        if (loc.hasAltitude()) put("altitude", loc.altitude)
        if (loc.hasVerticalAccuracy()) put("altitudeAccuracy", loc.verticalAccuracyMeters.toDouble())
        if (loc.hasBearing()) put("bearing", loc.bearing.toDouble())
        if (loc.hasSpeed()) put("speed", loc.speed.toDouble())
        put("timestamp", loc.time)
        put("elapsedRealtimeNanos", loc.elapsedRealtimeNanos)
        put("provider", loc.provider ?: "unknown")
    }
}
