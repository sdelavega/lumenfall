// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.importer

import com.sdelavega.lumenfall.storage.BundleMeta
import com.sdelavega.lumenfall.storage.BundleScreening
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private class RecordingSink : ImportSink {
        val metas = mutableListOf<BundleMeta>()
        val screenings = mutableMapOf<String, String>()
        val properties = mutableMapOf<String, String>()
        override fun saveMeta(meta: BundleMeta) { metas += meta }
        override fun saveScreeningEnvelope(id: String, envelope: String) { screenings[id] = envelope }
        override fun savePropertiesEnvelope(id: String, envelope: String) { properties[id] = envelope }
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private val goodEntries = arrayOf(
        "LivelyInfo.json" to "{}",
        "index.html" to "<html></html>"
    )

    private fun importer(core: FakeCore, sink: RecordingSink, root: File = tmp.newFolder("bundles")) =
        BundleImporter(root, core, sink, logger = {}) to root

    private fun assertFailedAndClean(r: BundleImporter.Result, root: File, sink: RecordingSink) {
        assertTrue("expected Error, got $r", r is BundleImporter.Result.Error)
        assertEquals(emptyList<String>(), root.list()!!.toList())
        assertTrue(sink.metas.isEmpty())
    }

    @Test fun zipSlipEntryFailsAndCleansDir() {
        val sink = RecordingSink()
        val (imp, root) = importer(FakeCore(), sink)
        assertFailedAndClean(imp.importZip(zip("../evil.js" to "x", *goodEntries), "n"), root, sink)
    }

    @Test fun absolutePathEntryFailsAndCleansDir() {
        val sink = RecordingSink()
        val (imp, root) = importer(FakeCore(), sink)
        assertFailedAndClean(imp.importZip(zip("/abs.js" to "x", *goodEntries), "n"), root, sink)
    }

    @Test fun invalidLivelyInfoFailsAndCleansDir() {
        val sink = RecordingSink()
        val core = FakeCore(validation = FakeCore.validationJson(ok = false, error = "bad info", entryPath = null))
        val (imp, root) = importer(core, sink)
        val r = imp.importZip(zip(*goodEntries), "n")
        assertEquals("bad info", (r as BundleImporter.Result.Error).message)
        assertFailedAndClean(r, root, sink)
    }

    @Test fun screeningRejectionFailsAndCleansDir() {
        val sink = RecordingSink()
        val core = FakeCore(screening = FakeCore.screeningJson("rejected", issues = listOf("miner detected")))
        val (imp, root) = importer(core, sink)
        val r = imp.importZip(zip(*goodEntries), "n")
        assertTrue((r as BundleImporter.Result.Error).message.contains("miner detected"))
        assertFailedAndClean(r, root, sink)
    }

    @Test fun missingEntryFailsAndCleansDir() {
        val sink = RecordingSink()
        val core = FakeCore(validation = FakeCore.validationJson(entryPath = "gone.html"))
        val (imp, root) = importer(core, sink)
        val r = imp.importZip(zip(*goodEntries), "n")
        assertTrue((r as BundleImporter.Result.Error).message.contains("gone.html"))
        assertFailedAndClean(r, root, sink)
    }

    @Test fun validBundleWritesMetaAndScreening() {
        val sink = RecordingSink()
        val core = FakeCore()
        val (imp, root) = importer(core, sink)
        val r = imp.importZip(zip(*goodEntries, "LivelyProperties.json" to "{}"), "fallback")
        val meta = (r as BundleImporter.Result.Ok).meta
        assertEquals(listOf(meta), sink.metas)
        assertEquals("Fake", meta.title)
        assertEquals("index.html", meta.entryPath)
        assertEquals("allowed", JSONObject(sink.screenings.getValue(meta.id)).getString("classification"))
        assertEquals(setOf(meta.id), sink.properties.keys)
        assertTrue(File(root, meta.id).isDirectory)
    }

    @Test fun unreadableScriptDowngradesAllowedToLimited() {
        val sink = RecordingSink()
        val root = tmp.newFolder("bundles")
        var readable = true
        val core = FakeCore().apply {
            // The importer validates after extraction and before screening; make the
            // script unreadable in between (the bundle dir is the root's only child).
            onValidate = {
                val script = File(root.listFiles()!!.single(), "x.js")
                readable = script.setReadable(false) && !script.canRead()
            }
        }
        val r = BundleImporter(root, core, sink, logger = {}).importZip(zip(*goodEntries, "x.js" to "var a=1;"), "n")
        assumeFalse("running as root: cannot make a file unreadable", !readable)
        val meta = (r as BundleImporter.Result.Ok).meta
        val env = JSONObject(sink.screenings.getValue(meta.id))
        assertEquals("limited", env.getString("classification"))
        assertEquals(30, env.getInt("fps_cap"))
        val note = "1 script file(s) could not be read for screening"
        assertEquals(note, env.getJSONArray("issues").getString(0))
        // The UI reads "warnings", so the note must be there too.
        assertEquals(note, env.getJSONArray("warnings").getString(0))
        assertEquals(listOf(note), BundleScreening.fromJson(env.toString()).warnings)
    }

    @Test fun downgradeKeepsExistingIssuesAndWarnings() {
        val sink = RecordingSink()
        val root = tmp.newFolder("bundles")
        var readable = true
        val core = FakeCore(screening = FakeCore.screeningJson("allowed", warnings = listOf("w"), issues = listOf("i"))).apply {
            onValidate = {
                val script = File(root.listFiles()!!.single(), "x.js")
                readable = script.setReadable(false) && !script.canRead()
            }
        }
        val r = BundleImporter(root, core, sink, logger = {}).importZip(zip(*goodEntries, "x.js" to "a"), "n")
        assumeFalse("running as root", !readable)
        val env = JSONObject(sink.screenings.getValue((r as BundleImporter.Result.Ok).meta.id))
        assertEquals(2, env.getJSONArray("issues").length())
        assertEquals("i", env.getJSONArray("issues").getString(0))
        assertEquals("w", env.getJSONArray("warnings").getString(0))
    }

    @Test fun collectReportsUnreadableFileByRelativePath() {
        val dir = tmp.newFolder("c")
        File(dir, "ok.js").writeText("a")
        val sub = File(dir, "sub").apply { mkdirs() }
        val bad = File(sub, "bad.js").apply { writeText("b") }
        bad.setReadable(false)
        assumeFalse("running as root", bad.canRead())
        val logs = mutableListOf<String>()
        val c = collectScriptSource(dir, logs::add)
        assertEquals(listOf("sub/bad.js"), c.skipped)
        assertTrue(c.source.contains("a"))
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("sub/bad.js"))
    }

    @Test fun collectCountsFilesPastTotalCapAsSkippedButNotTruncatedOnes() {
        val dir = tmp.newFolder("cap")
        val big = "a".repeat(1_500_000) // truncated to the 1 MB per-file cap, not skipped
        repeat(9) { File(dir, "f$it.js").writeText(big) }
        val c = collectScriptSource(dir) {}
        // 8 x 1 MB reaches the 8 MB total cap; the 9th file is left out and reported.
        assertEquals(1, c.skipped.size)
        assertTrue(c.skipped[0].endsWith(".js"))
        assertEquals(8 * 1_000_001, c.source.length)
    }
}
