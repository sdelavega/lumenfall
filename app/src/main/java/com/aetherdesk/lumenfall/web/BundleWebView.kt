package com.aetherdesk.lumenfall.web

import android.content.Context
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aetherdesk.lumenfall.bridge.AetherDeskBridge
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
 * - runs every non-local request through [WallpaperNetworkPolicy] and refuses
 *   main-frame navigation off the bundle origin;
 * - injects the ÆtherDesk bridge (properties, fps-capped rAF, raw-IP WebSocket
 *   guard) at document-start where supported, post-load otherwise.
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

    fun create(
        context: Context,
        initialProperties: String = "{}",
        fpsCap: Int = DEFAULT_FPS_CAP,
        networkBudgetPerMinute: Int? = null,
        onHeartbeat: () -> Unit = {},
        onLog: (String) -> Unit = {}
    ): WebView {
        val bundlesDir = File(context.filesDir, "bundles").apply { mkdirs() }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/bundles/", WebViewAssetLoader.InternalStoragePathHandler(context, bundlesDir))
            .build()
        val networkPolicy = WallpaperNetworkPolicy(networkBudgetPerMinute)

        val shim = buildShim(initialProperties, fpsCap.coerceIn(MIN_FPS, MAX_FPS))
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
        return wv
    }

    /** Push one live property change into a running bundle's bridge. */
    fun pushProperty(webView: WebView, key: String, value: Any?) {
        val js = "window.aetherDesk && window.aetherDesk._setProperty(" +
            "${JSONObject.quote(key)}, ${jsLiteral(value)});"
        webView.evaluateJavascript(js, null)
    }

    /** A JS literal for a property value pushed from native. */
    private fun jsLiteral(value: Any?): String = when (value) {
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
    private fun buildShim(propertiesJson: String, fpsCap: Int): String = """
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
