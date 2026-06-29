package com.aetherdesk.lumenfall.web

import android.content.Context
import android.webkit.WebView
import com.aetherdesk.lumenfall.bridge.AetherDeskBridge

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
