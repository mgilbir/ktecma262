package io.github.mgilbir.ecma262.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Decoding one `EscapeSequence` or `LineContinuation`.
 *
 * Every expectation is what node produces for a string literal containing
 * just that escape, taken by evaluating it rather than by reading the table.
 * The rejections are what node rejects in strict code, which is the dialect
 * this implements: legacy octal is Annex B and sloppy-mode only.
 */
class StringEscapeTest {

    private fun decode(s: String) = decodeEscapeSequence(s, 0)

    private fun text(s: String) = decode(s)?.text

    @Test
    fun singleEscapeCharacters() {
        assertEquals("\u0008", text("\\b"))
        assertEquals("\u000C", text("\\f"))
        assertEquals("\u000A", text("\\n"))
        assertEquals("\u000D", text("\\r"))
        assertEquals("\u0009", text("\\t"))
        assertEquals("\u000B", text("\\v"))
        assertEquals("\"", text("\\\""))
        assertEquals("'", text("\\'"))
        assertEquals("\\", text("\\\\"))
        for (s in listOf("\\b", "\\n", "\\t")) assertEquals(2, decode(s)!!.end)
    }

    /** The rule that catches people: a zero escape is NUL only on its own. */
    @Test
    fun zeroIsNulOnlyWhenNoDigitFollows() {
        assertEquals("\u0000", text("\\0"))
        assertEquals("\u0000", text("\\0x"))
        assertNull(decode("\\01"), "a legacy octal escape, not NUL then one")
        assertNull(decode("\\09"))
    }

    @Test
    fun hexAndUnicode() {
        assertEquals("A", text("\\x41"))
        assertEquals(4, decode("\\x41")!!.end)
        assertEquals("A", text("\\u0041"))
        assertEquals(6, decode("\\u0041")!!.end)
        assertEquals("A", text("\\u{41}"))
        assertEquals(6, decode("\\u{41}")!!.end)
        // A supplementary code point comes back as a surrogate pair.
        assertEquals("\uD83D\uDE00", text("\\u{1F600}"))
        assertEquals(9, decode("\\u{1F600}")!!.end)
        assertEquals("\uD83D\uDE00", text("\\u{01F600}"), "leading zeros are allowed")
    }

    @Test
    fun lineContinuationProducesNothing() {
        assertEquals("", text("\\\u000A"))
        assertEquals(2, decode("\\\u000A")!!.end)
        assertEquals("", text("\\\u2028"))
        assertEquals("", text("\\\u2029"))
        // CR LF is one line break, not two.
        assertEquals("", text("\\\u000D\u000A"))
        assertEquals(3, decode("\\\u000D\u000A")!!.end, "CR LF is consumed together")
        // A lone CR is one on its own.
        assertEquals(2, decode("\\\u000D")!!.end)
    }

    /** Anything not in the table stands for itself. */
    @Test
    fun identityEscapes() {
        assertEquals("q", text("\\q"))
        assertEquals("a", text("\\a"), "not the bell character")
        assertEquals("/", text("\\/"))
        assertEquals("\u00E9", text("\\\u00E9"))
    }

    @Test
    fun rejections() {
        assertNull(decode("\\"), "nothing after the backslash")
        assertNull(decode("x"), "not a backslash at all")
        assertNull(decode("\\x"), "truncated hex")
        assertNull(decode("\\xZZ"))
        assertNull(decode("\\u00"), "truncated unicode")
        assertNull(decode("\\u{}"), "no digits")
        assertNull(decode("\\u{110000}"), "beyond the last code point")
        assertNull(decode("\\u{41"), "unterminated brace")
        // Legacy octal, which is Annex B and sloppy-mode only.
        for (d in 1..9) assertNull(decode("\\" + d), "digit escape " + d)
    }

    /** Walking a whole literal, which is the shape a lexer actually uses. */
    @Test
    fun walkingALiteral() {
        val source = "a\\u0041b\\tc\\\u000Ad"
        val out = StringBuilder()
        var i = 0
        while (i < source.length) {
            if (source[i] == 92.toChar()) {
                val d = decodeEscapeSequence(source, i)!!
                out.append(d.text)
                i = d.end
            } else {
                out.append(source[i]); i++
            }
        }
        assertEquals("aAb\u0009cd", out.toString())
    }
}
