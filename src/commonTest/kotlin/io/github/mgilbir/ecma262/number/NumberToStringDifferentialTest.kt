package io.github.mgilbir.ecma262.number

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every string checked against what node produced for the same double.
 *
 * `NumberToStringPropertyTest` already proves the digits are the shortest that
 * round-trip, which is the specification's requirement. This adds the other
 * half: that the *layout* rules agree with a real JavaScript engine, since
 * those are pure presentation and no property can catch getting them wrong.
 *
 * The sample is walked from an index rather than stored, so both sides visit
 * the identical sequence and the fixture is a hash rather than a megabyte of
 * expected strings. The phases must stay in step with
 * `tools/numbers/gen-fixture.mjs`.
 */
class NumberToStringDifferentialTest {

    /** Odd multiplier that sweeps the whole 64-bit space; matches the generator. */
    private val golden = 0x9E3779B97F4A7C15uL.toLong()

    private fun fnv1a(start: UInt, s: String): UInt {
        var h = start
        for (ch in s) {
            h = h xor ch.code.toUInt()
            h *= 16777619u
        }
        h = h xor 0x7Cu // separator, so concatenation cannot collide
        h *= 16777619u
        return h
    }

    @Test
    fun explicitValuesMatchNode() {
        for ((bits, expected) in NumberFixture.EXPLICIT) {
            val value = Double.fromBits(bits.toLong())
            assertEquals(expected, value.toEcmaString(), "for raw bits ${bits.toString(16)}")
        }
    }

    @Test
    fun theWholeSampleMatchesNode() {
        var hash = 2166136261u
        var count = 0

        // Phase A - a deterministic sweep of the whole bit space.
        for (i in 1L..200_000L) {
            val value = Double.fromBits(i * golden)
            if (value.isNaN() || value.isInfinite()) continue
            hash = fnv1a(hash, value.toEcmaString())
            count++
        }
        // Phase B - the smallest subnormals.
        for (bits in 1L..20_000L) {
            hash = fnv1a(hash, Double.fromBits(bits).toEcmaString())
            count++
        }
        // Phase C - every power of two.
        for (e in 1L..2046L) {
            hash = fnv1a(hash, Double.fromBits(e shl 52).toEcmaString())
            count++
        }
        // Phase D - short decimals.
        for (i in 1..5_000) {
            hash = fnv1a(hash, (i / 10.0).toEcmaString())
            hash = fnv1a(hash, (i / 1000.0).toEcmaString())
            count += 2
        }

        assertEquals(NumberFixture.SAMPLE_COUNT, count, "the sample walked out of step with the generator")
        assertEquals(
            NumberFixture.SAMPLE_HASH,
            hash,
            "at least one of $count values differs from ${NumberFixture.ORACLE}",
        )
    }
}
