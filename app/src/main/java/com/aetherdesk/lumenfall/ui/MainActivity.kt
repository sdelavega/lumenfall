package com.aetherdesk.lumenfall.ui

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aetherdesk.lumenfall.R
import com.aetherdesk.lumenfall.importer.BundleImporter
import com.aetherdesk.lumenfall.render.ActiveBundleBus
import com.aetherdesk.lumenfall.render.PerformanceSettingsBus
import com.aetherdesk.lumenfall.service.LumenfallWallpaperService
import com.aetherdesk.lumenfall.storage.AppSettingsStore
import com.aetherdesk.lumenfall.storage.BundleMeta
import com.aetherdesk.lumenfall.storage.BundleStore
import com.aetherdesk.lumenfall.storage.BundledWallpapers
import com.aetherdesk.lumenfall.storage.PerformanceSettings
import com.aetherdesk.lumenfall.web.BundleThumbnailer
import com.aetherdesk.lumenfall.web.ContactedDomains
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
    private lateinit var listContainer: LinearLayout
    private var libraryScroll: ScrollView? = null
    private var libraryPane: View? = null
    private var settingsPane: View? = null
    private var content: SplitPaneLayout? = null
    private var selectedSettingsTab = SettingsTab.PERFORMANCE
    private var settingsBody: LinearLayout? = null
    private var performanceTabButton: View? = null
    private var aboutTabButton: View? = null
    private var pendingImportedId: String? = null

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
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) }
        }
        libraryScroll = scroll
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.START
                addView(importButton)
            })
            addView(scroll)
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
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        listContainer.removeAllViews()
        val bundles = store.list()
        val activeId = store.activeId
        if (bundles.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "No bundles yet. Import a Lively .zip to get started."
                setTextColor(getColor(R.color.glass_text_secondary))
                setPadding(0, dp(24), 0, 0)
            })
            return
        }
        var importedView: View? = null
        val importedId = pendingImportedId
        bundles.forEach { meta ->
            val panel = GlassUi.panel(this, rowFor(meta, meta.id == activeId), bottomMarginDp = 10)
            listContainer.addView(panel)
            if (meta.id == importedId) importedView = panel
        }
        if (importedView != null) {
            libraryScroll?.post {
                importedView?.let { libraryScroll?.smoothScrollTo(0, it.top) }
            }
            pendingImportedId = null
        }
        // Backfill thumbnails for bundles that didn't declare a preview image;
        // each one that lands re-renders the list so its tile fills in.
        BundleThumbnailer.generateMissing(this, store) { if (!isDestroyed) refresh() }
    }

    private fun rowFor(meta: BundleMeta, isActive: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { setActiveAndPreview(meta) }
            // Bundled wallpapers can't be deleted — they ship with the app.
            setOnLongClickListener {
                if (!BundledWallpapers.isBundled(meta.id)) {
                    confirmDelete(meta); true
                } else false
            }
        }

        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(16, 24, 40))
            loadPreview(meta)?.let(::setImageBitmap)
        })

        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = if (isActive) "● ${meta.title}" else meta.title
                setTextColor(getColor(R.color.glass_text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            })
            val renderable = meta.kind in RENDERABLE_KINDS
            val sub = buildString {
                append(meta.kind)
                meta.author?.let { append("  •  ").append(it) }
                if (!renderable) append("   (not supported)")
            }
            addView(TextView(context).apply {
                text = sub
                setTextColor(if (renderable) getColor(R.color.glass_text_secondary) else getColor(R.color.glass_danger))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        })

        // Bundles with LivelyProperties expose a settings entry point; so does
        // every web bundle regardless — its settings screen carries the live
        // preview and the contacted-domains transparency readout.
        if (meta.kind == "web" || store.properties(meta.id).isNotEmpty()) {
            row.addView(GlassUi.settingsButton(this, "Settings") {
                startActivity(
                    Intent(this@MainActivity, BundleSettingsActivity::class.java)
                        .putExtra(BundleSettingsActivity.EXTRA_BUNDLE_ID, meta.id)
                )
            }.apply { layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) } })
        }
        return row
    }

    // --- Performance (ported from ÆtherDesk's Preferences > Performance tab) ---

    private fun buildPerformanceSection(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(GlassUi.sectionHeader(this, "Performance"))
        column.addView(buildInfoLine("Applies immediately to the running wallpaper when Lumenfall is active."))

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
        box.addView(GlassUi.label(this, "Default FPS cap"))
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
            setTextColor(getColor(R.color.glass_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(TextView(this).apply {
            text = "Sibling project to ÆtherDesk (macOS) — same bundle format and property " +
                "bridge contract, ported practical limits (import screening, fps caps, " +
                "network policy)."
            setTextColor(getColor(R.color.glass_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(TextView(this).apply {
            text = "Wallpapers run sandboxed: no filesystem access, no ad/tracker/miner " +
                "domains, and SSRF-protected network requests."
            setTextColor(getColor(R.color.glass_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, 0)
        })
        return column
    }

    private fun loadPreview(meta: BundleMeta) =
        if (BundledWallpapers.isBundled(meta.id)) {
            bundledPreview(meta)
        } else {
            meta.previewPath?.let { rel ->
                runCatching { BitmapFactory.decodeFile(store.fileFor(meta.id, rel).absolutePath) }.getOrNull()
            }
        }

    private fun bundledPreview(meta: BundleMeta): android.graphics.Bitmap? {
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
            val bitmap = runCatching {
                assets.open("wallpapers/$folder/${rel.trimStart('/')}").use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }.getOrNull()
            if (bitmap != null) return bitmap
        }
        return BundleThumbnailer.bundledThumbnailFile(this, meta)
            ?.takeIf { it.exists() }
            ?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
    }

    private fun setActiveAndPreview(meta: BundleMeta) {
        activationProblem(meta)?.let { problem ->
            toast(problem)
            return
        }

        store.previousActiveId = store.activeId
        store.activeId = meta.id
        refresh()

        // Already the live wallpaper? Swap in place — nudge any surviving
        // engine now, and an engine recreated on next visibility reads the new
        // active id anyway. No system chooser round-trip needed.
        val ourComponent = ComponentName(this, LumenfallWallpaperService::class.java)
        if (WallpaperManager.getInstance(this).wallpaperInfo?.component == ourComponent) {
            ActiveBundleBus.notifyChanged()
            toast("Now live: ${meta.title}")
            return
        }

        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ourComponent)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("Selected '${meta.title}'. Set Lumenfall as your live wallpaper to see it.")
        }
    }

    private fun confirmDelete(meta: BundleMeta) {
        GlassUi.confirmDialog(
            this,
            title = "Delete \"${meta.title}\"?",
            confirmText = "Delete",
            destructive = true
        ) {
            store.delete(meta.id)
            ContactedDomains.clear(meta.id)
            refresh()
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
                    is BundleImporter.Result.Error ->
                        toast("Import failed: ${result.message}")
                }
                refresh()
            }
        }
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
