// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.video

import android.content.Context
import android.net.Uri
import android.view.SurfaceHolder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sdelavega.lumenfall.render.WallpaperRenderer
import java.io.File

/**
 * Renders a looping video bundle straight onto the wallpaper surface with
 * ExoPlayer. Muted by default — a live wallpaper has no business making noise;
 * a per-bundle audio policy can come later. Playback pauses while hidden so the
 * decoder isn't running behind an opaque app.
 */
class VideoWallpaperRenderer(context: Context, file: File) : WallpaperRenderer {

    private val player = ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        repeatMode = Player.REPEAT_MODE_ALL
        volume = 0f
        playWhenReady = true
        prepare()
    }
    private var boundHolder: SurfaceHolder? = null

    override fun onSurface(holder: SurfaceHolder, width: Int, height: Int) {
        if (boundHolder !== holder) {
            player.setVideoSurfaceHolder(holder)
            boundHolder = holder
        }
    }

    override fun setVisible(visible: Boolean) {
        player.playWhenReady = visible
    }

    override fun onSurfaceDestroyed() {
        boundHolder?.let(player::clearVideoSurfaceHolder)
        boundHolder = null
    }

    override fun release() {
        player.release()
    }
}
