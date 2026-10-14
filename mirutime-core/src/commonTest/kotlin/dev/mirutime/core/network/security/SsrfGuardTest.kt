package dev.mirutime.core.network.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SsrfGuardTest {

    private fun guardWith(vararg addresses: String) =
        SsrfGuard { addresses.toList() }

    private val throwingResolver = SsrfGuard { _ -> throw IllegalStateException("dns down") }

    // Literal URLs are decided locally — resolver must never be consulted
    // (it would throw and surface DnsFailure instead of Blocked).
    private val noDnsNeeded = SsrfGuard { host -> error("resolver must not be called for $host") }

    @Test
    fun blockedLiteralTable() {
        val blocked = listOf(
            // brief block-list
            "http://localhost/page",
            "http://127.0.0.1/",
            "http://10.1.2.3/",
            "http://172.16.0.1/",
            "http://172.31.255.255/", // /12 upper bound
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/", // cloud metadata
            "http://[::1]/",
            "http://[fc00::1]/",
            "http://[ff02::1]/", // IPv6 multicast
            "ftp://example.com/", // non-http scheme
            // extensions beyond the brief (report documents these)
            "http://0.0.0.0/", // 0.0.0.0/8 any
            "http://[::]/", // ::/128 any
            "http://[fe80::1]/", // IPv6 link-local
            "http://[::ffff:127.0.0.1]/", // IPv4-mapped loopback
        )
        for (url in blocked) {
            val verdict = noDnsNeeded.isSafe(url)
            assertIs<SsrfVerdict.Blocked>(verdict, "expected Blocked for $url, got $verdict")
        }
    }

    @Test
    fun publicHostWithPublicResolutionIsOk() {
        assertEquals(SsrfVerdict.Ok, guardWith("93.184.216.34").isSafe("https://example.com/page"))
    }

    @Test
    fun publicLiteralIpIsOk() {
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://93.184.216.34/"))
    }

    @Test
    fun mixedPublicAndPrivateResolutionIsBlocked() {
        val verdict = guardWith("93.184.216.34", "10.0.0.1").isSafe("https://mixed.example.com/")
        assertIs<SsrfVerdict.Blocked>(verdict)
    }

    @Test
    fun privateOnlyResolutionIsBlocked() {
        val verdict = guardWith("192.168.0.10").isSafe("https://lan.example.com/")
        assertIs<SsrfVerdict.Blocked>(verdict)
    }

    @Test
    fun emptyResolutionIsDnsFailure() {
        assertEquals(SsrfVerdict.DnsFailure, guardWith().isSafe("https://ghost.example.com/"))
    }

    @Test
    fun resolverExceptionIsDnsFailure() {
        assertEquals(SsrfVerdict.DnsFailure, throwingResolver.isSafe("https://broken.example.com/"))
    }

    @Test
    fun boundaryAddressesRespectCidrEdges() {
        // Just outside each private range must stay public (Ok).
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://11.255.255.255/"))
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://172.15.255.255/"))
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://172.32.0.0/"))
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://192.169.0.0/"))
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://169.253.0.1/"))
        // Inside each edge must be blocked.
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://10.255.255.255/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://172.16.0.0/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://172.31.255.255/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://192.168.255.255/"))
    }

    @Test
    fun leadingZeroOctetsAreBlocked() {
        // Native inet_aton-style parsers read "012" as octal (10.x) — an
        // octet with a leading '0' must never be treated as decimal.
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://012.1.1.1/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://10.0.0.01/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://0177.0.0.1/"))
    }

    @Test
    fun ipv4CompatibleIpv6IsCheckedWithV4Rules() {
        // ::a.b.c.d (::/96 without ffff) — deprecated but still parsed by
        // stacks as the embedded IPv4 address.
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://[::10.0.0.1]/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://[::127.0.0.1]/"))
        // Public embedded v4 stays Ok.
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://[::93.184.216.34]/"))
    }

    @Test
    fun trailingDotIsNormalized() {
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://localhost./"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://127.0.0.1./"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://169.254.169.254./"))
    }
}
