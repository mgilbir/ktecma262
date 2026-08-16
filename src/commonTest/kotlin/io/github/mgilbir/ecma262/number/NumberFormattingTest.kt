package io.github.mgilbir.ecma262.number

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `toFixed`, `toExponential` and `toPrecision`, checked against node.
 *
 * The grid is walked from an index on both sides, so the fixture holds a count
 * and a hash rather than a hundred thousand strings; the phases here must stay
 * in step with `tools/numbers/gen-format-fixture.mjs`.
 *
 * These three round ties **up**, where `toString` rounds them to even. The
 * explicit cases below are chosen to show that and to show what it does *not*
 * mean: `(1.005).toFixed(2)` is `"1.00"`, because 1.005 is not 1.005 — the
 * nearest double is 1.00499999999999989..., and rounding is applied to the
 * value that is actually there.
 */
class NumberFormattingTest {

    private val golden = 0x9E3779B97F4A7C15uL.toLong()

    private val handPicked = listOf(
        0.0, 1.0, 1.005, 1.5, 2.5, 0.5, 1234.5678, 0.000001, 1e-7, 1e20,
        1e21, 9.999999999999999e20, 123.456, 0.1, 1.0 / 3.0, 5e-324, 1e-320,
        Double.MAX_VALUE, 2.2250738585072014e-308, 99.995, 0.00001,
        1e-6, 999999.5, 0.0000001234, 1e100, 4.35, 1.45, 8.005,
    )

    private fun sampleValues(): List<Double> {
        val values = ArrayList<Double>(VALUE_HEADROOM)
        for (i in 1L..4000L) {
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
        assertEquals(FormatFixture.VALUE_COUNT, values.size, "the value list drifted from the generator")

        var hash = 2166136261u
        var count = 0
        for (v in values) {
            for (f in intArrayOf(0, 1, 2, 3, 6, 17, 20, 100)) {
                hash = fnv1a(hash, v.toEcmaFixed(f)); count++
                hash = fnv1a(hash, v.toEcmaExponential(f)); count++
            }
            hash = fnv1a(hash, v.toEcmaExponential(null)); count++
            for (p in intArrayOf(1, 2, 3, 6, 17, 21, 100)) {
                hash = fnv1a(hash, v.toEcmaPrecision(p)); count++
            }
        }
        assertEquals(FormatFixture.SAMPLE_COUNT, count, "the grid walked out of step with the generator")
        assertEquals(
            FormatFixture.SAMPLE_HASH,
            hash,
            "at least one of $count strings differs from ${FormatFixture.ORACLE}",
        )
    }

    @Test
    fun explicitCasesMatchNode() {
        for ((key, expected) in FormatFixture.EXPLICIT) {
            val (bits, spec) = key
            val value = Double.fromBits(bits.toLong())
            val method = spec.substringBefore(':')
            val argument = spec.substringAfter(':', "").toIntOrNull()
            val actual = when (method) {
                "fixed" -> value.toEcmaFixed(argument!!)
                "exp" -> value.toEcmaExponential(argument)
                else -> value.toEcmaPrecision(argument)
            }
            assertEquals(expected, actual, "$value.$spec")
        }
    }

    /** Out-of-range arguments are a RangeError in JavaScript. */
    @Test
    fun argumentsAreRangeChecked() {
        assertFailsWith<IllegalArgumentException> { 1.0.toEcmaFixed(-1) }
        assertFailsWith<IllegalArgumentException> { 1.0.toEcmaFixed(101) }
        assertFailsWith<IllegalArgumentException> { 1.0.toEcmaExponential(-1) }
        assertFailsWith<IllegalArgumentException> { 1.0.toEcmaExponential(101) }
        assertFailsWith<IllegalArgumentException> { 1.0.toEcmaPrecision(0) }
        assertFailsWith<IllegalArgumentException> { 1.0.toEcmaPrecision(101) }
        // NaN is checked before the argument, matching the specification's order.
        assertEquals("NaN", Double.NaN.toEcmaPrecision(200))
    }

    /** Null means "no argument", which is `toString` for precision. */
    @Test
    fun nullArgumentsBehaveLikeNoArgument() {
        assertEquals(1234.5678.toEcmaString(), 1234.5678.toEcmaPrecision(null))
        assertEquals("1.2345678e+3", 1234.5678.toEcmaExponential(null))
    }

    private companion object {
        const val VALUE_HEADROOM = 4100
    }
}
