package com.sdelavega.lumenfall.bridge

import android.webkit.JavascriptInterface

class AetherDeskBridge(
    private val onHeartbeat: () -> Unit,
    private val onLog: (String) -> Unit
) {
    @JavascriptInterface
    fun postMessage(payload: String) {
        if (payload.contains("\"heartbeat\"")) {
            onHeartbeat()
        } else {
            onLog(payload)
        }
    }
}
