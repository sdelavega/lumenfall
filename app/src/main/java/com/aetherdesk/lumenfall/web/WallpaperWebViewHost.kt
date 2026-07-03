package com.aetherdesk.lumenfall.web

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aetherdesk.lumenfall.bridge.AetherDeskBridge
import com.aetherdesk.lumenfall.render.WallpaperRenderer
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Hosts a [WebView] on a wallpaper [android.view.Surface] using a
 * [VirtualDisplay] + [Presentation].
 *
 * Why this approach: a [android.service.wallpaper.WallpaperService.Engine] hands
 * you a bare `Surface`, not a window or a `ViewGroup`, so a `WebView` (which is a
 * `View` and needs a real, attached window to render) has nowhere to live.
 * Backing a `VirtualDisplay` with the wallpaper surface and showing a
 * `Presentation` on that display gives the WebView a genuine hardware-accelerated
 * window. See docs/spikes/webview-wallpaper-surface.md for the full rationale.
 *
 * [initialProperties] is a JSON object of effective property values, baked into
 * the bridge at document-start so a bundle reads its config before its first
 * script runs. [setProperty] pushes a live change into the running bundle.
 *
 * All calls must happen on the main thread (Engine callbacks already are).
 */
class WallpaperWebViewHost(
    private val context: Context,
    private val initialProperties: String = "{}",
    private val fpsCap: Int = DEFAULT_FPS_CAP,
    private val networkBudgetPerMinute: Int? = null,
    private val onHeartbeat: () -> Unit = {},
    private val onLog: (String) -> Unit = {}
) : WallpaperRenderer {
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val densityDpi = context.resources.displayMetrics.densityDpi

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: WallpaperPresentation? = null
    private var pendingUrl: String? = null

    /** Queue (or immediately load) the bundle entry point. */
    fun load(url: String) {
        pendingUrl = url
        presentation?.load(url)
    }

    /** Push a live property change into the running bundle's bridge. */
    fun setProperty(key: String, value: Any?) {
        presentation?.setProperty(key, value)
    }

    override fun onSurface(holder: SurfaceHolder, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val existing = virtualDisplay
        if (existing == null) {
            attach(holder, width, height)
        } else {
            existing.resize(width, height, densityDpi)
        }
    }

    private fun attach(holder: SurfaceHolder, width: Int, height: Int) {
        detach() // idempotent guard against a leaked previous surface

        val vd = displayManager.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
            holder.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        if (vd == null) {
            onLog("createVirtualDisplay returned null")
            return
        }
        virtualDisplay = vd

        val pres = WallpaperPresentation(
            outerContext = context,
            targetDisplay = vd.display,
            initialProperties = initialProperties,
            fpsCap = fpsCap.coerceIn(MIN_FPS, MAX_FPS),
            networkBudgetPerMinute = networkBudgetPerMinute,
            onHeartbeat = onHeartbeat,
            onLog = onLog
        )
        pendingUrl?.let(pres::load)
        try {
            pres.show()
        } catch (t: Throwable) {
            // Some OEMs refuse a Presentation from a non-activity context; log so
            // it surfaces instead of failing silently.
            Log.e(TAG, "Presentation.show() failed", t)
            onLog("presentation show failed: ${t.message}")
            pres.dismiss()
            vd.release()
            virtualDisplay = null
            return
        }
        presentation = pres
    }

    override fun setVisible(visible: Boolean) {
        presentation?.setContentVisible(visible)
    }

    override fun onSurfaceDestroyed() = detach()

    /** Tear down the presentation + virtual display for the current surface. */
    fun detach() {
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    override fun release() {
        detach()
        pendingUrl = null
    }

    companion object {
        private const val TAG = "LumenfallWebHost"
        private const val VIRTUAL_DISPLAY_NAME = "lumenfall-wallpaper"

        // Same practical limits as ÆtherDesk (Constants.Defaults).
        const val DEFAULT_FPS_CAP = 30
        const val MIN_FPS = 15
        const val MAX_FPS = 60
    }
}

/**
 * The [Presentation] that actually owns the [WebView]. The WebView is created
 * with the presentation's display context so its rendering lands on the virtual
 * display (= the wallpaper surface).
 */
private class WallpaperPresentation(
    outerContext: Context,
    targetDisplay: Display,
    private val initialProperties: String,
    private val fpsCap: Int,
    networkBudgetPerMinute: Int?,
    private val onHeartbeat: () -> Unit,
    private val onLog: (String) -> Unit
) : Presentation(outerContext, targetDisplay) {

    private val networkPolicy = WallpaperNetworkPolicy(networkBudgetPerMinute)

    private var webView: WebView? = null
    private var pendingUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Serve content over a virtual https origin instead of file://, so
        // untrusted bundle code runs under a normal web origin with no filesystem
        // reach. /assets/ -> baked-in app assets (the spike); /bundles/ -> imported
        // bundles under filesDir/bundles (InternalStoragePathHandler blocks any
        // path traversal outside that dir).
        val bundlesDir = File(context.filesDir, "bundles").apply { mkdirs() }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/bundles/", WebViewAssetLoader.InternalStoragePathHandler(context, bundlesDir))
            .build()

        // Inject the bridge at document-start when supported, so window.aetherDesk
        // (and its baked-in property values) exist before any bundle script runs.
        // DOCUMENT_START_SCRIPT is a runtime WebView feature, so keep a post-load
        // fallback for WebViews that lack it.
        val shim = buildShim(initialProperties, fpsCap)
        val docStartSupported =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        val wv = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
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

                override fun onPageFinished(view: WebView, url: String) {
                    if (!docStartSupported) view.evaluateJavascript(shim, null)
                }
            }
        }
        if (docStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(wv, shim, setOf(APP_ORIGIN))
        }
        setContentView(wv)
        webView = wv
        pendingUrl?.let(wv::loadUrl)
    }

    fun load(url: String) {
        pendingUrl = url
        webView?.loadUrl(url)
    }

    /** Push one property change into the running bundle. */
    fun setProperty(key: String, value: Any?) {
        val js = "window.aetherDesk && window.aetherDesk._setProperty(" +
            "${JSONObject.quote(key)}, ${jsLiteral(value)});"
        webView?.evaluateJavascript(js, null)
    }

    /** Maps wallpaper visibility to WebView lifecycle + a JS suspend/resume hook. */
    fun setContentVisible(visible: Boolean) {
        val wv = webView ?: return
        if (visible) {
            wv.onResume()
            wv.resumeTimers()
            wv.evaluateJavascript("window.aetherDesk && window.aetherDesk.system.resume();", null)
        } else {
            wv.evaluateJavascript("window.aetherDesk && window.aetherDesk.system.suspend();", null)
            wv.onPause()
            wv.pauseTimers() // process-global, fine while this is the only WebView
        }
    }

    override fun dismiss() {
        webView?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.dismiss()
    }

    private companion object {
        // Virtual origin the WebViewAssetLoader serves bundles from; also the
        // origin the document-start bridge script is scoped to.
        const val APP_HOST = "appassets.androidplatform.net"
        const val APP_ORIGIN = "https://$APP_HOST"

        /** A JS literal for a property value pushed from native. */
        fun jsLiteral(value: Any?): String = when (value) {
            null -> "null"
            is String -> JSONObject.quote(value)
            is Boolean, is Int, is Long, is Double, is Float -> value.toString()
            else -> JSONObject.quote(value.toString())
        }

        /**
         * The ÆtherDesk-compatible bridge. [propertiesJson] is baked in as the
         * initial `properties` map. `_setProperty` is called from native on a live
         * change: it updates `properties`, fires `onPropertyChange`, and also calls
         * Lively's `livelyPropertyListener` when a bundle defines one (so real
         * Lively wallpapers react too).
         *
         * Practical limits (ported from ÆtherDesk's runtime shim):
         * - requestAnimationFrame is throttled to `system.fpsCap` using setTimeout
         *   between frames, so the JS thread sleeps most of each interval instead
         *   of waking at every display vsync (60–120 Hz on this hardware);
         * - frames are dropped entirely while the wallpaper is hidden (suspend);
         * - WebSocket connections to raw IPv4/IPv6 literals are refused (FQDN
         *   sockets stay allowed) — sockets bypass shouldInterceptRequest, so this
         *   is the only hook Android gives us.
         */
        fun buildShim(propertiesJson: String, fpsCap: Int): String = """
            (function () {
              if (window.aetherDesk) return;
              var native = window.aetherDeskNative;
              var suspended = false;
              window.aetherDesk = {
                properties: $propertiesJson,
                system: {
                  fpsCap: $fpsCap,
                  suspend: function () { suspended = true; },
                  resume: function () { suspended = false; },
                  heartbeat: function () {
                    if (native) native.postMessage('{"heartbeat":true}');
                  }
                },
                display: {
                  width: window.innerWidth,
                  height: window.innerHeight,
                  dpr: window.devicePixelRatio || 1
                },
                postMessage: function (m) {
                  if (native) native.postMessage(typeof m === 'string' ? m : JSON.stringify(m));
                },
                onPropertyChange: function () {},
                _setProperty: function (key, value) {
                  this.properties[key] = value;
                  try { this.onPropertyChange(key, value); } catch (e) {}
                  if (typeof window.livelyPropertyListener === 'function') {
                    try { window.livelyPropertyListener(key, value); } catch (e) {}
                  }
                }
              };

              var _origRAF = window.requestAnimationFrame.bind(window);
              var _lastFrame = 0;
              window.requestAnimationFrame = function (cb) {
                var cap = window.aetherDesk.system.fpsCap || $fpsCap;
                var interval = 1000.0 / cap;
                var elapsed = performance.now() - _lastFrame;
                var delay = interval - elapsed;
                function fire() {
                  _origRAF(function (ts) {
                    if (suspended) return;
                    _lastFrame = ts;
                    cb(ts);
                  });
                }
                if (delay <= 4) { fire(); return 0; }
                setTimeout(fire, delay - 4);
                return 0;
              };

              var _OrigWS = window.WebSocket;
              if (typeof _OrigWS === 'function') {
                var RAW_IP = /^wss?:\/\/(\[|\d{1,3}(\.\d{1,3}){3}([\/:]|${'$'}))/i;
                window.WebSocket = function (url, protocols) {
                  if (RAW_IP.test(String(url))) {
                    throw new DOMException('WebSocket to raw IP blocked', 'SecurityError');
                  }
                  return protocols === undefined ? new _OrigWS(url) : new _OrigWS(url, protocols);
                };
                window.WebSocket.prototype = _OrigWS.prototype;
                ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (k) {
                  window.WebSocket[k] = _OrigWS[k];
                });
              }

              window.aetherDesk.system.heartbeat();
            })();
        """
    }
}
