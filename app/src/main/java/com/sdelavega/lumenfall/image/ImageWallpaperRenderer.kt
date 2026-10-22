package com.sdelavega.lumenfall.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import com.sdelavega.lumenfall.render.WallpaperRenderer
import java.io.File

/**
 * Draws an image bundle onto the wallpaper surface, center-cropped to fill.
 * Static images are painted once. Animated GIF/WebP use Android's
 * AnimatedImageDrawable clock and pause while the wallpaper is hidden.
 */
class ImageWallpaperRenderer(file: File) : WallpaperRenderer {

    private val animated: AnimatedImageDrawable? = decodeAnimated(file)
    private val bitmap: Bitmap? = if (animated == null) decodeDownsampled(file) else null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var holder: SurfaceHolder? = null
    private var width = 0
    private var height = 0
    private var visible = true

    override fun onSurface(holder: SurfaceHolder, width: Int, height: Int) {
        this.holder = holder
        this.width = width
        this.height = height
        if (animated != null) startAnimated() else drawBitmap()
    }

    override fun setVisible(visible: Boolean) {
        this.visible = visible
        if (visible) {
            if (animated != null) startAnimated() else drawBitmap()
        } else {
            animated?.stop()
        }
    }

    override fun onSurfaceDestroyed() {
        animated?.stop()
        holder = null
    }

    override fun release() {
        animated?.stop()
        animated?.callback = null
        bitmap?.recycle()
    }

    private fun drawBitmap() {
        val target = holder ?: return
        if (width <= 0 || height <= 0) return
        val canvas = target.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val bmp = bitmap ?: return
            val scale = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val left = (width - dw) / 2f
            val top = (height - dh) / 2f
            canvas.drawBitmap(
                bmp,
                Rect(0, 0, bmp.width, bmp.height),
                RectF(left, top, left + dw, top + dh),
                paint
            )
        } finally {
            target.unlockCanvasAndPost(canvas)
        }
    }

    private fun startAnimated() {
        val drawable = animated ?: return
        if (holder == null || width <= 0 || height <= 0 || !visible) return
        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                drawAnimatedFrame()
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                mainHandler.postAtTime(what, `when`)
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                mainHandler.removeCallbacks(what)
            }
        }
        if (!drawable.isRunning) drawable.start()
        drawAnimatedFrame()
    }

    private fun drawAnimatedFrame() {
        val drawable = animated ?: return
        val target = holder ?: return
        if (!visible || width <= 0 || height <= 0) return
        val canvas = target.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: width
            val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: height
            val scale = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
            val dw = sourceWidth * scale
            val dh = sourceHeight * scale
            val left = (width - dw) / 2f
            val top = (height - dh) / 2f
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(scale, scale)
            drawable.setBounds(0, 0, sourceWidth, sourceHeight)
            drawable.draw(canvas)
            canvas.restore()
        } finally {
            target.unlockCanvasAndPost(canvas)
        }
    }

    private companion object {
        fun decodeAnimated(file: File): AnimatedImageDrawable? =
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                    as? AnimatedImageDrawable
            }.getOrNull()

        /** Decode within a sane bound so a huge source image can't OOM the service. */
        fun decodeDownsampled(file: File, maxEdge: Int = 4096): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
            return runCatching {
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
    }
}
