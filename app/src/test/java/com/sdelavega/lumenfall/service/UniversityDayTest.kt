// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.service

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversityDayTest {
    @Test fun october12IsUniversityDayInAnyYear() {
        for (y in listOf(1999, 2024, 2026, 2100)) assertTrue(isUniversityDay(LocalDate.of(y, 10, 12)))
    }

    @Test fun neighbouringDaysAreNot() {
        assertFalse(isUniversityDay(LocalDate.of(2026, 10, 11)))
        assertFalse(isUniversityDay(LocalDate.of(2026, 10, 13)))
        assertFalse(isUniversityDay(LocalDate.of(2026, 12, 10)))
    }

    @Test fun placeholderUrlGetsFragmentOnlyOnUniversityDay() {
        assertEquals("u#carolina", placeholderUrl("u", LocalDate.of(2026, 10, 12), forced = false))
        assertEquals("u", placeholderUrl("u", LocalDate.of(2026, 10, 13), forced = false))
        assertEquals("u#carolina", placeholderUrl("u", LocalDate.of(2026, 10, 13), forced = true))
    }
}
