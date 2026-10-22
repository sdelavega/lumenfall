// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the Kotlin `external fun`s and the Rust JNI exports in lockstep. */
class JniNamesTest {
    // Gradle runs unit tests with the working directory set to `app/`.
    private val root = File(System.getProperty("user.dir")).parentFile

    private val prefix = "Java_com_sdelavega_lumenfall_core_LumenfallCore_"

    @Test
    fun externalFunsMatchRustExports() {
        val kotlin = File(root, "app/src/main/java/com/sdelavega/lumenfall/core/LumenfallCore.kt").readText()
        val rust = File(root, "core-rust/src/lib.rs").readText()

        val externals = Regex("""external\s+fun\s+(\w+)""").findAll(kotlin).map { it.groupValues[1] }.toSortedSet()
        val exports = Regex("""fn\s+${prefix}(\w+)""").findAll(rust).map { it.groupValues[1] }.toSortedSet()

        assertTrue("no external funs found", externals.isNotEmpty())
        assertEquals(externals, exports)
    }
}
