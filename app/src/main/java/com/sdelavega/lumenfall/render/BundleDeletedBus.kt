// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.render

import androidx.annotation.VisibleForTesting

/**
 * Process-local signal that a bundle was deleted, delivered on the main thread.
 * Same token-keyed registry pattern as the other buses.
 */
object BundleDeletedBus {
    @VisibleForTesting
    internal var dispatcher: MainThreadDispatcher = AndroidMainDispatcher

    private val listeners = HashMap<Any, (bundleId: String) -> Unit>()

    @Synchronized
    fun register(token: Any, listener: (bundleId: String) -> Unit) {
        listeners[token] = listener
    }

    @Synchronized
    fun unregister(token: Any) {
        listeners.remove(token)
    }

    fun notifyDeleted(bundleId: String) {
        val snapshot = synchronized(this) { listeners.values.toList() }
        snapshot.forEach { listener -> dispatcher.dispatch { listener(bundleId) } }
    }
}
