package com.aetherdesk.lumenfall.web

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
 */
object WebViewTimers {

    private val needy = mutableSetOf<Any>()

    /**
     * Declare whether [token]'s WebView currently needs timers running.
     * [webView] is only the instance used to reach the process-global switch.
     */
    @Synchronized
    fun setNeedsTimers(token: Any, webView: WebView, needs: Boolean) {
        if (needs) needy.add(token) else needy.remove(token)
        apply(webView)
    }

    /** Drop [token] entirely (owner is being destroyed). */
    @Synchronized
    fun forget(token: Any, webView: WebView?) {
        needy.remove(token)
        webView?.let(::apply)
    }

    private fun apply(webView: WebView) {
        if (needy.isEmpty()) webView.pauseTimers() else webView.resumeTimers()
    }
}
