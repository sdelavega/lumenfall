// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import java.io.File

/**
 * Versioning for the `bundled-thumbnails/` cache. Bundled wallpapers ship inside
 * the APK, so they can only change with an app update; the cache is therefore
 * stamped with the app's versionCode and wiped (thumbnails *and* failed-markers)
 * whenever the stamp differs. versionCode is free to read and every release that
 * re-pins a wallpaper bumps it, unlike hashing every bundled file on each launch.
 */
internal object BundledThumbnailCache {

    private const val STAMP = ".version"

    fun invalidateIfStale(dir: File, versionCode: Long) {
        val stamp = File(dir, STAMP)
        val current = runCatching { stamp.readText().trim() }.getOrNull()
        if (current == versionCode.toString()) return
        dir.listFiles()?.forEach { it.deleteRecursively() }
        stamp.writeText(versionCode.toString())
    }
}
