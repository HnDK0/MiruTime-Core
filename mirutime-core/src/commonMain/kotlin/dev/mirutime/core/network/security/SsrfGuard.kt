package dev.mirutime.core.network.security

import io.ktor.http.Url

// SSRF check verdict. Ok = safe to request; Blocked = refused with reason;
// DnsFailure = hostname could not be resolved (not a false "safe").
sealed class SsrfVerdict {
    data object Ok : SsrfVerdict()
    data class Blocked(val reason: String) : SsrfVerdict()
    data object DnsFailure : SsrfVerdict()
}

// DNS resolution is injected — commonMain has no InetAddress and no new
// dependencies are allowed; host wiring provides the implementation later.
fun interface HostResolver {
    fun resolve(host: String): List<String>
}

// PluginError is a data hierarchy, not Throwable — this bridges SSRF
// failures out of suspend call sites (precedent: CfChallengeRequiredException, Task 6).
class SsrfBlockedException(val reason: String) :
    IllegalStateException("SSRF blocked: $reason")

class DnsFailureException(val host: String) :
    IllegalStateException("DNS resolution failed for host=$host")

// URL-level SSRF guard: scheme allow-list, literal-IP block-list check,
// and resolved-address block-list check via the injected resolver.
// ceiling: the check runs before the request — DNS-rebinding/TOCTOU races
// between check and connect are not covered (full IP pinning is out of scope).
class SsrfGuard(private val resolver: HostResolver) {

    fun isSafe(url: String): SsrfVerdict {
        val parsed = try {
            Url(url)
        } catch (e: Exception) {
            return SsrfVerdict.Blocked("malformed url")
        }
        val scheme = parsed.protocol.name.lowercase()
        if (scheme != "http" && scheme != "https") {
            return SsrfVerdict.Blocked("scheme '$scheme' is not http/https")
        }
        // ktor keeps IPv6 literals bracketed — strip for parsing; a trailing
        // FQDN dot ("localhost.", "127.0.0.1.") is not meaningful here.
        val host = parsed.host.trim('[', ']').removeSuffix(".")
        if (host.isEmpty()) return SsrfVerdict.Blocked("empty host")
        val lower = host.lowercase()
        if (lower == "localhost" || lower.endsWith(".localhost")) {
            return SsrfVerdict.Blocked("localhost")
        }
        // Literal IP — decide locally, no DNS involved.
        parseIpv4(lower)?.let { octets ->
            blockedV4Reason(octets)?.let { return SsrfVerdict.Blocked(it) }
            return SsrfVerdict.Ok
        }
        // Four purely numeric labels that failed strict parsing (leading-zero
        // octet, out-of-range value) are ambiguous IPv4 literals — fail
        // closed, no DNS fallthrough.
        if (lower.count { it == '.' } == 3 && lower.all { it.isDigit() || it == '.' }) {
            return SsrfVerdict.Blocked("ambiguous ipv4 literal '$host'")
        }
        if (':' in lower) {
            val groups = parseIpv6(lower)
                ?: return SsrfVerdict.Blocked("unparseable ipv6 literal '$host'")
            blockedV6Reason(groups)?.let { return SsrfVerdict.Blocked(it) }
            return SsrfVerdict.Ok
        }
        val resolved = try {
            resolver.resolve(lower)
        } catch (e: Exception) {
            return SsrfVerdict.DnsFailure
        }
        if (resolved.isEmpty()) return SsrfVerdict.DnsFailure
        for (address in resolved) {
            val a = address.trim('[', ']').lowercase()
            val v4 = parseIpv4(a)
            val v6 = if (v4 == null && ':' in a) parseIpv6(a) else null
            if (v4 == null && v6 == null) {
                return SsrfVerdict.Blocked("unparseable resolved address '$address'")
            }
            // fail-closed: any blocked address rejects the URL, even when a
            // public one is also present (mixed public+private -> Blocked).
            val reason = v4?.let { blockedV4Reason(it) } ?: v6?.let { blockedV6Reason(it) }
            if (reason != null) return SsrfVerdict.Blocked("resolved $address: $reason")
        }
        return SsrfVerdict.Ok
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' }) return null
            // Leading zero is octal-vs-decimal ambiguous (inet_aton reads
            // "012" as 10) — strict decimal only, callers fail closed.
            if (p.length > 1 && p[0] == '0') return null
            val v = p.toInt()
            if (v > 255) return null
            octets[i] = v
        }
        return octets
    }

    // Expands "::" by hand and matches by16-bit groups; supports an
    // embedded IPv4 tail (e.g. ::ffff:127.0.0.1).
    private fun parseIpv6(host: String): IntArray? {
        var s = host
        val zone = s.indexOf('%')
        if (zone >= 0) s = s.substring(0, zone)
        if (s.isEmpty()) return null
        val parts = s.split("::")
        if (parts.size > 2) return null

        fun parseGroups(segment: String): List<Int>? {
            if (segment.isEmpty()) return emptyList()
            val groups = mutableListOf<Int>()
            for (piece in segment.split(':')) {
                if (piece.isEmpty()) return null
                if (piece.contains('.')) {
                    val v4 = parseIpv4(piece) ?: return null
                    groups += (v4[0] shl 8) or v4[1]
                    groups += (v4[2] shl 8) or v4[3]
                } else {
                    if (piece.length > 4 || piece.any { it !in "0123456789abcdef" }) return null
                    groups += piece.toInt(16)
                }
            }
            return groups
        }

        val head = parseGroups(parts[0]) ?: return null
        if (parts.size == 1) {
            return if (head.size == 8) head.toIntArray() else null
        }
        val tail = parseGroups(parts[1]) ?: return null
        // "::" must stand for at least one zero group.
        if (head.size + tail.size > 7) return null
        val full = ArrayList<Int>(8)
        full.addAll(head)
        repeat(8 - head.size - tail.size) { full += 0 }
        full.addAll(tail)
        return full.toIntArray()
    }

    private fun blockedV4Reason(o: IntArray): String? = when {
        o[0] == 0 -> "0.0.0.0/8 (any)"
        o[0] == 10 -> "10.0.0.0/8 (private)"
        o[0] == 127 -> "127.0.0.0/8 (loopback)"
        o[0] == 169 && o[1] == 254 -> "169.254.0.0/16 (link-local/metadata)"
        o[0] == 172 && o[1] in 16..31 -> "172.16.0.0/12 (private)"
        o[0] == 192 && o[1] == 168 -> "192.168.0.0/16 (private)"
        o[0] in 224..239 -> "${o.joinToString(".")} (multicast 224.0.0.0/4)"
        else -> null
    }

    private fun blockedV6Reason(g: IntArray): String? {
        if (g.all { it == 0 }) return "::/128 (any)"
        if (g[0] == 0 && g[1] == 0 && g[2] == 0 && g[3] == 0 &&
            g[4] == 0 && g[5] == 0 && g[6] == 0 && g[7] == 1
        ) {
            return "::1 (loopback)"
        }
        // IPv4-compatible ::a.b.c.d (::/96) and IPv4-mapped ::ffff:a.b.c.d
        // (::ffff:0:0/96) — fall through to the IPv4 rules for the embedded
        // address. "::" and "::1" are already handled above.
        if (g[0] == 0 && g[1] == 0 && g[2] == 0 && g[3] == 0 && g[4] == 0) {
            val v4 = intArrayOf(g[6] shr 8, g[6] and 0xff, g[7] shr 8, g[7] and 0xff)
            blockedV4Reason(v4)?.let { return "::/96 (embedded: $it)" }
        }
        val b0 = g[0] shr 8
        val b1 = g[0] and 0xff
        return when {
            b0 == 0xfc || b0 == 0xfd -> "fc00::/7 (unique-local)"
            b0 == 0xff -> "ff00::/8 (multicast)"
            b0 == 0xfe && (b1 and 0xc0) == 0x80 -> "fe80::/10 (link-local)"
            else -> null
        }
    }
}
