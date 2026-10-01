package io.github.youndie.kore.mcp

/**
 * Characters a person reading untrusted text cannot see, and a model reading it can (B-68).
 *
 * A service that hands an agent text it did not write — a log value, a crash message — screens it
 * before it does, because the agent does not reliably tell data from instructions. Which phrases look
 * like instructions is the service's own business and stays there: a log line is prose in a way a
 * stack trace is not, and one list tuned for both would be wrong for each. **This one rule is not
 * domain-specific**: nothing a service legitimately returns needs a character whose whole purpose is
 * to render as nothing or to reorder what a reviewer sees. A hand-written copy of the set per service
 * drifts, and the member a hand-written set most easily misses is the Unicode Tags block: a whole
 * instruction in invisible copies of ASCII, drawn by no viewer and read by a model as text.
 *
 * **A finding names the code point, never the text around it** — [label] exists so that is the easy
 * thing to write. Findings are read by the very agent the screen protects.
 *
 * The set, written as ranges on purpose (a literal of any of these is invisible in a diff, and a
 * formatter has silently removed one before):
 *
 * - C0 controls and DEL, **except tab, line feed and carriage return**, which are ordinary in a log
 *   line or a stack trace. ESC is here: an ANSI sequence hides text from a terminal for the same reason
 *   a zero-width character hides it from a page;
 * - U+00AD soft hyphen;
 * - U+200B–U+200F: zero-width space, non-joiner, joiner, and the left-to-right and right-to-left marks;
 * - U+202A–U+202E: bidi embeddings and overrides;
 * - U+2060–U+2064: word joiner and the invisible operators;
 * - U+2066–U+2069: bidi isolates;
 * - U+FEFF: zero-width no-break space;
 * - U+E0000–U+E007F: the Tags block — invisible copies of ASCII.
 *
 * **Deliberately not in it:** the variation selectors U+FE00–U+FE0F, which follow ordinary emoji
 * (a warning sign in a log line is U+26A0 U+FE0F) and would withhold every log line that carries one.
 */
public object HiddenCharacters {
    /** The first hidden code point in [text], or null when there is none. */
    public fun firstIn(text: String): Int? {
        var i = 0
        while (i < text.length) {
            val high = text[i]
            val codePoint =
                if (high.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    0x10000 + ((high.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                } else {
                    high.code
                }
            if (isHidden(codePoint)) return codePoint
            i += if (codePoint > 0xFFFF) 2 else 1
        }
        return null
    }

    /** Whether [codePoint] is one of the set above. */
    public fun isHidden(codePoint: Int): Boolean =
        when (codePoint) {
            0x09, 0x0A, 0x0D -> false
            in 0x00..0x1F, 0x7F -> true
            0xAD -> true
            in 0x200B..0x200F -> true
            in 0x202A..0x202E -> true
            in 0x2060..0x2064 -> true
            in 0x2066..0x2069 -> true
            0xFEFF -> true
            in 0xE0000..0xE007F -> true
            else -> false
        }

    /** `U+202E` — how a finding names a character without quoting the text it sat in. */
    public fun label(codePoint: Int): String = "U+" + codePoint.toString(16).uppercase().padStart(4, '0')
}
