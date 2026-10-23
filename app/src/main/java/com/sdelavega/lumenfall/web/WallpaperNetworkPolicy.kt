// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import android.net.Uri
import com.sdelavega.lumenfall.core.LumenfallCore
import org.json.JSONObject

/**
 * Decides whether a wallpaper WebView subresource request may proceed. Ported
 * from ÆtherDesk's ContentRuleListManager rule lists (Android's WebView has no
 * WKContentRuleList, so the checks run in shouldInterceptRequest instead), plus
 * the two global Performance-tab toggles ([blockAllExternal], [allowLan]):
 *
 * - ad / analytics / crypto-miner domains, proprietary geolocation hosts,
 *   cloud metadata hosts, `*.local` (mDNS), and IP-embedding DNS-rebinding
 *   services (nip.io/sslip.io) are always blocked, with no user override — these have no legitimate use in a
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
 *
 * The blocked domains/hosts/suffixes come from the Rust core ([Blocklist.shared]),
 * the single source of truth also used by the bundle screener.
 */
class WallpaperNetworkPolicy(
    private val blocklist: Blocklist = Blocklist.shared,
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

    /** Whether private ranges are currently permitted (the live Performance toggle). */
    val lanAllowed: Boolean get() = allowLan

    fun decide(uri: Uri): Verdict = decideUrl(uri.scheme, uri.host)

    /** Scheme + host part of [decide], free of `Uri` so JVM code (the fetch proxy) can share it. */
    fun decideUrl(scheme: String?, host: String?): Verdict {
        val s = scheme?.lowercase() ?: return Verdict.Block("no scheme")
        if (s != "http" && s != "https") {
            return Verdict.Block("scheme $s not allowed")
        }
        return decideHost(host?.lowercase() ?: return Verdict.Block("no host"))
    }

    /** Host-level part of [decide] (scheme already checked); separate so JVM tests need no `Uri`. */
    fun decideHost(rawHost: String): Verdict {
        val host = rawHost.lowercase()
        if (blocklist.blocks(host)) return Verdict.Block("blocklisted host $host")
        if (isPrivateOrLoopback(host)) {
            if (!allowLan) return Verdict.Block("LAN access disabled in Performance settings")
        } else if (isRawIpLiteral(host)) {
            return Verdict.Block("raw IP literal not allowed")
        }
        if (blockAllExternal) return Verdict.Block("external network disabled in Performance settings")

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
    }
}

/**
 * Network blocklist. [blocks] matches a lowercased host (one trailing dot
 * ignored) by exact or subdomain match against [domains] and [hosts], and by
 * suffix against [suffixes].
 */
data class Blocklist(
    val domains: List<String>,
    val hosts: Set<String>,
    val suffixes: List<String>
) {
    fun blocks(host: String): Boolean {
        val h = host.lowercase().removeSuffix(".")
        if (domains.any { h == it || h.endsWith(".$it") }) return true
        if (hosts.any { h == it || h.endsWith(".$it") }) return true
        return suffixes.any { h.endsWith(it) }
    }

    companion object {
        /** The core's list, loaded once per process and only on first use. */
        val shared: Blocklist by lazy { fromJson(LumenfallCore.blocklistJson()) }

        fun fromJson(json: String): Blocklist {
            val obj = JSONObject(json)
            fun strings(key: String): List<String> {
                val arr = obj.getJSONArray(key)
                return List(arr.length()) { arr.getString(it).lowercase() }
            }
            return Blocklist(strings("domains"), strings("hosts").toSet(), strings("suffixes"))
        }
    }
}
