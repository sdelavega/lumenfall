// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The licences asset must credit every bundled wallpaper and every third-party crate. Gradle runs with cwd `app/`. */
class LicensesAssetTest {
    private val wallpapers = File("src/main/assets/wallpapers")
    private val asset = File("src/main/assets/licenses/third_party.txt")

    @Test
    fun listsEveryBundledWallpaperAndCrate() {
        assertTrue("missing ${asset.path}", asset.isFile)
        val text = asset.readText()

        val folders = wallpapers.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }
        assertTrue("no wallpaper folders found", folders.isNotEmpty())
        for (name in folders) {
            assertTrue("wallpaper $name not listed", text.contains(name))
        }

        val lock = File("../Cargo.lock").readText()
        val crates = Regex("""\[\[package]]\s+name = "([^"]+)"\s+version = "([^"]+)"""")
            .findAll(lock).map { it.groupValues[1] to it.groupValues[2] }
            .filter { it.first != "lumenfall-core" }.toList()
        assertTrue("no crates found in Cargo.lock", crates.isNotEmpty())
        for ((name, version) in crates) {
            assertTrue("crate $name $version not listed", text.contains("$name $version"))
        }
    }
}
