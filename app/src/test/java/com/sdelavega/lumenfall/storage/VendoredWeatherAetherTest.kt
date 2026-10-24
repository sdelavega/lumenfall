// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.storage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the vendored WeatherAether bundle's IP-geolocation opt-in. The behavioural guarantee is
 * ÆtherDesk's own test at the pinned commit plus the network block; these tests catch a re-pin
 * that drops the gate. Gradle runs with cwd `app/`.
 */
class VendoredWeatherAetherTest {
    private val dir = File("src/main/assets/wallpapers/WeatherAether")
    private val weatherJs = File(dir, "js/weather.js")

    private fun bundleText(): String {
        val js = File(dir, "js").walkTopDown().filter { it.isFile && it.extension == "js" }.sortedBy { it.path }
        val all = js.toList() + File(dir, "index.html")
        return all.joinToString("\n") { it.readText() }
    }

    @Test
    fun ipFallbackGatedByCapability() {
        val text = bundleText()
        assertTrue(text.contains("capabilities"))
        assertTrue(text.contains("ipGeolocation"))
    }

    @Test
    fun shimNeverSetsIpGeolocation() {
        val shim = File("src/main/assets/bridge/aetherdesk-shim.js")
        assertTrue("missing shim", shim.isFile)
        assertFalse(shim.readText().contains("ipGeolocation"))
    }

    /**
     * Heuristic, line based: `hasHostCapability` must compare with `=== true`; and every line
     * naming `ipapi.co` or `bigdatacloud` must lie in a function (nearest preceding line starting
     * `function name(`) that, before that line, has an early return of the form
     * `if(!hasHostCapability(...))return ...`. Any unguarded host line fails the test.
     */
    @Test
    fun noUngatedIpServiceCalls() = assertIpServiceCallsGated(weatherJs)

    /** Neon City v3 ships the same shared weather module; it must carry the same gate. */
    @Test
    fun neonCitySharedWeatherGatesIpServiceCalls() =
        assertIpServiceCallsGated(File("src/main/assets/wallpapers/NeonCity/js/weather-shared/weather.js"))

    private fun assertIpServiceCallsGated(file: File) {
        assertTrue("missing ${file.path}", file.isFile)
        val lines = file.readLines()
        val def = lines.indexOfFirst { it.trimStart().startsWith("function hasHostCapability(") }
        assertTrue("hasHostCapability not defined", def >= 0)
        val defBody = lines.drop(def).take(6).joinToString("\n")
        assertTrue("capability check must use === true", defBody.contains("]===true"))

        val guard = Regex("""if\s*\(\s*!\s*hasHostCapability\(""")
        val fnStart = Regex("""^\s*function\s+\w+\s*\(""")
        val hostLines = lines.withIndex().filter { (_, l) ->
            !l.trimStart().startsWith("//") && (l.contains("ipapi.co") || l.contains("bigdatacloud"))
        }
        assertTrue("expected ipapi.co/bigdatacloud references", hostLines.isNotEmpty())
        for ((i, l) in hostLines) {
            val start = (i downTo 0).firstOrNull { fnStart.containsMatchIn(lines[it]) }
            assertTrue("host reference outside a function: ${l.trim()}", start != null)
            val guarded = (start!! until i).any { guard.containsMatchIn(lines[it]) && lines[it].contains("return") }
            assertTrue("ungated IP service reference at line ${i + 1}: ${l.trim()}", guarded)
        }
    }
}
