// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.storage

import org.json.JSONException
import org.json.JSONObject

/** A typed, validated bundle property parsed from the Rust `parseLivelyProperties` envelope. */
sealed interface PropertySchema {
    val key: String
    val label: String

    data class Slider(
        override val key: String, override val label: String,
        val min: Double, val max: Double, val step: Double, val default: Double
    ) : PropertySchema

    data class Color(override val key: String, override val label: String, val default: String) : PropertySchema
    data class Checkbox(override val key: String, override val label: String, val default: Boolean) : PropertySchema
    data class Dropdown(
        override val key: String, override val label: String,
        val items: List<String>, val defaultIndex: Int,
        /** True when the envelope's default_value was a JSON string, so the bridge value is the item name. */
        val valueIsName: Boolean
    ) : PropertySchema

    data class FolderDropdown(
        override val key: String, override val label: String,
        val folder: String, val extensions: Set<String>, val default: String
    ) : PropertySchema

    data class Text(override val key: String, override val label: String, val default: String) : PropertySchema
    data class Button(override val key: String, override val label: String) : PropertySchema
    data class Label(override val key: String, override val label: String) : PropertySchema
}

sealed interface PropertyParseResult {
    data class Ok(val properties: List<PropertySchema>) : PropertyParseResult
    data class Failed(val reason: String) : PropertyParseResult
}

object PropertySchemaParser {
    fun parseEnvelope(envelope: String): PropertyParseResult {
        val root = try {
            JSONObject(envelope)
        } catch (_: JSONException) {
            return PropertyParseResult.Failed("invalid properties envelope")
        }
        if (!root.optBoolean("ok", false)) {
            return PropertyParseResult.Failed(root.optString("error", "invalid properties envelope"))
        }
        val arr = root.optJSONArray("properties") ?: return PropertyParseResult.Ok(emptyList())
        val out = ArrayList<PropertySchema>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            parseOne(p)?.let(out::add)
        }
        return PropertyParseResult.Ok(out)
    }

    private fun parseOne(p: JSONObject): PropertySchema? {
        val key = p.optString("key", "")
        if (key.isEmpty()) return null
        val label = p.optString("display_name", "").ifEmpty { key }
        val raw = p.optJSONObject("raw") ?: JSONObject()
        val def: Any? = if (p.isNull("default_value")) null else p.opt("default_value")
        return when (p.optString("property_type", "").lowercase()) {
            "slider" -> {
                var min = raw.optDouble("min", 0.0)
                var max = raw.optDouble("max", 100.0)
                if (min > max) { val t = min; min = max; max = t }
                val step = raw.optDouble("step", 1.0).let { if (it > 0.0) it else 1.0 }
                PropertySchema.Slider(key, label, min, max, step, (def as? Number)?.toDouble() ?: min)
            }
            "color" -> PropertySchema.Color(key, label, def as? String ?: "")
            "checkbox" -> PropertySchema.Checkbox(key, label, def as? Boolean ?: false)
            "dropdown" -> {
                val itemsArr = raw.optJSONArray("items")
                if (itemsArr == null) {
                    fallback(key, label, def)
                } else {
                    val items = (0 until itemsArr.length()).map { itemsArr.optString(it) }
                    // Mirrors the legacy settings consumer: a number is the index, a string is
                    // looked up in items, and the result is clamped to [0, lastIndex] (a
                    // string not found, a negative index, or an empty list all yield 0).
                    val valueIsName = def is String
                    val rawIndex = when (def) {
                        is Number -> def.toInt()
                        is String -> items.indexOf(def)
                        else -> 0
                    }
                    PropertySchema.Dropdown(key, label, items, rawIndex.coerceIn(0, maxOf(0, items.size - 1)), valueIsName)
                }
            }
            "folderdropdown" -> PropertySchema.FolderDropdown(
                key, label, raw.optString("folder", ""),
                extensionsFromFilter(raw.optString("filter", "")), def as? String ?: ""
            )
            "textbox" -> PropertySchema.Text(key, label, def as? String ?: "")
            "button" -> PropertySchema.Button(key, label)
            "label" -> PropertySchema.Label(key, label)
            else -> fallback(key, label, def)
        }
    }

    private fun fallback(key: String, label: String, def: Any?): PropertySchema =
        if (def is String) PropertySchema.Text(key, label, def) else PropertySchema.Label(key, label)

    private fun extensionsFromFilter(filter: String): Set<String> =
        filter.split('|')
            .mapNotNull { pattern ->
                pattern.substringAfterLast('.', missingDelimiterValue = "")
                    .takeIf { it.isNotBlank() && !it.contains('*') }
                    ?.lowercase()
            }
            .toSet()
}
