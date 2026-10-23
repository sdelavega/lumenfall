// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BundledMigrationTest {
    @Test fun rainMapsToRainfall() {
        assertEquals("bundled/Rainfall", migrateBundledId("bundled/LivelyRain"))
    }

    @Test fun otherIdsUnchanged() {
        assertEquals("bundled/NeonCity", migrateBundledId("bundled/NeonCity"))
        assertEquals("b1700000000000", migrateBundledId("b1700000000000"))
        assertEquals("bundled/LivelyRain2", migrateBundledId("bundled/LivelyRain2"))
    }

    @Test fun nullStaysNull() {
        assertNull(migrateBundledId(null))
    }

    @Test fun schema3MigratesActiveIdsAndDropsRainOverrides() {
        val rainKey = PrefKeys.overrides("bundled/LivelyRain")
        val p = FakePrefs(hashMapOf(
            "prefs_schema" to 2,
            "active_bundle_id" to "bundled/LivelyRain",
            "previous_active_bundle_id" to "bundled/NeonCity",
            rainKey to "{\"rainIntensity\":40}"
        ))
        PrefKeys.migrate(p)
        assertEquals("bundled/Rainfall", p.getString("active_bundle_id", null))
        assertEquals("bundled/NeonCity", p.getString("previous_active_bundle_id", null))
        assertNull(p.map[rainKey])
        assertEquals(3, p.getInt("prefs_schema", 0))

        // Marker stops a second run from touching later state.
        p.map["active_bundle_id"] = "bundled/LivelyRain"
        PrefKeys.migrate(p)
        assertEquals("bundled/LivelyRain", p.getString("active_bundle_id", null))
    }

    @Test fun legacyRainOverridesDoNotSurviveFromSchema1() {
        val p = FakePrefs(hashMapOf("overrides_bundled/LivelyRain" to "{\"a\":1}"))
        PrefKeys.migrate(p)
        assertNull(p.map[PrefKeys.overrides("bundled/LivelyRain")])
        assertNull(p.map["overrides_bundled/LivelyRain"])
        assertEquals(3, p.getInt("prefs_schema", 0))
    }
}
