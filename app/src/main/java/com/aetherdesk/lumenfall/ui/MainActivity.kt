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
import com.aetherdesk.lumenfall.service.LumenfallWallpaperService
import com.aetherdesk.lumenfall.storage.AppSettingsStore
import com.aetherdesk.lumenfall.storage.BundleMeta
import com.aetherdesk.lumenfall.storage.BundleStore
import com.aetherdesk.lumenfall.web.BundleThumbnailer
import com.aetherdesk.lumenfall.web.ContactedDomains
import kotlin.concurrent.thread

/**
 * Bundle library + global app settings. Layout is responsive: side-by-side
 * (wallpapers/import left, performance/about right) in landscape, stacked
 * (wallpapers/import top, performance/about bottom) in portrait — same
 * relayout-on-rotation pattern as [BundleSettingsActivity]. Every visual
 * element is drawn from [GlassUi]; no stock Android widget chrome (buttons,
 * AlertDialog, Toast) appears anywhere in this screen.
 */
class MainActivity : AppCompatActivity() {

    private val store by lazy { BundleStore(this) }
    private val settingsStore by lazy { AppSettingsStore(this) }
    private lateinit var listContainer: LinearLayout
    private var libraryPane: View? = null
    private var settingsPane: View? = null
    private var content: LinearLayout? = null

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

        content = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) }
        }
        root.addView(content)
        setContentView(root)
        relayout()
    }

    private fun buildLibraryPane(): View {
        val importButton = GlassUi.primaryButton(this, "Import bundle (.zip)") {
            // Many exporters mislabel zips; accept the proper type plus a wildcard.
            openDoc.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(importButton)
            addView(scroll)
        }
    }

    private fun buildSettingsPane(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        column.addView(buildPerformanceSection())
        column.addView(GlassUi.sectionHeader(this, "About").apply { setPadding(0, dp(20), 0, dp(10)) })
        column.addView(GlassUi.panel(this, buildAboutContent()))
        return ScrollView(this).apply { addView(column) }
    }

    /** Flip between side-by-side (landscape) and stacked (portrait). */
    private fun relayout() {
        val c = content ?: return
        val library = libraryPane ?: return
        val settings = settingsPane ?: return

        (library.parent as? ViewGroup)?.removeView(library)
        (settings.parent as? ViewGroup)?.removeView(settings)

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            c.orientation = LinearLayout.HORIZONTAL
            // Wallpapers/import on the left, performance/about on the right.
            c.addView(library, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(12) })
            c.addView(settings, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        } else {
            c.orientation = LinearLayout.VERTICAL
            // Wallpapers/import on top, performance/about on the bottom.
            c.addView(library, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(12) })
            c.addView(settings, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
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
        bundles.forEach { listContainer.addView(GlassUi.panel(this, rowFor(it, it.id == activeId), bottomMarginDp = 10)) }
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
                if (!com.aetherdesk.lumenfall.storage.BundledWallpapers.isBundled(meta.id)) {
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
            row.addView(GlassUi.flatButton(this, "Settings") {
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
        column.addView(GlassUi.panel(this, buildInfoLine(
            "Applies the next time a wallpaper loads (on selection, or after a restart) — not live to an already-running wallpaper."
        )))

        val settings = settingsStore.get()

        column.addView(GlassUi.panel(this, fpsCapControl(settings.fpsCap)))
        column.addView(GlassUi.panel(this, toggleControl(
            "Respect Low Power Mode",
            "Cap frame rate further while the system's battery saver is on.",
            settings.respectLowPowerMode
        ) { v -> settingsStore.update { it.copy(respectLowPowerMode = v) } }))
        column.addView(GlassUi.panel(this, toggleControl(
            "Pause when wallpaper is not visible",
            "Stop rendering while another app covers the home screen. Strongly recommended for battery life.",
            settings.pauseWhenNotVisible
        ) { v -> settingsStore.update { it.copy(pauseWhenNotVisible = v) } }))
        column.addView(GlassUi.panel(this, toggleControl(
            "Pause when on battery power",
            "Stop rendering entirely while the device isn't plugged in.",
            settings.pauseOnBattery
        ) { v -> settingsStore.update { it.copy(pauseOnBattery = v) } }))
        column.addView(GlassUi.panel(this, toggleControl(
            "Block external network requests",
            "Wallpapers can only read their own bundled files — no network access at all.",
            settings.blockExternalNetwork
        ) { v -> settingsStore.update { it.copy(blockExternalNetwork = v) } }))
        column.addView(GlassUi.panel(this, toggleControl(
            "Allow local network (LAN) access",
            "When off, wallpapers can't reach private IP addresses (192.168.x.x, 10.x.x.x, etc.) or localhost.",
            settings.allowLan
        ) { v -> settingsStore.update { it.copy(allowLan = v) } }))
        return column
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
                    settingsStore.update { it.copy(fpsCap = values[position]) }
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
        row.addView(GlassUi.switchControl(this, checked, onChange))
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
        if (com.aetherdesk.lumenfall.storage.BundledWallpapers.isBundled(meta.id)) {
            // Bundled wallpapers have no on-disk preview; the thumbnailer will
            // generate one from the asset (web capture) or return null.
            null
        } else {
            meta.previewPath?.let { rel ->
                runCatching { BitmapFactory.decodeFile(store.fileFor(meta.id, rel).absolutePath) }.getOrNull()
            }
        }

    private fun setActiveAndPreview(meta: BundleMeta) {
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
                    is BundleImporter.Result.Ok ->
                        toast("Imported '${result.meta.title}' (${result.meta.kind})")
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

    private companion object {
        val RENDERABLE_KINDS = setOf("web", "video", "image")
    }
}
