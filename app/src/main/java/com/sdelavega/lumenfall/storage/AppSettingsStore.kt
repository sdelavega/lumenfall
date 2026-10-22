// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.storage

import android.content.Context

/**
 * Global, app-level performance preferences — ported from ÆtherDesk's
 * Preferences > Performance tab (`PerformanceSettings`/`AppSettingsStore.swift`).
 * Distinct from [BundleStore]'s per-bundle property overrides: these apply to
 * every wallpaper regardless of what its `LivelyProperties.json` declares.
 *
 * Persisted here and also pushed through PerformanceSettingsBus by the UI so a
 * running wallpaper can update its WebView policy immediately. Import screening
 * remains a per-load limit; these user preferences are live controls.
 */
data class PerformanceSettings(
    val fpsCap: Int = 30,
    val respectLowPowerMode: Boolean = true,
    val pauseWhenNotVisible: Boolean = true,
    val pauseOnBattery: Boolean = false,
    val blockExternalNetwork: Boolean = false,
    val allowLan: Boolean = false
)

class AppSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lumenfall", Context.MODE_PRIVATE)

    fun get(): PerformanceSettings = PerformanceSettings(
        fpsCap = prefs.getInt(KEY_FPS_CAP, 30),
        respectLowPowerMode = prefs.getBoolean(KEY_RESPECT_LOW_POWER, true),
        pauseWhenNotVisible = prefs.getBoolean(KEY_PAUSE_HIDDEN, true),
        pauseOnBattery = prefs.getBoolean(KEY_PAUSE_BATTERY, false),
        blockExternalNetwork = prefs.getBoolean(KEY_BLOCK_NETWORK, false),
        allowLan = prefs.getBoolean(KEY_ALLOW_LAN, false)
    )

    fun update(transform: (PerformanceSettings) -> PerformanceSettings) {
        val next = transform(get())
        prefs.edit()
            .putInt(KEY_FPS_CAP, next.fpsCap)
            .putBoolean(KEY_RESPECT_LOW_POWER, next.respectLowPowerMode)
            .putBoolean(KEY_PAUSE_HIDDEN, next.pauseWhenNotVisible)
            .putBoolean(KEY_PAUSE_BATTERY, next.pauseOnBattery)
            .putBoolean(KEY_BLOCK_NETWORK, next.blockExternalNetwork)
            .putBoolean(KEY_ALLOW_LAN, next.allowLan)
            .apply()
    }

    private companion object {
        const val KEY_FPS_CAP = "perf_fps_cap"
        const val KEY_RESPECT_LOW_POWER = "perf_respect_low_power"
        const val KEY_PAUSE_HIDDEN = "perf_pause_hidden"
        const val KEY_PAUSE_BATTERY = "perf_pause_battery"
        const val KEY_BLOCK_NETWORK = "perf_block_network"
        const val KEY_ALLOW_LAN = "perf_allow_lan"
    }
}
