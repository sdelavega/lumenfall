package com.aetherdesk.lumenfall.bridge

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.aetherdesk.lumenfall.web.GeolocationProvider
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * The `window.aetherDeskGeo` native surface backing
 * `window.aetherDesk.geolocation.getCurrentPosition`. Registered on every
 * bundle WebView so the per-bundle toggle can change without a reload; calls
 * are still rejected unless [com.aetherdesk.lumenfall.web.GeolocationPolicy]
 * currently allows the bundle.
 *
 * Call flow: bundle JS calls `aetherDesk.geolocation.getCurrentPosition(opts)`
 * (a Promise wrapper in the shim), which assigns a numeric callId, parks the
 * `{resolve, reject}` pair in a map, and calls [request] with the callId +
 * options. Native fetches the fix via [GeolocationProvider] and resolves the
 * promise by `evaluateJavascript`-ing `window.aetherDesk._geoResolve(callId,json)`
 * (or `_geoReject`). Because `@JavascriptInterface` methods run on a private
 * WebView binder thread, the result is posted back to the main thread before
 * touching the WebView.
 *
 * Permission is re-checked at call time inside [GeolocationProvider] — a live
 * wallpaper runs in the background and can't host a permission dialog, so a
 * mid-session revoke must surface as a clean JS rejection, not a crash.
 */
class GeolocationBridge(
    webView: WebView,
    private val provider: GeolocationProvider,
    private val onLog: (String) -> Unit,
    initiallyEnabled: Boolean
) {
    private val webViewRef = WeakReference(webView)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var enabled = initiallyEnabled

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    @JavascriptInterface
    fun request(callId: String, enableHighAccuracy: Boolean, timeoutMs: Int) {
        if (!enabled) {
            reject(callId, "disabled", "geolocation is disabled for this bundle")
            return
        }
        val wv = webViewRef.get()
        if (wv == null) {
            reject(callId, "unavailable", "webview gone")
            return
        }
        val boundedTimeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        provider.current(enableHighAccuracy, boundedTimeout) { result ->
            when (result) {
                is GeolocationProvider.Result.Fix -> resolve(callId, result.json)
                is GeolocationProvider.Result.Denied ->
                    reject(callId, "denied", result.reason)
                is GeolocationProvider.Result.Unavailable ->
                    reject(callId, "unavailable", result.reason)
                is GeolocationProvider.Result.Timeout ->
                    reject(callId, "timeout", result.reason)
            }
        }
    }

    private fun resolve(callId: String, json: String) {
        // Pass the JSON as a quoted JS string so the shim's _geoResolve receives
        // a string and JSON.parse's it (its contract). Passing it raw would make
        // the arg a live JS object, JSON.parse would see "[object Object]", and
        // every fix would reject as "bad payload".
        val js = "window.aetherDesk && window.aetherDesk._geoResolve(" +
            "${JSONObject.quote(callId)}, ${JSONObject.quote(json)});"
        eval(js)
    }

    private fun reject(callId: String, code: String, message: String) {
        onLog("geolocation $callId -> $code: $message")
        val js = "window.aetherDesk && window.aetherDesk._geoReject(" +
            "${JSONObject.quote(callId)}, ${JSONObject.quote(code)}, ${JSONObject.quote(message)});"
        eval(js)
    }

    private fun eval(js: String) {
        val wv = webViewRef.get() ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            wv.evaluateJavascript(js, null)
        } else {
            main.post { wv.evaluateJavascript(js, null) }
        }
    }

    private companion object {
        const val MIN_TIMEOUT_MS = 1000
        const val MAX_TIMEOUT_MS = 30_000
    }
}
