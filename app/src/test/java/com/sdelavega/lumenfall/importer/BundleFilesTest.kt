// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.importer

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun extractsZipIntoDestination() {
        val dest = temp.newFolder("bundle")

        BundleFiles.extractZip(
            zipOf(
                "Nested/LivelyInfo.json" to """{"Title":"Nested","Type":1}""",
                "Nested/index.html" to "<html></html>",
            ),
            dest
        )

        assertEquals("""{"Title":"Nested","Type":1}""", dest.resolve("Nested/LivelyInfo.json").readText())
        assertEquals("<html></html>", dest.resolve("Nested/index.html").readText())
    }

    @Test
    fun rejectsZipSlipEntries() {
        val dest = temp.newFolder("bundle")

        assertThrows(SecurityException::class.java) {
            BundleFiles.extractZip(zipOf("../escape.txt" to "nope"), dest)
        }
    }

    private val oneMb = 1024L * 1024L

    @Test
    fun abortsAndCleansUpWhenOneEntryInflatesPastTheFileCap() {
        val dest = temp.newFolder("bundle")
        dest.resolve("keep-parent-dir-only").mkdirs()
        // 16 MB of zeros deflates to a few KB: the classic zip bomb shape.
        val bomb = ByteArray((16 * oneMb).toInt())

        val e = assertThrows(SecurityException::class.java) {
            BundleFiles.extractZip(
                zipOfBytes("ok.txt" to "hi".toByteArray(), "bomb.bin" to bomb),
                dest,
                ExtractLimits(maxFileBytes = oneMb, maxTotalBytes = 64 * oneMb, maxEntries = 100)
            )
        }

        assertEquals(true, e.message!!.contains("bomb.bin"))
        assertEquals(emptyList<String>(), dest.listFiles()!!.map { it.name })
    }

    @Test
    fun abortsWhenTotalInflatedBytesPassTheTotalCap() {
        val dest = temp.newFolder("bundle")
        val chunk = ByteArray((600 * 1024))

        assertThrows(SecurityException::class.java) {
            BundleFiles.extractZip(
                zipOfBytes("a" to chunk, "b" to chunk, "c" to chunk),
                dest,
                ExtractLimits(maxFileBytes = oneMb, maxTotalBytes = oneMb, maxEntries = 100)
            )
        }
        assertEquals(emptyList<String>(), dest.listFiles()!!.map { it.name })
    }

    @Test
    fun abortsWhenEntryCountPassesTheCap() {
        val dest = temp.newFolder("bundle")

        assertThrows(SecurityException::class.java) {
            BundleFiles.extractZip(
                zipOf("a" to "1", "b" to "2", "c" to "3"),
                dest,
                ExtractLimits(maxFileBytes = oneMb, maxTotalBytes = oneMb, maxEntries = 2)
            )
        }
        assertEquals(emptyList<String>(), dest.listFiles()!!.map { it.name })
    }

    @Test
    fun entriesWithinTheCapsExtractNormally() {
        val dest = temp.newFolder("bundle")
        BundleFiles.extractZip(
            zipOfBytes("a" to ByteArray(1000), "b" to ByteArray(1000)),
            dest,
            ExtractLimits(maxFileBytes = 1000, maxTotalBytes = 2000, maxEntries = 2)
        )
        assertEquals(1000L, dest.resolve("a").length())
    }

    @Test
    fun defaultLimitsMatchTheRustValidator() {
        assertEquals(256L * 1024 * 1024, ExtractLimits.DEFAULT.maxFileBytes)
        assertEquals(512L * 1024 * 1024, ExtractLimits.DEFAULT.maxTotalBytes)
        assertEquals(2_000, ExtractLimits.DEFAULT.maxEntries)
    }

    @Test
    fun findsShallowestLivelyInfo() {
        val root = temp.newFolder("bundle")
        root.resolve("Nested/Deeper").mkdirs()
        root.resolve("Nested/LivelyInfo.json").writeText("{}")
        root.resolve("Nested/Deeper/LivelyInfo.json").writeText("{}")

        val info = BundleFiles.findLivelyInfo(root)

        assertNotNull(info)
        assertEquals("Nested/LivelyInfo.json", info!!.relativeTo(root).invariantSeparatorsPath)
    }

    @Test
    fun computesMetadataBasePath() {
        val root = temp.newFolder("bundle")
        root.resolve("Nested").mkdirs()
        val info = root.resolve("Nested/LivelyInfo.json").apply { writeText("{}") }

        assertEquals("Nested", BundleFiles.metadataBasePath(root, info))
    }

    @Test
    fun buildsRelativeFileInventoryWithSizes() {
        val root = temp.newFolder("bundle")
        root.resolve("Nested").mkdirs()
        root.resolve("Nested/index.html").writeText("abcd")
        root.resolve("preview.png").writeText("xy")

        val inventory = BundleFiles.fileInventory(root).sortedByPath()

        assertEquals("Nested/index.html", inventory.getJSONObject(0).getString("path"))
        assertEquals(4L, inventory.getJSONObject(0).getLong("size"))
        assertEquals("preview.png", inventory.getJSONObject(1).getString("path"))
        assertEquals(2L, inventory.getJSONObject(1).getLong("size"))
    }

    private fun zipOfBytes(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun JSONArray.sortedByPath(): JSONArray {
        val objects = (0 until length())
            .map(::getJSONObject)
            .sortedBy { it.getString("path") }
        return JSONArray().apply { objects.forEach(::put) }
    }
}
