package io.github.youndie.kore.mcp

/**
 * Who the MCP endpoint lets in, as a pure function of two header values (B-68).
 *
 * [installKoreMcp] asks this for every call that routing resolved to the transport. It takes strings
 * rather than an `ApplicationCall` so a test can state a case in one line and a service can reuse the
 * verdict without standing up an application.
 *
 * The order is host, then token, and both are answered without a redirect: the client here is a
 * machine, and a login page in place of a `401` is how this endpoint once broke from the outside.
 */
public class KoreMcpAuth(
    private val token: String,
    allowedHosts: List<String> = emptyList(),
) {
    init {
        require(token.isNotBlank()) { "kore-mcp: a blank token would let an empty bearer in; pass null to turn MCP off" }
    }

    // Normalised once, at construction, by the same parser the request's `Host` goes through — so an
    // entry written as `example.com:443` or `EXAMPLE.com` means what its author meant, and an entry
    // no request could ever match fails the deployment at start instead of refusing every call.
    private val hosts: Set<String> =
        allowedHosts.mapTo(mutableSetOf()) { entry ->
            requireNotNull(hostnameOf(entry)) {
                "kore-mcp: '$entry' in the allowed hosts is not a host name (host or host:port, IPv6 in brackets)"
            }
        }

    /**
     * [host] is the `Host` header as received, [authorization] the `Authorization` header; either may
     * be absent.
     *
     * - **Host is checked only when hosts are configured**, and then a missing or malformed one is
     *   refused as well as a foreign one. With none configured it is not looked at: a deployment that
     *   names no host has nothing to compare against, and the SDK's own default — localhost only —
     *   is the check that cannot fail on a laptop and refuses everything once deployed.
     * - **The token is a bearer token, scheme required, scheme case-insensitive** (RFC 6750 §2.1,
     *   RFC 7235 §2.1). A bare token without `Bearer ` is refused: two of the three copies this
     *   replaces accepted one, which was leniency nobody documented.
     * - **Compared in constant time**: the time spent depends on the length presented, never on how
     *   many leading characters of a guess were right.
     */
    public fun check(
        host: String?,
        authorization: String?,
    ): KoreMcpVerdict {
        if (hosts.isNotEmpty()) {
            val hostname = host?.let(::hostnameOf)
            if (hostname == null || hostname !in hosts) return KoreMcpVerdict.InvalidHost(host)
        }
        val presented = bearerOf(authorization) ?: return KoreMcpVerdict.Unauthorized
        return if (constantTimeEquals(presented, token)) KoreMcpVerdict.Allowed else KoreMcpVerdict.Unauthorized
    }

    override fun toString(): String = "KoreMcpAuth(token=<redacted>, allowedHosts=$hosts)"
}

/** What [KoreMcpAuth.check] decided. */
public sealed interface KoreMcpVerdict {
    /** Through to the transport. */
    public data object Allowed : KoreMcpVerdict

    /** `401` — no bearer token, another scheme, or the wrong token. */
    public data object Unauthorized : KoreMcpVerdict

    /** `400` — hosts are configured and this `Host` is not one of them; [host] is as received, or null when absent. */
    public data class InvalidHost(
        val host: String?,
    ) : KoreMcpVerdict
}

private const val BEARER = "Bearer"

/** The credentials of a `Bearer` authorization, or null for an absent header, another scheme or no credentials. */
internal fun bearerOf(authorization: String?): String? {
    val value = authorization?.trim() ?: return null
    val space = value.indexOfFirst { it == ' ' || it == '\t' }
    if (space <= 0) return null
    if (!value.substring(0, space).equals(BEARER, ignoreCase = true)) return null
    return value.substring(space + 1).trim().takeIf { it.isNotEmpty() }
}

/**
 * Equality whose running time depends on [presented]'s length alone.
 *
 * No early exit on the first differing character, which is what lets a token be guessed one
 * character at a time from response timing; and no early exit on a length mismatch either, which the
 * three copies this replaces all had. The expected token is read cyclically so that every character
 * presented costs the same.
 */
internal fun constantTimeEquals(
    presented: String,
    expected: String,
): Boolean {
    if (expected.isEmpty()) return false
    var diff = presented.length xor expected.length
    for (i in presented.indices) {
        diff = diff or (presented[i].code xor expected[i % expected.length].code)
    }
    return diff == 0
}

private val FORBIDDEN_IN_HOST = charArrayOf('/', '@', '?', '#', '\\')

/**
 * The lower-cased host name of a `Host` value — `host`, `host:port`, `[v6]` or `[v6]:port` — or null
 * when it is not one (RFC 9110 §7.2).
 *
 * Strict on purpose. A generic URL parser accepts `evil.example@allowed.example`, and the copy this
 * replaces cut at the first `:`, which turned every IPv6 literal into `[`. The grammar is the one the
 * SDK's own `DnsRebindingProtection` applies, so the two can never disagree about a header.
 */
internal fun hostnameOf(value: String): String? {
    if (value.isBlank() || value.any { it in FORBIDDEN_IN_HOST || it.isWhitespace() }) return null
    val (name, port) =
        if (value.startsWith('[')) {
            val end = value.indexOf(']')
            if (end <= 1) return null
            value.substring(0, end + 1) to value.substring(end + 1)
        } else {
            val colon = value.indexOf(':')
            if (colon == 0) return null
            if (colon < 0) value to "" else value.substring(0, colon) to value.substring(colon)
        }
    if (port.isNotEmpty() && (port[0] != ':' || !port.substring(1).all { it in '0'..'9' })) return null
    return name.lowercase()
}
