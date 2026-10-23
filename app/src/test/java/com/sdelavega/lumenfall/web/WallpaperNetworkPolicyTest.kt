// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperNetworkPolicyTest {
    private val fixture = """
        {"domains":["doubleclick.net","googletagmanager.com","coinhive.com"],
         "hosts":["metadata.google.internal","ipapi.co","api.bigdatacloud.net"],
         "suffixes":[".local",".nip.io",".sslip.io"]}
    """.trimIndent()
    private val blocklist = Blocklist.fromJson(fixture)
    private val policy = WallpaperNetworkPolicy(blocklist = blocklist)

    private fun blocked(host: String) = policy.decideHost(host) is WallpaperNetworkPolicy.Verdict.Block

    @Test fun blocksProprietaryGeoServices() {
        assertTrue(blocked("ipapi.co"))
        assertTrue(blocked("api.bigdatacloud.net"))
        assertTrue(blocked("eu.api.bigdatacloud.net"))
    }

    @Test fun blocksTrackerSubdomain() {
        assertTrue(blocked("stats.g.doubleclick.net"))
        assertTrue(blocked("doubleclick.net."))
        assertTrue(blocked("DoubleClick.net"))
    }

    @Test fun allowsOpenMeteo() {
        assertEquals(WallpaperNetworkPolicy.Verdict.Allow, policy.decideHost("api.open-meteo.com"))
        assertEquals(WallpaperNetworkPolicy.Verdict.Allow, policy.decideHost("api.met.no"))
    }

    @Test fun lookalikesAndMetadataAzureAreNotBlocked() {
        assertFalse(blocklist.blocks("notdoubleclick.net"))
        assertFalse(blocklist.blocks("metadata.azure.com"))
    }

    @Test fun alwaysBlockedHostsAndSuffixes() {
        assertTrue(blocklist.blocks("metadata.google.internal"))
        assertTrue(blocklist.blocks("printer.local"))
        assertTrue(blocklist.blocks("10.0.0.1.nip.io"))
    }
}
