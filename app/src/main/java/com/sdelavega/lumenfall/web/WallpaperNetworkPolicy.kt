package com.sdelavega.lumenfall.web

import android.net.Uri

/**
 * Decides whether a wallpaper WebView subresource request may proceed. Ported
 * from ÆtherDesk's ContentRuleListManager rule lists (Android's WebView has no
 * WKContentRuleList, so the checks run in shouldInterceptRequest instead), plus
 * the two global Performance-tab toggles ([blockAllExternal], [allowLan]):
 *
 * - ad / analytics / crypto-miner domains, cloud metadata hosts, `*.local`
 *   (mDNS), and IP-embedding DNS-rebinding services (nip.io/sslip.io) are
 *   always blocked, with no user override — these have no legitimate use in a
 *   wallpaper and exist mainly as SSRF/credential-exfiltration vectors;
 * - private-range and loopback addresses (RFC1918, `localhost`) are blocked
 *   by default but permitted when [allowLan] is on, matching ÆtherDesk's
 *   "Allow wallpapers to access local network" toggle;
 * - any other raw IPv4/IPv6 literal (i.e. a public IP address) is always
 *   blocked — forces bundles to use hostnames — independent of [allowLan],
 *   which is specifically about the LAN;
 * - [blockAllExternal] (ÆtherDesk's "Block external network requests") short-
 *   circuits everything else once past the always-blocked/LAN checks;
 * - when [budgetPerMinute] is set (screening classified the bundle as
 *   "limited"), surviving external http(s) requests are also rate-limited.
 *
 * Local bundle traffic (the WebViewAssetLoader origin) never reaches this
 * policy — the host routes it to the asset loader first.
 */
class WallpaperNetworkPolicy(
    private val budgetPerMinute: Int? = null,
    blockAllExternal: Boolean = false,
    allowLan: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis
) {
    sealed interface Verdict {
        object Allow : Verdict
        data class Block(val reason: String) : Verdict
    }

    private val windowStarts = ArrayDeque<Long>()
    @Volatile private var blockAllExternal = blockAllExternal
    @Volatile private var allowLan = allowLan

    fun update(blockAllExternal: Boolean, allowLan: Boolean) {
        this.blockAllExternal = blockAllExternal
        this.allowLan = allowLan
    }

    fun decide(uri: Uri): Verdict {
        val scheme = uri.scheme?.lowercase() ?: return Verdict.Block("no scheme")
        if (scheme != "http" && scheme != "https") {
            return Verdict.Block("scheme $scheme not allowed")
        }
        val host = uri.host?.lowercase() ?: return Verdict.Block("no host")

        if (isAlwaysBlocked(host)) return Verdict.Block("SSRF-protected host $host")
        if (isPrivateOrLoopback(host)) {
            if (!allowLan) return Verdict.Block("LAN access disabled in Performance settings")
        } else if (isRawIpLiteral(host)) {
            return Verdict.Block("raw IP literal not allowed")
        }
        if (blockAllExternal) return Verdict.Block("external network disabled in Performance settings")
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

    /** Hosts with no legitimate wallpaper use case — blocked regardless of settings. */
    private fun isAlwaysBlocked(host: String): Boolean {
        if (host.endsWith(".local")) return true // mDNS: can resolve to arbitrary LAN devices
        if (host == "metadata.google.internal" || host == "metadata.azure.com") return true
        if (host.endsWith(".nip.io") || host.endsWith(".sslip.io")) return true // DNS-rebinding as a service
        return false
    }

    /** RFC1918 private ranges + loopback/localhost — gated by the allowLan setting. */
    private fun isPrivateOrLoopback(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        val octets = IPV4.matchEntire(host)?.let { host.split(".").mapNotNull(String::toIntOrNull) } ?: return false
        if (octets.size != 4) return false
        val (a, b) = octets[0] to octets[1]
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    private fun isRawIpLiteral(host: String): Boolean =
        IPV4.matches(host) || host.startsWith("[") || host.contains(":")

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
