package io.github.mgilbir.ecma262.number

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fast path must agree with the exact one wherever it answers at all.
 *
 * Grisu3 is allowed to decline — that is what makes it safe — but it is never
 * allowed to be confidently wrong. Comparing the two implementations directly
 * is a sharper check than comparing either against node, because it isolates
 * the fast path: a disagreement here names it immediately rather than surfacing
 * as one differing string in a hash over 231,948 values.
 *
 * The declining rate is asserted loosely in both directions. Too high and the
 * fast path has stopped being fast; **zero** would mean the exact fallback had
 * become unreachable and was quietly rotting.
 */
class Grisu3AgreesWithExactTest {

    private class Lcg(private var state: Long) {
        fun next(): Long {
            state = state * 6364136223846793005L + 1442695040888963407L
            return state
        }
    }

    private fun compare(value: Double): Boolean {
        val fast = grisu3ShortestDigits(value) ?: return false
        assertEquals(exactShortestDigits(value), fast, "fast path disagrees for ${value.toRawBits()}")
        return true
    }

    @Test
    fun agreesOverRandomDoubles() {
        val rng = Lcg(987654321)
        var total = 0
        var declined = 0
        repeat(50_000) {
            val value = Double.fromBits(rng.next())
            if (value.isNaN() || value.isInfinite() || value == 0.0) return@repeat
            val magnitude = if (value < 0) -value else value
            total++
            if (!compare(magnitude)) declined++
        }
        assertTrue(total > 40_000, "too few usable values: $total")
        // Around one in two hundred is expected; the bounds only catch a change
        // of character, not noise.
        assertTrue(declined > 0, "the exact fallback was never reached - it is no longer covered here")
        assertTrue(
            declined * 100 < total * 5,
            "the fast path declined $declined of $total, far more than expected",
        )
    }

    @Test
    fun agreesOverSubnormalsAndSimpleDecimals() {
        for (bits in 1L..20_000L) compare(Double.fromBits(bits))
        for (exponent in 1..2046) compare(Double.fromBits(exponent.toLong() shl 52))
        for (i in 1..5_000) {
            compare(i.toDouble())
            compare(i / 10.0)
            compare(i / 1000.0)
        }
    }
}
