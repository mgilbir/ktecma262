package io.github.mgilbir.ecma262.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `WhiteSpace` and `LineTerminator`, over the whole BMP.
 *
 * Both are finite yes-or-no questions, so neither is sampled. The sets were
 * taken from node by asking the engine rather than by reading the table: a line
 * terminator ends a single-line comment, and whitespace separates tokens in a
 * declaration. The declaration form matters - an arithmetic probe reports `-`
 * as whitespace, because `1 - + - 1` really is 2.
 */
class LexicalPredicateTest {

    /** ECMA-262 12.3, all four of them. */
    private val lineTerminators = setOf(0x000A, 0x000D, 0x2028, 0x2029)

    /** ECMA-262 12.2: TAB, VT, FF, ZWNBSP and every Space_Separator. */
    private val whiteSpace = setOf(
        0x0009, 0x000B, 0x000C, 0x0020, 0x00A0, 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
        0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x202F, 0x205F, 0x3000, 0xFEFF,
    )

    @Test
    fun whiteSpaceIsExactlyTheSpecifiedSet() {
        val actual = (0..0xFFFF).filter { it !in 0xD800..0xDFFF && isEcmaWhiteSpace(it.toChar()) }.toSet()
        assertEquals(whiteSpace, actual, "WhiteSpace differs from ECMA-262 12.2")
        assertEquals(21, actual.size)
    }

    @Test
    fun lineTerminatorIsExactlyTheSpecifiedSet() {
        val actual = (0..0xFFFF).filter { it !in 0xD800..0xDFFF && isEcmaLineTerminator(it.toChar()) }.toSet()
        assertEquals(lineTerminators, actual, "LineTerminator differs from ECMA-262 12.3")
        assertEquals(4, actual.size)
    }

    /**
     * The two productions are disjoint, and together they are exactly what
     * `trim` removes - which is checked against node separately, so this ties
     * the split to a set that was verified as a whole.
     */
    @Test
    fun theTwoAreDisjointAndTogetherAreWhatTrimRemoves() {
        for (c in 0..0xFFFF) {
            if (c in 0xD800..0xDFFF) continue
            val ch = c.toChar()
            assertFalse(
                isEcmaWhiteSpace(ch) && isEcmaLineTerminator(ch),
                "U+${c.toString(16).uppercase()} is in both productions",
            )
        }
        val union = (0..0xFFFF)
            .filter { it !in 0xD800..0xDFFF && (isEcmaWhiteSpace(it.toChar()) || isEcmaLineTerminator(it.toChar())) }
            .toSet()
        assertEquals(SemanticsFixture.WHITESPACE.toSet(), union, "the union is not what trim removes")
        assertEquals(25, union.size)
    }

    /** Characters that look like they belong and do not. */
    @Test
    fun lookalikesAreExcluded() {
        // NEXT LINE ends a line in some encodings, but not in ECMA-262.
        assertFalse(isEcmaLineTerminator('\u0085'))
        assertFalse(isEcmaWhiteSpace('\u0085'))
        // ZERO WIDTH SPACE is a format character, not whitespace.
        assertFalse(isEcmaWhiteSpace('\u200B'))
        // The file and record separators, which Kotlin's own trim removes.
        for (c in '\u001C'..'\u001F') assertFalse(isEcmaWhiteSpace(c), "U+001C..1F are not whitespace")
        // The byte order mark is whitespace, which is the surprising one.
        assertTrue(isEcmaWhiteSpace('\uFEFF'))
    }
}
