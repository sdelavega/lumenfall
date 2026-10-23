// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.importer

import android.content.Context
import android.util.Log
import com.sdelavega.lumenfall.core.CoreApi
import com.sdelavega.lumenfall.core.LumenfallCore
import com.sdelavega.lumenfall.storage.BundleMeta
import com.sdelavega.lumenfall.storage.BundleStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Imports a Lively-style bundle (a `.zip`) into app storage: extracts it safely,
 * locates and parses `LivelyInfo.json` via the Rust core, and persists metadata.
 *
 * Bundles are untrusted, so extraction is zip-slip guarded and the whole import
 * is transactional — any failure deletes the partial bundle dir.
 */
class BundleImporter(
    private val bundlesRoot: File,
    private val core: CoreApi = LumenfallCore,
    private val sink: ImportSink,
    private val logger: (String) -> Unit = { Log.w(TAG, it) }
) {
    constructor(context: Context) : this(BundleStore(context))

    private constructor(store: BundleStore) : this(store.bundlesDir, LumenfallCore, store)

    sealed interface Result {
        data class Ok(val meta: BundleMeta) : Result
        data class Error(val message: String) : Result
    }

    fun importZip(input: InputStream, fallbackName: String): Result {
        val id = "b" + System.currentTimeMillis()
        val dir = File(bundlesRoot, id)
        return try {
            dir.mkdirs()
            BundleFiles.extractZip(input, dir)

            val infoFile = BundleFiles.findLivelyInfo(dir)
                ?: return fail(dir, "No LivelyInfo.json in bundle")
            val validation = validateBundle(dir, infoFile)
                ?: return fail(dir, "Could not validate bundle")
            if (!validation.ok) return fail(dir, validation.error ?: "Bundle is not supported")
            validation.entryPath?.let { entryPath ->
                if (!File(dir, entryPath).isFile) {
                    return fail(dir, "Entry file is missing: $entryPath")
                }
            } ?: return fail(dir, "Bundle has no renderable entry file")

            // Web bundles run third-party script, so screen the collected JS/HTML
            // source through the Rust heuristics (trackers, miners, resource
            // hogs). Rejection aborts the import with the Rust-provided reason;
            // a "limited" verdict is persisted so the web renderer clamps FPS
            // and rate-limits network for this bundle.
            var screeningEnvelope: String? = null
            if (validation.kind == "web") {
                val collected = collectScriptSource(dir, logger)
                val raw = core.screenBundle(collected.source)
                val report = JSONObject(raw)
                if (report.optString("classification") == "rejected") {
                    val issues = report.optJSONArray("issues")
                        ?.let { arr -> (0 until arr.length()).joinToString("; ") { arr.getString(it) } }
                    return fail(dir, "Blocked at import: ${issues ?: "failed content screening"}")
                }
                screeningEnvelope =
                    if (collected.skipped.isNotEmpty() && report.optString("classification") == "allowed") {
                        downgradeToLimited(report, collected.skipped.size).toString()
                    } else {
                        raw
                    }
            }

            val meta = BundleMeta(
                id = id,
                title = validation.title.ifBlank { fallbackName },
                kind = validation.kind,
                author = validation.author,
                entryPath = validation.entryPath,
                previewPath = validation.previewPath,
                importedAt = System.currentTimeMillis()
            )
            sink.saveMeta(meta)
            screeningEnvelope?.let { sink.saveScreeningEnvelope(id, it) }

            // Optional: configurable properties (LivelyProperties.json, next to
            // LivelyInfo.json). Parsed by the Rust core; absent is fine.
            val propsSource = infoFile.parentFile
                ?.listFiles()
                ?.firstOrNull { it.name.equals("LivelyProperties.json", ignoreCase = true) }
            if (propsSource != null) {
                runCatching {
                    sink.savePropertiesEnvelope(id, core.parseLivelyProperties(propsSource.readText()))
                }.onFailure { logger("Could not save properties for $id: $it") }
            }

            Result.Ok(meta)
        } catch (t: Throwable) {
            fail(dir, t.message ?: t.toString())
        }
    }

    private fun fail(dir: File, message: String): Result {
        dir.deleteRecursively()
        return Result.Error(message)
    }

    private data class Validation(
        val ok: Boolean,
        val error: String?,
        val title: String,
        val author: String?,
        val kind: String,
        val entryPath: String?,
        val previewPath: String?
    )

    private fun validateBundle(dir: File, infoFile: File): Validation? {
        val o = JSONObject(
            core.validateBundle(
                infoJson = infoFile.readText(),
                basePath = BundleFiles.metadataBasePath(dir, infoFile),
                filesJson = BundleFiles.fileInventory(dir).toString()
            )
        )
        return Validation(
            ok = o.optBoolean("ok", false),
            error = o.stringOrNull("error"),
            title = o.optString("title", "Untitled"),
            author = o.stringOrNull("author"),
            kind = o.optString("kind", "unsupported"),
            entryPath = o.stringOrNull("entry_path"),
            previewPath = o.stringOrNull("preview_path")
        )
    }
}

/** Persistence boundary of an import; [BundleStore] is the production implementation. */
interface ImportSink {
    fun saveMeta(meta: BundleMeta)
    fun saveScreeningEnvelope(id: String, envelope: String)
    fun savePropertiesEnvelope(id: String, envelope: String)
}

internal data class ScriptCollection(val source: String, val skipped: List<String>)

private const val TAG = "BundleImporter"
private val SCRIPT_EXTENSIONS = setOf("js", "html", "htm")
private const val PER_FILE_SCRIPT_CAP = 1_000_000 // 1 MB, matches AetherDesk
private const val TOTAL_SCRIPT_CAP = 8_000_000

/**
 * Concatenates every .js/.html/.htm file in the bundle (per-file and total
 * caps so a hostile bundle can't OOM the import) — the input the Rust
 * screener inspects. Mirrors ÆtherDesk's collectAllScriptSource.
 *
 * Files the screener never saw — unreadable, or left out because the total cap
 * was reached — are returned in `skipped` (bundle-relative paths) and logged.
 * Files merely truncated at the per-file cap are not skipped: the screener saw
 * their head.
 */
internal fun collectScriptSource(dir: File, logger: (String) -> Unit = { Log.w(TAG, it) }): ScriptCollection {
    val sb = StringBuilder()
    val skipped = mutableListOf<String>()
    dir.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in SCRIPT_EXTENSIONS }
        .forEach { file ->
            val rel = file.relativeTo(dir).invariantSeparatorsPath
            if (sb.length >= TOTAL_SCRIPT_CAP) {
                skipped += rel
                logger("Screening skipped $rel: total script cap reached")
                return@forEach
            }
            try {
                file.inputStream().use { input ->
                    val bytes = input.readNBytes(PER_FILE_SCRIPT_CAP)
                    sb.append(String(bytes, Charsets.UTF_8)).append('\n')
                }
            } catch (e: Exception) {
                skipped += rel
                logger("Screening skipped $rel: $e")
            }
        }
    return ScriptCollection(sb.toString(), skipped)
}

/**
 * Rewrites an "allowed" screening envelope to "limited" because some script
 * files could not be screened. Every other field is kept; the note is added to
 * `issues` and to `warnings`, which is what [BundleScreening] (and so the UI) reads.
 */
private fun downgradeToLimited(report: JSONObject, skippedCount: Int): JSONObject {
    val note = "$skippedCount script file(s) could not be read for screening"
    report.put("classification", "limited")
    for (key in listOf("issues", "warnings")) {
        val arr = report.optJSONArray(key) ?: JSONArray().also { report.put(key, it) }
        arr.put(note)
    }
    return report
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)
