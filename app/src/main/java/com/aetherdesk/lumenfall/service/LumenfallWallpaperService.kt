package com.aetherdesk.lumenfall.service

import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.aetherdesk.lumenfall.image.ImageWallpaperRenderer
import com.aetherdesk.lumenfall.render.ActiveBundleBus
import com.aetherdesk.lumenfall.render.PropertyBus
import com.aetherdesk.lumenfall.render.WallpaperRenderer
import com.aetherdesk.lumenfall.storage.AppSettingsStore
import com.aetherdesk.lumenfall.storage.BundleMeta
import com.aetherdesk.lumenfall.storage.BundleStore
import com.aetherdesk.lumenfall.storage.BundledWallpapers
import com.aetherdesk.lumenfall.video.VideoWallpaperRenderer
import com.aetherdesk.lumenfall.web.BundleWebView
import com.aetherdesk.lumenfall.web.GeolocationPolicy
import com.aetherdesk.lumenfall.web.PerformancePolicy
import com.aetherdesk.lumenfall.web.WallpaperWebViewHost

class LumenfallWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = LumenfallEngine()

    private inner class LumenfallEngine : Engine() {
        // The renderer chosen for the active bundle's kind; null only if creation
        // failed. The Engine just forwards its lifecycle here.
        private var renderer: WallpaperRenderer? = null

        // Last-known surface + visibility, so an in-place bundle swap can
        // re-attach the fresh renderer without waiting for new callbacks.
        private var lastHolder: SurfaceHolder? = null
        private var lastWidth = 0
        private var lastHeight = 0
        private var lastVisible = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer = createRenderer()
            ActiveBundleBus.register(this, ::swapRenderer)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            lastHolder = holder
            lastWidth = width
            lastHeight = height
            renderer?.onSurface(holder, width, height)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            lastVisible = visible
            renderer?.setVisible(visible)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            lastHolder = null
            renderer?.onSurfaceDestroyed()
        }

        override fun onDestroy() {
            super.onDestroy()
            ActiveBundleBus.unregister(this)
            PropertyBus.unregister(this)
            renderer?.release()
            renderer = null
        }

        /**
         * The picker changed the active bundle while this engine is alive:
         * tear down the old renderer and stand up a fresh one for the new
         * bundle on the same surface. (When the engine was instead destroyed
         * under the picker Activity — the common case on this OEM — the next
         * engine creation reads the new active id anyway; this path covers
         * engines that survive, e.g. under multi-window or a floating picker.)
         */
        private fun swapRenderer() {
            Log.d(TAG, "active bundle changed; swapping renderer in place")
            PropertyBus.unregister(this) // webRenderer re-registers for the new bundle
            renderer?.release()
            renderer = createRenderer()
            val holder = lastHolder
            if (holder != null && lastWidth > 0 && lastHeight > 0) {
                renderer?.onSurface(holder, lastWidth, lastHeight)
                renderer?.setVisible(lastVisible)
            }
        }

        /** Pick a renderer for the active bundle's kind, falling back to the spike. */
        private fun createRenderer(): WallpaperRenderer {
            val service = this@LumenfallWallpaperService
            val store = BundleStore(service)
            val active = store.active()
            val entry = active?.entryPath
            return when {
                active != null && entry != null && active.kind == "video" -> {
                    Log.d(TAG, "video bundle ${active.id}: ${active.title}")
                    VideoWallpaperRenderer(service, store.fileFor(active.id, entry))
                }
                active != null && entry != null && active.kind == "image" -> {
                    Log.d(TAG, "image bundle ${active.id}: ${active.title}")
                    ImageWallpaperRenderer(store.fileFor(active.id, entry))
                }
                else -> webRenderer(active)
            }
        }

        /** Web bundle (or the baked-in spike when none is selected). */
        private fun webRenderer(active: BundleMeta?): WallpaperRenderer {
            val service = this@LumenfallWallpaperService
            val store = BundleStore(service)
            val webBundle = active?.takeIf { it.kind == "web" && it.entryPath != null }
            val url = if (webBundle != null) {
                Log.d(TAG, "web bundle ${webBundle.id}: ${webBundle.title}")
                if (BundledWallpapers.isBundled(webBundle.id)) {
                    // Bundled wallpaper: load from the assets path, not /bundles/.
                    val folder = BundledWallpapers.folderFor(webBundle.id)
                    "$APP_ORIGIN/assets/wallpapers/$folder/${webBundle.entryPath}"
                } else {
                    "$APP_ORIGIN/bundles/${webBundle.id}/${webBundle.entryPath}"
                }
            } else {
                Log.d(TAG, "no renderable bundle; loading default spike")
                SPIKE_BUNDLE_URL
            }
            val props = webBundle?.let { store.effectiveValues(it.id).toString() } ?: "{}"
            // Practical limits from import screening. Bundles without a report
            // (pre-screening imports) run as "limited" — the conservative default.
            val screening = webBundle?.let { store.screening(it.id) }
            val limited = webBundle != null && (screening?.limited ?: true)
            val settings = AppSettingsStore(service).get()
            val resolved = PerformancePolicy.resolve(
                service, settings, screening?.fpsCap ?: BundleWebView.DEFAULT_FPS_CAP
            )
            val allowGeolocation = webBundle != null &&
                GeolocationPolicy.allowed(service, store, webBundle.id)
            val host = WallpaperWebViewHost(
                context = service,
                initialProperties = props,
                fpsCap = resolved.fpsCap,
                networkBudgetPerMinute =
                    if (limited) screening?.networkBudgetPerMinute ?: 5 else null,
                blockExternalNetwork = settings.blockExternalNetwork,
                allowLan = settings.allowLan,
                initiallySuspended = resolved.initiallySuspended,
                pauseWhenHidden = settings.pauseWhenNotVisible,
                bundleId = webBundle?.id,
                allowGeolocation = allowGeolocation,
                onHeartbeat = { Log.d(TAG, "web heartbeat") },
                onLog = { Log.d(TAG, "web bridge: $it") }
            )
            if (webBundle != null) {
                PropertyBus.register(this, webBundle.id) { key, value ->
                    Log.d(TAG, "live property $key=$value")
                    host.setProperty(key, value)
                }
            }
            host.load(url)
            return host
        }
    }

    private companion object {
        const val TAG = "LumenfallWallpaper"

        /** Virtual origin the WebViewAssetLoader serves from (see WallpaperWebViewHost). */
        const val APP_ORIGIN = "https://appassets.androidplatform.net"

        /** Bundled test wallpaper, the default when no web bundle is selected. */
        const val SPIKE_BUNDLE_URL = "$APP_ORIGIN/assets/spike/webgl.html"
    }
}
