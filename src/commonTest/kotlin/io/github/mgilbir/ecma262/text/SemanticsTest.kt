package io.github.mgilbir.ecma262.text

import io.github.mgilbir.ecma262.number.EcmaMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The places Kotlin quietly disagrees with JavaScript.
 *
 * Neither of these produces an error: `trim()` leaves characters in place and
 * `round()` returns a different number, on every target including Kotlin/JS.
 * That makes them worth pinning against node rather than reasoning about.
 *
 * Whitespace is written with escapes throughout. Most of these characters are
 * invisible, and a test that depends on one surviving a copy-paste is a test
 * that will eventually assert something other than what it says.
 */
class SemanticsTest {

    private val golden = 0x9E3779B97F4A7C15uL.toLong()

    /** Every character ECMA-262 counts as whitespace, in one string. */
    private val allWhitespace =
        "\u0009\u000A\u000B\u000C\u000D\u0020\u00A0\u1680" +
            "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A" +
            "\u2028\u2029\u202F\u205F\u3000\uFEFF"

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

    /** The same sweep the generator used, in the same order. */
    private fun sample(): List<Double> {
        val out = ArrayList<Double>(50_000)
        for (i in 1L..20_000L) {
            val d = Double.fromBits(i * golden)
            if (!d.isNaN() && !d.isInfinite()) out.add(d)
        }
        for (i in -2000..2000) {
            out.add(i.toDouble())
            out.add(i + 0.5)
            out.add(i - 0.5)
            out.add(i / 3.0)
            out.add(i / 7.0)
            out.add(i * 1e10)
            out.add(i * 1e-10)
        }
        for (special in listOf(
            0.0, -0.0, 0.5, -0.5, 1.5, -1.5, 2.5, -2.5, 0.49999999999999994, -0.49999999999999994,
            4503599627370495.5, 4503599627370496.0, -4503599627370496.0, 9007199254740993.0,
            1e300, -1e300, 5e-324, Double.MAX_VALUE,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
        )) {
            out.add(special)
        }
        return out
    }

    private fun bitsHex(v: Double): String = v.toRawBits().toULong().toString(16)

    /**
     * Exactly which characters count as whitespace, over the whole BMP.
     *
     * A finite yes-or-no question, so it is answered completely rather than
     * sampled.
     */
    @Test
    fun trimStripsExactlyTheSpecifiedCharacters() {
        val expected = SemanticsFixture.WHITESPACE.toHashSet()
        val wrong = ArrayList<Int>()
        for (c in 0..0xFFFF) {
            if (c in 0xD800..0xDFFF) continue
            val ch = c.toChar()
            val trimmed = ("$ch" + "x" + "$ch").ecmaTrim() == "x"
            if (trimmed != (c in expected)) wrong.add(c)
        }
        assertEquals(
            emptyList(),
            wrong.take(8).map { "U+" + it.toString(16).uppercase() },
            "our whitespace set differs from node's in ${wrong.size} places",
        )
        assertEquals(25, expected.size, "the whitespace set changed size unexpectedly")
    }

    @Test
    fun trimVariantsAgree() {
        val ws = allWhitespace
        assertEquals("x y", (ws + "x y" + ws).ecmaTrim())
        assertEquals("x y$ws", (ws + "x y" + ws).ecmaTrimStart())
        assertEquals("${ws}x y", (ws + "x y" + ws).ecmaTrimEnd())
        assertEquals("", ws.ecmaTrim())
        assertEquals("", "".ecmaTrim())
        // U+200B ZERO WIDTH SPACE and U+0085 NEXT LINE only look like whitespace.
        assertEquals("\u200Bx\u0085", "\u200Bx\u0085".ecmaTrim())
    }

    /**
     * A canary rather than a requirement: if Kotlin ever adopts these
     * semantics, this fails and the corresponding function here stops earning
     * its place.
     */
    @Test
    fun kotlinStillDisagrees() {
        // Measured, not assumed: Kotlin keeps U+FEFF where the specification
        // strips it, and strips U+001C..U+001F where the specification keeps
        // them. U+00A0 is trimmed by both, contrary to what one might expect.
        assertTrue("x\uFEFF".trim() != "x\uFEFF".ecmaTrim(), "Kotlin trim now strips U+FEFF")
        assertTrue("x\u001C".trim() != "x\u001C".ecmaTrim(), "Kotlin trim now keeps U+001C")
        assertEquals("\u00A0x".trim(), "\u00A0x".ecmaTrim(), "both strip U+00A0")
        assertTrue(
            kotlin.math.round(0.5) != EcmaMath.round(0.5),
            "kotlin.math.round now rounds halves toward positive infinity",
        )
        assertTrue(kotlin.math.round(2.5) != EcmaMath.round(2.5))
    }

    @Test
    fun mathMatchesNode() {
        val values = sample()
        assertEquals(SemanticsFixture.SAMPLE_COUNT, values.size, "the sweep drifted from the generator")
        var inputs = 2166136261u
        for (d in values) inputs = fnv1a(inputs, bitsHex(d))
        assertEquals(
            SemanticsFixture.INPUT_HASH,
            inputs,
            "the sweep itself differs from the generator's, so any result hash below is meaningless",
        )

        var round = 2166136261u
        var trunc = 2166136261u
        var sign = 2166136261u
        var fround = 2166136261u
        var clz = 2166136261u
        for (d in values) {
            round = fnv1a(round, bitsHex(EcmaMath.round(d)))
            trunc = fnv1a(trunc, bitsHex(EcmaMath.trunc(d)))
            sign = fnv1a(sign, bitsHex(EcmaMath.sign(d)))
            fround = fnv1a(fround, bitsHex(EcmaMath.fround(d)))
            clz = fnv1a(clz, EcmaMath.clz32(d).toString())
        }
        var imul = 2166136261u
        for (i in 0 until values.size - 1) {
            imul = fnv1a(imul, EcmaMath.imul(values[i], values[i + 1]).toString())
        }

        assertEquals(SemanticsFixture.ROUND_HASH, round, "round differs from node")
        assertEquals(SemanticsFixture.TRUNC_HASH, trunc, "trunc differs from node")
        assertEquals(SemanticsFixture.SIGN_HASH, sign, "sign differs from node")
        assertEquals(SemanticsFixture.FROUND_HASH, fround, "fround differs from node")
        assertEquals(SemanticsFixture.CLZ32_HASH, clz, "clz32 differs from node")
        assertEquals(SemanticsFixture.IMUL_HASH, imul, "imul differs from node")
    }

    @Test
    fun explicitCasesMatchNode() {
        for (case in SemanticsFixture.EXPLICIT) {
            val argument = Double.fromBits(case.argumentBits.toLong())
            val actual = when (case.function) {
                "round" -> EcmaMath.round(argument)
                "trunc" -> EcmaMath.trunc(argument)
                "sign" -> EcmaMath.sign(argument)
                else -> EcmaMath.fround(argument)
            }
            assertEquals(
                case.resultBits.toLong(),
                actual.toRawBits(),
                "${case.function}($argument) gave $actual",
            )
        }
    }

    /** The value that makes `floor(x + 0.5)` the wrong implementation. */
    @Test
    fun roundDoesNotUseFloorOfXPlusHalf() {
        assertEquals(0.0, EcmaMath.round(0.49999999999999994))
        assertEquals(1.0, kotlin.math.floor(0.49999999999999994 + 0.5), "the trap this avoids")
        // Negative zero survives where it should.
        assertEquals(Double.NEGATIVE_INFINITY, 1.0 / EcmaMath.round(-0.2))
        assertEquals(Double.NEGATIVE_INFINITY, 1.0 / EcmaMath.round(-0.5))
        assertEquals(Double.NEGATIVE_INFINITY, 1.0 / EcmaMath.trunc(-0.5))
        assertEquals(Double.NEGATIVE_INFINITY, 1.0 / EcmaMath.sign(-0.0))
    }
}
