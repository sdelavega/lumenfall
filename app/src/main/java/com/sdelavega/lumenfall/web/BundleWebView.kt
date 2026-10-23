// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.content.Context
import android.graphics.Color
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sdelavega.lumenfall.bridge.AetherDeskBridge
import com.sdelavega.lumenfall.bridge.GeolocationBridge
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Builds the sandboxed WebView every bundle-rendering surface uses — the
 * wallpaper Presentation and the settings live preview. One construction path
 * means one place where the sandbox, network policy, and bridge shim are
 * defined; the two hosts can't drift apart.
 *
 * The WebView it returns:
 * - serves bundle content over the virtual `https` origin (`/assets/` from app
 *   assets, `/bundles/` from filesDir/bundles) with no filesystem reach;
 * - runs every non-local request through [WallpaperNetworkPolicy] (screening-
 *   derived budget plus the global Performance settings' blockExternalNetwork/
 *   allowLan) and refuses main-frame navigation off the bundle origin;
 * - injects the ÆtherDesk bridge (properties, fps-capped rAF, raw-IP WebSocket
 *   guard) at document-start where supported, post-load otherwise;
 * - reports renderer-process death and main-frame load failures instead of
 *   silently going dark (or, for process death specifically, taking this
 *   app's whole process down with it — see [onRenderProcessGone] below).
 *   Callers that want recovery (retry, fallback content) wire up the hooks;
 *   this factory only reports, it doesn't decide policy.
 */
object BundleWebView {

    // Virtual origin the WebViewAssetLoader serves bundles from; also the
    // origin the document-start bridge script is scoped to.
    const val APP_HOST = "appassets.androidplatform.net"
    const val APP_ORIGIN = "https://$APP_HOST"

    // Same practical limits as ÆtherDesk (Constants.Defaults).
    const val DEFAULT_FPS_CAP = 30
    const val MIN_FPS = 15
    const val MAX_FPS = 60
    const val DEBOUNCE_MS = 50L

    fun create(
        context: Context,
        initialProperties: String = "{}",
        fpsCap: Int = DEFAULT_FPS_CAP,
        networkBudgetPerMinute: Int? = null,
        blockExternalNetwork: Boolean = false,
        allowLan: Boolean = false,
        initiallySuspended: Boolean = false,
        /** When set, allowed external requests are recorded in [ContactedDomains]. */
        bundleId: String? = null,
        /**
         * Initial `window.aetherDesk.geolocation` permission for this bundle.
         * The native bridge stays registered so callers can flip the live gate
         * with [setGeolocationEnabled] without reloading the page.
         */
        allowGeolocation: Boolean = false,
        onHeartbeat: () -> Unit = {},
        onLog: (String) -> Unit = {},
        onPageLoaded: () -> Unit = {},
        onRenderProcessGone: () -> Unit = {},
        onMainFrameLoadFailed: () -> Unit = {}
    ): WebView {
        val bundlesDir = File(context.filesDir, "bundles").apply { mkdirs() }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/bundles/", WebViewAssetLoader.InternalStoragePathHandler(context, bundlesDir))
            .build()
        val networkPolicy = WallpaperNetworkPolicy(
            budgetPerMinute = networkBudgetPerMinute,
            blockAllExternal = blockExternalNetwork,
            allowLan = allowLan
        )

        val shim = ShimSource.compose(
            ShimSource.load(context.assets),
            initialProperties,
            fpsCap.coerceIn(MIN_FPS, MAX_FPS),
            initiallySuspended
        )
        val docStartSupported =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        val wv = WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                // Sandbox third-party bundles: no filesystem or content-provider
                // reach. Bundles load over the asset loader's https origin.
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
            }
            addJavascriptInterface(
                AetherDeskBridge(onHeartbeat = onHeartbeat, onLog = onLog),
                "aetherDeskNative"
            )
            // Geolocation: always register the native surface, but keep a
            // mutable policy gate inside it. That lets the per-bundle toggle
            // apply immediately while still rejecting disabled bundles at the
            // native boundary.
            val geoBridge = GeolocationBridge(
                webView = this,
                provider = GeolocationProvider(context.applicationContext),
                onLog = onLog,
                initiallyEnabled = allowGeolocation
            )
            addJavascriptInterface(geoBridge, "aetherDeskGeo")
            geoBridges[this] = geoBridge
            // WebSocket DNS-rebinding guard: always registered for every web
            // bundle. Android's shouldInterceptRequest can't reach WebSocket
            // connections, so without this a hostname resolving to a private/
            // metadata IP would bypass the network policy entirely.
            addJavascriptInterface(WebSocketGuard(this), "aetherDeskWebSocket")
            // Native fetch proxy: routes external fetch()/XHR through native
            // HttpURLConnection, bypassing the WebView's CORS enforcement so
            // bundles can reach external APIs (weather, geocoding). Same
            // network policy + budget applies. Always registered (it's a policy
            // enforcement point, not a user preference); the shim only routes
            // external http(s) through it.
            addJavascriptInterface(
                NativeFetchBridge(this, networkPolicy, onLog),
                "aetherDeskFetch"
            )
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    // Local bundle content: served by the asset loader, never
                    // filtered. Everything else runs the network policy
                    // (blocklist + SSRF guard + optional per-minute budget).
                    if (request.url.host == APP_HOST) {
                        return assetLoader.shouldInterceptRequest(request.url)
                    }
                    val verdict = networkPolicy.decide(request.url)
                    if (verdict is WallpaperNetworkPolicy.Verdict.Block) {
                        onLog("blocked ${request.url.host}: ${verdict.reason}")
                        return WebResourceResponse(
                            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    // Transparency ledger: only requests that are actually
                    // going out. Blocked ones never happened, and are already
                    // surfaced through onLog above.
                    if (bundleId != null) {
                        request.url.host?.let { ContactedDomains.log(bundleId, it) }
                    }
                    return null
                }

                // A wallpaper never navigates: only same-origin (re)loads of
                // bundle content may proceed as the main frame.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = (request.url.host != APP_HOST).also { blocked ->
                    if (blocked) onLog("blocked navigation to ${request.url}")
                }

                // Android fires onPageFinished even for a navigation that just
                // failed — the WebView "finishes" rendering its internal error
                // page. Reporting that as a successful load would hand callers
                // fake liveness right after they were told the load failed
                // (poking the wallpaper host's watchdog, or scheduling a
                // thumbnail capture of the error page). Consume one finish per
                // main-frame error instead.
                private var mainFrameErrored = false

                override fun onPageFinished(view: WebView, url: String) {
                    if (!docStartSupported) view.evaluateJavascript(shim, null)
                    if (mainFrameErrored) {
                        mainFrameErrored = false
                        return
                    }
                    onPageLoaded()
                }

                // The renderer sandbox process died (crashed, or killed by the
                // system under memory pressure) — the WebView instance is now
                // unusable. Returning true tells Android we've handled it;
                // returning false (the default if unoverridden) kills this
                // app's whole process, which for a live wallpaper with no
                // crash UI is a much worse failure than a caller-managed
                // recovery. The caller must not reuse this WebView afterward.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    onLog("renderer process gone (crashed=${detail.didCrash()})")
                    onRenderProcessGone()
                    return true
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        mainFrameErrored = true
                        onLog("main-frame load error: ${error.description} (${error.errorCode})")
                        onMainFrameLoadFailed()
                    }
                }
            }
            networkPolicies[this] = networkPolicy
        }
        if (docStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(wv, shim, setOf(APP_ORIGIN))
        }
        return wv
    }

    /** Push one live property change into a running bundle's bridge. */
    fun pushProperty(webView: WebView, key: String, value: Any?) {
        val js = "window.aetherDesk && window.aetherDesk._setProperty(" +
            "${JSONObject.quote(key)}, ${jsLiteral(value)});"
        webView.evaluateJavascript(js, null)
    }

    /** Apply live global Performance settings to an already-running WebView. */
    fun applyPerformance(
        webView: WebView,
        fpsCap: Int,
        blockExternalNetwork: Boolean,
        allowLan: Boolean
    ) {
        networkPolicies[webView]?.update(
            blockAllExternal = blockExternalNetwork,
            allowLan = allowLan
        )
        val cap = fpsCap.coerceIn(MIN_FPS, MAX_FPS)
        webView.evaluateJavascript(
            "window.aetherDesk && (window.aetherDesk.system.fpsCap = $cap);",
            null
        )
    }

    /** Apply the effective live geolocation policy for this WebView. */
    fun setGeolocationEnabled(webView: WebView, enabled: Boolean) {
        geoBridges[webView]?.setEnabled(enabled)
    }

    // --- Debounced property push ---------------------------------------------
    // A slider drag fires PropertyBus.notify once per step — up to 60/s. Each
    // notify would otherwise trigger a separate evaluateJavascript call.
    // Debounce accumulates pending updates and flushes them in one JS eval
    // after a short idle window (ported from ÆtherDesk's PropertyBridge, which
    // uses the same pattern at 50ms).

    private data class DebounceState(
        val pending: MutableMap<String, Any?> = mutableMapOf(),
        var flushRunnable: Runnable? = null
    )

    private val debounceStates = java.util.WeakHashMap<WebView, DebounceState>()
    private val networkPolicies = java.util.WeakHashMap<WebView, WallpaperNetworkPolicy>()
    private val geoBridges = java.util.WeakHashMap<WebView, GeolocationBridge>()
    private val debounceHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Push a property change with a short debounce. Multiple changes within
     * [DEBOUNCE_MS] are batched into a single [pushProperty] call. Use this for
     * high-frequency live updates (slider drags); use [pushProperty] directly
     * for one-shot changes where the debounce delay would be noticeable.
     */
    fun pushPropertyDebounced(webView: WebView, key: String, value: Any?) {
        val state = debounceStates.getOrPut(webView) { DebounceState() }
        state.pending[key] = value
        state.flushRunnable?.let { debounceHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val snapshot = state.pending.toMap()
            state.pending.clear()
            state.flushRunnable = null
            if (snapshot.isNotEmpty()) flushProperties(webView, snapshot)
        }
        state.flushRunnable = runnable
        debounceHandler.postDelayed(runnable, DEBOUNCE_MS)
    }

    /** Flush any pending debounced updates immediately (before teardown/pause). */
    fun flushPendingProperties(webView: WebView) {
        val state = debounceStates[webView] ?: return
        state.flushRunnable?.let { debounceHandler.removeCallbacks(it) }
        state.flushRunnable = null
        val snapshot = state.pending.toMap()
        state.pending.clear()
        if (snapshot.isNotEmpty()) flushProperties(webView, snapshot)
    }

    private fun flushProperties(webView: WebView, updates: Map<String, Any?>) {
        if (updates.isEmpty()) return
        val sb = StringBuilder("(function(){")
        updates.forEach { (key, value) ->
            sb.append("window.aetherDesk && window.aetherDesk._setProperty(")
            sb.append(JSONObject.quote(key)).append(", ").append(jsLiteral(value))
            sb.append(");")
        }
        sb.append("})();")
        webView.evaluateJavascript(sb.toString(), null)
    }

    /** A JS literal for a property value pushed from native. */
    private fun jsLiteral(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Boolean, is Int, is Long, is Double, is Float -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
