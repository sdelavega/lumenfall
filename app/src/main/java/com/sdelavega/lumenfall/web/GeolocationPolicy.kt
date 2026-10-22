// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.Manifest
import android.content.Context
import androidx.core.content.ContextCompat
import com.sdelavega.lumenfall.storage.BundleStore

/**
 * Resolves whether the geolocation bridge should be wired up for a bundle at
 * render time, mirroring how [PerformancePolicy] snapshots power/Performance
 * settings once when a bundle loads (not a live-reacting daemon).
 *
 * Three gates, all must pass:
 *  1. **Screening gate** — the import screener classified this web bundle
 *     `allowed` (not `limited`). A bundle that tripped the network/heavy-GPU
 *     heuristics — or one imported before screening existed, treated
 *     conservatively as `limited` — never even sees the native bridge surface,
 *     so a hostile or resource-hungry wallpaper can't reach location as a
 *     side channel. This is the privacy-relevant gate: it ties access to
 *     the same trust decision the user already made at import.
 *  2. **Per-bundle toggle** ([BundleStore.geolocationEnabled]) — defaults off,
 *     surfaced in `BundleSettingsActivity`. Even a clean bundle doesn't get
 *     location until the user explicitly opts this wallpaper in.
 *  3. **System permission** — `ACCESS_FINE_LOCATION` granted. Re-checked per
 *     bridge call (see [GeolocationProvider]) so a mid-session revoke surfaces
 *     as a clean JS rejection rather than a crash; a live wallpaper runs in the
 *     background and can't host a permission dialog, so revocation is the one
 *     condition that can change after the snapshot.
 *
 * Non-web bundles never reach this path (they have no JS bridge); the wallpaper
 * service only calls [allowed] for web bundles.
 */
object GeolocationPolicy {
    fun allowed(context: Context, store: BundleStore, bundleId: String): Boolean {
        val screening = store.screening(bundleId)
        val screeningClean = screening != null && !screening.limited
        if (!screeningClean) return false
        if (!store.geolocationEnabled(bundleId)) return false
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
