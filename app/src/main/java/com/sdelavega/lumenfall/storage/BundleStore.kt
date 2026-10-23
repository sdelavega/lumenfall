// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.storage

import android.content.Context
import org.json.JSONObject
import com.sdelavega.lumenfall.core.LumenfallCore
import com.sdelavega.lumenfall.render.BundleDeletedBus
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

/** Parse a stored overrides blob; null, corrupt, or non-object text yields an empty object. */
internal fun parseOverrides(text: String?): JSONObject =
    if (text == null) JSONObject() else runCatching { JSONObject(text) }.getOrDefault(JSONObject())

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
    private val schemaCache = ConcurrentHashMap<String, PropertyParseResult>()

    init {
        PrefKeys.migrate(prefs)
    }

    fun bundleDir(id: String) = File(bundlesDir, id)
    private fun metaFile(id: String) = File(bundleDir(id), META_NAME)

    fun list(): List<BundleMeta> {
        val bundled = BundledWallpapers.list(app)
        val imported = bundlesDir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir -> runCatching { BundleMeta.fromJson(File(dir, META_NAME).readText()) }.getOrNull() }
            ?.sortedByDescending { it.importedAt }
            ?: emptyList()
        return bundled + imported
    }

    fun get(id: String): BundleMeta? =
        if (BundledWallpapers.isBundled(id)) BundledWallpapers.get(app, id)
        else runCatching { BundleMeta.fromJson(metaFile(id).readText()) }.getOrNull()

    fun saveMeta(meta: BundleMeta) = metaFile(meta.id).writeText(meta.toJson())

    fun delete(id: String) {
        if (BundledWallpapers.isBundled(id)) return // can't delete a bundled wallpaper
        bundleDir(id).deleteRecursively()
        schemaCache.remove(id)
        prefs.edit().remove(PrefKeys.geolocation(id)).remove(PrefKeys.overrides(id)).apply()
        if (activeId == id) activeId = null
        if (previousActiveId == id) previousActiveId = null
        BundleDeletedBus.notifyDeleted(id)
    }

    var activeId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, value)
            }.apply()
        }

    var previousActiveId: String?
        get() = prefs.getString(KEY_PREVIOUS_ACTIVE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_PREVIOUS_ACTIVE) else putString(KEY_PREVIOUS_ACTIVE, value)
            }.apply()
        }

    fun active(): BundleMeta? = activeId?.let(::get)

    fun fileFor(id: String, rel: String): File = File(bundleDir(id), rel)

    // --- Properties (schema from LivelyProperties.json) + user overrides --------

    private fun propsFile(id: String) = File(bundleDir(id), PROPS_NAME)
    private fun overridesFile(id: String) = File(bundleDir(id), OVERRIDES_NAME)

    /** Persist the Rust `parseLivelyProperties` envelope for later reads. */
    fun savePropertiesEnvelope(id: String, envelope: String) {
        propsFile(id).writeText(envelope)
        schemaCache.remove(id)
    }

    /** Typed property schema for a bundle, parsed once and cached until import or delete. */
    fun propertySchema(id: String): PropertyParseResult =
        schemaCache.getOrPut(id) { loadPropertySchema(id) }

    private fun loadPropertySchema(id: String): PropertyParseResult = try {
        val envelope = if (BundledWallpapers.isBundled(id)) {
            BundledWallpapers.propertiesEnvelope(app, id)?.let { LumenfallCore.parseLivelyProperties(it) }
        } else {
            propsFile(id).takeIf { it.isFile }?.readText()
        }
        if (envelope == null) PropertyParseResult.Ok(emptyList())
        else PropertySchemaParser.parseEnvelope(envelope)
    } catch (e: Exception) {
        PropertyParseResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    @Deprecated("Task 11 removes this; use propertySchema")
    fun properties(id: String): List<BundleProperty> =
        if (BundledWallpapers.isBundled(id)) {
            // Bundled wallpapers: parse from assets via Rust, cached per call.
            // No on-disk properties.json to read from.
            val envelope = BundledWallpapers.propertiesEnvelope(app, id) ?: return emptyList()
            runCatching {
                val parsed = com.sdelavega.lumenfall.core.LumenfallCore.parseLivelyProperties(envelope)
                BundleProperty.parseEnvelope(parsed)
            }.getOrDefault(emptyList())
        } else {
            runCatching { BundleProperty.parseEnvelope(propsFile(id).readText()) }.getOrDefault(emptyList())
        }

    fun overrides(id: String): JSONObject =
        if (BundledWallpapers.isBundled(id)) {
            // Overrides for bundled wallpapers live in SharedPreferences only
            // (there's no on-disk overrides.json).
            parseOverrides(prefs.getString(PrefKeys.overrides(id), null))
        } else {
            parseOverrides(runCatching { overridesFile(id).readText() }.getOrNull())
        }

    /** Set (or clear, when [value] is null) one user override and persist it. */
    fun setOverride(id: String, key: String, value: Any?) {
        val o = overrides(id)
        if (value == null) o.remove(key) else o.put(key, value)
        if (BundledWallpapers.isBundled(id)) {
            prefs.edit().putString(PrefKeys.overrides(id), o.toString()).apply()
        } else {
            overridesFile(id).writeText(o.toString())
        }
    }

    /** Property defaults overlaid with user overrides — what the bridge gets. */
    fun effectiveValues(id: String): JSONObject {
        val out = JSONObject()
        val props = (propertySchema(id) as? PropertyParseResult.Ok)?.properties ?: emptyList()
        val ov = overrides(id)
        props.forEach { p ->
            val value = if (ov.has(p.key)) ov.opt(p.key) else defaultOf(p)
            if (value != null) out.put(p.key, bridgeValue(p, value))
        }
        ov.keys().forEach { k ->
            if (props.none { it.key == k }) out.put(k, ov.get(k))
        }
        return out
    }

    private fun defaultOf(p: PropertySchema): Any? = when (p) {
        is PropertySchema.Slider -> p.default
        is PropertySchema.Color -> p.default
        is PropertySchema.Checkbox -> p.default
        is PropertySchema.Dropdown -> p.defaultIndex
        is PropertySchema.FolderDropdown -> p.default.takeIf { it.isNotEmpty() }
        is PropertySchema.Text -> p.default
        is PropertySchema.Button, is PropertySchema.Label -> null
    }

    private fun bridgeValue(property: PropertySchema, value: Any): Any {
        if (property !is PropertySchema.FolderDropdown || value !is String) return value
        val folder = property.folder.trim().trim('/')
        if (folder.isEmpty() || value.contains("/") || value.startsWith("blob:") ||
            value.startsWith("data:") || value.startsWith("http://") || value.startsWith("https://")
        ) {
            return value
        }
        return "$folder/$value"
    }

    // --- Screening (Rust screenBundle report, web bundles only) -----------------

    private fun screeningFile(id: String) = File(bundleDir(id), SCREENING_NAME)

    /** Persist the Rust `screenBundle` envelope for later reads. */
    fun saveScreeningEnvelope(id: String, envelope: String) =
        screeningFile(id).writeText(envelope)

    /**
     * The screening result for a bundle, or null when none was recorded (non-web
     * kinds, or bundles imported before screening existed — treated as limited
     * by the web renderer, the conservative default).
     */
    fun screening(id: String): BundleScreening? =
        if (BundledWallpapers.isBundled(id)) {
            // Bundled wallpapers ship with the app — trusted, always "allowed".
            BundleScreening(
                classification = "allowed",
                warnings = emptyList(),
                fpsCap = 30,
                networkBudgetPerMinute = 5
            )
        } else {
            runCatching { BundleScreening.fromJson(screeningFile(id).readText()) }.getOrNull()
        }

    /**
     * Per-bundle geolocation opt-in. Defaults off. Only takes effect for web
     * bundles the import screener classified "allowed" (see [GeolocationPolicy]);
     * a "limited" bundle can't use geolocation even with this on, so the
     * settings UI doesn't surface the toggle for those bundles.
     */
    fun geolocationEnabled(id: String): Boolean = prefs.getBoolean(PrefKeys.geolocation(id), false)

    fun setGeolocationEnabled(id: String, enabled: Boolean) =
        prefs.edit().putBoolean(PrefKeys.geolocation(id), enabled).apply()

    companion object {
        const val META_NAME = "bundle.json"
        const val PROPS_NAME = "properties.json"
        const val OVERRIDES_NAME = "overrides.json"
        const val SCREENING_NAME = "screening.json"
        private const val KEY_ACTIVE = "active_bundle_id"
        private const val KEY_PREVIOUS_ACTIVE = "previous_active_bundle_id"
    }
}

/** Parsed `screening.json` — the Rust screenBundle envelope. */
data class BundleScreening(
    val classification: String, // allowed | limited | rejected
    val warnings: List<String>,
    val fpsCap: Int,
    val networkBudgetPerMinute: Int
) {
    val limited: Boolean get() = classification != "allowed"

    companion object {
        fun fromJson(text: String): BundleScreening {
            val o = JSONObject(text)
            val warnings = o.optJSONArray("warnings")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList()
            return BundleScreening(
                classification = o.optString("classification", "limited"),
                warnings = warnings,
                fpsCap = o.optInt("fps_cap", 30),
                networkBudgetPerMinute = o.optInt("network_budget_per_minute", 5)
            )
        }
    }
}
