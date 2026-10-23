// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/*
 * Pure decision logic for the native fetch proxy ([NativeFetchBridge]): vetting of
 * resolved addresses and a redirect loop that re-applies the network policy on every
 * hop. No Android types, so it is unit tested on the JVM (FetchGuardTest).
 */

/** Cloud metadata endpoints (never reachable, even with LAN access on). */
private val METADATA_ADDRESSES: Set<InetAddress> = listOf(
    "169.254.169.254", // AWS / GCP / Azure / OpenStack
    "fd00:ec2::254", // AWS IPv6
    "100.100.100.200", // Alibaba
    "168.63.129.16" // Azure wire server
).map { InetAddress.getByName(it) }.toSet()

/** Collapse IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d) forms to the IPv4 they carry. */
private fun unwrapV4(addr: InetAddress): InetAddress {
    if (addr !is Inet6Address) return addr
    val b = addr.address
    val leadingZero = (0 until 10).all { b[it] == 0.toByte() }
    val mapped = leadingZero && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
    val compatible = leadingZero && b[10] == 0.toByte() && b[11] == 0.toByte() &&
        !(b[12] == 0.toByte() && b[13] == 0.toByte() && b[14] == 0.toByte() && b[15] <= 1)
    return if (mapped || compatible) InetAddress.getByAddress(b.copyOfRange(12, 16)) else addr
}

private fun isPrivateRange(addr: InetAddress): Boolean {
    if (addr.isLoopbackAddress || addr.isSiteLocalAddress) return true
    val b = addr.address
    if (addr is Inet6Address) return (b[0].toInt() and 0xFE) == 0xFC // ULA fc00::/7
    return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64 // CGNAT 100.64/10
}

/**
 * Reject reason for a resolved address set, or null when every address may be
 * connected to. Metadata, link-local, any-local and multicast addresses are never
 * allowed; loopback, RFC1918, CGNAT and ULA only when [allowLan]. One bad address
 * rejects the whole set (the connection could pick it). An empty set is rejected.
 */
internal fun vetAddresses(addrs: List<InetAddress>, allowLan: Boolean): String? {
    if (addrs.isEmpty()) return "host did not resolve"
    for (raw in addrs) {
        val a = unwrapV4(raw)
        val text = a.hostAddress
        if (a in METADATA_ADDRESSES) return "metadata address $text"
        if (a.isLinkLocalAddress) return "link-local address $text"
        if (a.isAnyLocalAddress) return "unspecified address $text"
        if (a.isMulticastAddress) return "multicast address $text"
        if (isPrivateRange(a) && !allowLan) return "private address $text (LAN access disabled in Performance settings)"
    }
    return null
}

/** What one connection attempt produced. */
internal sealed interface Hop<out R> {
    data class Done<R>(val value: R) : Hop<R>
    data class Redirect(val status: Int, val location: String?) : Hop<Nothing>
}

internal sealed interface FetchOutcome<out R> {
    data class Done<R>(val value: R) : FetchOutcome<R>
    data class Rejected(val reason: String) : FetchOutcome<Nothing>
}

/** One vetted connection attempt, handed to the caller's `connect`. */
internal class HopRequest(
    val uri: URI,
    val addresses: List<InetAddress>,
    val method: String,
    val sendBody: Boolean,
    /** The host differs from the previous hop: drop credentials. */
    val crossHost: Boolean
)

internal const val MAX_REDIRECTS = 5

/**
 * Runs [startUrl] through up to [MAX_REDIRECTS] redirects. Before every connect (the
 * first one included) the hop is checked with [decide] (scheme + host policy, which
 * also spends the per-minute budget) and its host is resolved with [resolve] and
 * vetted with [vetAddresses]; only then is [onAllowedHost] called and [connect] run.
 * [connect] must not follow redirects itself.
 */
internal fun <R> followVetted(
    startUrl: String,
    method: String = "GET",
    hasBody: Boolean = false,
    decide: (scheme: String, host: String) -> String?,
    resolve: (host: String) -> List<InetAddress>,
    allowLan: () -> Boolean,
    onAllowedHost: (String) -> Unit,
    connect: (HopRequest) -> Hop<R>
): FetchOutcome<R> {
    var uri = try {
        URI(startUrl)
    } catch (e: Exception) {
        return FetchOutcome.Rejected("malformed URL")
    }
    var m = method.uppercase()
    var body = hasBody
    var previousHost: String? = null
    var redirects = 0
    while (true) {
        val scheme = uri.scheme?.lowercase() ?: return FetchOutcome.Rejected("no scheme")
        val host = uri.host?.lowercase() ?: return FetchOutcome.Rejected("no host")
        decide(scheme, host)?.let { return FetchOutcome.Rejected(it) }

        val addrs = try {
            resolve(host.removePrefix("[").removeSuffix("]"))
        } catch (e: Exception) {
            return FetchOutcome.Rejected("DNS failure for $host")
        }
        vetAddresses(addrs, allowLan())?.let { return FetchOutcome.Rejected(it) }

        onAllowedHost(host)
        when (val hop = connect(HopRequest(uri, addrs, m, body, previousHost != null && previousHost != host))) {
            is Hop.Done -> return FetchOutcome.Done(hop.value)
            is Hop.Redirect -> {
                val location = hop.location ?: return FetchOutcome.Rejected("redirect without Location")
                if (++redirects > MAX_REDIRECTS) return FetchOutcome.Rejected("too many redirects (max $MAX_REDIRECTS)")
                uri = try {
                    uri.resolve(location)
                } catch (e: Exception) {
                    return FetchOutcome.Rejected("malformed redirect Location")
                }
                // Browser semantics: 303, and 301/302 on POST, become a body-less GET.
                if ((hop.status == 303 && m != "HEAD") || ((hop.status == 301 || hop.status == 302) && m == "POST")) {
                    m = "GET"
                    body = false
                }
                previousHost = host
            }
        }
    }
}

/**
 * Reject reason for opening a WebSocket to [host], or null. Applies the bundle's
 * network policy first ([decideHost]: blocklist, block-all, budget, LAN toggle), then
 * resolves and vets every address. A DNS failure rejects: the guard fails closed.
 */
internal fun webSocketRejection(
    host: String,
    decideHost: (String) -> String?,
    resolve: (String) -> List<InetAddress>,
    allowLan: () -> Boolean
): String? {
    decideHost(host)?.let { return it }
    val addrs = try {
        resolve(host)
    } catch (e: Exception) {
        return "DNS failure for $host"
    }
    return vetAddresses(addrs, allowLan())
}
