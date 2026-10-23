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
    private const val SCHEMA_VERSION = 2
    private const val LEGACY_OVERRIDES_PREFIX = "overrides_"
    private const val LEGACY_GEO_PREFIX = "geo_bundle_"

    fun overrides(id: String): String = "v2/overrides/" + URLEncoder.encode(id, Charsets.UTF_8.name())
    fun geolocation(id: String): String = "v2/geo/" + URLEncoder.encode(id, Charsets.UTF_8.name())

    /** Move legacy `overrides_<id>` / `geo_bundle_<id>` keys to their v2 names, once. */
    fun migrate(prefs: SharedPreferences) {
        if (prefs.getInt(SCHEMA_KEY, 0) >= SCHEMA_VERSION) return
        val editor = prefs.edit()
        for ((key, value) in prefs.all.toMap()) {
            val target = when {
                key.startsWith(LEGACY_OVERRIDES_PREFIX) ->
                    overrides(key.removePrefix(LEGACY_OVERRIDES_PREFIX))
                key.startsWith(LEGACY_GEO_PREFIX) ->
                    geolocation(key.removePrefix(LEGACY_GEO_PREFIX))
                else -> continue
            }
            if (!prefs.contains(target)) {
                when (value) {
                    is String -> editor.putString(target, value)
                    is Boolean -> editor.putBoolean(target, value)
                    else -> {}
                }
            }
            editor.remove(key)
        }
        editor.putInt(SCHEMA_KEY, SCHEMA_VERSION).apply()
    }
}
