// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.storage

import android.content.SharedPreferences
import java.net.URLEncoder

/**
 * Per-bundle SharedPreferences keys. Ids are URL-encoded so separators inside an id
 * ("_", "/") can never make two different ids produce the same key.
 */
object PrefKeys {
    private const val SCHEMA_KEY = "prefs_schema"
    private const val SCHEMA_VERSION = 3
    private const val LEGACY_OVERRIDES_PREFIX = "overrides_"
    private const val LEGACY_GEO_PREFIX = "geo_bundle_"
    private const val SCHEMA_V2 = 2
    private const val RETIRED_RAIN_ID = "bundled/LivelyRain"

    fun overrides(id: String): String = "v2/overrides/" + URLEncoder.encode(id, Charsets.UTF_8.name())
    fun geolocation(id: String): String = "v2/geo/" + URLEncoder.encode(id, Charsets.UTF_8.name())

    /**
     * Bring stored preferences up to the current schema, once.
     * v2: legacy `overrides_<id>` / `geo_bundle_<id>` keys move to their v2 names.
     * v3: the retired `LivelyRain` bundled wallpaper becomes `Rainfall` in the
     * active/previous pointers, and its overrides (a different property scale)
     * are dropped.
     */
    fun migrate(prefs: SharedPreferences) {
        val schema = prefs.getInt(SCHEMA_KEY, 0)
        if (schema >= SCHEMA_VERSION) return
        val editor = prefs.edit()
        val retiredRainKey = overrides(RETIRED_RAIN_ID)
        if (schema < SCHEMA_V2) {
            for ((key, value) in prefs.all.toMap()) {
                val target = when {
                    key.startsWith(LEGACY_OVERRIDES_PREFIX) ->
                        overrides(key.removePrefix(LEGACY_OVERRIDES_PREFIX))
                    key.startsWith(LEGACY_GEO_PREFIX) ->
                        geolocation(key.removePrefix(LEGACY_GEO_PREFIX))
                    else -> continue
                }
                if (target != retiredRainKey && !prefs.contains(target)) {
                    when (value) {
                        is String -> editor.putString(target, value)
                        is Boolean -> editor.putBoolean(target, value)
                        else -> {}
                    }
                }
                editor.remove(key)
            }
        }
        for (pointer in listOf(BundleStore.KEY_ACTIVE, BundleStore.KEY_PREVIOUS_ACTIVE)) {
            val old = prefs.getString(pointer, null)
            val migrated = migrateBundledId(old)
            if (migrated != old && migrated != null) editor.putString(pointer, migrated)
        }
        editor.remove(retiredRainKey)
        editor.putInt(SCHEMA_KEY, SCHEMA_VERSION).apply()
    }
}
