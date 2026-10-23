// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val cache = DnsCache(ttlMs = 1_000, now = { clock }, resolve = { calls++; addr })
        cache.lookup("example.com")
        clock = 1_000
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
        assertTrue(calls == 2)
    }
}
