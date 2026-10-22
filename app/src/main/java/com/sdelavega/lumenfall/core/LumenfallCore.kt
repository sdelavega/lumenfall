package com.sdelavega.lumenfall.core

/**
 * Narrow Kotlin facade over the Rust core (`liblumenfall_core.so`, built by the
 * cargo-ndk Gradle task). The library is required: if it fails to load, that is a
 * build/packaging bug we want to surface immediately, not swallow — a missing
 * core means no bundle parsing at all.
 */
object LumenfallCore {
    init {
        System.loadLibrary("lumenfall_core")
    }

    external fun parseLivelyInfo(json: String): String
    external fun parseLivelyProperties(json: String): String
    external fun validateBundle(infoJson: String, basePath: String, filesJson: String): String
    external fun screenBundle(scriptSource: String): String
}
