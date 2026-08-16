package io.github.mgilbir.ecma262.number

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `toString(radix)` for radix other than 10, checked against node.
 *
 * Unlike every other test in this package, the fixture here is not
 * corroborating a specified answer — it *is* the answer. ECMA-262 says the
 * result is implementation-approximated and defines nothing further, so what
 * node prints is the whole contract, and this test exists to notice if either
 * side of that agreement moves.
 */
class RadixToStringTest {

    private val golden = 0x9E3779B97F4A7C15uL.toLong()

    private val handPicked = listOf(
        0.0, 1.0, 2.0, 255.0, 4095.0, 0.5, 0.25, 0.1, 1.0 / 3.0, 1e21, 1e-7, 123.456,
        9007199254740992.0, 9007199254740993.0, 1e100, 5e-324, 1e-320,
        Double.MAX_VALUE, 2.2250738585072014e-308, 0.0625, 1023.999,
    )

    private fun sampleValues(): List<Double> {
        val values = ArrayList<Double>()
        for (i in 1L..3000L) {
            val d = Double.fromBits(i * golden)
            if (!d.isNaN() && !d.isInfinite()) values.add(d)
        }
        for (v in handPicked) {
            values.add(v)
            values.add(-v)
        }
        return values
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

    @Test
    fun theWholeGridMatchesNode() {
        val values = sampleValues()
        assertEquals(RadixFixture.VALUE_COUNT, values.size, "the value list drifted from the generator")
        var hash = 2166136261u
        var count = 0
        for (v in values) {
            for (radix in RadixFixture.RADICES) {
                hash = fnv1a(hash, v.toEcmaString(radix))
                count++
            }
        }
        assertEquals(RadixFixture.SAMPLE_COUNT, count)
        assertEquals(
            RadixFixture.SAMPLE_HASH,
            hash,
            "at least one of $count strings differs from ${RadixFixture.ORACLE}",
        )
    }

    @Test
    fun explicitCasesMatchNode() {
        for ((key, expected) in RadixFixture.EXPLICIT) {
            val (bits, radix) = key
            val value = Double.fromBits(bits.toLong())
            assertEquals(expected, value.toEcmaString(radix), "$value in base $radix")
        }
    }

    /** Radix 10 is the specified path, not this one. */
    @Test
    fun radixTenDelegatesToTheSpecifiedForm() {
        for (v in handPicked) {
            assertEquals(v.toEcmaString(), v.toEcmaString(10))
            assertEquals((-v).toEcmaString(), (-v).toEcmaString(10))
        }
    }

    @Test
    fun nonFiniteAndRangeChecks() {
        assertEquals("NaN", Double.NaN.toEcmaString(16))
        assertEquals("Infinity", Double.POSITIVE_INFINITY.toEcmaString(16))
        assertEquals("-Infinity", Double.NEGATIVE_INFINITY.toEcmaString(16))
        assertEquals("0", 0.0.toEcmaString(16))
        assertEquals("0", (-0.0).toEcmaString(16))
        assertFailsWith<IllegalArgumentException> { 1.0.toEcmaString(1) }
        assertFailsWith<IllegalArgumentException> { 1.0.toEcmaString(37) }
        assertFailsWith<IllegalArgumentException> { 1.0.toEcmaString(0) }
    }
}
