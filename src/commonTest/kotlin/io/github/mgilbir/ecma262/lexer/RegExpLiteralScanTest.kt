package io.github.mgilbir.ecma262.lexer

import io.github.mgilbir.ecma262.RegExp
import io.github.mgilbir.ecma262.RegExpSyntaxError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Scanning a `RegularExpressionLiteral` out of source.
 *
 * Every expectation is what node does with the same text, evaluated rather
 * than reasoned about. The one place the two deliberately differ is flags:
 * node rejects `/a/$_1` because those are not valid flags, while the grammar
 * collects any identifier part here. Scanning finds delimiters; validity is
 * `RegExp.compile`'s job, and the last test pins that split.
 */
class RegExpLiteralScanTest {

    private fun scan(text: String) = scanRegExpLiteral(text, 0)

    @Test
    fun theThreeRulesThatDecideTheDelimiter() {
        // A backslash escapes the next character, so this slash is not the end.
        scan("/a\\/b/")!!.let {
            assertEquals("a\\/b", it.source)
            assertEquals(6, it.end)
        }
        // A slash inside a class is an ordinary character.
        assertEquals("[/]", scan("/[/]/")!!.source)
        assertEquals("a[b/c]d", scan("/a[b/c]d/u")!!.source)
        assertEquals("u", scan("/a[b/c]d/u")!!.flags)
        // A line terminator may not appear in the body at all, which is what
        // stops an unterminated literal swallowing the rest of a file.
        for (terminator in listOf("\u000A", "\u000D", "\u2028", "\u2029")) {
            assertNull(scan("/a" + terminator + "b/"), "a line terminator ends the attempt")
            assertNull(scan("/a[" + terminator + "]b/"), "including inside a class")
            assertNull(scan("/a\\" + terminator + "b/"), "and after a backslash")
        }
    }

    @Test
    fun commentsAreNotLiterals() {
        // Two slashes open a line comment, so the body may not be empty.
        assertNull(scan("//"))
        assertNull(scan("//a"))
        // A star first would open a block comment.
        assertNull(scan("/*x*/"))
    }

    @Test
    fun unterminatedLiterals() {
        assertNull(scan("/x"))
        assertNull(scan("/[x/"), "the class is never closed")
        assertNull(scan("/a\\"), "a trailing backslash has nothing to escape")
        assertNull(scan(""))
        assertNull(scanRegExpLiteral("a / b", 2), "division is not a literal")
    }

    @Test
    fun offsetsAndFlags() {
        val text = "x.replace(/ #\\d+$/, \"\")"
        val at = text.indexOf(47.toChar(), startIndex = 9)
        val scan = scanRegExpLiteral(text, at)!!
        assertEquals(" #\\d+$", scan.source)
        assertEquals("", scan.flags)
        assertEquals(",", text.substring(scan.end, scan.end + 1), "end is just past the literal")

        assertEquals("gi", scan("/a/gi")!!.flags)
        assertEquals(5, scan("/a/gi")!!.end)
        assertEquals("", scan("/a/")!!.flags)
        assertEquals(3, scan("/a/")!!.end)
    }

    /**
     * Scanning and validating are separate, deliberately.
     *
     * The grammar collects any identifier part as flags, so this scans; it is
     * `RegExp.compile` that knows `$` is not a flag. A consumer gets a proper
     * message from there rather than writing its own flag list.
     */
    @Test
    fun scanningDoesNotValidate() {
        val scan = scan("/a/\$_1")!!
        assertEquals("a", scan.source)
        assertEquals("\$_1", scan.flags)
        assertFailsWith<RegExpSyntaxError> { RegExp.compile(scan.source, scan.flags) }
        // And a valid one still compiles, from the same two pieces.
        val ok = scan("/a[b/c]d/giu")!!
        RegExp.compile(ok.source, ok.flags)
    }
}
