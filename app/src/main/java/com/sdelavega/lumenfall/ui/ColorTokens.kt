// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.ui

/** Named colour shortcuts accepted by the wallpaper Color controls. Bundles only ever see hex. */
object ColorTokens {
    private const val CAROLINA = "#4B9CD3"

    /** The hex for a recognised token (case-insensitive, trimmed), else null. */
    fun resolve(input: String): String? =
        if (input.trim().equals("carolina", ignoreCase = true)) CAROLINA else null
}
