// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSleepSchedulerTest {
    private var pending = 0
    private var scheduledCount = 0
    private var lastDelay = -1L
    private var fired = 0
    private var delay = 60_000L

    private val scheduler = DeepSleepScheduler(
        schedule = { d, _ -> pending = 1; scheduledCount++; lastDelay = d },
        cancel = { pending = 0 },
        delayMs = { delay },
        onDeepSleep = { fired++ }
    )

    @Test
    fun performanceChangeBeforeAnyHideSchedulesNothing() {
        scheduler.onPerformanceChanged()
        assertEquals(0, scheduledCount)
        assertEquals(0, pending)
        assertFalse(scheduler.isHidden)
    }

    @Test
    fun hideSchedulesOnce() {
        scheduler.onVisibilityChanged(false)
        assertEquals(1, scheduledCount)
        assertEquals(1, pending)
        assertEquals(60_000L, lastDelay)
        assertTrue(scheduler.isHidden)
    }

    @Test
    fun showCancels() {
        scheduler.onVisibilityChanged(false)
        scheduler.onVisibilityChanged(true)
        assertEquals(0, pending)
        assertFalse(scheduler.isHidden)
    }

    @Test
    fun performanceChangeWhileHiddenReschedules() {
        scheduler.onVisibilityChanged(false)
        delay = 5_000L
        scheduler.onPerformanceChanged()
        assertEquals(2, scheduledCount)
        assertEquals(1, pending)
        assertEquals(5_000L, lastDelay)
    }

    @Test
    fun flickerHideShowLeavesNothingScheduled() {
        scheduler.onVisibilityChanged(false)
        scheduler.onVisibilityChanged(true)
        scheduler.onPerformanceChanged()
        assertEquals(0, pending)
        assertEquals(1, scheduledCount)
    }

    @Test
    fun visibilityGenerationChangesOnEveryVisibilityReport() {
        val g0 = scheduler.visibilityGeneration
        scheduler.onVisibilityChanged(false)
        val g1 = scheduler.visibilityGeneration
        scheduler.onVisibilityChanged(true)
        val g2 = scheduler.visibilityGeneration
        assertTrue(g0 != g1 && g1 != g2 && g0 != g2)
    }

    @Test
    fun performanceChangeDoesNotInvalidateAnInFlightSnapshot() {
        scheduler.onVisibilityChanged(false)
        val g = scheduler.visibilityGeneration
        scheduler.onPerformanceChanged()
        assertEquals(g, scheduler.visibilityGeneration)
    }
}
