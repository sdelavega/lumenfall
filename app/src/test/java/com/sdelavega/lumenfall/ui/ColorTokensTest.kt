// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorTokensTest {
    @Test fun carolinaResolvesToHex() {
        assertEquals("#4B9CD3", ColorTokens.resolve("carolina"))
    }

    @Test fun caseAndWhitespaceInsensitive() {
        assertEquals("#4B9CD3", ColorTokens.resolve("  Carolina "))
        assertEquals("#4B9CD3", ColorTokens.resolve("CAROLINA"))
    }

    @Test fun hexAndJunkAreNotTokens() {
        assertNull(ColorTokens.resolve("#4b9cd3"))
        assertNull(ColorTokens.resolve("carolin"))
        assertNull(ColorTokens.resolve("carolina blue"))
        assertNull(ColorTokens.resolve(""))
    }
}
