// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceGovernorPressureTest {
    private val cap = 30

    // 10 frames in 2s = 5 fps, well under 60% of the 30 fps cap.
    private val jank = isJankWindow(framesInWindow = 10, windowMs = 2000, fpsCap = cap)
    private val good = isJankWindow(framesInWindow = 60, windowMs = 2000, fpsCap = cap)

    private fun streakOf(vararg hits: Boolean): Int =
        hits.fold(0) { s, h -> nextStreak(s, h) }

    @Test
    fun sustainedShortfallTriggersAfterStreak() {
        assertTrue(jank)
        val hits = BooleanArray(PerformanceGovernor.STREAK_TO_TRIGGER) { jank }
        assertTrue(streakOf(*hits) >= PerformanceGovernor.STREAK_TO_TRIGGER)
    }

    @Test
    fun singleSpikeDoesNot() {
        assertFalse(good)
        val streak = streakOf(good, jank, good)
        assertEquals(0, streak)
        assertTrue(streakOf(good, jank) < PerformanceGovernor.STREAK_TO_TRIGGER)
    }

    @Test
    fun zeroFramesOverEmptyWindowIsNotJank() {
        assertFalse(isJankWindow(framesInWindow = 0, windowMs = 0, fpsCap = cap))
        // Hidden/paused preview: the shim counts no frames at all.
        assertFalse(isJankWindow(framesInWindow = 0, windowMs = 2000, fpsCap = cap))
    }

    @Test
    fun atCapIsNotJank() {
        assertFalse(isJankWindow(framesInWindow = 60, windowMs = 2000, fpsCap = cap))
        // Exactly on the 60% threshold is not below it.
        assertFalse(isJankWindow(framesInWindow = 36, windowMs = 2000, fpsCap = cap))
    }
}
