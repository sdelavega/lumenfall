// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class DnsCacheTest {
    private val addr = listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))

    @Test
    fun cachesWithinTtl() {
        var clock = 0L
        var calls = 0
        val cache = DnsCache(ttlMs = 1_000, now = { clock }, resolve = { calls++; addr })
        assertEquals(addr, cache.lookup("example.com"))
        clock = 999
        assertEquals(addr, cache.lookup("example.com"))
        assertEquals(1, calls)
    }

    @Test
    fun refreshesAfterTtl() {
        var clock = 0L
        var calls = 0
        val cache = DnsCache(ttlMs = 5_000, now = { clock }, resolve = { calls++; addr })
        cache.lookup("example.com")
        clock = 5_000
        cache.lookup("example.com")
        assertEquals(2, calls)
    }

    @Test
    fun failuresNotCached() {
        var calls = 0
        val cache = DnsCache(ttlMs = 1_000, now = { 0L }, resolve = {
            calls++
            if (calls == 1) throw UnknownHostException("boom") else addr
        })
        try {
            cache.lookup("example.com")
            fail("expected failure")
        } catch (e: UnknownHostException) {
            // expected
        }
        assertEquals(addr, cache.lookup("example.com"))
        assertEquals(2, calls)
    }

    @Test
    fun expiredEntryIsEvicted() {
        var clock = 0L
        val cache = DnsCache(ttlMs = 1_000, now = { clock }, resolve = { addr })
        for (i in 0..299) cache.lookup("h$i.example.com")
        assertEquals(300, cache.size)
        clock = 2_000
        cache.lookup("fresh.example.com")
        assertEquals(1, cache.size)
    }
}
