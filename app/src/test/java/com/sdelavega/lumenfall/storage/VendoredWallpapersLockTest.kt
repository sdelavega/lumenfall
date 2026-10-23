// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.storage

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the vendored ÆtherDesk wallpapers to `wallpapers.lock`. Gradle runs with cwd `app/`. */
class VendoredWallpapersLockTest {
    private val root = File("src/main/assets/wallpapers")
    private val folders = listOf("WeatherAether", "GradientWave", "MatrixRain", "NeonCity")

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun lockMatchesFiles() {
        val lock = File(root, "wallpapers.lock")
        assertTrue("missing ${lock.path}", lock.isFile)
        val lines = lock.readLines().filter { it.isNotBlank() }

        assertEquals("upstream https://github.com/sdelavega/AetherDesk", lines.getOrNull(0))
        val commit = lines.getOrNull(1).orEmpty()
        assertTrue("bad commit line: $commit", Regex("commit [0-9a-f]{40}").matches(commit))

        val listed = linkedMapOf<String, String>()
        for (line in lines.drop(2)) {
            val m = Regex("([0-9a-f]{64})  (.+)").matchEntire(line)
            assertTrue("malformed lock line: $line", m != null)
            val (hash, path) = m!!.destructured
            assertTrue("duplicate lock entry: $path", listed.put(path, hash) == null)
        }
        assertEquals("lock must be sorted by path", listed.keys.sorted(), listed.keys.toList())

        for ((path, hash) in listed) {
            val file = File(root, path)
            assertTrue("listed file missing: $path", file.isFile)
            assertEquals("sha256 mismatch: $path", hash, sha256(file))
        }

        val actual = folders.flatMap { name ->
            val dir = File(root, name)
            assertTrue("missing folder $name", dir.isDirectory)
            dir.walkTopDown().filter { it.isFile }.map {
                it.relativeTo(root).invariantSeparatorsPath
            }.toList()
        }
        val unlisted = actual.filter { it !in listed }
        assertTrue("unlisted file(s): $unlisted", unlisted.isEmpty())
        val outside = listed.keys.filter { p -> folders.none { p.startsWith("$it/") } }
        assertTrue("lock entries outside the four folders: $outside", outside.isEmpty())
    }
}
