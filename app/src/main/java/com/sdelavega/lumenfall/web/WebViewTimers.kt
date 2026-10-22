// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Single owner of the process-global WebView timer latch.
 *
 * `WebView.pauseTimers()` stops JS timers and rAF for EVERY WebView in the
 * process, and the latch outlives the WebView that set it. With two
 * independent renderers (the wallpaper engine and the settings live preview)
 * issuing pause/resume directly, whichever acts last wins — a hidden engine
 * can freeze a visible preview. So nobody calls pauseTimers/resumeTimers
 * directly; owners declare whether they currently need timers, and the latch
 * pauses only when nobody does (the battery-idle case: a hidden wallpaper and
 * nothing else running).
 *
 * The actual pauseTimers()/resumeTimers() call is debounced, not applied
 * synchronously on every declaration. Android's Engine lifecycle routinely
 * fires visibility as false-then-true within milliseconds of a brand-new
 * WebView's creation (observed on-device: a fresh engine's onCreate claims
 * "needs timers," then a startup visibility churn toggles it false and back
 * to true, all within ~20ms) — and physically pausing/resuming Chromium's
 * timers that early, before the renderer has even attached, can leave the
 * page's own bootstrap (its first script, including the bridge shim) wedged
 * even though the *logical* end state a moment later is "resumed." Debouncing
 * means a rapid settle-down burst collapses into a single physical toggle (or
 * often none at all, if it never stays paused for the whole window), giving
 * a new WebView an uninterrupted head start to actually finish loading.
 */
object WebViewTimers {

    private val needy = mutableSetOf<Any>()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingWebView: WebView? = null
    private val applyRunnable = Runnable {
        val webView = pendingWebView
        pendingWebView = null
        if (webView != null) {
            runCatching { if (needy.isEmpty()) webView.pauseTimers() else webView.resumeTimers() }
        }
    }

    /**
     * Declare whether [token]'s WebView currently needs timers running.
     * [webView] is only the instance used to reach the process-global switch.
     */
    @Synchronized
    fun setNeedsTimers(token: Any, webView: WebView, needs: Boolean) {
        if (needs) needy.add(token) else needy.remove(token)
        scheduleApply(webView)
    }

    /** Drop [token] entirely (owner is being destroyed). */
    @Synchronized
    fun forget(token: Any, webView: WebView?) {
        needy.remove(token)
        webView?.let(::scheduleApply)
    }

    private fun scheduleApply(webView: WebView) {
        pendingWebView = webView
        handler.removeCallbacks(applyRunnable)
        handler.postDelayed(applyRunnable, DEBOUNCE_MS)
    }

    private const val DEBOUNCE_MS = 250L
}
