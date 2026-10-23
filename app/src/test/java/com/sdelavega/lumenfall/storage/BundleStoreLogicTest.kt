// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class BundleStoreLogicTest {
    @Test fun corruptOverridesYieldEmpty() {
        assertEquals(0, parseOverrides("{not json").length())
        assertEquals(0, parseOverrides("[1,2]").length())
    }

    @Test fun nullOverridesYieldEmpty() {
        assertEquals(0, parseOverrides(null).length())
    }

    @Test fun validOverridesParse() {
        assertEquals(3, parseOverrides("{\"a\":3}").getInt("a"))
    }
}
