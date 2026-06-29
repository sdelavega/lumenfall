package com.aetherdesk.lumenfall.importer

import android.content.Context
import com.aetherdesk.lumenfall.core.LumenfallCore
import com.aetherdesk.lumenfall.storage.BundleMeta
import com.aetherdesk.lumenfall.storage.BundleStore
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
