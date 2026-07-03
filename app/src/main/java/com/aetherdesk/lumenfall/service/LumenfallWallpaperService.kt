package com.aetherdesk.lumenfall.service

import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.aetherdesk.lumenfall.image.ImageWallpaperRenderer
import com.aetherdesk.lumenfall.render.PropertyBus
import com.aetherdesk.lumenfall.render.WallpaperRenderer
import com.aetherdesk.lumenfall.storage.BundleMeta
import com.aetherdesk.lumenfall.storage.BundleStore
import com.aetherdesk.lumenfall.video.VideoWallpaperRenderer
import com.aetherdesk.lumenfall.web.WallpaperWebViewHost

class LumenfallWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = LumenfallEngine()

    private inner class LumenfallEngine : Engine() {
        // The renderer chosen for the active bundle's kind; null only if creation
        // failed. The Engine just forwards its lifecycle here.
        private var renderer: WallpaperRenderer? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer = createRenderer()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer?.onSurface(holder, width, height)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            renderer?.setVisible(visible)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            renderer?.onSurfaceDestroyed()
        }

        override fun onDestroy() {
            super.onDestroy()
            PropertyBus.unregister(this)
            renderer?.release()
            renderer = null
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
                "$APP_ORIGIN/bundles/${webBundle.id}/${webBundle.entryPath}"
            } else {
                Log.d(TAG, "no renderable bundle; loading default spike")
                SPIKE_BUNDLE_URL
            }
            val props = webBundle?.let { store.effectiveValues(it.id).toString() } ?: "{}"
            // Practical limits from import screening. Bundles without a report
            // (pre-screening imports) run as "limited" — the conservative default.
            val screening = webBundle?.let { store.screening(it.id) }
            val limited = webBundle != null && (screening?.limited ?: true)
            val host = WallpaperWebViewHost(
                context = service,
                initialProperties = props,
                fpsCap = screening?.fpsCap ?: WallpaperWebViewHost.DEFAULT_FPS_CAP,
                networkBudgetPerMinute =
                    if (limited) screening?.networkBudgetPerMinute ?: 5 else null,
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
