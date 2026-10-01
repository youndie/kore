package io.github.youndie.kore.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B-68: the verdict as a pure function of two header values — every case one line, no application.
 */
class KoreMcpAuthTest {
    private val open = KoreMcpAuth("s3cret")
    private val pinned = KoreMcpAuth("s3cret", listOf("mcp.example.com", "[::1]"))

    @Test
    fun `the right bearer is let through and anything else is a 401`() {
        assertEquals(KoreMcpVerdict.Allowed, open.check("anything", "Bearer s3cret"))
        assertEquals(KoreMcpVerdict.Unauthorized, open.check("anything", null))
        assertEquals(KoreMcpVerdict.Unauthorized, open.check("anything", "Bearer wrong"))
        assertEquals(KoreMcpVerdict.Unauthorized, open.check("anything", "Bearer s3cre"))
        assertEquals(KoreMcpVerdict.Unauthorized, open.check("anything", "Bearer s3cret2"))
        assertEquals(KoreMcpVerdict.Unauthorized, open.check("anything", "Bearer "))
        assertEquals(KoreMcpVerdict.Unauthorized, open.check("anything", "Basic s3cret"))
    }

    @Test
    fun `the scheme is required - a bare token is refused`() {
        // Two of the three copies this replaces stripped an optional `Bearer ` and so accepted the
        // bare token. Nothing documented that, and RFC 6750 does not allow it.
        assertEquals(KoreMcpVerdict.Unauthorized, open.check("anything", "s3cret"))
    }

    @Test
    fun `the scheme is case-insensitive and surrounding whitespace is not part of the token`() {
        assertEquals(KoreMcpVerdict.Allowed, open.check("anything", "bearer s3cret"))
        assertEquals(KoreMcpVerdict.Allowed, open.check("anything", "BEARER s3cret"))
        assertEquals(KoreMcpVerdict.Allowed, open.check("anything", "  Bearer   s3cret  "))
    }

    @Test
    fun `a header from the browser contour is not authorisation`() {
        // The proxy in front of the HTML pages authorises by `X-Auth-Request-*` and accepts whatever
        // identity is claimed; this check never reads it, so the only way in is the token.
        assertEquals(KoreMcpVerdict.Unauthorized, open.check("anything", "X-Auth-Request-User anyone@example.com"))
    }

    @Test
    fun `with no hosts configured the host is not looked at`() {
        assertEquals(KoreMcpVerdict.Allowed, open.check(null, "Bearer s3cret"))
        assertEquals(KoreMcpVerdict.Allowed, open.check("evil.example", "Bearer s3cret"))
        assertEquals(KoreMcpVerdict.Allowed, open.check("evil.example@mcp.example.com", "Bearer s3cret"))
    }

    @Test
    fun `with hosts configured a foreign or missing or malformed host is refused before the token`() {
        assertEquals(KoreMcpVerdict.Allowed, pinned.check("mcp.example.com", "Bearer s3cret"))
        assertEquals(KoreMcpVerdict.Allowed, pinned.check("MCP.Example.com:443", "Bearer s3cret"))
        assertEquals(KoreMcpVerdict.Allowed, pinned.check("[::1]:8080", "Bearer s3cret"))

        assertEquals(KoreMcpVerdict.InvalidHost("evil.example"), pinned.check("evil.example", "Bearer s3cret"))
        assertEquals(KoreMcpVerdict.InvalidHost(null), pinned.check(null, "Bearer s3cret"))
        // A URL parser would read the part after `@` as the host; a Host header has no userinfo.
        assertEquals(
            KoreMcpVerdict.InvalidHost("evil.example@mcp.example.com"),
            pinned.check("evil.example@mcp.example.com", "Bearer s3cret"),
        )
        // Host first: a rebinding page never has the token, and the answer says what is wrong.
        assertEquals(KoreMcpVerdict.InvalidHost("evil.example"), pinned.check("evil.example", null))
    }

    @Test
    fun `an allowed-host entry that no request could match fails at construction`() {
        assertFailsWith<IllegalArgumentException> { KoreMcpAuth("s3cret", listOf("https://mcp.example.com")) }
        assertFailsWith<IllegalArgumentException> { KoreMcpAuth("s3cret", listOf("mcp.example.com:https")) }
        assertFailsWith<IllegalArgumentException> { KoreMcpAuth("s3cret", listOf(" ")) }
    }

    @Test
    fun `a blank token is refused rather than letting an empty bearer in`() {
        assertFailsWith<IllegalArgumentException> { KoreMcpAuth(" ") }
    }

    @Test
    fun `the host grammar is the Host header's and not a URL's`() {
        assertEquals("example.com", hostnameOf("Example.COM"))
        assertEquals("example.com", hostnameOf("example.com:8080"))
        assertEquals("example.com", hostnameOf("example.com:"))
        assertEquals("[::1]", hostnameOf("[::1]:3000"))
        // The copy this replaces cut at the first colon, which made every IPv6 literal `[`.
        assertEquals("[2001:db8::1]", hostnameOf("[2001:db8::1]"))
        assertNull(hostnameOf(""))
        assertNull(hostnameOf(":80"))
        assertNull(hostnameOf("[]"))
        assertNull(hostnameOf("example.com:80x"))
        assertNull(hostnameOf("example.com/path"))
        assertNull(hostnameOf("a b"))
    }

    @Test
    fun `constant-time equality is equality`() {
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abd", "abc"))
        assertFalse(constantTimeEquals("ab", "abc"))
        assertFalse(constantTimeEquals("abcabc", "abc"))
        assertFalse(constantTimeEquals("", "abc"))
        assertFalse(constantTimeEquals("", ""))
    }

    @Test
    fun `the token never reaches a string`() {
        assertFalse("s3cret" in open.toString())
        assertFalse("s3cret" in KoreMcpConfig("s3cret").toString())
    }

    @Test
    fun `a config without a token or with a blank one is off`() {
        assertFalse(KoreMcpConfig(null).enabled)
        assertFalse(KoreMcpConfig("  ").enabled)
        assertTrue(KoreMcpConfig("s3cret").enabled)
        assertFailsWith<IllegalArgumentException> { KoreMcpConfig("s3cret", path = "mcp") }
        assertFailsWith<IllegalArgumentException> { KoreMcpConfig("s3cret", path = "/mcp/") }
    }
}
