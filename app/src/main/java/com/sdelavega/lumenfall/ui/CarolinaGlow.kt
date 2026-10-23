// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** One-off Carolina Blue glow: a transient overlay on the decor view, no layout changes. */
object CarolinaGlow {
    private const val BLUE = 0xFF4B9CD3.toInt()
    private const val PEAK_ALPHA = 0.35f
    private const val DURATION_MS = 1200L
    const val NOTE = "Every accent in Lumenfall is Carolina Blue — a nod to Chapel Hill."

    fun reducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    fun play(activity: Activity) {
        if (reducedMotion(activity)) return
        val decor = activity.window.decorView as? ViewGroup ?: return
        val glow = GlowView(activity).apply {
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        decor.addView(glow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            addUpdateListener {
                val f = it.animatedValue as Float
                // Triangle envelope: fade in over the first half, out over the second.
                glow.alpha = PEAK_ALPHA * (1f - kotlin.math.abs(2f * f - 1f))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { decor.removeView(glow) }
            })
            start()
        }
    }

    private class GlowView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            paint.shader = RadialGradient(
                w / 2f, h / 2f, maxOf(w, h) * 0.7f,
                intArrayOf(BLUE, BLUE and 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}
