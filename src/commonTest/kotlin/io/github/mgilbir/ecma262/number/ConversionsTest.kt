package io.github.mgilbir.ecma262.number

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ToInt32` and `ToUint32`, against node.
 *
 * The oracle is JavaScript itself: `x | 0` is ToInt32 and `x >>> 0` is
 * ToUint32, so the fixture records what the engine does rather than what the
 * specification is read to say.
 *
 * The sweep is the same one the Math fixture uses, with the boundaries around
 * 2^31 and 2^32 added, since those are where wrapping starts to matter.
 */
class ConversionsTest {

    private val golden = 0x9E3779B97F4A7C15uL.toLong()

    private fun fnv1a(start: UInt, s: String): UInt {
        var h = start
        for (ch in s) { h = h xor ch.code.toUInt(); h *= 16777619u }
        h = h xor 0x7Cu
        h *= 16777619u
        return h
    }

    private fun sample(): List<Double> {
        val out = ArrayList<Double>(50_000)
        for (i in 1L..20_000L) {
            val d = Double.fromBits(i * golden)
            if (!d.isNaN() && !d.isInfinite()) out.add(d)
        }
        for (i in -2000..2000) {
            out.add(i.toDouble()); out.add(i + 0.5); out.add(i - 0.5)
            out.add(i / 3.0); out.add(i / 7.0); out.add(i * 1e10); out.add(i * 1e-10)
        }
        for (s in listOf(
            0.0, -0.0, 0.5, -0.5, 1.5, -1.5, 2.5, -2.5, 0.49999999999999994, -0.49999999999999994,
            4503599627370495.5, 4503599627370496.0, -4503599627370496.0, 9007199254740993.0,
            1e300, -1e300, 5e-324, Double.MAX_VALUE,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
            2147483647.0, 2147483648.0, -2147483648.0, -2147483649.0,
            4294967295.0, 4294967296.0, 4294967295.9, 1e21,
        )) out.add(s)
        return out
    }

    @Test
    fun matchesNode() {
        val values = sample()
        assertEquals(ConversionsFixture.SAMPLE_COUNT, values.size, "the sweep drifted")
        var i32 = 2166136261u
        var u32 = 2166136261u
        for (d in values) {
            i32 = fnv1a(i32, d.toEcmaInt32().toString())
            u32 = fnv1a(u32, d.toEcmaUint32().toString())
        }
        assertEquals(ConversionsFixture.INT32_HASH, i32, "ToInt32 differs from node")
        assertEquals(ConversionsFixture.UINT32_HASH, u32, "ToUint32 differs from node")
    }

    /** The cases that make this more than a cast. */
    @Test
    fun readableCases() {
        assertEquals(3, 3.7.toEcmaInt32(), "truncated, not rounded")
        assertEquals(-3, (-3.7).toEcmaInt32(), "toward zero, not floored")
        assertEquals(0, Double.NaN.toEcmaInt32())
        assertEquals(0, Double.POSITIVE_INFINITY.toEcmaInt32())
        assertEquals(0, Double.NEGATIVE_INFINITY.toEcmaInt32())
        assertEquals(Int.MIN_VALUE, 2147483648.0.toEcmaInt32(), "wraps at 2^31")
        assertEquals(0, 4294967296.0.toEcmaInt32(), "wraps at 2^32")
        assertEquals(-559939584, 1e21.toEcmaInt32())
        assertEquals(-1, 4294967295.9.toEcmaInt32())

        assertEquals(4294967295u, (-1.0).toEcmaUint32(), "why -1 >>> 0 is 4294967295")
        assertEquals(4294967293u, (-3.7).toEcmaUint32())
        assertEquals(2147483648u, 2147483648.0.toEcmaUint32())
        assertEquals(0u, Double.NaN.toEcmaUint32())
    }
}
