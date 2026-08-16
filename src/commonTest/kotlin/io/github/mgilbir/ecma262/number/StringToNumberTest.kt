package io.github.mgilbir.ecma262.number

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Number(string)` — every expectation taken from node.
 *
 * Compared as raw bits, so `-0` is distinguished from `0` and a value one unit
 * in the last place away from the correctly rounded answer cannot pass.
 *
 * The list covers the grammar (signs, `Infinity`, radix prefixes, whitespace,
 * and the many ways to not be a numeric literal) and the rounding cases that
 * separate a correctly rounded parser from an approximate one — including
 * `2.2250738585072012e-308`, the value that used to hang `Double.parseDouble`.
 */
class StringToNumberTest {

    /** Input to the raw bits node produces, or null for NaN. */
    private val cases: List<Pair<String, ULong?>> = listOf(
        "" to 0x0uL, // 0
        " " to 0x0uL, // 0
        "\t\n " to 0x0uL, // 0
        "\u00A0\u2028 1 \u3000" to 0x3FF0000000000000uL, // 1
        "0" to 0x0uL, // 0
        "-0" to 0x8000000000000000uL, // -0
        "+0" to 0x0uL, // 0
        "1" to 0x3FF0000000000000uL, // 1
        "-1" to 0xBFF0000000000000uL, // -1
        "1.5" to 0x3FF8000000000000uL, // 1.5
        ".5" to 0x3FE0000000000000uL, // 0.5
        "5." to 0x4014000000000000uL, // 5
        "1e3" to 0x408F400000000000uL, // 1000
        "1E3" to 0x408F400000000000uL, // 1000
        "1e+3" to 0x408F400000000000uL, // 1000
        "1e-3" to 0x3F50624DD2F1A9FCuL, // 0.001
        "0x10" to 0x4030000000000000uL, // 16
        "0X1f" to 0x403F000000000000uL, // 31
        "0b101" to 0x4014000000000000uL, // 5
        "0o17" to 0x402E000000000000uL, // 15
        "-0x10" to null, // NaN
        "0x" to null, // NaN
        "Infinity" to 0x7FF0000000000000uL, // Infinity
        "-Infinity" to 0xFFF0000000000000uL, // -Infinity
        "+Infinity" to 0x7FF0000000000000uL, // Infinity
        "infinity" to null, // NaN
        "NaN" to null, // NaN
        "abc" to null, // NaN
        "12abc" to null, // NaN
        "1_0" to null, // NaN
        " 12 " to 0x4028000000000000uL, // 12
        "1." to 0x3FF0000000000000uL, // 1
        "." to null, // NaN
        "e5" to null, // NaN
        "1e" to null, // NaN
        "1e+" to null, // NaN
        "0.1" to 0x3FB999999999999AuL, // 0.1
        "1e309" to 0x7FF0000000000000uL, // Infinity
        "1e-400" to 0x0uL, // 0
        "5e-324" to 0x1uL, // 5e-324
        "2.5e-324" to 0x1uL, // 5e-324
        "2.4e-324" to 0x0uL, // 0
        "1.7976931348623157e308" to 0x7FEFFFFFFFFFFFFFuL, // 1.7976931348623157e+308
        "1.7976931348623159e308" to 0x7FF0000000000000uL, // Infinity
        "2.2250738585072012e-308" to 0x10000000000000uL, // 2.2250738585072014e-308
        "2.2250738585072011e-308" to 0xFFFFFFFFFFFFFuL, // 2.225073858507201e-308
        "9007199254740993" to 0x4340000000000000uL, // 9007199254740992
        "0.000001" to 0x3EB0C6F7A0B5ED8DuL, // 0.000001
        "1e21" to 0x444B1AE4D6E2EF50uL, // 1e+21
        "  -1.5e-3  " to 0xBF589374BC6A7EFAuL, // -0.0015
        "0x1fffffffffffff" to 0x433FFFFFFFFFFFFFuL, // 9007199254740991
        "0x20000000000000" to 0x4340000000000000uL, // 9007199254740992
        "0x20000000000001" to 0x4340000000000000uL, // 9007199254740992
        ".e3" to null, // NaN
        "+" to null, // NaN
        "-" to null, // NaN
        "1.2.3" to null, // NaN
        "--1" to null, // NaN
        "1e1e1" to null, // NaN
        "00012" to 0x4028000000000000uL, // 12
        "0.0" to 0x0uL, // 0
        "-0.0" to 0x8000000000000000uL, // -0
        "1e-323" to 0x2uL, // 1e-323
        "4.9406564584124654e-324" to 0x1uL, // 5e-324
        "7.4109846876186981e-323" to 0xFuL, // 7.4e-323
        "1.0000000000000002" to 0x3FF0000000000001uL, // 1.0000000000000002
        "0.30000000000000004" to 0x3FD3333333333334uL, // 0.30000000000000004
        "123456789012345678901234567890" to 0x45F8EE90FF6C373EuL, // 1.2345678901234568e+29
    )

    @Test
    fun matchesNode() {
        for ((input, expected) in cases) {
            val actual = input.toEcmaDouble()
            if (expected == null) {
                assertTrue(actual.isNaN(), "expected NaN for ${quote(input)} but got $actual")
            } else {
                assertEquals(
                    expected.toLong(),
                    actual.toRawBits(),
                    "for ${quote(input)}: expected ${Double.fromBits(expected.toLong())} but got $actual",
                )
            }
        }
    }

    /**
     * Digits past the significant-digit cap still decide an exact tie.
     *
     * The midpoint between 1 and the next double is 1 + 2^-53, which has a
     * finite decimal expansion. Padded past the cap it truncates to exactly that
     * midpoint, so what lies beyond is the only thing that separates "round to
     * even" from "round up" — which is what the sticky flag carries.
     *
     * Without it the last case silently returns 1. Nothing else in this file
     * catches that: a tie needs at most 767 significant digits, so a genuine
     * tie is never itself truncated.
     */
    @Test
    fun digitsBeyondTheCapBreakTies() {
        val midpoint = "1.00000000000000011102230246251565404236316680908203125"
        val padding = "0".repeat(800 - 54)

        // An exact tie: the even significand wins, which is 1.
        assertEquals(1.0.toRawBits(), midpoint.toEcmaDouble().toRawBits())
        // Still exactly the tie, just written longer.
        assertEquals(1.0.toRawBits(), (midpoint + padding + "0").toEcmaDouble().toRawBits())
        // Strictly above the tie, so it must round up - even though the digit
        // that makes it so is past the cap.
        assertEquals(
            1.0000000000000002.toRawBits(),
            (midpoint + padding + "1").toEcmaDouble().toRawBits(),
            "a digit past the cap must still break the tie upward",
        )
    }

    private fun quote(s: String) = "\"" + s.map {
        if (it.code in 32..126) it.toString() else "\\u" + it.code.toString(16).padStart(4, '0')
    }.joinToString("") + "\""
}
