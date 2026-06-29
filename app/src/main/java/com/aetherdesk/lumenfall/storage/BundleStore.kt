package com.aetherdesk.lumenfall.storage

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Metadata for one imported wallpaper bundle. The actual bundle files live in
 * `filesDir/bundles/<id>/`; this is persisted as `bundle.json` in that dir.
 *
 * [entryPath] / [previewPath] are relative to the bundle dir (a bundle may nest
 * its files one folder deep, so these are not always bare filenames).
 */
data class BundleMeta(
    val id: String,
    val title: String,
    val kind: String,          // web | video | image | unsupported
    val author: String?,
    val entryPath: String?,
    val previewPath: String?,
    val importedAt: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("kind", kind)
        put("author", author ?: JSONObject.NULL)
        put("entryPath", entryPath ?: JSONObject.NULL)
        put("previewPath", previewPath ?: JSONObject.NULL)
        put("importedAt", importedAt)
    }.toString()

    companion object {
        fun fromJson(text: String): BundleMeta {
            val o = JSONObject(text)
            return BundleMeta(
                id = o.getString("id"),
                title = o.optString("title", "Untitled"),
                kind = o.optString("kind", "unsupported"),
                author = o.stringOrNull("author"),
                entryPath = o.stringOrNull("entryPath"),
                previewPath = o.stringOrNull("previewPath"),
                importedAt = o.optLong("importedAt", 0L)
            )
        }
    }
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)

/**
 * Owns the on-disk bundle directory and the "active bundle" pointer. Shared by
 * the picker UI and the wallpaper service (same process), so SharedPreferences
 * is a sufficient store for the active id.
 */
class BundleStore(context: Context) {
    private val app = context.applicationContext
    val bundlesDir: File = File(app.filesDir, "bundles").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("lumenfall", Context.MODE_PRIVATE)

    fun bundleDir(id: String) = File(bundlesDir, id)
    private fun metaFile(id: String) = File(bundleDir(id), META_NAME)

    fun list(): List<BundleMeta> =
        bundlesDir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir -> runCatching { BundleMeta.fromJson(File(dir, META_NAME).readText()) }.getOrNull() }
            ?.sortedByDescending { it.importedAt }
            ?: emptyList()

    fun get(id: String): BundleMeta? =
        runCatching { BundleMeta.fromJson(metaFile(id).readText()) }.getOrNull()

    fun saveMeta(meta: BundleMeta) = metaFile(meta.id).writeText(meta.toJson())

    fun delete(id: String) {
        bundleDir(id).deleteRecursively()
        if (activeId == id) activeId = null
    }

    var activeId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, value)
            }.apply()
        }

    fun active(): BundleMeta? = activeId?.let(::get)

    fun fileFor(id: String, rel: String): File = File(bundleDir(id), rel)

    // --- Properties (schema from LivelyProperties.json) + user overrides --------

    private fun propsFile(id: String) = File(bundleDir(id), PROPS_NAME)
    private fun overridesFile(id: String) = File(bundleDir(id), OVERRIDES_NAME)

    /** Persist the Rust `parseLivelyProperties` envelope for later reads. */
    fun savePropertiesEnvelope(id: String, envelope: String) = propsFile(id).writeText(envelope)

    fun properties(id: String): List<BundleProperty> =
        runCatching { BundleProperty.parseEnvelope(propsFile(id).readText()) }.getOrDefault(emptyList())

    fun overrides(id: String): JSONObject =
        runCatching { JSONObject(overridesFile(id).readText()) }.getOrDefault(JSONObject())

    /** Set (or clear, when [value] is null) one user override and persist it. */
    fun setOverride(id: String, key: String, value: Any?) {
        val o = overrides(id)
        if (value == null) o.remove(key) else o.put(key, value)
        overridesFile(id).writeText(o.toString())
    }

    /** Property defaults overlaid with user overrides — what the bridge gets. */
    fun effectiveValues(id: String): JSONObject {
        val out = JSONObject()
        properties(id).forEach { p -> if (p.defaultValue != null) out.put(p.key, p.defaultValue) }
        val ov = overrides(id)
        ov.keys().forEach { k -> out.put(k, ov.get(k)) }
        return out
    }

    companion object {
        const val META_NAME = "bundle.json"
        const val PROPS_NAME = "properties.json"
        const val OVERRIDES_NAME = "overrides.json"
        private const val KEY_ACTIVE = "active_bundle_id"
    }
}
