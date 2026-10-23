// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.storage

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Read-only bundled wallpapers shipped in `assets/wallpapers/`. Each is a
 * directory containing `LivelyInfo.json`, `index.html`, and optionally
 * `LivelyProperties.json`. They can't be deleted, don't have on-disk
 * `bundle.json`/`screening.json` files, and are treated as screening-clean
 * (classification "allowed") — they ship with the app, so they're trusted.
 *
 * A bundled wallpaper's [BundleMeta] is constructed at runtime from its
 * `LivelyInfo.json` via the Rust core. Its id is prefixed `bundled/` so it
 * can't collide with imported bundle ids (which are `b<timestamp>`).
 *
 * Property overrides for bundled wallpapers are stored in SharedPreferences
 * (same key scheme as imported bundles), not on disk — there's no bundle dir.
 */
/**
 * Maps a stored wallpaper id from a retired bundled wallpaper to its
 * replacement (`bundled/LivelyRain` became `bundled/Rainfall`); anything else,
 * including null, is returned unchanged.
 */
internal fun migrateBundledId(id: String?): String? =
    if (id == "bundled/LivelyRain") "bundled/Rainfall" else id

object BundledWallpapers {
    private const val ASSETS_DIR = "wallpapers"
    const val ID_PREFIX = "bundled/"

    /**
     * The folder name is the part after `bundled/` — e.g. `GradientWave`.
     * Used as both the display fallback and the asset path segment.
     */
    fun folderFor(id: String): String? =
        if (id.startsWith(ID_PREFIX)) id.removePrefix(ID_PREFIX) else null

    fun isBundled(id: String): Boolean = id.startsWith(ID_PREFIX)

    fun list(context: Context): List<BundleMeta> {
        val assetManager = context.assets
        val dirs = try {
            assetManager.list(ASSETS_DIR) ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
        return dirs.sorted().mapNotNull { dir -> metaFromAssets(context, dir) }
    }

    fun get(context: Context, id: String): BundleMeta? {
        val folder = folderFor(id) ?: return null
        return metaFromAssets(context, folder)
    }

    private fun metaFromAssets(context: Context, folder: String): BundleMeta? {
        val infoJson = try {
            context.assets.open("$ASSETS_DIR/$folder/LivelyInfo.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) { return null }

        val info = try {
            JSONObject(infoJson)
        } catch (e: Exception) { return null }

        val title = info.optString("Title", folder)
        val author = if (info.has("Author") && !info.isNull("Author"))
            info.getString("Author") else null
        val fileName = if (info.has("FileName") && !info.isNull("FileName"))
            info.getString("FileName") else "index.html"
        val kind = "web" // All bundled wallpapers are web type
        val preview = if (info.has("Preview") && !info.isNull("Preview"))
            info.getString("Preview") else null

        return BundleMeta(
            id = "$ID_PREFIX$folder",
            title = title,
            kind = kind,
            author = author,
            entryPath = fileName,
            previewPath = preview,
            importedAt = 0
        )
    }

    /**
     * Parse `LivelyProperties.json` from assets for a bundled wallpaper.
     * Returns the Rust envelope JSON string (same shape as
     * [BundleStore.savePropertiesEnvelope]), or null if none exists.
     */
    fun propertiesEnvelope(context: Context, id: String): String? {
        val folder = folderFor(id) ?: return null
        return try {
            context.assets.open("$ASSETS_DIR/$folder/LivelyProperties.json")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) { null }
    }
}
