// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class FetchGuardTest {
    private fun ip(s: String): InetAddress = InetAddress.getByName(s) // literals only: no DNS

    private val blocklist = Blocklist.fromJson(
        """{"domains":[],"hosts":["ipapi.co"],"suffixes":[".local"]}"""
    )

    private fun policy(allowLan: Boolean = false) =
        WallpaperNetworkPolicy(blocklist = blocklist, allowLan = allowLan)

    private val pub = ip("93.184.216.34")

    // --- vetAddresses -------------------------------------------------------

    @Test fun publicAddressPasses() {
        assertNull(vetAddresses(listOf(pub), allowLan = false))
    }

    @Test fun privateRangesRejectedByDefault() {
        for (s in listOf("192.168.1.1", "10.0.0.5", "172.16.3.4", "127.0.0.1", "0.0.0.0", "::1", "fd12:3456::1", "fc00::1", "::ffff:192.168.1.1", "::ffff:127.0.0.1")) {
            assertNotNull(s, vetAddresses(listOf(ip(s)), allowLan = false))
        }
    }

    @Test fun anyPrivateAddressAmongPublicOnesRejects() {
        assertNotNull(vetAddresses(listOf(pub, ip("10.1.1.1")), allowLan = false))
    }

    @Test fun allowLanPermitsPrivateButNeverMetadataOrLinkLocal() {
        for (s in listOf("192.168.1.1", "10.0.0.5", "127.0.0.1", "fd12:3456::1", "::ffff:10.0.0.1")) {
            assertNull(s, vetAddresses(listOf(ip(s)), allowLan = true))
        }
        for (s in listOf("169.254.169.254", "169.254.1.1", "fe80::1", "fd00:ec2::254", "::ffff:169.254.169.254", "0.0.0.0", "100.100.100.200")) {
            assertNotNull(s, vetAddresses(listOf(ip(s)), allowLan = true))
        }
    }

    @Test fun emptyResolutionRejected() {
        assertNotNull(vetAddresses(emptyList(), allowLan = true))
    }

    // --- followVetted -------------------------------------------------------

    private class Harness(
        val policy: WallpaperNetworkPolicy,
        val dns: Map<String, String> = emptyMap(),
        val hops: Map<String, Hop<String>>,
        val allowLan: Boolean = false
    ) {
        val connected = mutableListOf<String>()
        val logged = mutableListOf<String>()
        fun run(url: String): FetchOutcome<String> = followVetted(
            startUrl = url,
            decide = { scheme, host ->
                (policy.decideUrl(scheme, host) as? WallpaperNetworkPolicy.Verdict.Block)?.reason
            },
            resolve = { host -> listOf(InetAddress.getByName(dns[host] ?: host)) },
            allowLan = { allowLan },
            onAllowedHost = { logged += it },
            connect = { req ->
                connected += req.uri.toString()
                hops.getValue(req.uri.toString())
            }
        )
    }

    private fun rejected(o: FetchOutcome<String>) = (o as FetchOutcome.Rejected).reason

    @Test fun hostnameResolvingToPrivateIsRejectedBeforeConnect() {
        val h = Harness(policy(), dns = mapOf("lan.attacker.example" to "192.168.1.1"), hops = emptyMap())
        assertTrue(h.run("https://lan.attacker.example/").let { it is FetchOutcome.Rejected })
        assertTrue(h.connected.isEmpty())
        assertTrue(h.logged.isEmpty())
    }

    @Test fun redirectToRawPrivateIpIsRejected() {
        val h = Harness(
            policy(allowLan = false),
            dns = mapOf("ok.example" to "93.184.216.34"),
            hops = mapOf("https://ok.example/" to Hop.Redirect(302, "https://10.0.0.1/admin"))
        )
        val out = h.run("https://ok.example/")
        assertTrue(rejected(out).isNotEmpty())
        assertEquals(listOf("https://ok.example/"), h.connected)
    }

    @Test fun redirectToBlocklistedHostIsRejected() {
        val h = Harness(
            policy(),
            dns = mapOf("ok.example" to "93.184.216.34", "ipapi.co" to "93.184.216.35"),
            hops = mapOf("https://ok.example/" to Hop.Redirect(301, "https://ipapi.co/json"))
        )
        assertTrue(rejected(h.run("https://ok.example/")).contains("ipapi.co"))
        assertEquals(1, h.connected.size)
    }

    @Test fun redirectToPrivateIpAllowedWithLanOnlyIfNotMetadata() {
        val ok = Harness(
            policy(allowLan = true),
            dns = mapOf("ok.example" to "93.184.216.34"),
            hops = mapOf(
                "https://ok.example/" to Hop.Redirect(302, "https://192.168.1.1/x"),
                "https://192.168.1.1/x" to Hop.Done("lan")
            ),
            allowLan = true
        )
        assertEquals("lan", (ok.run("https://ok.example/") as FetchOutcome.Done).value)
        val bad = Harness(
            policy(allowLan = true),
            dns = mapOf("ok.example" to "93.184.216.34"),
            hops = mapOf("https://ok.example/" to Hop.Redirect(302, "https://169.254.169.254/latest")),
            allowLan = true
        )
        assertTrue(bad.run("https://ok.example/") is FetchOutcome.Rejected)
    }

    @Test fun chainLongerThanFiveRedirectsIsRejected() {
        val hops = (0..6).associate { i ->
            "https://h$i.example/" to Hop.Redirect(302, "https://h${i + 1}.example/")
        }
        val dns = (0..7).associate { "h$it.example" to "93.184.216.34" }
        val h = Harness(policy(), dns = dns, hops = hops)
        assertTrue(rejected(h.run("https://h0.example/")).contains("redirect"))
        assertEquals(6, h.connected.size) // initial + 5 followed
    }

    @Test fun exactlyFiveRedirectsStillSucceeds() {
        // relative Location resolves against the current URL
        val all = mutableMapOf<String, Hop<String>>()
        for (i in 0..4) all[if (i == 0) "https://h.example/" else "https://h.example/next${i - 1}"] = Hop.Redirect(302, "/next$i")
        all["https://h.example/next4"] = Hop.Done("end")
        val h = Harness(policy(), dns = mapOf("h.example" to "93.184.216.34"), hops = all)
        assertEquals("end", (h.run("https://h.example/") as FetchOutcome.Done).value)
        assertEquals(listOf("h.example"), h.logged.distinct())
    }

    @Test fun everyHopCountsTowardTheBudget() {
        var t = 0L
        val limited = WallpaperNetworkPolicy(blocklist = blocklist, budgetPerMinute = 2, now = { t })
        val h = Harness(
            limited,
            dns = mapOf("a.example" to "93.184.216.34", "b.example" to "93.184.216.34"),
            hops = mapOf(
                "https://a.example/" to Hop.Redirect(302, "https://b.example/"),
                "https://b.example/" to Hop.Redirect(302, "https://a.example/y")
            )
        )
        assertTrue(rejected(h.run("https://a.example/")).contains("budget"))
    }

    @Test fun nonHttpRedirectSchemeRejected() {
        val h = Harness(
            policy(), dns = mapOf("a.example" to "93.184.216.34"),
            hops = mapOf("https://a.example/" to Hop.Redirect(302, "file:///etc/passwd"))
        )
        assertTrue(h.run("https://a.example/") is FetchOutcome.Rejected)
    }

    @Test fun postBecomesGetOn302ButSurvivesOn307() {
        val seen = mutableListOf<Pair<String, Boolean>>()
        val dns = mapOf("a.example" to "93.184.216.34")
        for ((status, expectBody) in listOf(302 to false, 307 to true)) {
            seen.clear()
            followVetted(
                startUrl = "https://a.example/", method = "POST", hasBody = true,
                decide = { _, _ -> null }, resolve = { listOf(InetAddress.getByName(dns.getValue(it))) },
                allowLan = { false }, onAllowedHost = {},
                connect = { req ->
                    seen += req.method to req.sendBody
                    if (req.uri.path == "/") Hop.Redirect(status, "/n") else Hop.Done("x")
                }
            )
            assertEquals(listOf("POST" to true, (if (expectBody) "POST" else "GET") to expectBody), seen)
        }
    }

    @Test fun onlyAllowedHopsAreLogged() {
        val h = Harness(
            policy(),
            dns = mapOf("a.example" to "93.184.216.34", "ipapi.co" to "93.184.216.35"),
            hops = mapOf("https://a.example/" to Hop.Redirect(302, "https://ipapi.co/"))
        )
        h.run("https://a.example/")
        assertEquals(listOf("a.example"), h.logged)
    }

    // --- webSocketRejection -------------------------------------------------

    private fun wsReason(
        host: String,
        policy: WallpaperNetworkPolicy = policy(),
        resolve: (String) -> List<InetAddress> = { listOf(pub) },
        allowLan: Boolean = false
    ) = webSocketRejection(
        host,
        decideHost = { (policy.decideHost(it) as? WallpaperNetworkPolicy.Verdict.Block)?.reason },
        resolve = resolve,
        allowLan = { allowLan }
    )

    @Test fun webSocketPublicHostAllowed() {
        assertNull(wsReason("socket.example"))
    }

    @Test fun webSocketPolicyRunsBeforeDns() {
        var resolved = false
        val r = wsReason("ipapi.co", resolve = { resolved = true; listOf(pub) })
        assertNotNull(r)
        assertEquals(false, resolved)
    }

    @Test fun webSocketHonoursBlockAllExternal() {
        val p = WallpaperNetworkPolicy(blocklist = blocklist, blockAllExternal = true)
        assertNotNull(wsReason("socket.example", policy = p))
    }

    @Test fun webSocketSpendsTheBudget() {
        val p = WallpaperNetworkPolicy(blocklist = blocklist, budgetPerMinute = 1, now = { 0L })
        assertNull(wsReason("a.example", policy = p))
        assertNotNull(wsReason("b.example", policy = p))
    }

    @Test fun webSocketDnsFailureFailsClosed() {
        assertNotNull(wsReason("socket.example", resolve = { throw java.net.UnknownHostException("x") }))
    }

    @Test fun webSocketResolvingToPrivateIsRejectedUnlessLan() {
        val lan = { _: String -> listOf(ip("192.168.1.10")) }
        assertNotNull(wsReason("socket.example", resolve = lan))
        assertNull(wsReason("socket.example", resolve = lan, allowLan = true))
        assertNotNull(wsReason("socket.example", resolve = { listOf(ip("169.254.169.254")) }, allowLan = true))
    }
}
