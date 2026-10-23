// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.service

import java.time.LocalDate
import java.time.Month

/** 12 October, any year. */
fun isUniversityDay(date: LocalDate): Boolean =
    date.month == Month.OCTOBER && date.dayOfMonth == 12

/** Appends the `#carolina` fragment the script-free placeholder styles itself from. */
fun placeholderUrl(base: String, date: LocalDate, forced: Boolean): String =
    if (forced || isUniversityDay(date)) "$base#carolina" else base

/** Release-safe hook: only the debug source set ever sets it. */
object DebugHooks {
    @Volatile var forceUniversityDay = false
}
