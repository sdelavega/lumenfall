package com.sdelavega.lumenfall.importer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Pure JVM file helpers for imported bundles; kept Android-free for tests. */
object BundleFiles {
    /** Extract every entry into [destDir], rejecting any path that escapes it. */
    fun extractZip(input: InputStream, destDir: File) {
        val root = destDir.canonicalFile
        val prefix = root.path + File.separator
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(root, entry.name).canonicalFile
                // Zip-slip guard: the resolved path must stay under root.
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
