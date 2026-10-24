// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundledThumbnailCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun seed(dir: File) {
        File(dir, "neon-city.lumenfall-thumb.jpg").writeText("thumb")
        File(dir, "rain.lumenfall-thumb-failed").writeText("")
    }

    @Test
    fun sameVersionKeepsEntriesAndMarkers() {
        val dir = tmp.newFolder("cache")
        BundledThumbnailCache.invalidateIfStale(dir, 10000)
        seed(dir)
        BundledThumbnailCache.invalidateIfStale(dir, 10000)
        assertTrue(File(dir, "neon-city.lumenfall-thumb.jpg").exists())
        assertTrue(File(dir, "rain.lumenfall-thumb-failed").exists())
    }

    @Test
    fun newVersionDiscardsEntriesAndMarkers() {
        val dir = tmp.newFolder("cache")
        BundledThumbnailCache.invalidateIfStale(dir, 10000)
        seed(dir)
        BundledThumbnailCache.invalidateIfStale(dir, 10001)
        assertFalse(File(dir, "neon-city.lumenfall-thumb.jpg").exists())
        assertFalse(File(dir, "rain.lumenfall-thumb-failed").exists())
        // The new version is stamped, so the next call keeps fresh entries.
        seed(dir)
        BundledThumbnailCache.invalidateIfStale(dir, 10001)
        assertTrue(File(dir, "neon-city.lumenfall-thumb.jpg").exists())
    }

    @Test
    fun unstampedCacheFromBefore_theFix_isDiscarded() {
        val dir = tmp.newFolder("cache")
        seed(dir)
        BundledThumbnailCache.invalidateIfStale(dir, 10000)
        assertFalse(File(dir, "neon-city.lumenfall-thumb.jpg").exists())
        assertEquals("10000", File(dir, ".version").readText())
    }

    @Test
    fun downgradeAlsoInvalidates() {
        val dir = tmp.newFolder("cache")
        BundledThumbnailCache.invalidateIfStale(dir, 10001)
        seed(dir)
        BundledThumbnailCache.invalidateIfStale(dir, 10000)
        assertFalse(File(dir, "neon-city.lumenfall-thumb.jpg").exists())
    }
}
