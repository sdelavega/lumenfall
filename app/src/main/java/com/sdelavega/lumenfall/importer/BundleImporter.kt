package com.sdelavega.lumenfall.importer

import android.content.Context
import com.sdelavega.lumenfall.core.LumenfallCore
import com.sdelavega.lumenfall.storage.BundleMeta
import com.sdelavega.lumenfall.storage.BundleStore
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
class BundleImporter(context: Context) {
    private val store = BundleStore(context)

    sealed interface Result {
        data class Ok(val meta: BundleMeta) : Result
        data class Error(val message: String) : Result
    }

    fun importZip(input: InputStream, fallbackName: String): Result {
        val id = "b" + System.currentTimeMillis()
        val dir = store.bundleDir(id)
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
                val envelope = LumenfallCore.screenBundle(collectScriptSource(dir))
                val report = JSONObject(envelope)
                if (report.optString("classification") == "rejected") {
                    val issues = report.optJSONArray("issues")
                        ?.let { arr -> (0 until arr.length()).joinToString("; ") { arr.getString(it) } }
                    return fail(dir, "Blocked at import: ${issues ?: "failed content screening"}")
                }
                screeningEnvelope = envelope
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
            store.saveMeta(meta)
            screeningEnvelope?.let { store.saveScreeningEnvelope(id, it) }

            // Optional: configurable properties (LivelyProperties.json, next to
            // LivelyInfo.json). Parsed by the Rust core; absent is fine.
            val propsSource = infoFile.parentFile
                ?.listFiles()
                ?.firstOrNull { it.name.equals("LivelyProperties.json", ignoreCase = true) }
            if (propsSource != null) {
                runCatching {
                    store.savePropertiesEnvelope(id, LumenfallCore.parseLivelyProperties(propsSource.readText()))
                }
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

    /**
     * Concatenates every .js/.html/.htm file in the bundle (per-file and total
     * caps so a hostile bundle can't OOM the import) — the input the Rust
     * screener inspects. Mirrors ÆtherDesk's collectAllScriptSource.
     */
    private fun collectScriptSource(dir: File): String {
        val sb = StringBuilder()
        dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in SCRIPT_EXTENSIONS }
            .forEach { file ->
                if (sb.length >= TOTAL_SCRIPT_CAP) return@forEach
                runCatching {
                    file.inputStream().use { input ->
                        val bytes = input.readNBytes(PER_FILE_SCRIPT_CAP)
                        sb.append(String(bytes, Charsets.UTF_8)).append('\n')
                    }
                }
            }
        return sb.toString()
    }

    private companion object {
        val SCRIPT_EXTENSIONS = setOf("js", "html", "htm")
        const val PER_FILE_SCRIPT_CAP = 1_000_000 // 1 MB, matches AetherDesk
        const val TOTAL_SCRIPT_CAP = 8_000_000
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
            LumenfallCore.validateBundle(
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

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)
