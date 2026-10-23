// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.core

/**
 * Narrow Kotlin facade over the Rust core (`liblumenfall_core.so`, built by the
 * cargo-ndk Gradle task). The library is required: if it fails to load, that is a
 * build/packaging bug we want to surface immediately, not swallow — a missing
 * core means no bundle parsing at all.
 */
object LumenfallCore : CoreApi {
    init {
        System.loadLibrary("lumenfall_core")
    }

    override external fun parseLivelyInfo(json: String): String
    override external fun parseLivelyProperties(json: String): String
    override external fun validateBundle(infoJson: String, basePath: String, filesJson: String): String
    override external fun screenBundle(scriptSource: String): String
    override external fun blocklistJson(): String
}
