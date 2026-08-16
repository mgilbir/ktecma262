package io.github.mgilbir.ecma262.number

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Inputs designed to make a decimal parser do unbounded work.
 *
 * Correctly rounded parsing needs exact arithmetic, and the obvious
 * implementation scales a big integer once per digit or once per power of ten
 * in the exponent — which turns a short string into an enormous computation.
 * `Double.parseDouble` hung on `2.2250738585072012e-308` for exactly this
 * shape of reason, and PHP shipped the same class of bug.
 *
 * The guards are: significant digits capped, exponent clamped as it is read,
 * and out-of-range magnitudes resolved before any big integer exists. These
 * cases exercise each of them. That the test finishes at all is the assertion —
 * every expectation is also checked against node so the shortcuts cannot be
 * hiding a wrong answer.
 */
class StringToNumberBoundsTest {

    private fun bits(s: String) = s.toEcmaDouble().toRawBits()

    /** An exponent far outside the range must not drive any scaling. */
    @Test
    fun absurdExponents() {
        assertEquals(Double.POSITIVE_INFINITY.toRawBits(), bits("1e1000000000"))
        assertEquals(0.0.toRawBits(), bits("1e-1000000000"))
        assertEquals(Double.NEGATIVE_INFINITY.toRawBits(), bits("-1e1000000000"))
        // Enough exponent digits to overflow an Int several times over.
        assertEquals(Double.POSITIVE_INFINITY.toRawBits(), bits("1e" + "9".repeat(1_000)))
        assertEquals(0.0.toRawBits(), bits("1e-" + "9".repeat(1_000)))
    }

    /** A very long mantissa is capped, not multiplied out digit by digit. */
    @Test
    fun veryLongMantissas() {
        assertEquals(Double.POSITIVE_INFINITY.toRawBits(), bits("1" + "0".repeat(100_000)))
        assertEquals(0.0.toRawBits(), bits("0." + "0".repeat(100_000) + "1"))
        // 100,000 digits of precision, of which only the leading ones can matter.
        assertEquals(
            1.2222222222222223.toRawBits(),
            bits("1." + "2".repeat(100_000)),
            "a long mantissa must still round correctly",
        )
    }

    /** Digits and exponent cancelling out must land exactly on the value. */
    @Test
    fun cancellingMagnitudes() {
        assertEquals(1.0.toRawBits(), bits("1" + "0".repeat(300) + "e-300"))
        assertEquals(1.0.toRawBits(), bits("0." + "0".repeat(299) + "1e300"))
    }

    /** Nothing here should be mistaken for a numeric literal. */
    @Test
    fun longNonLiterals() {
        val garbage = "1".repeat(100_000) + "x"
        assertTrue(garbage.toEcmaDouble().isNaN(), "100,000 digits then an x is not a literal")
        val onlyDots = ".".repeat(10_000)
        assertTrue(onlyDots.toEcmaDouble().isNaN(), "dots alone are not a literal")
    }
}
