package com.aetherdesk.lumenfall.web

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.webkit.WebView
import com.aetherdesk.lumenfall.render.WallpaperRenderer

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
 * The WebView itself (sandbox, network policy, bridge shim) comes from
 * [BundleWebView] — the same construction the settings live preview uses.
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
    private val fpsCap: Int = BundleWebView.DEFAULT_FPS_CAP,
    private val networkBudgetPerMinute: Int? = null,
    private val blockExternalNetwork: Boolean = false,
    private val allowLan: Boolean = false,
    private val initiallySuspended: Boolean = false,
    /** Global Performance setting: whether hiding the wallpaper should pause it at all. */
    private val pauseWhenHidden: Boolean = true,
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
            fpsCap = fpsCap,
            networkBudgetPerMinute = networkBudgetPerMinute,
            blockExternalNetwork = blockExternalNetwork,
            allowLan = allowLan,
            initiallySuspended = initiallySuspended,
            pauseWhenHidden = pauseWhenHidden,
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

    private companion object {
        const val TAG = "LumenfallWebHost"
        const val VIRTUAL_DISPLAY_NAME = "lumenfall-wallpaper"
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
    private val networkBudgetPerMinute: Int?,
    private val blockExternalNetwork: Boolean,
    private val allowLan: Boolean,
    private val initiallySuspended: Boolean,
    private val pauseWhenHidden: Boolean,
    private val onHeartbeat: () -> Unit,
    private val onLog: (String) -> Unit
) : Presentation(outerContext, targetDisplay) {

    private var webView: WebView? = null
    private var pendingUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wv = BundleWebView.create(
            context = context, // presentation context -> renders on the virtual display
            initialProperties = initialProperties,
            fpsCap = fpsCap,
            networkBudgetPerMinute = networkBudgetPerMinute,
            blockExternalNetwork = blockExternalNetwork,
            allowLan = allowLan,
            initiallySuspended = initiallySuspended,
            onHeartbeat = onHeartbeat,
            onLog = onLog
        ).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(wv)
        webView = wv
        pendingUrl?.let(wv::loadUrl)
        if (!pauseWhenHidden) {
            // This engine never wants its timers paused on hide — hold a
            // permanent claim so a stale pause latch left by a prior engine
            // (WebViewTimers is process-shared) can't leave this WebView
            // silently frozen before the first onVisibilityChanged(true).
            WebViewTimers.setNeedsTimers(this, wv, true)
        }
    }

    fun load(url: String) {
        pendingUrl = url
        webView?.loadUrl(url)
    }

    /** Push one property change into the running bundle. */
    fun setProperty(key: String, value: Any?) {
        webView?.let { BundleWebView.pushProperty(it, key, value) }
    }

    /**
     * Maps wallpaper visibility to WebView lifecycle + a JS suspend/resume hook.
     * [pauseWhenHidden] (the "Pause when wallpaper is not visible" Performance
     * setting, default on — matching ÆtherDesk) gates only the suspend/resume
     * JS call and the timer latch; the underlying WebView lifecycle calls
     * (onResume/onPause) still track visibility regardless, since those are an
     * Android correctness requirement, not a soft preference.
     */
    fun setContentVisible(visible: Boolean) {
        val wv = webView ?: return
        if (visible) {
            wv.onResume()
            if (pauseWhenHidden) {
                WebViewTimers.setNeedsTimers(this, wv, true)
                wv.evaluateJavascript("window.aetherDesk && window.aetherDesk.system.resume();", null)
            }
        } else {
            wv.onPause()
            if (pauseWhenHidden) {
                wv.evaluateJavascript("window.aetherDesk && window.aetherDesk.system.suspend();", null)
                WebViewTimers.setNeedsTimers(this, wv, false)
            }
        }
    }

    override fun dismiss() {
        webView?.let {
            it.stopLoading()
            WebViewTimers.forget(this, it)
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.dismiss()
    }
}
