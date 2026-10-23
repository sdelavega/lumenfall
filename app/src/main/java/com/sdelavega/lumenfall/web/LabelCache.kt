// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory place-label cache keyed by coordinates rounded to 2 decimals
 * (~1 km). Filled asynchronously by the geocoder, read synchronously when
 * building a position result.
 */
class LabelCache {
    private val labels = ConcurrentHashMap<String, String>()

    private fun key(lat: Double, lon: Double) =
        String.format(Locale.US, "%.2f,%.2f", lat, lon)

    fun get(lat: Double, lon: Double): String? = labels[key(lat, lon)]

    fun put(lat: Double, lon: Double, label: String) {
        labels[key(lat, lon)] = label
    }
}
