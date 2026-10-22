// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.ViewGroup
import android.webkit.WebView
import com.sdelavega.lumenfall.storage.AppSettingsStore
import com.sdelavega.lumenfall.storage.BundleMeta
import com.sdelavega.lumenfall.storage.BundleStore
import com.sdelavega.lumenfall.storage.BundledWallpapers
import java.io.File
import kotlin.concurrent.thread

/**
 * Generates picker thumbnails for bundles that didn't declare their own preview
 * image (ported from ÆtherDesk's thumbnail auto-generation). Lively zips *may*
 * ship a preview; plenty don't, and those render as blank tiles in the library.
 *
 * Per kind:
 * - image: the wallpaper *is* an image — point previewPath at the entry itself;
 * - video: grab a frame ~1s in via [MediaMetadataRetriever];
 * - web: actually run the bundle offscreen for a few seconds and snapshot it.
 *   The page renders into a [VirtualDisplay] backed by an [ImageReader] surface
 *   (the same Presentation technique the wallpaper host uses, pointed at a
 *   capturable surface instead of the wallpaper) — a plain View.draw() capture
 *   can't see hardware-composited content like WebGL or accelerated canvas.
 *   The WebView is the same sandboxed [BundleWebView] construction the real
 *   renderers use, so a bundle can't do anything during thumbnailing it
 *   couldn't do as a wallpaper.
 *
 * Imported bundle thumbnails are saved into the bundle dir under a dot-prefixed
 * name and bundle.json's previewPath is updated to point at it. Bundled asset
 * wallpapers can't be modified, so their generated web snapshots are cached in
 * app data under `bundled-thumbnails/` and read directly by the picker. A
 * failed attempt writes a marker file instead so the bundle isn't retried on
 * every picker refresh.
 */
object BundleThumbnailer {

    private const val THUMB_NAME = ".lumenfall-thumb.jpg"
    private const val FAILED_MARKER = ".lumenfall-thumb-failed"

    /** Offscreen render + capture geometry: tablet-ish 16:10, low density. */
    private const val CAPTURE_WIDTH = 960
    private const val CAPTURE_HEIGHT = 600
    private const val CAPTURE_DPI = 160

    /** Stored thumbnail width (picker tiles are 72dp; this is plenty). */
    private const val THUMB_WIDTH = 480

    /** How long after page load to let the animation settle before capturing. */
    private const val SETTLE_MS = 3_000L

    /** Overall cap per web capture — a bundle that can't paint by then fails. */
    private const val TIMEOUT_MS = 12_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false

    fun bundledThumbnailFile(context: Context, meta: BundleMeta): File? {
        if (!BundledWallpapers.isBundled(meta.id)) return null
        val folder = BundledWallpapers.folderFor(meta.id) ?: return null
        return File(bundledCacheDir(context), "$folder$THUMB_NAME")
    }

    private fun needsThumbnail(context: Context, store: BundleStore, meta: BundleMeta): Boolean {
        if (meta.entryPath == null || meta.kind !in setOf("web", "video", "image")) return false
        if (BundledWallpapers.isBundled(meta.id)) {
            val thumb = bundledThumbnailFile(context, meta) ?: return false
            val marker = bundledFailedMarker(context, meta) ?: return false
            return !hasBundledAssetPreview(context, meta) && !thumb.exists() && !marker.exists()
        }
        return meta.previewPath == null && !File(store.bundleDir(meta.id), FAILED_MARKER).exists()
    }

    /**
     * Generate thumbnails for every bundle that's missing one, one at a time
     * (a second concurrent WebView capture would just double memory pressure
     * for no latency win). Call from the main thread; safe to call repeatedly —
     * a call while a generation is in flight is a no-op, and each finished
     * thumbnail invokes [onProgress] (also on main) so the caller can refresh.
     */
    fun generateMissing(context: Context, store: BundleStore, onProgress: () -> Unit) {
        if (running) return
        val app = context.applicationContext
        val next = store.list().firstOrNull { needsThumbnail(app, store, it) } ?: return
        running = true
        generate(app, store, next) { updated ->
            running = false
            if (updated != null) onProgress()
            generateMissing(app, store, onProgress)
        }
    }

    /**
     * Generate one thumbnail and persist it. Main thread only. [onDone] gets the
     * updated meta, or null on failure (after the retry-suppression marker is
     * written). Uses the application context internally, so generation survives
     * the caller's Activity being destroyed mid-capture.
     */
    fun generate(context: Context, store: BundleStore, meta: BundleMeta, onDone: (BundleMeta?) -> Unit) {
        val entry = meta.entryPath
        if (entry == null) {
            markFailed(context, store, meta); onDone(null); return
        }
        when (meta.kind) {
            "image" -> imageThumbnail(context.applicationContext, store, meta, entry, onDone)
            "video" -> videoThumbnail(context.applicationContext, store, meta, entry, onDone)
            "web" -> WebCapture(context.applicationContext, store, meta, entry, onDone).start()
            else -> {
                markFailed(context, store, meta); onDone(null)
            }
        }
    }

    /** The wallpaper is an image; the entry file is its own best preview. */
    private fun imageThumbnail(context: Context, store: BundleStore, meta: BundleMeta, entry: String, onDone: (BundleMeta?) -> Unit) {
        thread {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(store.fileFor(meta.id, entry).absolutePath, opts)
            val ok = opts.outWidth > 0 && opts.outHeight > 0
            mainHandler.post {
                if (ok) persist(store, meta.copy(previewPath = entry), onDone)
                else fail(context, store, meta, onDone)
            }
        }
    }

    private fun videoThumbnail(context: Context, store: BundleStore, meta: BundleMeta, entry: String, onDone: (BundleMeta?) -> Unit) {
        thread {
            val updated = runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(store.fileFor(meta.id, entry).absolutePath)
                    // ~1s in skips a black lead-in frame; fall back to frame 0.
                    val frame = mmr.getFrameAtTime(1_000_000) ?: mmr.getFrameAtTime(0)
                    frame?.let { saveThumb(context, store, meta, it) }
                } finally {
                    mmr.release()
                }
            }.getOrNull()
            mainHandler.post {
                if (updated != null) persist(store, updated, onDone)
                else fail(context, store, meta, onDone)
            }
        }
    }

    /** Downscale + JPEG-encode [bitmap] into the bundle dir; returns updated meta. */
    private fun saveThumb(context: Context?, store: BundleStore, meta: BundleMeta, bitmap: Bitmap): BundleMeta {
        val scaled = if (bitmap.width > THUMB_WIDTH) {
            val h = (bitmap.height.toLong() * THUMB_WIDTH / bitmap.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, THUMB_WIDTH, h, true)
        } else bitmap
        val file = if (BundledWallpapers.isBundled(meta.id)) {
            val appContext = context ?: error("context required for bundled thumbnail")
            bundledThumbnailFile(appContext, meta) ?: error("invalid bundled wallpaper id")
        } else {
            File(store.bundleDir(meta.id), THUMB_NAME)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return if (BundledWallpapers.isBundled(meta.id)) meta else meta.copy(previewPath = THUMB_NAME)
    }

    private fun persist(store: BundleStore, updated: BundleMeta, onDone: (BundleMeta?) -> Unit) {
        if (BundledWallpapers.isBundled(updated.id)) {
            onDone(updated)
            return
        }
        // saveMeta throws if the bundle was deleted mid-generation; that's a
        // clean "never mind," not a failure to mark.
        if (runCatching { store.saveMeta(updated) }.isSuccess) onDone(updated) else onDone(null)
    }

    private fun fail(context: Context, store: BundleStore, meta: BundleMeta, onDone: (BundleMeta?) -> Unit) {
        markFailed(context, store, meta)
        onDone(null)
    }

    private fun markFailed(context: Context, store: BundleStore, meta: BundleMeta) {
        val marker = if (BundledWallpapers.isBundled(meta.id)) {
            bundledFailedMarker(context, meta)
        } else {
            File(store.bundleDir(meta.id), FAILED_MARKER)
        }
        runCatching {
            marker?.parentFile?.mkdirs()
            marker?.writeText("")
        }
    }

    private fun bundledCacheDir(context: Context): File =
        File(context.applicationContext.filesDir, "bundled-thumbnails").apply { mkdirs() }

    private fun bundledFailedMarker(context: Context, meta: BundleMeta): File? {
        val folder = BundledWallpapers.folderFor(meta.id) ?: return null
        return File(bundledCacheDir(context), "$folder$FAILED_MARKER")
    }

    private fun hasBundledAssetPreview(context: Context, meta: BundleMeta): Boolean {
        val folder = BundledWallpapers.folderFor(meta.id) ?: return false
        val preview = meta.previewPath?.trim()?.trimStart('/') ?: return false
        return runCatching {
            context.assets.open("wallpapers/$folder/$preview").use { true }
        }.getOrDefault(false)
    }

    /**
     * One offscreen run of a web bundle: virtual display -> Presentation ->
     * sandboxed WebView -> ImageReader. Captures [SETTLE_MS] after the page
     * finishes loading (letting the first animation frames land), or gives up
     * at [TIMEOUT_MS] / on renderer death / on main-frame load failure.
     */
    private class WebCapture(
        private val context: Context,
        private val store: BundleStore,
        private val meta: BundleMeta,
        entry: String,
        private val onDone: (BundleMeta?) -> Unit
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val url = if (BundledWallpapers.isBundled(meta.id)) {
            val folder = BundledWallpapers.folderFor(meta.id)
            "${BundleWebView.APP_ORIGIN}/assets/wallpapers/$folder/$entry"
        } else {
            "${BundleWebView.APP_ORIGIN}/bundles/${meta.id}/$entry"
        }

        private var reader: ImageReader? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var presentation: ThumbPresentation? = null

        // Most recent composited frame, kept open until capture or teardown.
        // The reader's listener and the capture both run on [handler], so
        // access is serialized.
        private var latest: Image? = null
        private var finished = false

        private val timeoutRunnable = Runnable { finish(null) }
        private val captureRunnable = Runnable { snapshot() }

        fun start() {
            val displayManager =
                context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val r = ImageReader.newInstance(
                CAPTURE_WIDTH, CAPTURE_HEIGHT, PixelFormat.RGBA_8888, 3
            )
            r.setOnImageAvailableListener({ onFrame(it) }, handler)
            reader = r
            val vd = displayManager.createVirtualDisplay(
                "lumenfall-thumbnail",
                CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_DPI,
                r.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            if (vd == null) {
                finish(null); return
            }
            virtualDisplay = vd
            val pres = ThumbPresentation(context, vd.display)
            presentation = pres
            try {
                pres.show()
            } catch (t: Throwable) {
                finish(null); return
            }
            handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        }

        private fun onFrame(r: ImageReader) {
            if (finished) return
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
            latest?.close()
            latest = img
        }

        fun onPageLoaded() {
            if (finished) return
            handler.removeCallbacks(captureRunnable)
            handler.postDelayed(captureRunnable, SETTLE_MS)
        }

        private fun snapshot() {
            if (finished) return
            val img = latest ?: run { finish(null); return }
            val bitmap = runCatching { imageToBitmap(img) }.getOrNull()
                ?: run { finish(null); return }
            // Encode off the main thread; teardown waits for the result so the
            // WebView isn't destroyed while its last frame is being saved.
            thread {
                val updated = runCatching { saveThumb(context, store, meta, bitmap) }.getOrNull()
                handler.post { finish(updated) }
            }
        }

        private fun imageToBitmap(image: Image): Bitmap {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val bmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(plane.buffer)
            return if (rowPadding == 0) bmp
            else Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        }

        fun finish(updated: BundleMeta?) {
            if (finished) return
            finished = true
            handler.removeCallbacks(timeoutRunnable)
            handler.removeCallbacks(captureRunnable)
            presentation?.dismiss()
            presentation = null
            virtualDisplay?.release()
            virtualDisplay = null
            latest?.close()
            latest = null
            reader?.close()
            reader = null
            if (updated != null) persist(store, updated, onDone) else fail(context, store, meta, onDone)
        }

        private inner class ThumbPresentation(
            outerContext: Context,
            display: Display
        ) : Presentation(outerContext, display) {

            private var webView: WebView? = null

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                val settings = AppSettingsStore(context).get()
                val screening = store.screening(meta.id)
                val limited = screening?.limited ?: true
                val wv = BundleWebView.create(
                    context = context, // presentation context -> renders on the virtual display
                    initialProperties = store.effectiveValues(meta.id).toString(),
                    // A thumbnail doesn't need smooth animation, just a painted frame.
                    fpsCap = BundleWebView.MIN_FPS,
                    networkBudgetPerMinute =
                        if (limited) screening?.networkBudgetPerMinute ?: 5 else null,
                    blockExternalNetwork = settings.blockExternalNetwork,
                    allowLan = settings.allowLan,
                    bundleId = meta.id,
                    onPageLoaded = { this@WebCapture.onPageLoaded() },
                    onRenderProcessGone = { this@WebCapture.finish(null) },
                    onMainFrameLoadFailed = { this@WebCapture.finish(null) }
                ).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                setContentView(wv)
                webView = wv
                // Same process-shared latch rules as the wallpaper host: claim
                // timers for this WebView's lifetime or a paused latch left by
                // a hidden engine freezes the capture page at birth.
                WebViewTimers.setNeedsTimers(this, wv, true)
                wv.loadUrl(url)
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
    }
}
