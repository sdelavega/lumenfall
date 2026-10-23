// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShimAssetTest {
    // Gradle runs JVM unit tests with cwd = app/.
    private val shim = File("src/main/assets/bridge/aetherdesk-shim.js").readText()

    @Test
    fun composeInjectsInitPrefix() {
        val out = ShimSource.compose("SHIM();", """{"a":1}""", 45, true)
        assertEquals(
            "window.__lumenfallInit={properties:{\"a\":1},fpsCap:45,suspended:true};\nSHIM();",
            out
        )
        assertTrue(ShimSource.compose("x", "{}", 30, false).contains("suspended:false"))
    }

    @Test
    fun shimDefinesBridgeEntryPoints() {
        for (name in listOf("window.aetherDesk", "livelyPropertyListener", "_setProperty", "onPropertyChange", "fpsCap")) {
            assertTrue("missing $name", shim.contains(name))
        }
        assertTrue(shim.contains("window.__lumenfallInit"))
        assertTrue(shim.contains("delete window.__lumenfallInit"))
        assertFalse("no Kotlin template residue", shim.contains("\${'$'}") || shim.contains("\$initiallySuspended"))
    }

    @Test
    fun tickSuppressedWhileSuspended() {
        val tick = shim.substringAfter("var _frames = 0;").substringBefore("}, 2000)")
        assertTrue(tick.contains("setInterval"))
        assertTrue(tick.contains("if (suspended) return"))
    }

    @Test
    fun unknownHostCallsAreNoOps() {
        assertTrue(shim.contains("reportWeatherRendererStatus"))
        assertTrue(shim.contains("messageHandlers"))
    }
}
