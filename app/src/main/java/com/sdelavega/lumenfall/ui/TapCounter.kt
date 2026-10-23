// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.ui

/** Counts rapid taps: [tap] returns true on the [threshold]th tap with no gap over [windowMs]. */
class TapCounter(
    private val threshold: Int = 7,
    private val windowMs: Long = 2000,
    private val now: () -> Long
) {
    private var count = 0
    private var last = 0L

    fun tap(): Boolean {
        val t = now()
        if (count > 0 && t - last > windowMs) count = 0
        last = t
        count++
        if (count >= threshold) {
            count = 0
            return true
        }
        return false
    }
}
