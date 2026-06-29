package com.aetherdesk.lumenfall.storage

import org.json.JSONObject

/**
 * One configurable property from a bundle's `LivelyProperties.json`, as
 * normalized by the Rust core's `parseLivelyProperties`.
 *
 * [type] is the Lively control type (slider, color, checkbox, dropdown, textbox,
 * label, button, ...). [raw] carries the type-specific extras the settings UI
 * needs — e.g. `min`/`max`/`step` for a slider, `items` for a dropdown.
 */
data class BundleProperty(
    val key: String,
    val displayName: String,
    val type: String,
    val defaultValue: Any?,   // Boolean | Number | String | null
    val raw: JSONObject
) {
    companion object {
        /** Parse the Rust `parseLivelyProperties` envelope into a property list. */
        fun parseEnvelope(envelope: String): List<BundleProperty> {
            val root = JSONObject(envelope)
            if (!root.optBoolean("ok", false)) return emptyList()
            val arr = root.optJSONArray("properties") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                BundleProperty(
                    key = p.getString("key"),
                    displayName = p.optString("display_name", p.getString("key")),
                    type = p.optString("property_type", "unknown"),
                    defaultValue = if (p.isNull("default_value")) null else p.opt("default_value"),
                    raw = p.optJSONObject("raw") ?: JSONObject()
                )
            }
        }
    }
}
