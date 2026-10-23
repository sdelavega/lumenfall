// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailSamplingTest {
    @Test fun smallImagesAreNotSubsampled() {
        assertEquals(1, sampleSizeFor(100, 100, 216))
        assertEquals(1, sampleSizeFor(216, 400, 216))
    }

    @Test fun picksLargestPowerOfTwoKeepingTheShortSideAtOrAboveTarget() {
        assertEquals(2, sampleSizeFor(480, 600, 216))   // 240 >= 216, 120 < 216
        assertEquals(8, sampleSizeFor(4000, 3000, 216)) // 375 >= 216, 187 < 216
        assertEquals(4, sampleSizeFor(1000, 4000, 216)) // short side decides: 250
    }

    @Test fun degenerateInputsFallBackToOne() {
        assertEquals(1, sampleSizeFor(0, 0, 216))
        assertEquals(1, sampleSizeFor(4000, 3000, 0))
    }
}
