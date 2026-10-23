// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.core

/** The Rust core's JNI surface as an interface, so importer logic can be tested with a fake. */
interface CoreApi {
    fun parseLivelyInfo(json: String): String
    fun parseLivelyProperties(json: String): String
    fun validateBundle(infoJson: String, basePath: String, filesJson: String): String
    fun screenBundle(scriptSource: String): String
    fun blocklistJson(): String
}
