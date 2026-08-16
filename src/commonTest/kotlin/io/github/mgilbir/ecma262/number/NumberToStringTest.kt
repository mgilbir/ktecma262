package io.github.mgilbir.ecma262.number

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every expectation here is what node prints for the same value.
 *
 * The cases are chosen around the places the layout rules change and the places
 * other implementations get the digits wrong: the 10^21 and 10^-6 thresholds,
 * the extremes of the range, and the subnormals where even a current JVM
 * produces a longer string than the specification allows.
 */
class NumberToStringTest {

    private fun check(expected: String, value: Double) =
        assertEquals(expected, value.toEcmaString(), "for $expected")

    @Test
    fun nonFinite() {
        check("NaN", Double.NaN)
        check("Infinity", Double.POSITIVE_INFINITY)
        check("-Infinity", Double.NEGATIVE_INFINITY)
    }

    @Test
    fun zeroes() {
        check("0", 0.0)
        // Negative zero prints without the sign.
        check("0", -0.0)
    }

    @Test
    fun integersPrintWithoutATrailingPoint() {
        check("1", 1.0)
        check("2", 2.0)
        check("100", 100.0)
        check("1000000", 1e6)
        check("10000000", 1e7)
        check("9007199254740992", 9007199254740992.0)
        check("-1", -1.0)
        check("-100", -100.0)
    }

    @Test
    fun fractions() {
        check("0.1", 0.1)
        check("0.3", 0.3)
        check("4.35", 4.35)
        check("0.3333333333333333", 1.0 / 3.0)
        check("-1.5", -1.5)
    }

    /** Positional out to 10^21, scientific beyond — not the JVM's 10^7. */
    @Test
    fun theUpperThreshold() {
        check("100000000000000000000", 1e20)
        check("999999999999999900000", 9.999999999999999e20)
        check("1e+21", 1e21)
        check("123456789012345680000", 1.23456789012345678e20)
        check("1234567890123456800", 1.234567890123456789e18)
    }

    /** Positional in to 10^-6, scientific below — not the JVM's 10^-3. */
    @Test
    fun theLowerThreshold() {
        check("0.000001", 1e-6)
        check("1e-7", 1e-7)
        check("0.00001", 1e-5)
    }

    @Test
    fun extremesOfTheRange() {
        check("1.7976931348623157e+308", Double.MAX_VALUE)
        check("2.2250738585072014e-308", 2.2250738585072014e-308) // smallest normal
        check("1.5e+300", 1.5e300)
        check("1e+100", 1e100)
    }

    /**
     * Exact ties take the even significand.
     *
     * Both candidates round-trip and both are equally short, so neither the
     * round-trip nor the shortestness property can tell them apart — only the
     * specification's tie-break can. Getting this wrong cost 48 values in
     * 231,948 when checked against node, and nothing else caught it.
     */
    @Test
    fun exactTiesTakeTheEvenSignificand() {
        check("280549993592253.38", Double.fromBits(0x42EFE51456B6B7ACuL.toLong()))
        check("18212176942782.938", Double.fromBits(0x42B0905A5656BEF0uL.toLong()))
        check("-85933467182950.38", Double.fromBits(0xC2D389FC7249D998uL.toLong()))
        check("888654146529588.8", Double.fromBits(0x430941CF55DB49A6uL.toLong()))
    }

    /**
     * The smallest subnormals, where a JDK 21 `Double.toString` still produces
     * a longer string than necessary: `4.9E-324` where one digit round-trips.
     */
    @Test
    fun subnormals() {
        check("5e-324", Double.MIN_VALUE)
        check("1e-323", 1e-323)
        check("5e-323", 5e-323)
        check("2.5e-323", 2.5e-323)
    }
}
