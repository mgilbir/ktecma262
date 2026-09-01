package io.github.mgilbir.ecma262.text

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `String.compareTo` orders by UTF-16 code unit on every target, which is what
 * `IsLessThan` requires for strings.
 *
 * There is no function here for this, deliberately. The comparison a consumer
 * already has is correct, so wrapping it would add a name without adding
 * behaviour. What was missing was the *guarantee*, and this is it: if a target
 * ever ordered by code point instead, or by locale, this fails.
 *
 * The distinction is not academic. U+1D400 is the surrogate pair D835 DC00, so
 * by code unit it sorts before U+FFFD, and by code point it sorts after. The
 * two orders disagree for every supplementary character against every BMP
 * character above U+DFFF.
 */
class StringOrderingTest {

    @Test
    fun comparisonIsByCodeUnitNotCodePoint() {
        val astral = "\uD835\uDC00"   // U+1D400, code units D835 DC00
        val bmp = "\uFFFD"             // U+FFFD, one code unit
        assertTrue(
            astral < bmp,
            "code-unit order puts the surrogate pair first; this target used another order",
        )
        // The same the other way round, so a reversed comparison cannot pass.
        assertTrue(bmp > astral)
    }

    @Test
    fun orderingMatchesJavaScriptOnKnownPairs() {
        // Each pair is what JavaScript reports for a < b.
        val lessThan = listOf(
            "" to "a",
            "a" to "b",
            "a" to "ab",
            "10" to "9",           // strings, so "1" < "9"
            "A" to "a",            // upper case sorts first
            "\uD835\uDC00" to "\uFFFD",
            "\u00E9" to "\uFB01",
        )
        for ((a, b) in lessThan) {
            assertTrue(a < b, "expected " + a.length + "-unit string to sort before the other")
        }
    }
}
