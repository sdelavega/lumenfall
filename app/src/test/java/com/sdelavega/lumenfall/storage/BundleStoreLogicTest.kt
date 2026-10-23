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

    @Test fun stringDefaultDropdownBridgesName() {
        val d = PropertySchema.Dropdown("d", "D", listOf("Low", "High"), 1, true)
        assertEquals("High", bridgedDropdownDefault(d))
    }

    @Test fun numericDefaultDropdownBridgesIndex() {
        val d = PropertySchema.Dropdown("d", "D", listOf("Low", "High"), 1, false)
        assertEquals(1, bridgedDropdownDefault(d))
    }

    @Test fun nameDropdownWithNoItemsBridgesEmpty() {
        assertEquals("", bridgedDropdownDefault(PropertySchema.Dropdown("d", "D", emptyList(), 0, true)))
    }
}
