// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.content.res.AssetManager

/**
 * The ÆtherDesk-compatible bridge shim lives in `assets/bridge/aetherdesk-shim.js`
 * so it can be syntax-checked and reviewed as JavaScript. [compose] prefixes it
 * with the per-view values the shim reads (and then deletes) from
 * `window.__lumenfallInit`.
 */
object ShimSource {
    private const val ASSET_PATH = "bridge/aetherdesk-shim.js"

    @Volatile
    private var cached: String? = null

    /** Reads the shim asset once; later calls return the cached text. */
    fun load(assets: AssetManager): String =
        cached ?: synchronized(this) {
            cached ?: assets.open(ASSET_PATH).bufferedReader().use { it.readText() }.also { cached = it }
        }

    /** [propertiesJson] must already be valid JSON (an object literal). */
    fun compose(shim: String, propertiesJson: String, fpsCap: Int, initiallySuspended: Boolean): String =
        "window.__lumenfallInit={properties:$propertiesJson,fpsCap:$fpsCap,suspended:$initiallySuspended};\n" + shim
}
