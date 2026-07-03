package com.aetherdesk.lumenfall.render

import com.aetherdesk.lumenfall.storage.PerformanceSettings

/**
 * Process-local fan-out for global Performance preference changes. The settings
 * screen persists first, then notifies so any already-running wallpaper engine
 * can update its live WebView policy without waiting for a reload.
 */
object PerformanceSettingsBus {
    fun interface Listener {
        fun onSettings(settings: PerformanceSettings)
    }

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
        snapshot.forEach { it.onSettings(settings) }
    }
}
