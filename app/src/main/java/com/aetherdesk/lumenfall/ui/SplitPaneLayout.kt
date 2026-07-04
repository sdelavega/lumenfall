package com.aetherdesk.lumenfall.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.aetherdesk.lumenfall.R

/**
 * Two-pane layout with a draggable divider. Used by the main picker/settings
 * split and the per-wallpaper settings/preview split.
 */
class SplitPaneLayout(context: Context) : LinearLayout(context) {
    private val divider = FrameLayout(context).apply {
        contentDescription = "Resize panels"
        isClickable = true
        setBackgroundColor(context.getColor(android.R.color.transparent))
    }

    private var firstPane: View? = null
    private var secondPane: View? = null
    private var firstFraction = 0.5f
    private var dragStartPx = 0f
    private var dragStartFraction = 0.5f

    init {
        showDividers = SHOW_DIVIDER_NONE
        divider.setOnTouchListener { _, event -> handleDividerTouch(event) }
    }

    fun setPanes(first: View, second: View?, landscape: Boolean, defaultFirstFraction: Float = 0.5f) {
        orientation = if (landscape) HORIZONTAL else VERTICAL
        firstPane = first
        secondPane = second
        if (childCount == 0) firstFraction = defaultFirstFraction.coerceIn(MIN_FRACTION, MAX_FRACTION)
        rebuild()
    }

    private fun rebuild() {
        removeAllViews()
        val first = firstPane ?: return
        val second = secondPane
        if (second == null) {
            addView(first, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            return
        }
        addView(first, paneParams(firstFraction))
        configureDivider()
        addView(divider, dividerParams())
        addView(second, paneParams(1f - firstFraction))
    }

    private fun configureDivider() {
        divider.removeAllViews()
        val line = View(context).apply {
            setBackgroundColor(context.getColor(R.color.glass_splitter_fill))
        }
        val grip = LinearLayout(context).apply {
            orientation = if (this@SplitPaneLayout.orientation == HORIZONTAL) VERTICAL else HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.glass_splitter_grip_bg)
            repeat(3) {
                addView(View(context).apply {
                    setBackgroundResource(R.drawable.glass_splitter_dot_bg)
                    layoutParams = LinearLayout.LayoutParams(GlassUi.dp(context, 3), GlassUi.dp(context, 3)).apply {
                        val gap = GlassUi.dp(context, 2)
                        if (this@SplitPaneLayout.orientation == HORIZONTAL) {
                            topMargin = gap
                            bottomMargin = gap
                        } else {
                            marginStart = gap
                            marginEnd = gap
                        }
                    }
                })
            }
        }
        divider.addView(line, lineParams())
        divider.addView(grip, gripParams())
    }

    private fun paneParams(weight: Float): LayoutParams =
        if (orientation == HORIZONTAL) {
            LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
        } else {
            LayoutParams(LayoutParams.MATCH_PARENT, 0, weight)
        }

    private fun dividerParams(): LayoutParams =
        if (orientation == HORIZONTAL) {
            LayoutParams(GlassUi.dp(context, TOUCH_SIZE_DP), LayoutParams.MATCH_PARENT)
        } else {
            LayoutParams(LayoutParams.MATCH_PARENT, GlassUi.dp(context, TOUCH_SIZE_DP))
        }

    private fun lineParams(): FrameLayout.LayoutParams =
        if (orientation == HORIZONTAL) {
            FrameLayout.LayoutParams(GlassUi.dp(context, LINE_SIZE_DP), FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER)
        } else {
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, GlassUi.dp(context, LINE_SIZE_DP), android.view.Gravity.CENTER)
        }

    private fun gripParams(): FrameLayout.LayoutParams =
        if (orientation == HORIZONTAL) {
            FrameLayout.LayoutParams(GlassUi.dp(context, GRIP_CROSS_DP), GlassUi.dp(context, GRIP_LONG_DP), android.view.Gravity.CENTER)
        } else {
            FrameLayout.LayoutParams(GlassUi.dp(context, GRIP_LONG_DP), GlassUi.dp(context, GRIP_CROSS_DP), android.view.Gravity.CENTER)
        }

    private fun handleDividerTouch(event: MotionEvent): Boolean {
        val available = if (orientation == HORIZONTAL) width else height
        if (available <= 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartPx = if (orientation == HORIZONTAL) event.rawX else event.rawY
                dragStartFraction = firstFraction
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val px = if (orientation == HORIZONTAL) event.rawX else event.rawY
                firstFraction = (dragStartFraction + (px - dragStartPx) / available)
                    .coerceIn(MIN_FRACTION, MAX_FRACTION)
                applyWeights()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return true
    }

    private fun applyWeights() {
        if (childCount < 3) return
        (getChildAt(0).layoutParams as? LayoutParams)?.let {
            it.weight = firstFraction
            getChildAt(0).layoutParams = it
        }
        (getChildAt(2).layoutParams as? LayoutParams)?.let {
            it.weight = 1f - firstFraction
            getChildAt(2).layoutParams = it
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val TOUCH_SIZE_DP = 18
        const val LINE_SIZE_DP = 2
        const val GRIP_LONG_DP = 36
        const val GRIP_CROSS_DP = 14
        const val MIN_FRACTION = 0.25f
        const val MAX_FRACTION = 0.75f
    }
}
