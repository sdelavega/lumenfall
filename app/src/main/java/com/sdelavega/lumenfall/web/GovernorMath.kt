// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

/**
 * True when a sampling window shows the renderer struggling: achieved fps
 * below [PerformanceGovernor.JANK_RATIO] of [fpsCap]. An empty window (zero
 * length) or one with no frames at all is not jank: a hidden or paused
 * preview emits no rAF ticks, and must not ratchet the cap down.
 */
internal fun isJankWindow(framesInWindow: Int, windowMs: Long, fpsCap: Int): Boolean {
    if (windowMs <= 0L || framesInWindow <= 0) return false
    val achieved = framesInWindow / (windowMs / 1000.0)
    return achieved < fpsCap * PerformanceGovernor.JANK_RATIO
}

/** Consecutive-jank counter: a hit extends the streak, anything else resets it. */
internal fun nextStreak(current: Int, hit: Boolean): Int = if (hit) current + 1 else 0
