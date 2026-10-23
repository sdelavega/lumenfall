// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sdelavega.lumenfall.R
import java.util.concurrent.Executors

/**
 * One library row. [thumbnailPath] is a file path, or `asset:<path>` for a
 * thumbnail shipped in the APK assets. [thumbnailVersion] is the thumbnail
 * file's `lastModified()` (0 if missing) so a regenerated thumbnail compares
 * as a content change. [subtitle] is the line shown under the title.
 */
data class BundleRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailPath: String?,
    val thumbnailVersion: Long,
    val isActive: Boolean,
    val screening: String?,
    // Presentation flags the row needs but the brief's core fields don't carry.
    val renderable: Boolean = true,
    val hasSettings: Boolean = false,
    val deletable: Boolean = true
)

object BundleRowDiff : DiffUtil.ItemCallback<BundleRow>() {
    override fun areItemsTheSame(oldItem: BundleRow, newItem: BundleRow) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: BundleRow, newItem: BundleRow) = oldItem == newItem
}

/**
 * BitmapFactory `inSampleSize` for a [width] x [height] source shown in a square tile
 * of [targetPx]: the largest power of two that keeps the short side at or above the
 * target (the tile crops to fill, so the short side decides).
 */
internal fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    val shortSide = minOf(width, height)
    if (shortSide <= 0 || targetPx <= 0) return 1
    var sample = 1
    while (shortSide / (sample * 2) >= targetPx) sample *= 2
    return sample
}

class BundleListAdapter(
    private val onSelect: (String) -> Unit,
    private val onSettings: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : ListAdapter<BundleRow, BundleListAdapter.Holder>(BundleRowDiff) {

    class Holder(
        val panel: View,
        val row: LinearLayout,
        val thumb: ImageView,
        val title: TextView,
        val subtitle: TextView,
        val settings: View
    ) : RecyclerView.ViewHolder(panel) {
        var boundId: String? = null
        var boundThumbPath: String? = null
        var boundThumbVersion: Long = -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }
        val thumb = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(GlassUi.dp(context, 72), GlassUi.dp(context, 72))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(16, 24, 40))
        }
        val title = TextView(context).apply {
            setTextColor(context.getColor(R.color.glass_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        val subtitle = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(GlassUi.dp(context, 12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(title)
            addView(subtitle)
        }
        // Bundles with LivelyProperties expose a settings entry point; so does
        // every web bundle (see MainActivity's hasSettings).
        val settings = GlassUi.settingsButton(context, "Settings") {}.apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                .apply { marginStart = GlassUi.dp(context, 8) }
        }
        row.addView(thumb)
        row.addView(text)
        row.addView(settings)
        val panel = GlassUi.panel(context, row, bottomMarginDp = 10)
        return Holder(panel, row, thumb, title, subtitle, settings)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val context = holder.panel.context
        holder.boundId = item.id
        holder.row.setOnClickListener { onSelect(item.id) }
        // Bundled wallpapers can't be deleted — they ship with the app.
        holder.row.setOnLongClickListener {
            if (item.deletable) { onDelete(item.id); true } else false
        }
        holder.title.text = if (item.isActive) "● ${item.title}" else item.title
        holder.subtitle.text = item.subtitle
        holder.subtitle.setTextColor(
            context.getColor(if (item.renderable) R.color.glass_text_secondary else R.color.glass_danger)
        )
        holder.settings.visibility = if (item.hasSettings) View.VISIBLE else View.GONE
        holder.settings.setOnClickListener { onSettings(item.id) }

        // Decode only when the thumbnail actually changed for this holder.
        if (holder.boundThumbPath != item.thumbnailPath || holder.boundThumbVersion != item.thumbnailVersion) {
            holder.boundThumbPath = item.thumbnailPath
            holder.boundThumbVersion = item.thumbnailVersion
            holder.thumb.setImageBitmap(null)
            val path = item.thumbnailPath
            if (path != null) {
                val version = item.thumbnailVersion
                val target = GlassUi.dp(context, 72)
                decoder.execute {
                    // Off the main thread, downsampled to the tile; a bundle's own preview
                    // can be a multi-megapixel image.
                    val bmp = decode(context, path, target)
                    mainHandler.post {
                        // The holder may have been rebound to another row meanwhile.
                        if (holder.boundThumbPath == path && holder.boundThumbVersion == version) {
                            holder.thumb.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }
    }

    private fun decode(context: Context, path: String, targetPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, path).use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
        }
        open(context, path).use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    private fun open(context: Context, path: String): java.io.InputStream =
        if (path.startsWith(ASSET_PREFIX)) context.assets.open(path.removePrefix(ASSET_PREFIX))
        else java.io.FileInputStream(path)

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val ASSET_PREFIX = "asset:"
        private val decoder = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "thumb-decode").apply { isDaemon = true }
        }
    }
}
