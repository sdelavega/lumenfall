// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes `window.fetch()` and `XMLHttpRequest` through native `HttpURLConnection`,
 * bypassing the WebView's CORS restrictions so wallpaper bundles can reach
 * external APIs (weather data, etc.) without CORS errors.
 *
 * The WebView serves bundles from `https://appassets.androidplatform.net`; a
 * `fetch('https://api.open-meteo.com/...')` from that origin would be blocked
 * by the WebView's CORS enforcement because the external API doesn't return
 * `Access-Control-Allow-Origin` for the appassets origin. This proxy sidesteps
 * the WebView entirely: the shim calls `aetherDeskFetch.request(...)`, native
 * makes the HTTP request, and delivers the response body back via
 * `evaluateJavascript('window.aetherDesk._nativeFetchResponse(...)')`.
 *
 * Because the response body is readable by the bundle, this path is policed
 * hop by hop (see [followVetted]):
 * - automatic redirects are off; up to [MAX_REDIRECTS] are followed manually;
 * - every hop (the first one included) goes through the bundle's
 *   [WallpaperNetworkPolicy] (scheme, blocklist, LAN toggle, block-all, and the
 *   per-minute budget, which each hop spends) and its host is resolved and every
 *   address vetted by [vetAddresses] (loopback, private, ULA, link-local,
 *   any-local, metadata, IPv4-mapped forms) before connecting;
 * - each allowed hop's host is recorded in [ContactedDomains];
 * - concurrent in-flight fetches are capped at [MAX_CONCURRENT] and the response
 *   body at [MAX_RESPONSE_BYTES]; a blocked fetch returns status 0.
 *
 * Limitation (best-effort, like [WebSocketGuard]): `HttpURLConnection` cannot
 * pin the vetted address, so it resolves the host again when it connects. A
 * DNS-rebinding attacker who answers differently the second time can slip
 * through that short window. Pinning by URL-rewriting is not an option: it
 * would break SNI and certificate checks for HTTPS (and cleartext HTTP is
 * refused by the platform anyway).
 *
 * Always registered for every web bundle. The shim only routes external
 * http(s) through it; same-origin (appassets) requests use the original fetch.
 */
class NativeFetchBridge(
    webView: WebView,
    private val networkPolicy: WallpaperNetworkPolicy,
    private val bundleId: String? = null,
    private val onLog: (String) -> Unit
) {
    private val webViewRef = WeakReference(webView)
    private val main = Handler(Looper.getMainLooper())
    private val inflight = ConcurrentHashMap<Int, Boolean>()
    private val idCounter = AtomicInteger(0)

    private class Result(val status: Int, val body: String)

    @JavascriptInterface
    fun request(callId: String, urlStr: String, method: String, body: String?, headers: String?) {
        val id = idCounter.incrementAndGet()

        if (inflight.size >= MAX_CONCURRENT) {
            deliverResult(callId, 0, "")
            onLog("native fetch blocked: concurrent cap ($MAX_CONCURRENT) reached")
            return
        }
        inflight[id] = true

        Thread {
            try {
                val outcome = followVetted(
                    startUrl = urlStr,
                    method = method,
                    hasBody = body != null,
                    decide = { scheme, host ->
                        (networkPolicy.decideUrl(scheme, host) as? WallpaperNetworkPolicy.Verdict.Block)?.reason
                    },
                    resolve = { host -> InetAddress.getAllByName(host).toList() },
                    allowLan = { networkPolicy.lanAllowed },
                    onAllowedHost = { host -> bundleId?.let { ContactedDomains.log(it, host) } },
                    connect = { hop -> connectOnce(hop, body, headers) }
                )
                when (outcome) {
                    is FetchOutcome.Done -> deliverResult(callId, outcome.value.status, outcome.value.body)
                    is FetchOutcome.Rejected -> {
                        onLog("native fetch blocked: ${outcome.reason}")
                        deliverResult(callId, 0, "")
                    }
                }
            } catch (e: Exception) {
                onLog("native fetch error: ${e.message}")
                deliverResult(callId, 0, "")
            } finally {
                inflight.remove(id)
            }
        }.start()
    }

    /** One request with redirects off; a 3xx with a Location comes back as [Hop.Redirect]. */
    private fun connectOnce(hop: HopRequest, body: String?, headers: String?): Hop<Result> {
        val conn = hop.uri.toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = hop.method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false

            if (headers != null) {
                val hdrs = JSONObject(headers)
                hdrs.keys().forEach { key ->
                    val lower = key.lowercase()
                    if (lower.startsWith("content-length")) return@forEach
                    // Never forward credentials to a different host than the one they were meant for.
                    if (hop.crossHost && (lower == "authorization" || lower == "cookie")) return@forEach
                    conn.setRequestProperty(key, hdrs.getString(key))
                }
            }
            if (body != null && hop.sendBody) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val status = conn.responseCode
            if (status in REDIRECT_STATUSES) {
                val location = conn.getHeaderField("Location")
                if (location != null) return Hop.Redirect(status, location)
            }
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            return Hop.Done(Result(status, stream?.use { readCapped(it) } ?: ""))
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(stream: java.io.InputStream): String {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_RESPONSE_BYTES) {
                onLog("native fetch: response exceeded $MAX_RESPONSE_BYTES bytes; truncated")
                buf.write(chunk, 0, n.coerceAtMost(MAX_RESPONSE_BYTES - buf.size()))
                break
            }
            buf.write(chunk, 0, n)
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    private fun deliverResult(callId: String, status: Int, body: String) {
        val payload = JSONObject().apply {
            put("id", callId)
            put("status", status)
            put("body", body)
        }
        val js = "window.aetherDesk && window.aetherDesk._nativeFetchResponse($payload);"
        val wv = webViewRef.get()
        if (wv != null) {
            main.post { wv.evaluateJavascript(js, null) }
        }
    }

    private companion object {
        const val MAX_CONCURRENT = 8
        const val MAX_RESPONSE_BYTES = 5_000_000
        const val TIMEOUT_MS = 30_000
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}
