package com.sdelavega.lumenfall.web

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.sdelavega.lumenfall.storage.PerformanceSettings

/**
 * Resolves the global Performance settings ([PerformanceSettings]) plus the
 * device's current power state into the concrete parameters a renderer needs.
 * Snapshotted once at render time — see [com.sdelavega.lumenfall.storage.AppSettingsStore]'s
 * doc comment for why this isn't a live-reacting background daemon.
 */
object PerformancePolicy {
    data class Resolved(val fpsCap: Int, val initiallySuspended: Boolean)

    fun resolve(context: Context, settings: PerformanceSettings, screeningFpsCap: Int): Resolved {
        var cap = minOf(screeningFpsCap, settings.fpsCap)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (settings.respectLowPowerMode && powerManager?.isPowerSaveMode == true) {
            cap = minOf(cap, LOW_POWER_FPS_CAP)
        }
        cap = cap.coerceIn(BundleWebView.MIN_FPS, BundleWebView.MAX_FPS)

        val suspended = settings.pauseOnBattery && isOnBattery(context)
        return Resolved(fpsCap = cap, initiallySuspended = suspended)
    }

    /** Reads the last sticky ACTION_BATTERY_CHANGED broadcast synchronously (no receiver registered). */
    private fun isOnBattery(context: Context): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged == 0 // 0 == not connected to any power source
    }

    private const val LOW_POWER_FPS_CAP = 15
}
