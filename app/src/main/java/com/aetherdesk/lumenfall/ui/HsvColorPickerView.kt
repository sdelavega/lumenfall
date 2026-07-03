package com.aetherdesk.lumenfall.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * A compact HSV color picker: a saturation/value pad with a hue bar underneath,
 * drawn to match the glass aesthetic (rounded corners, ring thumbs). Emits
 * [onColorChanged] continuously while dragging — callers pair it with a hex
 * field for exact entry, and settings apply live the same way slider drags do.
 *
 * [setColor] updates the picker without firing the callback (for programmatic
 * sync from the hex field or a governor revert), mirroring the silent-update
 * convention the other controls use.
 */
class HsvColorPickerView(context: Context) : View(context) {

    var onColorChanged: ((Int) -> Unit)? = null

    private val hsv = floatArrayOf(200f, 0.8f, 0.9f)

    private val padRect = RectF()
    private val hueRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(120, 0, 0, 0)
    }

    private var padShader: ComposeShader? = null
    private var padShaderHue = Float.NaN
    private var hueShader: LinearGradient? = null

    // Which region the current gesture started in; drags stay in that region
    // even when the finger wanders, so a hue scrub can't jump into the pad.
    private var dragging = Region.NONE

    private enum class Region { NONE, PAD, HUE }

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        invalidate()
    }

    private fun currentColor(): Int = Color.HSVToColor(hsv)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = dp(12f)
        val hueHeight = dp(24f)
        padRect.set(0f, 0f, w.toFloat(), h - hueHeight - gap)
        hueRect.set(0f, h - hueHeight, w.toFloat(), h.toFloat())
        padShader = null // size-dependent, rebuild on next draw
        hueShader = null
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dp(10f)

        // Saturation/value pad: white->hue horizontally, multiplied by
        // white->black vertically.
        if (padShader == null || padShaderHue != hsv[0]) {
            val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
            val sat = LinearGradient(
                padRect.left, padRect.top, padRect.right, padRect.top,
                Color.WHITE, hueColor, Shader.TileMode.CLAMP
            )
            val value = LinearGradient(
                padRect.left, padRect.top, padRect.left, padRect.bottom,
                Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP
            )
            padShader = ComposeShader(value, sat, PorterDuff.Mode.MULTIPLY)
            padShaderHue = hsv[0]
        }
        paint.shader = padShader
        canvas.drawRoundRect(padRect, radius, radius, paint)

        if (hueShader == null) {
            val colors = IntArray(13) { Color.HSVToColor(floatArrayOf(it * 30f, 1f, 1f)) }
            hueShader = LinearGradient(
                hueRect.left, hueRect.top, hueRect.right, hueRect.top,
                colors, null, Shader.TileMode.CLAMP
            )
        }
        paint.shader = hueShader
        canvas.drawRoundRect(hueRect, radius, radius, paint)
        paint.shader = null

        // Thumbs: filled with the value they select, ringed for contrast on
        // any backdrop.
        val padX = padRect.left + hsv[1] * padRect.width()
        val padY = padRect.top + (1f - hsv[2]) * padRect.height()
        drawThumb(canvas, padX.coerceIn(padRect.left, padRect.right),
            padY.coerceIn(padRect.top, padRect.bottom), currentColor())

        val hueX = hueRect.left + (hsv[0] / 360f) * hueRect.width()
        drawThumb(canvas, hueX.coerceIn(hueRect.left, hueRect.right),
            hueRect.centerY(), Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)))
    }

    private fun drawThumb(canvas: Canvas, x: Float, y: Float, color: Int) {
        val r = dp(9f)
        thumbFill.color = color
        canvas.drawCircle(x, y, r, thumbFill)
        thumbShadow.strokeWidth = dp(4f)
        canvas.drawCircle(x, y, r, thumbShadow)
        thumbRing.strokeWidth = dp(2.5f)
        canvas.drawCircle(x, y, r, thumbRing)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = when {
                    event.y <= padRect.bottom + dp(6f) -> Region.PAD
                    else -> Region.HUE
                }
                // The picker lives inside the settings ScrollView; a drag here
                // is color selection, not scrolling.
                parent?.requestDisallowInterceptTouchEvent(true)
                applyTouch(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                applyTouch(event)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = Region.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyTouch(event: MotionEvent) {
        when (dragging) {
            Region.PAD -> {
                hsv[1] = ((event.x - padRect.left) / padRect.width()).coerceIn(0f, 1f)
                hsv[2] = (1f - (event.y - padRect.top) / padRect.height()).coerceIn(0f, 1f)
            }
            Region.HUE -> {
                hsv[0] = (((event.x - hueRect.left) / hueRect.width()) * 360f).coerceIn(0f, 360f)
            }
            Region.NONE -> return
        }
        invalidate()
        onColorChanged?.invoke(currentColor())
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
