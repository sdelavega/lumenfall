// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.render

import android.os.Handler
import android.os.Looper

/** Runs a block on the main thread. Buses use it so listeners always see main-thread callbacks. */
fun interface MainThreadDispatcher {
    fun dispatch(block: () -> Unit)
}

/** Runs inline when already on the main thread, otherwise posts to it. */
object AndroidMainDispatcher : MainThreadDispatcher {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }
}
