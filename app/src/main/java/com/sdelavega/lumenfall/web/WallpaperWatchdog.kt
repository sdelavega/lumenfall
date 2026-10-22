// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Detects a wallpaper bundle that has stopped responding — hung JS, a wedged
 * event loop, anything that leaves the WebView alive but silent.
 *
 * Ported from ÆtherDesk's WatchdogTimer: a periodic liveness ping, and if none
 * arrives within [timeoutMs], the bundle is declared hung and [onHang] fires
 * once. Lumenfall already has a periodic signal that fits this role without
 * adding a dedicated ping: the bridge shim's tick message (`{"tick":...}`,
 * every 2s via `setInterval` — see `BundleWebView.buildShim`), which proves
 * both that JS timers are still running and that the postMessage round-trip
 * still works. It's driven by `setInterval`, not `requestAnimationFrame`, so
 * a stalled rAF chain alone doesn't trip this — only a genuinely wedged JS
 * thread does (no more timers firing at all), matching ÆtherDesk's "heartbeat
 * independent of the render loop" design.
 *
 * Like ÆtherDesk, a hang is a one-way trip: [onHang] fires once per
 * [start], then polling stops. The caller demotes to fallback content — a
 * hung bundle that gets silently reloaded risks hanging again the same way.
 */
class WallpaperWatchdog(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onHang: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = ::check

    private var lastSeenAt = 0L
    private var running = false
    private var fired = false

    fun start() {
        if (running) return
        running = true
        fired = false
        lastSeenAt = SystemClock.elapsedRealtime()
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(checkRunnable)
    }

    /** Feed any sign of life — a tick, a heartbeat, a successful page load. */
    fun poke() {
        lastSeenAt = SystemClock.elapsedRealtime()
    }

    private fun check() {
        if (!running || fired) return
        if (SystemClock.elapsedRealtime() - lastSeenAt > timeoutMs) {
            fired = true
            onHang()
            return
        }
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val CHECK_INTERVAL_MS = 5_000L
    }
}
