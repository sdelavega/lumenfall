// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.render

/**
 * Process-local fan-out for per-bundle geolocation toggle changes. The value
 * delivered to listeners is the effective "allowed now" decision after policy
 * checks, not just the raw stored preference.
 */
object GeolocationBus {
    fun interface Listener {
        fun onGeolocationAllowed(allowed: Boolean)
    }

    private val listeners = HashMap<Any, Pair<String, Listener>>()

    @Synchronized
    fun register(token: Any, bundleId: String, listener: Listener) {
        listeners[token] = bundleId to listener
    }

    @Synchronized
    fun unregister(token: Any) {
        listeners.remove(token)
    }

    fun notify(bundleId: String, allowed: Boolean) {
        val snapshot = synchronized(this) { listeners.values.toList() }
        snapshot.forEach { (id, listener) ->
            if (id == bundleId) listener.onGeolocationAllowed(allowed)
        }
    }
}
