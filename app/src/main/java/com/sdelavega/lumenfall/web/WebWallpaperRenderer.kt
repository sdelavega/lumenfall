// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.content.Context
import android.webkit.WebView
import com.sdelavega.lumenfall.bridge.AetherDeskBridge

class WebWallpaperRenderer(
    context: Context,
    onHeartbeat: () -> Unit,
    onLog: (String) -> Unit
) {
    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        addJavascriptInterface(
            AetherDeskBridge(onHeartbeat = onHeartbeat, onLog = onLog),
            "aetherDeskNative"
        )
    }

    fun load(url: String) {
        webView.loadUrl(url)
    }

    fun destroy() {
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
    }
}
