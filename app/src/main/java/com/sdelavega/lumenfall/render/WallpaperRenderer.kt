// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.render

import android.view.SurfaceHolder
import android.view.MotionEvent

/**
 * Drives one wallpaper bundle onto the Engine's surface. The Engine forwards its
 * lifecycle here; each implementation uses the mechanism that fits its kind:
 *  - web   -> VirtualDisplay + Presentation hosting a WebView
 *  - video -> ExoPlayer rendering straight onto the surface
 *  - image -> Canvas draw onto the surface
 *
 * All calls arrive on the main thread (Engine callbacks already are).
 */
interface WallpaperRenderer {
    /** From `onSurfaceChanged`: surface is ready at this size (also on resize). */
    fun onSurface(holder: SurfaceHolder, width: Int, height: Int)

    /** From `onVisibilityChanged`: pause/resume work when hidden/shown. */
    fun setVisible(visible: Boolean)

    /** From `Engine.onTouchEvent`: optional interactive wallpaper input. */
    fun onTouchEvent(event: MotionEvent) {}

    /** From `onSurfaceDestroyed`: release the surface but keep the renderer. */
    fun onSurfaceDestroyed()

    /** From `onDestroy`: tear everything down. */
    fun release()
}
