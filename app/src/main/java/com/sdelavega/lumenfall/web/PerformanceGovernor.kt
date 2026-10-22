// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock

/**
 * Watches a live bundle preview for signs it's overloading the device and
 * reports back so the caller can back off.
 *
 * Android has no public per-app GPU usage API, so this leans on the most
 * honest proxy available: achieved frames-per-second against the bundle's own
 * target `fpsCap`, fed in via [onFrameReport] from the bridge shim's rAF
 * counter (see `BundleWebView`'s periodic "tick" message). A sustained
 * shortfall means the device can't keep up — whatever the actual bottleneck
 * (CPU compositing, GPU fill rate, heavy JS) — and it's the one signal that
 * reaches inside the WebView's own sandboxed renderer process, which this
 * app's own process-local reads below cannot see into.
 *
 * Two coarser backstops run alongside it, in case a bundle is heavy without
 * visibly janking:
 *  - this process's own CPU time ([Process.getElapsedCpuTime] deltas — a
 *    public, no-permission API, self-process only);
 *  - system-wide memory pressure ([ActivityManager.MemoryInfo.lowMemory]).
 *
 * A streak requirement (multiple consecutive samples) and a cooldown after
 * acting avoid reacting to one-off blips or flip-flopping on the same episode.
 */
class PerformanceGovernor(
    context: Context,
    private var fpsCap: Int,
    private val onOverload: (OverloadReason) -> Unit
) {
    enum class OverloadReason { JANK, CPU, MEMORY }

    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = ::poll

    private var running = false
    private var lastCpuTimeMs = 0L
    private var lastWallTimeMs = 0L
    private var jankStreak = 0
    private var cpuStreak = 0
    private var lastActionAt = 0L

    // Frame telemetry, fed from outside (only the caller parses bridge JSON).
    private var lastFrameCount = 0
    private var framesAtWindowStart = 0
    private var windowStartMs = 0L

    fun start() {
        if (running) return
        running = true
        val now = SystemClock.elapsedRealtime()
        lastCpuTimeMs = Process.getElapsedCpuTime()
        lastWallTimeMs = now
        windowStartMs = now
        framesAtWindowStart = lastFrameCount
        jankStreak = 0
        cpuStreak = 0
        handler.postDelayed(pollRunnable, SAMPLE_INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
    }

    /** Feed the cumulative rAF frame count reported by the bridge shim's tick. */
    fun onFrameReport(totalFrames: Int) {
        lastFrameCount = totalFrames
    }

    /**
     * Recalibrate the jank threshold after easing the live fps cap. Without
     * this, achieved-fps keeps getting compared against the *original* cap
     * forever, so a bundle running smoothly at its new, lower cap still reads
     * as "jank" and the governor ratchets the cap down to the floor even at
     * light load. Resets the sampling window too, so a partial window
     * straddling the old and new cap doesn't produce one more false trigger.
     */
    fun updateFpsCap(newCap: Int) {
        fpsCap = newCap
        val now = SystemClock.elapsedRealtime()
        windowStartMs = now
        framesAtWindowStart = lastFrameCount
        jankStreak = 0
    }

    private fun poll() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()

        val elapsedS = (now - windowStartMs) / 1000.0
        if (elapsedS >= 1.0) {
            val achieved = (lastFrameCount - framesAtWindowStart) / elapsedS
            windowStartMs = now
            framesAtWindowStart = lastFrameCount
            jankStreak = if (achieved < fpsCap * JANK_RATIO) jankStreak + 1 else 0
        }

        val cpuNow = Process.getElapsedCpuTime()
        val cpuDeltaMs = cpuNow - lastCpuTimeMs
        val wallDeltaMs = now - lastWallTimeMs
        lastCpuTimeMs = cpuNow
        lastWallTimeMs = now
        val cpuPercent = if (wallDeltaMs > 0) 100.0 * cpuDeltaMs / wallDeltaMs else 0.0
        cpuStreak = if (cpuPercent > CPU_THRESHOLD_PERCENT) cpuStreak + 1 else 0

        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        if (now - lastActionAt > COOLDOWN_MS) {
            val reason = when {
                jankStreak >= STREAK_TO_TRIGGER -> OverloadReason.JANK
                cpuStreak >= STREAK_TO_TRIGGER -> OverloadReason.CPU
                memInfo.lowMemory -> OverloadReason.MEMORY
                else -> null
            }
            if (reason != null) {
                lastActionAt = now
                jankStreak = 0
                cpuStreak = 0
                onOverload(reason)
            }
        }

        if (running) handler.postDelayed(pollRunnable, SAMPLE_INTERVAL_MS)
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 2000L
        const val STREAK_TO_TRIGGER = 2 // ~4s sustained before acting
        const val COOLDOWN_MS = 6000L // don't react to the same episode twice
        const val JANK_RATIO = 0.6 // achieved fps below 60% of target = struggling
        const val CPU_THRESHOLD_PERCENT = 90.0
    }
}
