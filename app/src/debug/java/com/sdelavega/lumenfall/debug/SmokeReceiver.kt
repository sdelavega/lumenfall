// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sdelavega.lumenfall.render.ActiveBundleBus
import com.sdelavega.lumenfall.service.DebugHooks
import com.sdelavega.lumenfall.storage.BundleStore

/**
 * Debug-only hook for scripts/smoke.sh: sets the active bundle by id
 * (`adb shell am broadcast -a com.sdelavega.lumenfall.SMOKE_SET_ACTIVE --es id bundled/Rainfall`)
 * and nudges the running wallpaper engine. Lives in src/debug so release builds
 * cannot contain it; `just smoke-manifest-check` enforces that.
 */
class SmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_FORCE_UNIVERSITY_DAY) {
            DebugHooks.forceUniversityDay = intent.getBooleanExtra("on", false)
            ActiveBundleBus.notifyChanged()
            Log.i(TAG, "SMOKE_FORCE_UNIVERSITY_DAY -> ${DebugHooks.forceUniversityDay}")
            return
        }
        if (intent.action != ACTION) return
        val id = intent.getStringExtra("id")
        val store = BundleStore(context)
        if (id == null || store.get(id) == null) {
            Log.w(TAG, "SMOKE_SET_ACTIVE ignored: unknown bundle id '$id'")
            return
        }
        store.activeId = id
        ActiveBundleBus.notifyChanged()
        Log.i(TAG, "SMOKE_SET_ACTIVE -> $id")
    }

    private companion object {
        const val ACTION = "com.sdelavega.lumenfall.SMOKE_SET_ACTIVE"
        const val ACTION_FORCE_UNIVERSITY_DAY = "com.sdelavega.lumenfall.SMOKE_FORCE_UNIVERSITY_DAY"
        const val TAG = "LumenfallSmoke"
    }
}
