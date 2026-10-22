// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.render

import androidx.annotation.VisibleForTesting
import com.sdelavega.lumenfall.storage.PerformanceSettings

/**
 * Process-local fan-out for global Performance preference changes. The settings
 * screen persists first, then notifies so any already-running wallpaper engine
 * can update its live WebView policy without waiting for a reload.
 */
object PerformanceSettingsBus {
    fun interface Listener {
        fun onSettings(settings: PerformanceSettings)
    }

    @VisibleForTesting
    internal var dispatcher: MainThreadDispatcher = AndroidMainDispatcher

    private val listeners = HashMap<Any, Listener>()

    @Synchronized
    fun register(token: Any, listener: Listener) {
        listeners[token] = listener
    }

    @Synchronized
    fun unregister(token: Any) {
        listeners.remove(token)
    }

    fun notifyChanged(settings: PerformanceSettings) {
        val snapshot = synchronized(this) { listeners.values.toList() }
        snapshot.forEach { listener -> dispatcher.dispatch { listener.onSettings(settings) } }
    }
}
