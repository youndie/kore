package io.github.youndie.kore.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * B-68: the one screening rule that is not a service's domain. Every literal below is an escape — as
 * a literal it would be invisible here too.
 */
class HiddenCharactersTest {
    @Test
    fun `ordinary text and a stack trace and an emoji pass`() {
        assertNull(HiddenCharacters.firstIn(""))
        assertNull(HiddenCharacters.firstIn("payment declined for order 12345"))
        assertNull(HiddenCharacters.firstIn("java.lang.IllegalStateException: boom\n\tat a.B.c(B.kt:12)\r\n"))
        // U+26A0 U+FE0F: a warning sign with its variation selector, as a log line writes it.
        assertNull(HiddenCharacters.firstIn("\u26A0\uFE0F retrying"))
        // A rocket, outside the BMP: a surrogate pair that is not a tag.
        assertNull(HiddenCharacters.firstIn("\uD83D\uDE80 started"))
    }

    @Test
    fun `every member of the set is found`() {
        val members =
            listOf(
                0x00, 0x08, 0x0B, 0x0C, 0x0E, 0x1B, 0x1F, 0x7F,
                0xAD,
                0x200B, 0x200C, 0x200D, 0x200E, 0x200F,
                0x202A, 0x202E,
                0x2060, 0x2064,
                0x2066, 0x2069,
                0xFEFF,
                0xE0000, 0xE0041, 0xE007F,
            )
        for (codePoint in members) {
            val text = "before " + codePointToString(codePoint) + " after"
            assertEquals(codePoint, HiddenCharacters.firstIn(text), "missed ${HiddenCharacters.label(codePoint)}")
        }
    }

    @Test
    fun `the neighbours of the set are not in it`() {
        for (codePoint in listOf(0x09, 0x0A, 0x0D, 0x20, 0x7E, 0x200A, 0x2010, 0x2065, 0xFE0F, 0xE0080, 0xE0100)) {
            assertNull(HiddenCharacters.firstIn(codePointToString(codePoint)), "flagged ${HiddenCharacters.label(codePoint)}")
        }
    }

    @Test
    fun `a whole instruction in tag characters is caught`() {
        // "hi" as U+E0068 U+E0069: drawn as nothing at all, read as text by a model.
        val smuggled = "build failed" + codePointToString(0xE0068) + codePointToString(0xE0069)
        assertEquals(0xE0068, HiddenCharacters.firstIn(smuggled))
    }

    @Test
    fun `the first one is reported and named without the text around it`() {
        assertEquals(0x202E, HiddenCharacters.firstIn("a\u202Eb\u200Bc"))
        assertEquals("U+202E", HiddenCharacters.label(0x202E))
        assertEquals("U+001B", HiddenCharacters.label(0x1B))
        assertEquals("U+E0041", HiddenCharacters.label(0xE0041))
    }

    private fun codePointToString(codePoint: Int): String =
        if (codePoint <= 0xFFFF) {
            codePoint.toChar().toString()
        } else {
            val offset = codePoint - 0x10000
            charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
        }
}
