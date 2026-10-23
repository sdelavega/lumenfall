// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapCounterTest {
    private var t = 0L
    private fun counter() = TapCounter(threshold = 7, windowMs = 2000, now = { t })

    @Test fun firesOnSeventhTapWithinWindow() {
        val c = counter()
        repeat(6) { assertFalse(c.tap()); t += 200 }
        assertTrue(c.tap())
    }

    @Test fun resetsAfterIdleWindow() {
        val c = counter()
        repeat(6) { assertFalse(c.tap()); t += 100 }
        t += 2001
        assertFalse(c.tap())
        repeat(5) { t += 100; assertFalse(c.tap()) }
        t += 100
        assertTrue(c.tap())
    }

    @Test fun resetsAfterFiring() {
        val c = counter()
        repeat(6) { c.tap() }
        assertTrue(c.tap())
        repeat(6) { assertFalse(c.tap()) }
        assertTrue(c.tap())
    }

    @Test fun gapExactlyAtWindowKeepsCount() {
        val c = counter()
        repeat(6) { assertFalse(c.tap()); t += 2000 }
        assertTrue(c.tap())
    }
}
