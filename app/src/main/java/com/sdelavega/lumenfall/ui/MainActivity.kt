// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.ui

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sdelavega.lumenfall.R
import com.sdelavega.lumenfall.importer.BundleImporter
import com.sdelavega.lumenfall.render.ActiveBundleBus
import com.sdelavega.lumenfall.render.PerformanceSettingsBus
import com.sdelavega.lumenfall.service.LumenfallWallpaperService
import com.sdelavega.lumenfall.storage.AppSettingsStore
import com.sdelavega.lumenfall.storage.BundleMeta
import com.sdelavega.lumenfall.storage.BundleStore
import com.sdelavega.lumenfall.storage.PropertyParseResult
import com.sdelavega.lumenfall.storage.BundledWallpapers
import com.sdelavega.lumenfall.storage.PerformanceSettings
import com.sdelavega.lumenfall.web.BundleThumbnailer
import com.sdelavega.lumenfall.web.ContactedDomains
import java.io.File
import kotlin.concurrent.thread

/**
 * Bundle library + global app settings. Layout is responsive: side-by-side
 * (wallpapers/import left, performance/about right) in landscape, stacked
 * (wallpapers/import top, performance/about bottom) in portrait — same
 * relayout-on-rotation pattern as [BundleSettingsActivity]. Every visual
 * element is drawn from [GlassUi]'s Fluent acrylic controls; no stock Android
 * widget chrome (buttons, AlertDialog, Toast) appears anywhere in this screen.
 */
class MainActivity : AppCompatActivity() {

    private val store by lazy { BundleStore(this) }
    private val settingsStore by lazy { AppSettingsStore(this) }
    private lateinit var libraryList: RecyclerView
    private lateinit var emptyLibraryText: TextView
    private val bundleAdapter by lazy {
        BundleListAdapter(
            onSelect = { id -> store.get(id)?.let(::setActiveAndPreview) },
            onSettings = { id ->
                startActivity(
                    Intent(this, BundleSettingsActivity::class.java)
                        .putExtra(BundleSettingsActivity.EXTRA_BUNDLE_ID, id)
                )
            },
            onDelete = { id -> store.get(id)?.let(::confirmDelete) }
        )
    }
    private var libraryPane: View? = null
    private var settingsPane: View? = null
    private var content: SplitPaneLayout? = null
    private var selectedSettingsTab = SettingsTab.PERFORMANCE
    private var settingsBody: LinearLayout? = null
    private var performanceTabButton: View? = null
    private var aboutTabButton: View? = null
    private var pendingImportedId: String? = null
    private var pendingActivationTitle: String? = null

    // System live-wallpaper chooser. Resulting toast only if the user really
    // ended up with Lumenfall as the live wallpaper (they may have cancelled).
    private val wallpaperChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val title = pendingActivationTitle
            pendingActivationTitle = null
            if (title != null && isLumenfallLiveWallpaper()) toast("Now live: $title")
        }

    // SAF document picker. content:// URIs need no storage permission.
    private val openDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importFrom)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundResource(R.drawable.glass_backdrop_bg)
        }
        root.addView(GlassUi.screenTitle(this, "Lumenfall"))

        libraryPane = buildLibraryPane()
        settingsPane = buildSettingsPane()

        content = SplitPaneLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) }
        }
        root.addView(content)
        setContentView(root)
        relayout()
    }

    private fun buildLibraryPane(): View {
        val importButton = GlassUi.settingsButton(this, "Import bundle (.zip)") {
            // Many exporters mislabel zips; accept the proper type plus a wildcard.
            openDoc.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }.apply {
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(18), 0, dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(44))
        }
        libraryList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = bundleAdapter
            itemAnimator = null
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) }
        }
        emptyLibraryText = TextView(this).apply {
            text = "No bundles yet. Import a Lively .zip to get started."
            setTextColor(getColor(R.color.glass_text_secondary))
            setPadding(0, dp(24), 0, 0)
            visibility = View.GONE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.START
                addView(importButton)
            })
            addView(emptyLibraryText)
            addView(libraryList)
        }
    }

    private fun buildSettingsPane(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        column.addView(buildSettingsTabs())
        settingsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(settingsBody)
        updateSettingsTab()
        return ScrollView(this).apply { addView(column) }
    }

    private fun buildSettingsTabs(): View {
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(0, 0, 0, dp(10))
        }
        fun tabButton(tab: SettingsTab, label: String) =
            TextView(this).apply {
                text = label
                tag = tab
                isClickable = true
                isFocusable = true
                gravity = Gravity.CENTER
                minHeight = dp(34)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                letterSpacing = 0.04f
                setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener {
                    selectedSettingsTab = tab
                    updateSettingsTab()
                }
                if (tab == SettingsTab.PERFORMANCE) performanceTabButton = this else aboutTabButton = this
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(36)).apply {
                    marginEnd = dp(4)
                }
            }
        tabs.addView(tabButton(SettingsTab.PERFORMANCE, "Performance"))
        tabs.addView(tabButton(SettingsTab.ABOUT, "About"))
        return tabs
    }

    private fun updateSettingsTab() {
        val body = settingsBody ?: return
        body.removeAllViews()
        body.addView(
            when (selectedSettingsTab) {
                SettingsTab.PERFORMANCE -> buildPerformanceSection()
                SettingsTab.ABOUT -> GlassUi.noteBubble(this, buildAboutContent())
            }
        )
        updateTabSelection()
    }

    private fun updateTabSelection() {
        listOfNotNull(performanceTabButton, aboutTabButton).forEach { view ->
            val selected = view.tag == selectedSettingsTab
            view.isSelected = selected
            (view as? TextView)?.setTextColor(getColor(R.color.glass_text_primary))
            view.setBackgroundResource(
                if (selected) R.drawable.glass_tab_selected_bg else R.drawable.glass_tab_bg
            )
        }
    }

    /** Flip between side-by-side (landscape) and stacked (portrait). */
    private fun relayout() {
        val c = content ?: return
        val library = libraryPane ?: return
        val settings = settingsPane ?: return

        (library.parent as? ViewGroup)?.removeView(library)
        (settings.parent as? ViewGroup)?.removeView(settings)

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        c.setPanes(library, settings, landscape)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        relayout()
        libraryList.post { snapLibraryScrollToNearestRow() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        libraryList.postDelayed({ snapLibraryScrollToNearestRow() }, 200)
    }

    private fun refresh() {
        val bundles = store.list()
        val activeId = store.activeId
        val rows = bundles.map { rowFor(it, it.id == activeId) }
        emptyLibraryText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        libraryList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        val importedId = pendingImportedId
        bundleAdapter.submitList(rows) {
            if (importedId != null) {
                val index = rows.indexOfFirst { it.id == importedId }
                if (index >= 0) libraryList.smoothScrollToPosition(index)
            }
        }
        if (importedId != null) pendingImportedId = null
        // Backfill thumbnails for bundles that didn't declare a preview image;
        // each one that lands re-renders the list so its tile fills in.
        if (rows.isNotEmpty()) BundleThumbnailer.generateMissing(this, store) { if (!isDestroyed) refresh() }
    }

    private fun rowFor(meta: BundleMeta, isActive: Boolean): BundleRow {
        val renderable = meta.kind in RENDERABLE_KINDS
        val subtitle = buildString {
            append(meta.kind)
            meta.author?.let { append("  •  ").append(it) }
            if (!renderable) append("   (not supported)")
        }
        // Web bundles always get a settings screen (live preview + contacted
        // domains); other kinds only when they declare LivelyProperties.
        val hasSettings = meta.kind == "web" || store.propertySchema(meta.id).let {
            it is PropertyParseResult.Failed ||
                (it as? PropertyParseResult.Ok)?.properties?.isNotEmpty() == true
        }
        val thumbnailPath = thumbnailPathFor(meta)
        val thumbnailFile = thumbnailPath?.takeIf { !it.startsWith(BundleListAdapter.ASSET_PREFIX) }?.let(::File)
        return BundleRow(
            id = meta.id,
            title = meta.title,
            subtitle = subtitle,
            thumbnailPath = thumbnailPath,
            thumbnailVersion = thumbnailFile?.lastModified() ?: 0L,
            isActive = isActive,
            screening = store.screening(meta.id)?.classification,
            renderable = renderable,
            hasSettings = hasSettings,
            deletable = !BundledWallpapers.isBundled(meta.id)
        )
    }

    // --- Performance (ported from ÆtherDesk's Preferences > Performance tab) ---

    private fun buildPerformanceSection(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(GlassUi.sectionHeader(this, "Performance"))

        val settings = settingsStore.get()

        column.addView(GlassUi.panel(this, fpsCapControl(settings.fpsCap)))
        column.addView(GlassUi.panel(this, toggleControl(
            "Respect Low Power Mode",
            "Cap frame rate further while the system's battery saver is on.",
            settings.respectLowPowerMode
        ) { v -> updatePerformance { it.copy(respectLowPowerMode = v) } }))
        column.addView(GlassUi.panel(this, toggleControl(
            "Pause when wallpaper is not visible",
            "Stop rendering while another app covers the home screen. Strongly recommended for battery life.",
            settings.pauseWhenNotVisible
        ) { v -> updatePerformance { it.copy(pauseWhenNotVisible = v) } }))
        column.addView(GlassUi.panel(this, toggleControl(
            "Pause when on battery power",
            "Stop rendering entirely while the device isn't plugged in.",
            settings.pauseOnBattery
        ) { v -> updatePerformance { it.copy(pauseOnBattery = v) } }))
        column.addView(GlassUi.panel(this, toggleControl(
            "Block external network requests",
            "Wallpapers can only read their own bundled files — no network access at all.",
            settings.blockExternalNetwork
        ) { v -> updatePerformance { it.copy(blockExternalNetwork = v) } }))
        column.addView(GlassUi.panel(this, toggleControl(
            "Allow local network (LAN) access",
            "When off, wallpapers can't reach private IP addresses (192.168.x.x, 10.x.x.x, etc.) or localhost.",
            settings.allowLan
        ) { v -> updatePerformance { it.copy(allowLan = v) } }))
        column.addView(buildInfoLine("Applies immediately to the running wallpaper when Lumenfall is active.").apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(10)
                leftMargin = dp(2)
                rightMargin = dp(2)
            }
        })
        return column
    }

    private fun updatePerformance(transform: (PerformanceSettings) -> PerformanceSettings) {
        settingsStore.update(transform)
        PerformanceSettingsBus.notifyChanged(settingsStore.get())
    }

    private fun buildInfoLine(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.glass_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun fpsCapControl(current: Int): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = "Default FPS cap"
            setTextColor(getColor(R.color.glass_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, 0, dp(6))
        })
        val values = listOf(15, 30, 60)
        val items = values.map { "$it fps" }
        val spinner = Spinner(this).apply {
            adapter = GlassUi.spinnerAdapter(this@MainActivity, items)
            GlassUi.styleSpinner(this@MainActivity, this)
            var skipFirst = true
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (skipFirst) { skipFirst = false; return }
                    updatePerformance { it.copy(fpsCap = values[position]) }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            setSelection(values.indexOf(current).let { if (it < 0) 1 else it })
        }
        box.addView(spinner)
        return box
    }

    private fun toggleControl(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
            addView(TextView(context).apply {
                text = title
                setTextColor(getColor(R.color.glass_text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            addView(TextView(context).apply {
                text = description
                setTextColor(getColor(R.color.glass_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(2), 0, 0)
            })
        })
        row.addView(GlassUi.switchControl(this, checked, title, onChange))
        return row
    }

    // --- About ---

    private fun buildAboutContent(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        column.addView(TextView(this).apply {
            text = if (version != null) "Lumenfall $version" else "Lumenfall"
            setTextColor(getColor(R.color.glass_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        column.addView(TextView(this).apply {
            text = "A Lively-compatible live wallpaper host for Android tablets, with an " +
                "embedded Rust core for bundle parsing, validation, and content screening."
            setTextColor(getColor(R.color.glass_text_body))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(TextView(this).apply {
            text = "Sibling project to ÆtherDesk (macOS) — same bundle format and property " +
                "bridge contract, ported practical limits (import screening, fps caps, " +
                "network policy)."
            setTextColor(getColor(R.color.glass_text_body))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(TextView(this).apply {
            text = "Wallpapers run sandboxed: no filesystem access, no ad/tracker/miner " +
                "domains, and SSRF-protected network requests."
            setTextColor(getColor(R.color.glass_text_body))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(aboutLink("Open-source licences") { showLicences() })
        column.addView(aboutLink("Donate") { openDonatePage() })
        return column
    }

    private fun aboutLink(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.glass_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(14), 0, dp(4))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun showLicences() {
        val text = runCatching {
            assets.open("licenses/third_party.txt").bufferedReader().use { it.readText() }
        }.getOrElse {
            android.util.Log.w("MainActivity", "Could not read licenses/third_party.txt", it)
            "The licence notices could not be loaded."
        }
        GlassUi.messageDialog(this, title = "Open-source licences", message = text, scrollable = true)
    }

    private fun openDonatePage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sponsors/sdelavega"))
        runCatching { startActivity(intent) }.onFailure {
            GlassUi.messageDialog(this, title = "Donate", message = "No browser is available to open https://github.com/sponsors/sdelavega")
        }
    }

    /** File path (or `asset:` path) of the thumbnail to show, or null when there is none yet. */
    private fun thumbnailPathFor(meta: BundleMeta): String? {
        if (!BundledWallpapers.isBundled(meta.id)) {
            val rel = meta.previewPath ?: return null
            return store.fileFor(meta.id, rel).absolutePath
        }
        val folder = BundledWallpapers.folderFor(meta.id) ?: return null
        val candidates = listOfNotNull(
            meta.previewPath,
            "thumbnail.jpg",
            "thumbnail.png",
            "lively_t.jpg",
            "preview.jpg",
            "preview.png"
        ).distinct()
        for (rel in candidates) {
            val assetPath = "wallpapers/$folder/${rel.trimStart('/')}"
            if (runCatching { assets.open(assetPath).close() }.isSuccess) {
                return BundleListAdapter.ASSET_PREFIX + assetPath
            }
        }
        return BundleThumbnailer.bundledThumbnailFile(this, meta)
            ?.takeIf { it.exists() }
            ?.absolutePath
    }

    private fun setActiveAndPreview(meta: BundleMeta) {
        activationProblem(meta)?.let { problem ->
            GlassUi.messageDialog(
                this,
                title = "Can't activate \"${meta.title}\"",
                message = problem + "\n\nTry importing the wallpaper again."
            )
            return
        }

        store.previousActiveId = store.activeId
        store.activeId = meta.id
        refresh()

        // Already the live wallpaper? Swap in place — nudge any surviving
        // engine now, and an engine recreated on next visibility reads the new
        // active id anyway. No system chooser round-trip needed.
        val ourComponent = ComponentName(this, LumenfallWallpaperService::class.java)
        if (isLumenfallLiveWallpaper()) {
            ActiveBundleBus.notifyChanged()
            toast("Switched to ${meta.title}")
            return
        }

        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ourComponent)
        }
        pendingActivationTitle = meta.title
        try {
            wallpaperChooser.launch(intent)
        } catch (e: ActivityNotFoundException) {
            pendingActivationTitle = null
            toast("Selected '${meta.title}'. Set Lumenfall as your live wallpaper to see it.")
        }
    }

    private fun isLumenfallLiveWallpaper(): Boolean =
        WallpaperManager.getInstance(this).wallpaperInfo?.component ==
            ComponentName(this, LumenfallWallpaperService::class.java)

    private fun confirmDelete(meta: BundleMeta) {
        GlassUi.confirmDialog(
            this,
            title = "Delete \"${meta.title}\"?",
            confirmText = "Delete",
            destructive = true
        ) {
            ContactedDomains.clear(meta.id)
            // State clears at once; the files go in the background and the list
            // refreshes once they are gone.
            store.delete(meta.id) { refresh() }
        }
    }

    private fun importFrom(uri: Uri) {
        val name = displayName(uri) ?: "Imported bundle"
        toast("Importing $name…")
        thread {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    BundleImporter(this).importZip(input, name.removeSuffix(".zip"))
                } ?: BundleImporter.Result.Error("Could not open file")
            }.getOrElse { BundleImporter.Result.Error(it.message ?: "Import failed") }

            runOnUiThread {
                when (result) {
                    is BundleImporter.Result.Ok -> {
                        pendingImportedId = result.meta.id
                        toast("Imported '${result.meta.title}' (${result.meta.kind})")
                    }
                    is BundleImporter.Result.Error -> {
                        GlassUi.messageDialog(
                            this,
                            title = "Import Failed",
                            message = result.message
                        )
                    }
                }
                refresh()
            }
        }
    }

    private fun snapLibraryScrollToNearestRow() {
        if (libraryList.childCount == 0) return
        var nearestTop = 0
        var nearestDistance = Int.MAX_VALUE
        for (i in 0 until libraryList.childCount) {
            val top = libraryList.getChildAt(i).top
            val distance = kotlin.math.abs(top)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestTop = top
            }
        }
        if (nearestDistance > 0) libraryList.smoothScrollBy(0, nearestTop)
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()

    private fun toast(msg: String) = GlassUi.toast(this, msg)

    private fun dp(v: Int): Int = GlassUi.dp(this, v)

    private fun activationProblem(meta: BundleMeta): String? {
        if (BundledWallpapers.isBundled(meta.id)) return null
        val entry = meta.entryPath ?: return "\"${meta.title}\" has no renderable entry file."
        if (!store.fileFor(meta.id, entry).isFile) {
            return "\"${meta.title}\" cannot be activated: missing $entry."
        }
        return null
    }

    private companion object {
        val RENDERABLE_KINDS = setOf("web", "video", "image")
    }

    private enum class SettingsTab { PERFORMANCE, ABOUT }
}
