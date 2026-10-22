// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.render

/**
 * Process-local signal that the active bundle selection changed. The picker
 * notifies after persisting the new active id; a live wallpaper engine, if one
 * exists, rebuilds its renderer in place — no system wallpaper-chooser
 * round-trip needed when Lumenfall is already the active wallpaper.
 *
 * Complements [PropertyBus] (live *property* changes into a running bundle);
 * this is the coarser "the bundle itself changed" nudge. Same process-local
 * registry pattern: the wallpaper service and the picker share one process.
 *
 * Listeners are invoked on the caller's thread — in practice the main thread,
 * since the picker notifies from UI code and Engine callbacks live there too.
 */
object ActiveBundleBus {

    private val listeners = HashMap<Any, () -> Unit>()

    @Synchronized
    fun register(token: Any, listener: () -> Unit) {
        listeners[token] = listener
    }

    @Synchronized
    fun unregister(token: Any) {
        listeners.remove(token)
    }

    fun notifyChanged() {
        val snapshot = synchronized(this) { listeners.values.toList() }
        snapshot.forEach { it() }
    }
}
