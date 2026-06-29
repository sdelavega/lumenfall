package com.aetherdesk.lumenfall.importer

import android.content.Context
import com.aetherdesk.lumenfall.core.LumenfallCore
import com.aetherdesk.lumenfall.storage.BundleMeta
import com.aetherdesk.lumenfall.storage.BundleStore
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

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
            extractZip(input, dir)

            val infoFile = findLivelyInfo(dir) ?: return fail(dir, "No LivelyInfo.json in bundle")
            val baseDir = infoFile.parentFile!!
            val info = parseInfo(infoFile.readText()) ?: return fail(dir, "Could not parse LivelyInfo.json")
            val entryPath = if (info.fileName != null) {
                safeRelativeFile(dir, baseDir, info.fileName)
                    ?: return fail(dir, "Entry file escapes or is missing: ${info.fileName}")
            } else {
                inferEntryPath(dir, baseDir, info.kind)
            }

            val meta = BundleMeta(
                id = id,
                title = info.title.ifBlank { fallbackName },
                kind = info.kind,
                author = info.author,
                entryPath = entryPath,
                previewPath = info.preview?.let { safeRelativeFile(dir, baseDir, it) },
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

    /** Extract every entry into [destDir], rejecting any path that escapes it. */
    private fun extractZip(input: InputStream, destDir: File) {
        val root = destDir.canonicalFile
        val prefix = root.path + File.separator
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(root, entry.name).canonicalFile
                // zip-slip guard: the resolved path must stay under root.
                if (out.path != root.path && !out.path.startsWith(prefix)) {
                    throw SecurityException("Zip entry escapes target: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** Shallowest `LivelyInfo.json` (bundles sometimes nest under one folder). */
    private fun findLivelyInfo(dir: File): File? =
        dir.walkTopDown()
            .filter { it.isFile && it.name.equals("LivelyInfo.json", ignoreCase = true) }
            .minByOrNull { it.relativeTo(dir).path.count { c -> c == File.separatorChar } }

    /**
     * Resolve a Lively metadata path relative to the directory containing
     * LivelyInfo.json and return a canonical bundle-relative path. Rejects
     * traversal outside the imported bundle and missing files.
     */
    private fun safeRelativeFile(rootDir: File, baseDir: File, name: String): String? {
        val root = rootDir.canonicalFile
        val file = File(baseDir, name).canonicalFile
        val prefix = root.path + File.separator
        if (file.path != root.path && !file.path.startsWith(prefix)) return null
        if (!file.isFile) return null
        return file.relativeTo(root).invariantSeparatorsPath
    }

    /** Lively metadata usually names the entry, but simple bundles may omit it. */
    private fun inferEntryPath(rootDir: File, baseDir: File, kind: String): String? {
        val names = when (kind) {
            "web" -> WEB_ENTRIES
            "video" -> VIDEO_EXTENSIONS
            "image" -> IMAGE_EXTENSIONS
            else -> return null
        }
        val candidates = baseDir.walkTopDown()
            .filter { it.isFile && !it.nameWithoutExtension.contains("preview", ignoreCase = true) }
        val found = if (kind == "web") {
            candidates.firstOrNull { f -> names.any { f.name.equals(it, ignoreCase = true) } }
        } else {
            candidates.firstOrNull { f -> names.any { f.extension.equals(it, ignoreCase = true) } }
        } ?: return null
        return found.canonicalFile.relativeTo(rootDir.canonicalFile).invariantSeparatorsPath
    }

    private data class Info(
        val title: String,
        val author: String?,
        val kind: String,
        val fileName: String?,
        val preview: String?
    )

    private fun parseInfo(json: String): Info? {
        val o = JSONObject(LumenfallCore.parseLivelyInfo(json))
        if (!o.optBoolean("ok", false)) return null
        return Info(
            title = o.optString("title", "Untitled"),
            author = o.stringOrNull("author"),
            kind = o.optString("kind", "unsupported"),
            fileName = o.stringOrNull("file_name"),
            preview = o.stringOrNull("preview_name")
        )
    }

    private companion object {
        val WEB_ENTRIES = setOf("index.html", "index.htm")
        val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "mkv")
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    }
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)
