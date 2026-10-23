// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Small thread-safe TTL cache over a DNS resolver. Failed lookups are never
 * cached: the exception from [resolve] propagates and the next call retries.
 */
class DnsCache(
    private val ttlMs: Long = 60_000,
    private val now: () -> Long,
    private val resolve: (String) -> List<InetAddress>
) {
    private class Entry(val expiresAt: Long, val addresses: List<InetAddress>)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun lookup(host: String): List<InetAddress> {
        val t = now()
        entries[host]?.let { if (t < it.expiresAt) return it.addresses }
        val addresses = resolve(host)
        entries[host] = Entry(t + ttlMs, addresses)
        return addresses
    }
}
