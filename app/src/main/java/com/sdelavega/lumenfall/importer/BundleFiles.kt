// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.importer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Extraction caps. The defaults mirror `MAX_SINGLE_FILE_BYTES`, `MAX_TOTAL_BYTES` and
 * `MAX_FILES` in `core-rust/src/bundle_validation.rs`; keep them in step. The Rust
 * validator only sees the finished on-disk inventory, so these stop a zip bomb
 * while it is still being written.
 */
data class ExtractLimits(val maxFileBytes: Long, val maxTotalBytes: Long, val maxEntries: Int) {
    companion object {
        val DEFAULT = ExtractLimits(
            maxFileBytes = 256L * 1024 * 1024,
            maxTotalBytes = 512L * 1024 * 1024,
            maxEntries = 2_000
        )
    }
}

/** Pure JVM file helpers for imported bundles; kept Android-free for tests. */
object BundleFiles {
    /**
     * Extract every entry into [destDir], rejecting any path that escapes it and
     * aborting with a [SecurityException] once inflated sizes or the entry count pass
     * [limits]. On any failure everything extracted so far is deleted.
     */
    fun extractZip(input: InputStream, destDir: File, limits: ExtractLimits = ExtractLimits.DEFAULT) {
        try {
            extract(input, destDir, limits)
        } catch (t: Throwable) {
            destDir.listFiles()?.forEach { it.deleteRecursively() }
            throw t
        }
    }

    private fun extract(input: InputStream, destDir: File, limits: ExtractLimits) {
        val root = destDir.canonicalFile
        val prefix = root.path + File.separator
        var entries = 0
        var total = 0L
        val buffer = ByteArray(COPY_BUFFER)
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (++entries > limits.maxEntries) {
                    throw SecurityException("Zip has more than ${limits.maxEntries} entries")
                }
                // File(root, "/abs") silently resolves under root, so reject absolute
                // names explicitly rather than extracting them somewhere unintended.
                if (entry.name.startsWith("/") || entry.name.startsWith("\\")) {
                    throw SecurityException("Zip entry has absolute path: ${entry.name}")
                }
                val out = File(root, entry.name).canonicalFile
                // Zip-slip guard: the resolved path must stay under root.
                if (out.path != root.path && !out.path.startsWith(prefix)) {
                    throw SecurityException("Zip entry escapes target: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    var fileBytes = 0L
                    out.outputStream().use { sink ->
                        while (true) {
                            val n = zis.read(buffer)
                            if (n < 0) break
                            fileBytes += n
                            total += n
                            if (fileBytes > limits.maxFileBytes) {
                                throw SecurityException(
                                    "Zip entry ${entry.name} is larger than ${limits.maxFileBytes} bytes"
                                )
                            }
                            if (total > limits.maxTotalBytes) {
                                throw SecurityException("Zip expands past ${limits.maxTotalBytes} bytes")
                            }
                            sink.write(buffer, 0, n)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private const val COPY_BUFFER = 64 * 1024

    /**
     * Shallowest `LivelyInfo.json` (bundles sometimes nest under one folder).
     * Ties at the same depth are broken by walk order, which is filesystem
     * dependent; a bundle with several equally shallow copies is ambiguous and
     * not a supported layout.
     */
    fun findLivelyInfo(dir: File): File? =
        dir.walkTopDown()
            .filter { it.isFile && it.name.equals("LivelyInfo.json", ignoreCase = true) }
            .minByOrNull { it.relativeTo(dir).path.count { c -> c == File.separatorChar } }

    fun metadataBasePath(rootDir: File, infoFile: File): String =
        infoFile.parentFile
            ?.takeUnless { it.canonicalFile == rootDir.canonicalFile }
            ?.relativeTo(rootDir)
            ?.invariantSeparatorsPath
            ?: ""

    /**
     * The directory a property's `folder` names, resolved against the LivelyInfo
     * directory ([basePath] under [bundleRoot], as the page itself resolves relative
     * URLs). Null for an empty, absolute or drive-letter folder, any `..` segment,
     * or a result (symlinks included) that leaves the bundle.
     */
    fun resolveBundleFolder(bundleRoot: File, basePath: String, folder: String): File? {
        val f = folder.trim()
        if (f.isEmpty() || f.startsWith("/") || f.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(f)) return null
        if (f.split('/', '\\').any { it == ".." }) return null
        val root = bundleRoot.canonicalFile
        val dir = File(File(root, basePath), f).canonicalFile
        return dir.takeIf { it.path == root.path || it.path.startsWith(root.path + File.separator) }
    }

    fun fileInventory(dir: File): JSONArray =
        JSONArray().apply {
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    put(JSONObject().apply {
                        put("path", file.relativeTo(dir).invariantSeparatorsPath)
                        put("size", file.length())
                    })
                }
        }
}
