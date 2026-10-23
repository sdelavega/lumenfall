// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.storage

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakePrefs(val map: MutableMap<String, Any?> = HashMap()) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = throw UnsupportedOperationException()
    override fun getLong(key: String, defValue: Long): Long = throw UnsupportedOperationException()
    override fun getFloat(key: String, defValue: Float): Float = throw UnsupportedOperationException()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = throw UnsupportedOperationException()
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = throw UnsupportedOperationException()
    override fun edit(): SharedPreferences.Editor = Ed()

    private inner class Ed : SharedPreferences.Editor {
        private val pending = ArrayList<() -> Unit>()
        override fun putString(key: String, value: String?) = apply { pending += { map[key] = value } }
        override fun putInt(key: String, value: Int) = apply { pending += { map[key] = value } }
        override fun putBoolean(key: String, value: Boolean) = apply { pending += { map[key] = value } }
        override fun remove(key: String) = apply { pending += { map.remove(key) } }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun clear(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun commit(): Boolean { pending.forEach { it() }; pending.clear(); return true }
        override fun apply() { commit() }
    }
}

class PrefKeysTest {
    @Test fun idsWithSeparatorsDoNotCollide() {
        val ids = listOf("a_b", "a", "_b", "a/b", "a%2Fb", "a b", "a+b")
        assertEquals(ids.size, ids.map { PrefKeys.overrides(it) }.toSet().size)
        assertEquals(ids.size, ids.map { PrefKeys.geolocation(it) }.toSet().size)
        assertNotEquals(PrefKeys.overrides("x"), PrefKeys.geolocation("x"))
    }

    @Test fun migrateMovesLegacyKeysOnce() {
        val p = FakePrefs(hashMapOf(
            "overrides_a_b" to "{\"k\":1}",
            "geo_bundle_a_b" to true,
            "active_bundle_id" to "a_b"
        ))
        PrefKeys.migrate(p)
        assertEquals("{\"k\":1}", p.getString(PrefKeys.overrides("a_b"), null))
        assertEquals(true, p.getBoolean(PrefKeys.geolocation("a_b"), false))
        assertNull(p.map["overrides_a_b"])
        assertNull(p.map["geo_bundle_a_b"])
        assertEquals("a_b", p.getString("active_bundle_id", null))
        assertEquals(2, p.getInt("prefs_schema", 0))

        // Second run: legacy key re-appears but marker stops migration.
        p.map["overrides_zzz"] = "{}"
        PrefKeys.migrate(p)
        assertEquals("{}", p.map["overrides_zzz"])
        assertNull(p.map[PrefKeys.overrides("zzz")])
    }

    @Test fun migrateDoesNotOverwriteExistingV2Key() {
        val p = FakePrefs(hashMapOf(
            "overrides_a" to "{\"old\":1}",
            PrefKeys.overrides("a") to "{\"new\":1}"
        ))
        PrefKeys.migrate(p)
        assertEquals("{\"new\":1}", p.getString(PrefKeys.overrides("a"), null))
        assertNull(p.map["overrides_a"])
    }
}
