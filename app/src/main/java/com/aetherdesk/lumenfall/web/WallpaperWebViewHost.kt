package com.aetherdesk.lumenfall.web

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.webkit.WebView
import com.aetherdesk.lumenfall.render.WallpaperRenderer
import com.aetherdesk.lumenfall.storage.PerformanceSettings

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
 * Failure handling (ported from ÆtherDesk's WatchdogTimer): a bundle that hangs
 * ([WallpaperWatchdog] — no bridge tick for 30s), whose renderer process dies,
 * or whose main frame fails to load twice (one retry) is a one-way trip to a
 * static fallback color — a live wallpaper has no crash UI, so silently
 * demoting beats leaving a frozen or black surface forever, and re-attempting
 * a bundle that already hung risks hanging again the same way. A fresh
 * attempt only happens when this host is recreated (wallpaper reselected, or
 * the engine is torn down and rebuilt, which already happens somewhat often —
 * see the settings-Activity note elsewhere in this file's callers).
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
    /** When set, allowed external requests are recorded in [ContactedDomains]. */
    private val bundleId: String? = null,
    /** Wire the geolocation bridge for this bundle (see [GeolocationPolicy]). */
    private val allowGeolocation: Boolean = false,
    private val onHeartbeat: () -> Unit = {},
    private val onLog: (String) -> Unit = {},
    private val onUnrecoverable: () -> Unit = {}
) : WallpaperRenderer {
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val densityDpi = context.resources.displayMetrics.densityDpi
    private val mainHandler = Handler(Looper.getMainLooper())

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: WallpaperPresentation? = null
    private var pendingUrl: String? = null
    private var lastSurfaceHolder: SurfaceHolder? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var failed = false
    private var contentVisible = false
    private var pauseWhenHiddenLive = pauseWhenHidden
    private var policySuspended = initiallySuspended

    /**
     * Deep-sleep: after the wallpaper has been hidden for an extended period,
     * capture the last frame, tear down the VirtualDisplay + WebView (freeing
     * its renderer process and display memory), and draw the frozen frame to
     * the surface. On resume, everything is recreated. Ported from ÆtherDesk's
     * paused-snapshot freeze; the Android equivalent of WKWebView.takeSnapshot
     * + tearDown is PixelCopy + VirtualDisplay release.
     */
    private var deepSleeping = false
    private val deepSleepRunnable = Runnable { enterDeepSleep() }

    /** Queue (or immediately load) the bundle entry point. */
    fun load(url: String) {
        pendingUrl = url
        presentation?.load(url)
    }

    /** Push a live property change into the running bundle's bridge. */
    fun setProperty(key: String, value: Any?) {
        presentation?.setProperty(key, value)
    }

    /** Apply global Performance preferences to the active WebView without reloading the page. */
    fun applyPerformance(settings: PerformanceSettings, screeningFpsCap: Int) {
        val resolved = PerformancePolicy.resolve(context, settings, screeningFpsCap)
        pauseWhenHiddenLive = settings.pauseWhenNotVisible
        policySuspended = resolved.initiallySuspended
        presentation?.applyPerformance(
            fpsCap = resolved.fpsCap,
            blockExternalNetwork = settings.blockExternalNetwork,
            allowLan = settings.allowLan,
            pauseWhenHidden = settings.pauseWhenNotVisible,
            policySuspended = resolved.initiallySuspended
        )
        mainHandler.removeCallbacks(deepSleepRunnable)
        if (pauseWhenHiddenLive && !policySuspended && !failed && !contentVisible) {
            mainHandler.postDelayed(deepSleepRunnable, DEEP_SLEEP_DELAY_MS)
        }
    }

    fun setGeolocationEnabled(enabled: Boolean) {
        presentation?.setGeolocationEnabled(enabled)
    }

    override fun onTouchEvent(event: MotionEvent) {
        if (failed || deepSleeping) return
        presentation?.dispatchWallpaperTouch(event)
    }

    override fun onSurface(holder: SurfaceHolder, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        lastSurfaceHolder = holder
        lastWidth = width
        lastHeight = height
        if (failed) {
            renderFallback(holder)
            return
        }
        deepSleeping = false
        mainHandler.removeCallbacks(deepSleepRunnable)
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
            initiallySuspended = policySuspended,
            pauseWhenHidden = pauseWhenHiddenLive,
            bundleId = bundleId,
            allowGeolocation = allowGeolocation,
            onHeartbeat = onHeartbeat,
            onLog = onLog,
            onIrrecoverable = ::markFailed
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

    /** A bundle that's hung, crashed the renderer, or failed to load twice — demote for good. */
    private fun markFailed() {
        if (failed) return
        failed = true
        onLog("bundle unrecoverable; showing static fallback")
        detach()
        lastSurfaceHolder?.let(::renderFallback)
        onUnrecoverable()
    }

    /**
     * Right after [detach] releases the virtual display, its disconnect from
     * [holder]'s Surface is not synchronous — the producer handoff finishes
     * async on the system side, and a [SurfaceHolder.lockCanvas] issued
     * immediately after can return null (observed on-device: reproducibly
     * null on the very first attempt, every time). Retry briefly instead of
     * silently giving up, which previously left the surface showing stale
     * (black) content forever instead of the fallback color. A handful of
     * short retries comfortably covers the handoff; a surface that's still
     * refusing after that is genuinely gone (e.g. actually destroyed), and
     * onSurfaceDestroyed/a future onSurface will handle it correctly.
     */
    private fun renderFallback(holder: SurfaceHolder, attempt: Int = 0) {
        val frame = holder.surfaceFrame
        if (frame.width() <= 0 || frame.height() <= 0) return
        val canvas = runCatching { holder.lockCanvas() }.getOrNull()
        if (canvas == null) {
            if (attempt < MAX_LOCK_ATTEMPTS) {
                mainHandler.postDelayed({ renderFallback(holder, attempt + 1) }, LOCK_RETRY_DELAY_MS)
            } else {
                onLog("renderFallback: lockCanvas kept returning null after $MAX_LOCK_ATTEMPTS retries")
            }
            return
        }
        try {
            canvas.drawColor(FALLBACK_COLOR)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun setVisible(visible: Boolean) {
        contentVisible = visible
        if (visible) {
            mainHandler.removeCallbacks(deepSleepRunnable)
            if (deepSleeping) {
                deepSleeping = false
                val holder = lastSurfaceHolder
                if (holder != null && lastWidth > 0 && lastHeight > 0 && !failed) {
                    attach(holder, lastWidth, lastHeight)
                }
            }
            presentation?.setContentVisible(visible)
        } else {
            presentation?.setContentVisible(false)
            if (pauseWhenHiddenLive && !policySuspended && !failed) {
                mainHandler.postDelayed(deepSleepRunnable, DEEP_SLEEP_DELAY_MS)
            }
        }
    }

    /**
     * Capture the current frame from the wallpaper surface, tear down the
     * VirtualDisplay + WebView, and draw the frozen frame back. The WebView's
     * renderer process and display memory are released; on resume, [setVisible]
     * recreates everything from [pendingUrl].
     */
    private fun enterDeepSleep() {
        val holder = lastSurfaceHolder
        if (holder == null || virtualDisplay == null || failed) return
        val frame = holder.surfaceFrame
        val w = frame.width()
        val h = frame.height()
        if (w <= 0 || h <= 0) {
            detach()
            deepSleeping = true
            onLog("deep-sleep: surface has no size; tore down without snapshot")
            return
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(holder.surface, bitmap, { result ->
                detach()
                if (result == PixelCopy.SUCCESS) {
                    drawSnapshot(holder, bitmap)
                    onLog("deep-sleep: entered (snapshot captured)")
                } else {
                    renderFallback(holder)
                    onLog("deep-sleep: entered (PixelCopy failed: $result)")
                }
                deepSleeping = true
            }, mainHandler)
        } catch (e: Exception) {
            detach()
            renderFallback(holder)
            deepSleeping = true
            onLog("deep-sleep: entered (PixelCopy threw: ${e.message})")
        }
    }

    /** Draw a captured bitmap to the wallpaper surface, filling it. */
    private fun drawSnapshot(holder: SurfaceHolder, bitmap: Bitmap, attempt: Int = 0) {
        val frame = holder.surfaceFrame
        if (frame.width() <= 0 || frame.height() <= 0) return
        val canvas = runCatching { holder.lockCanvas() }.getOrNull()
        if (canvas == null) {
            if (attempt < MAX_LOCK_ATTEMPTS) {
                mainHandler.postDelayed({ drawSnapshot(holder, bitmap, attempt + 1) }, LOCK_RETRY_DELAY_MS)
            } else {
                onLog("deep-sleep: lockCanvas kept returning null; snapshot not drawn")
            }
            return
        }
        try {
            val scale = maxOf(
                frame.width().toFloat() / bitmap.width,
                frame.height().toFloat() / bitmap.height
            )
            val matrix = Matrix().apply { setScale(scale, scale) }
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, matrix, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun onSurfaceDestroyed() {
        mainHandler.removeCallbacks(deepSleepRunnable)
        deepSleeping = false
        detach()
    }

    /** Tear down the presentation + virtual display for the current surface. */
    fun detach() {
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    override fun release() {
        mainHandler.removeCallbacks(deepSleepRunnable)
        deepSleeping = false
        detach()
        pendingUrl = null
    }

    private companion object {
        const val TAG = "LumenfallWebHost"
        const val VIRTUAL_DISPLAY_NAME = "lumenfall-wallpaper"

        // Matches the glass UI's dark backdrop, so a demoted wallpaper reads
        // as "quiet," not as a broken/error surface.
        val FALLBACK_COLOR = Color.parseColor("#0B0F1A")

        // See renderFallback's kdoc: bridges the async virtual-display-release
        // handoff. 5 * 30ms is comfortably more than the handoff has ever
        // taken on-device (one retry has sufficed in testing).
        const val MAX_LOCK_ATTEMPTS = 5
        const val LOCK_RETRY_DELAY_MS = 30L

        // How long the wallpaper stays hidden before deep-sleep tears down
        // the VirtualDisplay + WebView. Long enough that brief app-switches
        // (notifications, quick settings) don't trigger it, short enough to
        // reclaim memory during genuine disuse.
        const val DEEP_SLEEP_DELAY_MS = 60_000L
    }
}

/**
 * The [Presentation] that actually owns the [WebView]. The WebView is created
 * with the presentation's display context so its rendering lands on the virtual
 * display (= the wallpaper surface).
 *
 * Owns the recovery policy for the three failure modes [BundleWebView] reports,
 * mirroring ÆtherDesk: a hang or a dead renderer process demote immediately
 * (no retry — re-showing a bundle that just hung risks hanging again the same
 * way); a main-frame load failure gets one retry, and only demotes if the
 * retry also fails.
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
    private var pauseWhenHidden: Boolean,
    private val bundleId: String?,
    private val allowGeolocation: Boolean,
    private val onHeartbeat: () -> Unit,
    private val onLog: (String) -> Unit,
    private val onIrrecoverable: () -> Unit
) : Presentation(outerContext, targetDisplay) {

    private val watchdog = WallpaperWatchdog(onHang = { handleFailure("no response for 30s") })

    private var webView: WebView? = null
    private var pendingUrl: String? = null
    private var navRetried = false
    private var failed = false
    private var contentVisible = true
    private var policySuspended = initiallySuspended

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
            bundleId = bundleId,
            allowGeolocation = allowGeolocation,
            onHeartbeat = { watchdog.poke(); onHeartbeat() },
            onLog = { message ->
                if (message.contains("\"tick\"")) watchdog.poke()
                onLog(message)
            },
            onPageLoaded = { watchdog.poke() },
            onRenderProcessGone = ::handleRenderProcessGone,
            onMainFrameLoadFailed = ::handleLoadFailure
        ).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(wv)
        webView = wv
        // Unconditionally claim "needs timers" the moment this WebView is
        // created — regardless of pauseWhenHidden. WebViewTimers is a single
        // process-shared latch: if the previous engine's teardown (a wallpaper
        // switch tears the old one down right as the new one spins up) left it
        // paused, a brand-new WebView created into that state runs its JS with
        // timers frozen from birth — no shim init, no ticks, nothing — and
        // just sits there until something notices. setContentVisible(false)
        // relinquishes this claim correctly once real visibility is known;
        // starting from "assume alive" is the safe default; starting from
        // "maybe already paused" is how a bundle silently never starts.
        WebViewTimers.setNeedsTimers(this, wv, true)
        pendingUrl?.let(wv::loadUrl)
        watchdog.start()
    }

    fun load(url: String) {
        pendingUrl = url
        webView?.loadUrl(url)
    }

    /** Push one property change into the running bundle (debounced). */
    fun setProperty(key: String, value: Any?) {
        webView?.let { BundleWebView.pushPropertyDebounced(it, key, value) }
    }

    fun setGeolocationEnabled(enabled: Boolean) {
        webView?.let { BundleWebView.setGeolocationEnabled(it, enabled) }
    }

    fun dispatchWallpaperTouch(event: MotionEvent) {
        val wv = webView ?: return
        val forwarded = MotionEvent.obtain(event)
        try {
            forwarded.setLocation(event.x.coerceIn(0f, wv.width.toFloat()), event.y.coerceIn(0f, wv.height.toFloat()))
            wv.dispatchTouchEvent(forwarded)
        } finally {
            forwarded.recycle()
        }
    }

    fun applyPerformance(
        fpsCap: Int,
        blockExternalNetwork: Boolean,
        allowLan: Boolean,
        pauseWhenHidden: Boolean,
        policySuspended: Boolean
    ) {
        this.pauseWhenHidden = pauseWhenHidden
        this.policySuspended = policySuspended
        webView?.let {
            BundleWebView.applyPerformance(it, fpsCap, blockExternalNetwork, allowLan)
            syncVisibilityState(it)
        }
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
        contentVisible = visible
        val wv = webView ?: return
        syncVisibilityState(wv)
    }

    private fun syncVisibilityState(wv: WebView) {
        val renderAllowed = !policySuspended && (contentVisible || !pauseWhenHidden)
        if (contentVisible && !policySuspended) {
            wv.onResume()
            if (renderAllowed) {
                WebViewTimers.setNeedsTimers(this, wv, true)
                wv.evaluateJavascript("window.aetherDesk && window.aetherDesk.system.resume();", null)
                // Re-arm with a fresh window (start() resets the last-seen
                // clock) so the bundle isn't judged on its paused past.
                if (!failed) watchdog.start()
            }
        } else {
            wv.onPause()
            if (!renderAllowed) {
                wv.evaluateJavascript("window.aetherDesk && window.aetherDesk.system.suspend();", null)
                WebViewTimers.setNeedsTimers(this, wv, false)
                // A paused bundle can't tick — silence while hidden isn't a
                // hang. Without this, any engine surviving covered for >30s
                // (OEM-dependent; they're often destroyed instead) would be
                // falsely demoted. Found by the reliability soak: a 19s
                // hidden-tick gap was innocent, but 30s wouldn't have been.
                watchdog.stop()
            } else {
                WebViewTimers.setNeedsTimers(this, wv, true)
                wv.evaluateJavascript("window.aetherDesk && window.aetherDesk.system.resume();", null)
            }
        }
    }

    /** The renderer sandbox process died; this WebView instance is unusable now. */
    private fun handleRenderProcessGone() {
        if (failed) return
        webView?.let {
            WebViewTimers.forget(this, it)
            (it.parent as? ViewGroup)?.removeView(it)
            runCatching { it.destroy() }
        }
        webView = null
        handleFailure("renderer process gone")
    }

    /**
     * One retry, then demote. A retry that *stalls* (never errors, never
     * finishes) is caught by the watchdog rather than a dedicated timer: the
     * watchdog already pauses correctly while the wallpaper is hidden, whereas
     * a plain countdown here would keep running against a bundle whose timers
     * pauseWhenHidden froze mid-retry and falsely demote it.
     */
    private fun handleLoadFailure() {
        if (failed) return
        if (navRetried) {
            handleFailure("main-frame load failed after retry")
            return
        }
        val url = pendingUrl
        if (url == null) {
            handleFailure("main-frame load failed, no URL to retry")
            return
        }
        navRetried = true
        onLog("main-frame load failed; retrying once")
        webView?.loadUrl(url)
    }

    private fun handleFailure(reason: String) {
        if (failed) return
        failed = true
        onLog("bundle unrecoverable ($reason)")
        watchdog.stop()
        onIrrecoverable()
    }

    override fun dismiss() {
        watchdog.stop()
        webView?.let {
            BundleWebView.flushPendingProperties(it)
            it.stopLoading()
            WebViewTimers.forget(this, it)
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.dismiss()
    }
}
