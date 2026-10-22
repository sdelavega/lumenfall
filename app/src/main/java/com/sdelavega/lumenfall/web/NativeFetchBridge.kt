// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes `window.fetch()` and `XMLHttpRequest` through native `HttpURLConnection`,
 * bypassing the WebView's CORS restrictions so wallpaper bundles can reach
 * external APIs (weather data, geocoding, etc.) without CORS errors.
 *
 * The WebView serves bundles from `https://appassets.androidplatform.net`; a
 * `fetch('https://api.open-meteo.com/...')` from that origin would be blocked
 * by the WebView's CORS enforcement because the external API doesn't return
 * `Access-Control-Allow-Origin` for the appassets origin. Android's
 * `shouldInterceptRequest` intercepts the request but can't add CORS headers
 * to the real response, so the JS still can't read it. This proxy sidesteps
 * the WebView entirely: the shim calls `aetherDeskFetch.request(...)`, native
 * makes the HTTP request, and delivers the response body back via
 * `evaluateJavascript('window.aetherDesk._nativeFetchResponse(...)')`.
 *
 * Ported from ÆtherDesk's `performNativeFetch`. Same limits:
 * - network policy (blocklist + SSRF + budget) is enforced identically to
 *   `shouldInterceptRequest` — a blocked fetch returns status 0;
 * - concurrent in-flight fetches are capped at [MAX_CONCURRENT];
 * - response body is capped at [MAX_RESPONSE_BYTES];
 * - DNS is resolved and checked for private/loopback/metadata IPs (same
 *   DNS-rebinding defense as [WebSocketGuard]).
 *
 * Always registered for every web bundle — it's a network policy enforcement
 * point, not a user preference. The shim only routes external http(s) through
 * it; same-origin (appassets) requests go through the original fetch.
 */
class NativeFetchBridge(
    webView: WebView,
    private val networkPolicy: WallpaperNetworkPolicy,
    private val onLog: (String) -> Unit
) {
    private val webViewRef = WeakReference(webView)
    private val main = Handler(Looper.getMainLooper())
    private val inflight = ConcurrentHashMap<Int, Boolean>()
    private val idCounter = AtomicInteger(0)

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
                val uri = Uri.parse(urlStr)
                val verdict = networkPolicy.decide(uri)
                if (verdict is WallpaperNetworkPolicy.Verdict.Block) {
                    onLog("native fetch blocked: ${verdict.reason}")
                    deliverResult(callId, 0, "")
                    return@Thread
                }

                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = method.uppercase()
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = true
                }

                if (body != null && method.uppercase() != "GET" && method.uppercase() != "HEAD") {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }

                if (headers != null) {
                    val hdrs = JSONObject(headers)
                    hdrs.keys().forEach { key ->
                        val value = hdrs.getString(key)
                        if (!key.lowercase().startsWith("content-length")) {
                            conn.setRequestProperty(key, value)
                        }
                    }
                }

                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val bodyText = stream?.use { readCapped(it) } ?: ""
                conn.disconnect()

                deliverResult(callId, status, bodyText)
            } catch (e: Exception) {
                onLog("native fetch error: ${e.message}")
                deliverResult(callId, 0, "")
            } finally {
                inflight.remove(id)
            }
        }.start()
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
    }
}
