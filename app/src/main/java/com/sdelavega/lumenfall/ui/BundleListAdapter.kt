// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
            holder.thumb.setImageBitmap(item.thumbnailPath?.let { decode(context, it) })
        }
    }

    private fun decode(context: Context, path: String): Bitmap? = runCatching {
        if (path.startsWith(ASSET_PREFIX)) {
            context.assets.open(path.removePrefix(ASSET_PREFIX)).use { BitmapFactory.decodeStream(it) }
        } else {
            BitmapFactory.decodeFile(path)
        }
    }.getOrNull()

    companion object {
        const val ASSET_PREFIX = "asset:"
    }
}
