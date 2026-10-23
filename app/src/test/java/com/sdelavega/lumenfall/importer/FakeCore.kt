// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.importer

import com.sdelavega.lumenfall.core.CoreApi
import org.json.JSONArray
import org.json.JSONObject

/** Canned [CoreApi]; the validate response mirrors Rust's BundleValidationResponse. */
class FakeCore(
    var validation: String = validationJson(),
    var screening: String = screeningJson("allowed"),
    var properties: String = "{}"
) : CoreApi {
    var lastScreenedSource: String? = null
    var onValidate: () -> Unit = {}

    override fun parseLivelyInfo(json: String): String = "{}"
    override fun parseLivelyProperties(json: String): String = properties
    override fun validateBundle(infoJson: String, basePath: String, filesJson: String): String {
        onValidate()
        return validation
    }
    override fun blocklistJson(): String = """{"domains":[],"hosts":[],"suffixes":[]}"""
    override fun screenBundle(scriptSource: String): String {
        lastScreenedSource = scriptSource
        return screening
    }

    companion object {
        fun validationJson(
            ok: Boolean = true,
            error: String? = null,
            kind: String = "web",
            entryPath: String? = "index.html"
        ): String = JSONObject().apply {
            put("ok", ok)
            put("error", error ?: JSONObject.NULL)
            put("title", "Fake")
            put("author", JSONObject.NULL)
            put("kind", kind)
            put("entry_path", entryPath ?: JSONObject.NULL)
            put("preview_path", JSONObject.NULL)
            put("warnings", JSONArray())
        }.toString()

        fun screeningJson(
            classification: String,
            warnings: List<String> = emptyList(),
            issues: List<String> = emptyList()
        ): String = JSONObject().apply {
            put("ok", true)
            put("error", JSONObject.NULL)
            put("classification", classification)
            put("warnings", JSONArray(warnings))
            put("issues", JSONArray(issues))
            put("fps_cap", 30)
            put("network_budget_per_minute", 5)
        }.toString()
    }
}
