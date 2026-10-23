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
 * Validates WebSocket connection URLs before the real socket is created,
 * closing the DNS-rebinding gap that [WallpaperNetworkPolicy] can't reach
 * (Android's `shouldInterceptRequest` doesn't cover WebSocket connections).
 *
 * The shim's `BridgeWebSocket` proxy calls [validate] with a callId + URL;
 * native resolves the host off-thread, checks every resolved IP against the
 * same private/metadata ranges [WallpaperNetworkPolicy] uses for subresource
 * loads, and calls back `window.aetherDesk._wsValidated(callId, allowed)` via
 * `evaluateJavascript`. The shim creates the real `WebSocket` only on
 * `allowed=true`; otherwise it fires `onerror` and sets `readyState=CLOSED`.
 *
 * Raw-IP literals are still rejected in the shim itself (synchronous, no DNS
 * round-trip needed); this guard handles the FQDN case where a benign-looking
 * hostname resolves to a private/metadata address.
 *
 * Always registered for every web bundle — this is a security defense, not a
 * user preference. Ported from ÆtherDesk's `WebWallpaperRuntime.validateWebSocket`.
 */
class WebSocketGuard(webView: WebView, private val blocklist: Blocklist = Blocklist.shared) {
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

        // Raw-IP literals are already rejected in the shim, but double-check
        // here in case a future caller bypasses it.
        if (isRawIp(host)) return false

        // Shared blocklist (trackers, metadata, mDNS, DNS-rebinding services).
        if (blocklist.blocks(host)) return false

        // Resolve and check every resolved address. If ANY resolved IP is
        // private/loopback/metadata, reject — this is the DNS-rebinding defense.
        // A hostname that resolves to both a public and a private IP is still
        // rejected (defense in depth; the attacker could connect to the
        // private one).
        return try {
            val addresses = dnsCache.lookup(host)
            addresses.none { isDisallowedIp(it) }
        } catch (e: Exception) {
            // DNS resolution failed — allow the WebSocket to proceed; the real
            // socket will fail on its own. Rejecting here would block
            // legitimately-unreachable hosts (e.g. during a network glitch),
            // which isn't a security concern.
            true
        }
    }

    private fun isRawIp(host: String): Boolean {
        if (host.startsWith("[") || host.contains(":")) return true
        return host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
    }

    private fun isDisallowedIp(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true
        if (addr.isSiteLocalAddress) return true // 10.x, 172.16-31.x, 192.168.x
        if (addr.isLinkLocalAddress) return true // 169.254.x (includes metadata)
        if (addr.isAnyLocalAddress) return true // 0.0.0.0
        return false
    }
}
