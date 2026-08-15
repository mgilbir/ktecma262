package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Surrogate-pair handling under `u`/`v`, including one deliberate divergence
 * from V8.
 *
 * ECMA-262 matches a `u`/`v` pattern over a List of *code points*, so no match —
 * not even a zero-width one — can begin part-way through a surrogate pair.
 * V8 honours that for most patterns: `/(?:)/yu` with `lastIndex = 2` on `"😀"`
 * snaps back and reports index 0. But for zero-width assertions it reports
 * split positions, so `/\B/u.exec("b😀").index` is 2 in node where the
 * specification requires 3.
 *
 * This engine follows the specification. These tests exist so the choice is
 * explicit and cannot regress silently.
 */
class SurrogateBoundaryTest {

    private val grin = "😀" // U+1F600, two UTF-16 code units

    /**
     * The halves of [grin], built at runtime rather than written as literals.
     *
     * Kotlin/JS cannot hold a lone surrogate in a compile-time constant — both
     * `"\uD83D"` and a folded `0xD83D.toChar()` become "?" — so they are sliced
     * out of [grin] at run time instead.
     * See LoneSurrogateLiteralTest.
     */
    private val highHalf = grin.substring(0, 1)
    private val lowHalf = grin.substring(1, 2)

    @Test
    fun matchPositionsAreCodePointBoundariesUnderUnicode() {
        // "b😀": code units 0='b', 1=high, 2=low; boundaries are 0, 1 and 3.
        val positions = RegExp.compile("(?:)", "gu").findAll("b$grin").map { it.index }
        assertEquals(listOf(0, 1, 3), positions)
    }

    @Test
    fun withoutUnicodeEveryCodeUnitIsABoundary() {
        val positions = RegExp.compile("(?:)", "g").findAll("b$grin").map { it.index }
        assertEquals(listOf(0, 1, 2, 3), positions)
    }

    /**
     * The specific case where node disagrees: node reports 2, the spec requires
     * 3, because index 2 splits the pair.
     */
    @Test
    fun nonWordBoundarySkipsSplitPositions() {
        assertEquals(3, RegExp.compile("\\B", "u").exec("b$grin")?.index)
        // Without the flag, code units are the unit of matching and 2 is valid.
        assertEquals(2, RegExp.compile("\\B", "").exec("b$grin")?.index)
    }

    @Test
    fun negativeLookaheadSkipsSplitPositions() {
        // Matches only where [^a] fails, i.e. at end of input.
        val s = "ſ$grin ."
        assertEquals(s.length, RegExp.compile("(?!(?=[^a]))", "u").exec(s)?.index)
    }

    @Test
    fun wordBoundaryPositionsUnderUnicode() {
        val positions = RegExp.compile("\\b", "gu").findAll("a${grin}a").map { it.index }
        assertEquals(listOf(0, 1, 3, 4), positions)
    }

    @Test
    fun halfOfAPairIsNotMatchableUnderUnicode() {
        assertNull(RegExp.compile("\\uD83D", "u").exec(grin))
        assertNull(RegExp.compile("\\uDE00", "u").exec(grin))
        assertEquals(0, RegExp.compile("\\uD83D", "").exec(grin)?.index)
        assertEquals(1, RegExp.compile("\\uDE00", "").exec(grin)?.index)
    }

    @Test
    fun dotConsumesAWholeCodePointOnlyUnderUnicode() {
        assertEquals(grin, RegExp.compile(".", "u").exec(grin)?.value)
        assertEquals(highHalf, RegExp.compile(".", "").exec(grin)?.value)
        assertEquals(listOf(0, 1, 3), RegExp.compile(".", "gu").findAll("a${grin}b").map { it.index })
    }

    @Test
    fun anchorsAroundAPair() {
        assertEquals(0, RegExp.compile("^", "u").exec(grin)?.index)
        assertEquals(2, RegExp.compile("$", "u").exec(grin)?.index)
        assertEquals(grin, RegExp.compile("^.$", "u").exec(grin)?.value)
        assertNull(RegExp.compile("^.$", "").exec(grin))
    }

    @Test
    fun classRangesSpanAstralCodePoints() {
        val re = RegExp.compile("[\\u{1F600}-\\u{1F602}]", "u")
        assertEquals("😁", re.exec("😁")?.value)
        assertNull(re.exec("😅"))
    }

    /**
     * A backreference must not consume half a surrogate pair under `u`/`v`.
     *
     * Found by the differential fuzzer: a captured lone high surrogate compares
     * equal to the leading half of a real pair if the comparison is done by code
     * unit, which let `\1` match across a character boundary. ECMA-262 compares
     * code points, so it must not.
     */
    @Test
    fun backreferenceComparesCodePointsUnderUnicode() {
        // 'c', lone high, a real pair, '1', two long-s, tab, '0', space, lone low
        val input = "c" + highHalf + grin + "1ſſ\t0 " + lowHalf

        // The lone high at index 1 must not match the pair's leading half at
        // index 2; the first real match is the doubled long-s at index 5.
        for (flags in listOf("u", "v")) {
            val m = RegExp.compile("((?:\\D))\\1", flags).exec(input)
            assertEquals(5, m?.index, "/$flags index")
            assertEquals("ſſ", m?.value, "/$flags value")
            assertEquals("ſ", m?.get(1), "/$flags group 1")
        }

        // Without a Unicode flag the engine works in code units, so the same
        // pattern does match the two lone-ish halves at index 1.
        val unitMode = RegExp.compile("((?:\\D))\\1", "").exec(input)
        assertEquals(1, unitMode?.index)
    }

    /** An empty match must step over a whole pair, or iteration would stall. */
    @Test
    fun emptyMatchAdvancesByACodePoint() {
        assertEquals(listOf(0, 2), RegExp.compile("(?:)", "gu").findAll(grin).map { it.index })
        assertEquals(listOf(0, 1, 2), RegExp.compile("(?:)", "g").findAll(grin).map { it.index })
    }
}
