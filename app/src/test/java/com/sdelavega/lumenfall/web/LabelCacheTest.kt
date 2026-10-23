// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelCacheTest {
    @Test
    fun labelCacheRoundsToTwoDecimals() {
        val cache = LabelCache()
        cache.put(48.8566, 2.3522, "Paris, Ile-de-France")
        assertEquals("Paris, Ile-de-France", cache.get(48.8601, 2.3499))
        assertNull(cache.get(48.8801, 2.3522))
    }

    @Test
    fun labelCacheMissReturnsNull() {
        assertNull(LabelCache().get(1.0, 2.0))
    }
}
