// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.render

import androidx.annotation.VisibleForTesting

/**
 * Process-local fan-out of live property changes from the settings UI to running
 * renderers. The wallpaper service and the settings Activity share one process,
 * so a plain in-memory registry suffices.
 *
 * Keyed by an opaque [register] token (the engine instance), not by bundle id:
 * more than one engine can run for the same bundle at once (e.g. the wallpaper
 * preview pane plus the home-screen instance), and each needs its own listener
 * that tears down independently. [notify] reaches every listener for the bundle.
 *
 * Persistence is separate (BundleStore overrides) — this is only the live nudge
 * so a visible wallpaper updates without being recreated.
 */
object PropertyBus {
    fun interface Listener {
        fun onProperty(key: String, value: Any?)
    }

    @VisibleForTesting
    internal var dispatcher: MainThreadDispatcher = AndroidMainDispatcher

    private val listeners = HashMap<Any, Pair<String, Listener>>()

    @Synchronized
    fun register(token: Any, bundleId: String, listener: Listener) {
        listeners[token] = bundleId to listener
    }

    @Synchronized
    fun unregister(token: Any) {
        listeners.remove(token)
    }

    fun notify(bundleId: String, key: String, value: Any?) {
        val snapshot = synchronized(this) {
            listeners.values.filter { it.first == bundleId }.map { it.second }
        }
        snapshot.forEach { listener -> dispatcher.dispatch { listener.onProperty(key, value) } }
    }
}
