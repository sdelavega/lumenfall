// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

/**
 * Decides when the deep-sleep timer is armed. Pure Kotlin so it can be driven
 * by fake [schedule]/[cancel] lambdas in a JVM test.
 *
 * Visibility starts "unknown": nothing is scheduled until a real hide has been
 * observed, so a performance change at creation time never arms a timer for a
 * wallpaper that was never shown-then-hidden. Main thread only.
 */
class DeepSleepScheduler(
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    private val cancel: () -> Unit,
    private val delayMs: () -> Long,
    private val onDeepSleep: () -> Unit = {}
) {
    /** True only after [onVisibilityChanged] reported hidden (and not shown since). */
    var isHidden: Boolean = false
        private set

    /**
     * Bumped on every visibility report. A snapshot started for deep sleep captures
     * it and must abandon its teardown when it no longer matches: the wallpaper
     * was shown (or re-hidden) while PixelCopy was still running.
     */
    var visibilityGeneration: Int = 0
        private set

    fun onVisibilityChanged(visible: Boolean) {
        visibilityGeneration++
        isHidden = !visible
        cancel()
        if (!visible) schedule(delayMs(), onDeepSleep)
    }

    fun onPerformanceChanged() {
        cancel()
        if (isHidden) schedule(delayMs(), onDeepSleep)
    }

    /** Drop any pending timer without changing the visibility state (teardown). */
    fun cancelPending() = cancel()
}
