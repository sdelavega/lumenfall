package com.aetherdesk.lumenfall.ui

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.aetherdesk.lumenfall.importer.BundleImporter
import com.aetherdesk.lumenfall.service.LumenfallWallpaperService
import com.aetherdesk.lumenfall.storage.BundleMeta
import com.aetherdesk.lumenfall.storage.BundleStore
import kotlin.concurrent.thread

/** Bundle library: import Lively-style .zip bundles and pick which one to run. */
class MainActivity : AppCompatActivity() {

    private val store by lazy { BundleStore(this) }
    private lateinit var listContainer: LinearLayout

    // SAF document picker. content:// URIs need no storage permission.
    private val openDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importFrom)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(8))
        }
        root.addView(TextView(this).apply {
            text = "Lumenfall"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        })
        root.addView(Button(this).apply {
            text = "Import bundle (.zip)"
            setOnClickListener {
                // Many exporters mislabel zips; accept the proper type plus a wildcard.
                openDoc.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(12) }
        })

        setContentView(root)
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
                setTextColor(Color.GRAY)
                setPadding(0, dp(24), 0, 0)
            })
            return
        }
        bundles.forEach { listContainer.addView(rowFor(it, it.id == activeId)) }
    }

    private fun rowFor(meta: BundleMeta, isActive: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            isClickable = true
            setOnClickListener { setActiveAndPreview(meta) }
            setOnLongClickListener { confirmDelete(meta); true }
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = if (isActive) "● ${meta.title}" else meta.title
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
                setTextColor(if (renderable) Color.GRAY else Color.rgb(200, 120, 60))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        })

        // Bundles with LivelyProperties expose a settings entry point.
        if (store.properties(meta.id).isNotEmpty()) {
            row.addView(Button(this).apply {
                text = "Settings"
                setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, BundleSettingsActivity::class.java)
                            .putExtra(BundleSettingsActivity.EXTRA_BUNDLE_ID, meta.id)
                    )
                }
            })
        }
        return row
    }

    private fun loadPreview(meta: BundleMeta) =
        meta.previewPath?.let { rel ->
            runCatching { BitmapFactory.decodeFile(store.fileFor(meta.id, rel).absolutePath) }.getOrNull()
        }

    private fun setActiveAndPreview(meta: BundleMeta) {
        store.activeId = meta.id
        refresh()
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, LumenfallWallpaperService::class.java)
            )
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("Selected '${meta.title}'. Set Lumenfall as your live wallpaper to see it.")
        }
    }

    private fun confirmDelete(meta: BundleMeta) {
        AlertDialog.Builder(this)
            .setTitle("Delete '${meta.title}'?")
            .setPositiveButton("Delete") { _, _ -> store.delete(meta.id); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private companion object {
        val RENDERABLE_KINDS = setOf("web", "video", "image")
    }
}
