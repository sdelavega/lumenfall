// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Validates WebSocket connection URLs before the real socket is created. Android's
 * `shouldInterceptRequest` doesn't cover WebSocket connections, so this is the only
 * policy point for sockets.
 *
 * The shim's `BridgeWebSocket` proxy (in the page, and in every classic Worker via
 * the shim's worker prelude) calls [validate] with a callId + URL. Native then, off
 * the main thread: applies the bundle's [WallpaperNetworkPolicy] to the host
 * (blocklist, "Block external network requests", LAN toggle, the limited-bundle
 * budget), resolves the host and vets every address with [vetAddresses] (private,
 * loopback, link-local, metadata; private ranges only when LAN is allowed), and
 * calls back `window.aetherDesk._wsValidated(callId, allowed)`. DNS failure
 * rejects. The shim creates the real `WebSocket` only on `allowed=true`.
 * Validated hosts are recorded in [ContactedDomains].
 *
 * This is best-effort: validation and the real connect are separate DNS lookups
 * (see [dnsCache]), so a DNS-rebinding attacker keeps a short window, and module
 * workers/SharedWorker are refused rather than wrapped.
 */
class WebSocketGuard(
    webView: WebView,
    private val networkPolicy: WallpaperNetworkPolicy,
    private val bundleId: String? = null
) {
    private val webViewRef = WeakReference(webView)
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun validate(callId: String, url: String) {
        try {
            executor.execute { respond(callId, isAllowed(url)) }
        } catch (e: RejectedExecutionException) {
            // The JS API has no reason parameter: answer false and log why.
            Log.w(TAG, "WebSocket validation rejected: too many concurrent connections")
            respond(callId, false)
        }
    }

    private fun respond(callId: String, allowed: Boolean) {
        val js = "window.aetherDesk && window.aetherDesk._wsValidated(" +
            "${org.json.JSONObject.quote(callId)}, $allowed);"
        val wv = webViewRef.get()
        if (wv != null) {
            main.post { wv.evaluateJavascript(js, null) }
        }
    }

    companion object {
        private const val TAG = "WebSocketGuard"

        /** Process-wide bounded pool: at most 8 concurrent validations, no queueing. */
        private val executor = ThreadPoolExecutor(
            0, 8, 30L, TimeUnit.SECONDS, SynchronousQueue(), ThreadPoolExecutor.AbortPolicy()
        )

        /**
         * Validation and the real socket connect are separate DNS resolutions,
         * so a DNS-rebinding attacker always has a TOCTOU window. This cache
         * only dedupes a burst of parallel sockets to one host; its short TTL
         * bounds the accepted window to at most 5 s.
         */
        private val dnsCache = DnsCache(
            ttlMs = 5_000,
            now = { System.currentTimeMillis() },
            resolve = { InetAddress.getAllByName(it).toList() }
        )
    }

    private fun isAllowed(url: String): Boolean {
        val host = android.net.Uri.parse(url).host?.lowercase() ?: return false
        val reason = webSocketRejection(
            host = host,
            decideHost = { (networkPolicy.decideHost(it) as? WallpaperNetworkPolicy.Verdict.Block)?.reason },
            resolve = { dnsCache.lookup(it) },
            allowLan = { networkPolicy.lanAllowed }
        )
        if (reason != null) {
            Log.w(TAG, "WebSocket to $host rejected: $reason")
            return false
        }
        bundleId?.let { ContactedDomains.log(it, host) }
        return true
    }
}
