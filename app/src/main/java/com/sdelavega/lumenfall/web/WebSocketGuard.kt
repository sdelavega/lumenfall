package com.sdelavega.lumenfall.web

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.net.InetAddress

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
class WebSocketGuard(webView: WebView) {
    private val webViewRef = WeakReference(webView)
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun validate(callId: String, url: String) {
        Thread {
            val allowed = isAllowed(url)
            val js = "window.aetherDesk && window.aetherDesk._wsValidated(" +
                "${org.json.JSONObject.quote(callId)}, $allowed);"
            val wv = webViewRef.get()
            if (wv != null) {
                main.post { wv.evaluateJavascript(js, null) }
            }
        }.start()
    }

    private fun isAllowed(url: String): Boolean {
        val host = android.net.Uri.parse(url).host?.lowercase() ?: return false

        // Raw-IP literals are already rejected in the shim, but double-check
        // here in case a future caller bypasses it.
        if (isRawIp(host)) return false

        // Always-blocked hosts (mDNS, metadata, DNS-rebinding services).
        if (host.endsWith(".local")) return false
        if (host == "metadata.google.internal" || host == "metadata.azure.com") return false
        if (host.endsWith(".nip.io") || host.endsWith(".sslip.io")) return false

        // Resolve and check every resolved address. If ANY resolved IP is
        // private/loopback/metadata, reject — this is the DNS-rebinding defense.
        // A hostname that resolves to both a public and a private IP is still
        // rejected (defense in depth; the attacker could connect to the
        // private one).
        return try {
            val addresses = InetAddress.getAllByName(host)
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
