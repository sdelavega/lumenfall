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
