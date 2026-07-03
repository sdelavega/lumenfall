package com.aetherdesk.lumenfall.web

import android.net.Uri

/**
 * Decides whether a wallpaper WebView subresource request may proceed. Ported
 * from ÆtherDesk's ContentRuleListManager rule lists (Android's WebView has no
 * WKContentRuleList, so the checks run in shouldInterceptRequest instead):
 *
 * - ad / analytics / crypto-miner domains are always blocked;
 * - SSRF targets are always blocked: raw IPv4/IPv6 literals (covers private
 *   ranges, loopback, and cloud metadata like 169.254.169.254), localhost,
 *   `*.local` (mDNS), metadata hostnames, and IP-embedding DNS services;
 * - when [budgetPerMinute] is set (screening classified the bundle as
 *   "limited"), external http(s) requests are also rate-limited per minute.
 *
 * Local bundle traffic (the WebViewAssetLoader origin) never reaches this
 * policy — the host routes it to the asset loader first.
 */
class WallpaperNetworkPolicy(
    private val budgetPerMinute: Int? = null,
    private val now: () -> Long = System::currentTimeMillis
) {
    sealed interface Verdict {
        object Allow : Verdict
        data class Block(val reason: String) : Verdict
    }

    private val windowStarts = ArrayDeque<Long>()

    fun decide(uri: Uri): Verdict {
        val scheme = uri.scheme?.lowercase() ?: return Verdict.Block("no scheme")
        if (scheme != "http" && scheme != "https") {
            return Verdict.Block("scheme $scheme not allowed")
        }
        val host = uri.host?.lowercase() ?: return Verdict.Block("no host")

        if (isSsrfTarget(host)) return Verdict.Block("SSRF-protected host $host")
        BLOCKED_DOMAINS.firstOrNull { host == it || host.endsWith(".$it") }?.let {
            return Verdict.Block("blocklisted domain $it")
        }

        val budget = budgetPerMinute ?: return Verdict.Allow
        synchronized(windowStarts) {
            val cutoff = now() - 60_000L
            while (windowStarts.isNotEmpty() && windowStarts.first() < cutoff) {
                windowStarts.removeFirst()
            }
            if (windowStarts.size >= budget) {
                return Verdict.Block("network budget ($budget req/min) exhausted")
            }
            windowStarts.addLast(now())
        }
        return Verdict.Allow
    }

    private fun isSsrfTarget(host: String): Boolean {
        if (IPV4.matches(host)) return true
        if (host.startsWith("[") || host.contains(":")) return true // IPv6 literal
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host.endsWith(".local")) return true
        if (host == "metadata.google.internal" || host == "metadata.azure.com") return true
        if (host.endsWith(".nip.io") || host.endsWith(".sslip.io")) return true
        return false
    }

    private companion object {
        val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** Ad networks, analytics/trackers, and crypto miners (ÆtherDesk list). */
        val BLOCKED_DOMAINS = listOf(
            "google-analytics.com",
            "analytics.google.com",
            "googletagmanager.com",
            "googletagservices.com",
            "doubleclick.net",
            "googleadservices.com",
            "googlesyndication.com",
            "adservice.google.com",
            "adnxs.com",
            "adsrvr.org",
            "connect.facebook.net",
            "facebook.net",
            "hotjar.com",
            "mixpanel.com",
            "segment.io",
            "api.segment.com",
            "amplitude.com",
            "sentry.io",
            "bugsnag.com",
            "coinhive.com",
            "crypto-loot.com",
            "minero.cc",
            "jsecoin.com",
            "monerominer.rocks",
            "webmr.eu"
        )
    }
}
