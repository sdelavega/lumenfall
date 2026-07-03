package com.aetherdesk.lumenfall.web

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
import com.aetherdesk.lumenfall.bridge.AetherDeskBridge
import com.aetherdesk.lumenfall.bridge.GeolocationBridge
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
         * Wire the `window.aetherDesk.geolocation` bridge for this bundle. Must
         * already have passed [GeolocationPolicy] at render time — this factory
         * trusts the caller's snapshot and only re-checks the system permission
         * per call (inside [GeolocationProvider]), since a wallpaper runs in the
         * background and can't react to a mid-session revoke with a prompt.
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

        val shim = buildShim(initialProperties, fpsCap.coerceIn(MIN_FPS, MAX_FPS), initiallySuspended)
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
            // Geolocation: only register the native surface when the bundle
            // passed the screening + per-bundle-toggle + permission gate (see
            // GeolocationPolicy). A bundle that didn't never sees
            // `window.aetherDeskGeo`, and the shim's promise rejects 'disabled'
            // — the surface isn't there to call.
            if (allowGeolocation) {
                addJavascriptInterface(
                    GeolocationBridge(
                        webView = this,
                        provider = GeolocationProvider(context.applicationContext),
                        onLog = onLog
                    ),
                    "aetherDeskGeo"
                )
            }
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
    private fun buildShim(propertiesJson: String, fpsCap: Int, initiallySuspended: Boolean): String = """
        (function () {
          if (window.aetherDesk) return;
          var native = window.aetherDeskNative;
          var suspended = $initiallySuspended;
          window.aetherDesk = {
            properties: $propertiesJson,
            system: {
              fpsCap: $fpsCap,
              suspend: function () { suspended = true; },
              resume: function () {
                suspended = false;
                // A frame dropped while suspended broke the bundle's rAF chain
                // (its callback never ran, so it never re-scheduled). Replay the
                // parked callback so animation resumes.
                if (_parked) { var p = _parked; _parked = null; window.requestAnimationFrame(p); }
              },
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

          // Liveness + performance telemetry: report how many rAF frames were
          // delivered since start, every 2 s — frequent enough for
          // PerformanceGovernor to compute an achieved-fps window and react
          // within a few seconds. Also proves the frame loop isn't stalled
          // (setInterval proves timers tick; _frames proves rAF dispatches).
          var _frames = 0;
          setInterval(function () {
            if (native) native.postMessage('{"tick":true,"frames":' + _frames + '}');
          }, 2000);

          var _origRAF = window.requestAnimationFrame.bind(window);
          var _lastFrame = 0;
          var _parked = null;
          window.requestAnimationFrame = function (cb) {
            var cap = window.aetherDesk.system.fpsCap || $fpsCap;
            var interval = 1000.0 / cap;
            // Pace with performance.now() ONLY. The rAF callback timestamp can
            // sit on a different timebase than performance.now() in some WebView
            // hosts (seen on an Activity WebView, Android 17 beta); mixing the
            // two poisons `elapsed` and parks the chain in a years-long
            // setTimeout. Clamp so the wait is never negative or > one interval.
            var delay = interval - (performance.now() - _lastFrame);
            if (!(delay > 0)) delay = 0;
            if (delay > interval) delay = interval;
            function fire() {
              _origRAF(function (ts) {
                if (suspended) { _parked = cb; return; }
                _lastFrame = performance.now();
                _frames++;
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

          // --- Geolocation ----------------------------------------------------
          // Promise-based; resolves/rejects via the native aetherDeskGeo
          // surface (registered only when GeolocationPolicy passed). When that
          // surface isn't present (bundle not opted in, or screening-gated),
          // getCurrentPosition rejects 'disabled' — a consistent API that just
          // doesn't work, rather than `undefined`.
          //
          // _geoResolve/_geoReject are hoisted locals so the disabled-path
          // call below can reference them bare (a bare reference to
          // `window.aetherDesk._geoReject` would throw a ReferenceError inside
          // the Promise executor, rejecting with an Error object that
          // JSON.stringifies to {} — hiding the code/message). They're also
          // exposed on window.aetherDesk so the native bridge can call back in
          // via evaluateJavascript.
          var _geoSeq = 0;
          var _geoPending = {};
          function _geoResolve(callId, json) {
            var p = _geoPending[callId]; if (!p) return;
            delete _geoPending[callId];
            try { p.resolve(JSON.parse(json)); }
            catch (e) { p.reject({ code: 'unavailable', message: 'bad payload' }); }
          }
          function _geoReject(callId, code, message) {
            var p = _geoPending[callId]; if (!p) return;
            delete _geoPending[callId];
            p.reject({ code: code, message: message });
          }
          window.aetherDesk.geolocation = {
            getCurrentPosition: function (opts) {
              opts = opts || {};
              var callId = ++_geoSeq;
              return new Promise(function (resolve, reject) {
                _geoPending[callId] = { resolve: resolve, reject: reject };
                var native = window.aetherDeskGeo;
                if (!native) {
                  _geoReject(callId, 'disabled', 'geolocation not enabled for this bundle');
                  return;
                }
                try {
                  native.request(
                    String(callId),
                    !!opts.enableHighAccuracy,
                    (opts.timeoutMs | 0) || 10000
                  );
                } catch (e) {
                  _geoReject(callId, 'unavailable', String(e && e.message || e));
                }
              });
            }
          };
          window.aetherDesk._geoResolve = _geoResolve;
          window.aetherDesk._geoReject = _geoReject;

          window.aetherDesk.system.heartbeat();
        })();
    """
}
