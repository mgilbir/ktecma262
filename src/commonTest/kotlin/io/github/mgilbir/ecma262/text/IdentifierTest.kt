package io.github.mgilbir.ecma262.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Identifier validation, at two levels.
 *
 * The hashes cover every code point against the composed rule from 12.7. The
 * explicit cases were checked by actually parsing them in node - `var x` for a
 * binding and `o.x` for a name - which is the real question and far too slow to
 * ask 1,114,112 times. Cheap and exhaustive, plus expensive and authoritative,
 * is a better pair than either alone.
 */
class IdentifierTest {

    private fun stringOf(units: IntArray): String {
        val sb = StringBuilder(units.size)
        for (u in units) sb.append(u.toChar())
        return sb.toString()
    }

    private fun fnv1a(start: UInt, s: String): UInt {
        var h = start
        for (ch in s) {
            h = h xor ch.code.toUInt()
            h *= 16777619u
        }
        h = h xor 0x7Cu
        h *= 16777619u
        return h
    }

    private fun codePointToString(cp: Int): String {
        if (cp <= 0xFFFF) return cp.toChar().toString()
        val v = cp - 0x10000
        return charArrayOf((0xD800 + (v ushr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar())
            .concatToString()
    }

    @Test
    fun everyCodePointMatchesNode() {
        var start = 2166136261u
        var part = 2166136261u
        var starts = 0
        var parts = 0
        var counted = 0
        var cp = 0
        while (cp <= 0x10FFFF) {
            if (cp in 0xD800..0xDFFF) {
                cp++
                continue
            }
            val s = codePointToString(cp)
            // A single character is a name exactly when it can start one.
            val isStart = s.isEcmaIdentifierName()
            // Prefixing a known start makes this the "can continue" question.
            val isPart = ("a$s").isEcmaIdentifierName()
            if (isStart) starts++
            if (isPart) parts++
            start = fnv1a(start, if (isStart) "1" else "0")
            part = fnv1a(part, if (isPart) "1" else "0")
            counted++
            cp++
        }
        assertEquals(IdentifierFixture.CODE_POINTS, counted)
        assertEquals(IdentifierFixture.START_COUNT, starts, "wrong number of identifier starts")
        assertEquals(IdentifierFixture.PART_COUNT, parts, "wrong number of identifier parts")
        assertEquals(IdentifierFixture.START_HASH, start, "identifier starts differ from node")
        assertEquals(IdentifierFixture.PART_HASH, part, "identifier parts differ from node")
    }

    @Test
    fun explicitCasesMatchNodesParser() {
        for (case in IdentifierFixture.EXPLICIT) {
            val text = stringOf(case.units)
            assertEquals(
                case.isName,
                text.isEcmaIdentifierName(),
                "isEcmaIdentifierName(${describe(text)})",
            )
            // node's check ran in a sloppy script, which is what the defaults mean.
            assertEquals(
                case.isBinding,
                text.isEcmaIdentifier(),
                "isEcmaIdentifier(${describe(text)})",
            )
        }
    }

    private fun describe(s: String): String =
        s.map { if (it.code in 32..126) it.toString() else "\\u" + it.code.toString(16).padStart(4, '0') }
            .joinToString("")

    /** Keywords are names but not bindings, which is the whole distinction. */
    @Test
    fun keywordsAreNamesButNotBindings() {
        for (word in listOf("if", "class", "true", "null", "return", "typeof")) {
            assertTrue(word.isEcmaIdentifierName(), "$word is a valid property key")
            assertFalse(word.isEcmaIdentifier(), "$word cannot be a binding")
            assertTrue(word.isEcmaReservedWord())
        }
    }

    /** The words whose reservation depends on where the code appears. */
    @Test
    fun contextuallyReservedWords() {
        assertTrue("let".isEcmaIdentifier())
        assertFalse("let".isEcmaIdentifier(strict = true))
        assertTrue("static".isEcmaIdentifier())
        assertFalse("static".isEcmaIdentifier(strict = true))

        assertTrue("await".isEcmaIdentifier())
        assertFalse("await".isEcmaIdentifier(module = true))
        assertTrue("yield".isEcmaIdentifier())
        assertFalse("yield".isEcmaIdentifier(generator = true))

        // The production lists both, even though they are not always reserved.
        assertTrue("await".isEcmaReservedWord())
        assertTrue("yield".isEcmaReservedWord())

        // Never reserved at all, despite reading like keywords.
        for (word in listOf("of", "as", "from", "async", "get", "set", "undefined", "NaN")) {
            assertTrue(word.isEcmaIdentifier(strict = true), "$word is not reserved")
        }
    }

    @Test
    fun edgeCases() {
        assertFalse("".isEcmaIdentifierName(), "an empty string is not a name")
        assertFalse("1a".isEcmaIdentifierName())
        assertFalse("a-b".isEcmaIdentifierName())
        assertTrue("$".isEcmaIdentifierName())
        assertTrue("_".isEcmaIdentifierName())
        assertTrue("\$_a0".isEcmaIdentifierName())
        // Zero-width joiners are allowed inside a word but cannot start one.
        assertTrue("a\u200Cb".isEcmaIdentifierName())
        assertTrue("a\u200Db".isEcmaIdentifierName())
        assertFalse("\u200Cab".isEcmaIdentifierName())
        // Astral letters work; a lone surrogate does not.
        assertTrue("\uD835\uDC00".isEcmaIdentifierName(), "MATHEMATICAL BOLD CAPITAL A")
        assertFalse("\uD800".isEcmaIdentifierName())
        assertFalse("a\uD800".isEcmaIdentifierName())
    }
}
