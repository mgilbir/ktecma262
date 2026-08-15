package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `RegExp.escape`, checked against node over every code point.
 *
 * Escaping is what callers use to splice untrusted text into a pattern, so a
 * gap here is a security problem rather than a cosmetic one — hence the whole
 * range rather than a sample.
 */
class EscapeTest {

    private fun fnv1a(start: UInt, s: String): UInt {
        var h = start
        for (ch in s) {
            h = h xor ch.code.toUInt()
            h *= 16777619u
        }
        return h
    }

    @Test
    fun matchesNodeForEveryCodePoint() {
        var single = 2166136261u
        var trailing = 2166136261u
        var count = 0
        var cp = 0
        while (cp <= 0x10FFFF) {
            val ch = codePointToString(cp)
            single = fnv1a(single, RegExp.escape(ch))
            trailing = fnv1a(trailing, RegExp.escape("x$ch"))
            count++
            cp++
        }
        assertEquals(EscapeFixture.CODE_POINTS, count)
        assertEquals(EscapeFixture.SINGLE_HASH, single, "escape(c) differs from node somewhere")
        assertEquals(EscapeFixture.TRAILING_HASH, trailing, "escape(\"x\"+c) differs from node somewhere")
    }

    private fun codePointToString(cp: Int): String {
        if (cp <= 0xFFFF) return cp.toChar().toString()
        val v = cp - 0x10000
        return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

    @Test
    fun spotChecksMatchNode() {
        for ((input, expected) in EscapeFixture.spotChecks) {
            assertEquals(expected, RegExp.escape(input), "escape(${describe(input)})")
        }
    }

    private fun describe(s: String): String = buildString {
        for (ch in s) {
            if (ch.code in 0x20..0x7e) append(ch)
            else append("\\u").append(ch.code.toString(16).padStart(4, '0'))
        }
    }

    /** The point of escaping: the result matches the original text literally. */
    @Test
    fun escapedTextMatchesItself() {
        val samples = listOf(
            "a.b*c", "^start$", "[a-z]+", "(group)", "a|b", "c:\\path\\to",
            "1+1=2", "100%", "a-b-c", "\$var", "{n,m}", "back\\slash",
            "tab\there", "new\nline", "😀 emoji", "«quoted»", "#hash",
        )
        for (s in samples) {
            val re = RegExp.compile("^" + RegExp.escape(s) + "$")
            assertTrue(re.test(s), "escaped ${describe(s)} should match itself")
            assertEquals(s, re.exec(s)?.value)
        }
    }

    /** Escaping must survive concatenation with adjacent syntax. */
    @Test
    fun escapedTextIsSafeToConcatenate() {
        // A leading digit or letter is hex-escaped precisely so this cannot
        // combine into `\d`, `\b`, `\1` and so on.
        for (s in listOf("d", "b", "w", "s", "1", "0", "u0041", "x41", "k")) {
            val re = RegExp.compile("\\\\" + RegExp.escape(s))
            assertTrue(re.test("\\" + s), "\\\\ + escape(\"$s\") should match a literal backslash then \"$s\"")
        }
    }
}
