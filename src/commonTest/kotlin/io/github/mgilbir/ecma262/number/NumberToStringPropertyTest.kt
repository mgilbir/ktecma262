package io.github.mgilbir.ecma262.number

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Conformance by construction, without an oracle.
 *
 * 6.1.6.1.20 asks for the shortest digit string that identifies the double, so
 * two properties between them *are* the specification for radix 10:
 *
 *  - **round trip** — parsing the output returns the same double, bit for bit;
 *  - **shortest** — no decimal with fewer significant digits does.
 *
 * Together they pin the digits uniquely, which differential testing against
 * node can only corroborate. That also makes this the safety net for replacing
 * the exact algorithm with a table-driven one later: these tests are written
 * against the specification, not against how the digits are currently produced.
 *
 * Round-tripping relies on the host's decimal parser being correctly rounded.
 * That is true of the JVM, JavaScript and Kotlin/Native, and a failure here
 * would name the platform.
 */
class NumberToStringPropertyTest {

    /** Deterministic, so a failure is reproducible. */
    private class Lcg(private var state: Long) {
        fun next(): Long {
            state = state * 6364136223846793005L + 1442695040888963407L
            return state
        }
    }

    /** Strips the layout so only the significant digits and exponent remain. */
    private fun significantDigits(value: Double): Pair<String, Int> = shortestDigits(value)

    private fun roundTrips(digits: String, pointPosition: Int, target: Double): Boolean {
        val candidate = "0.${digits}e$pointPosition".toDouble()
        return candidate.toRawBits() == target.toRawBits()
    }

    /**
     * True when some decimal with fewer significant digits also identifies
     * [value] — which would mean the output was not shortest.
     *
     * For each shorter length the two nearest candidates are the digits
     * truncated to that length and that value incremented by one unit in the
     * last place; the nearest shorter decimal to [value] is always one of them.
     */
    private fun shorterCandidateRoundTrips(digits: String, n: Int, value: Double): String? {
        for (length in 1 until digits.length) {
            val head = digits.substring(0, length)
            if (roundTrips(head, n, value)) return "$head e$n"
            // Increment the last digit, allowing the carry to shorten the string
            // and push the exponent up: "99" becomes "100", i.e. 0.1 at n+1.
            val bumped = (head.toLong() + 1).toString()
            val (bumpedDigits, bumpedN) =
                if (bumped.length > length) bumped.substring(0, length) to n + 1 else bumped to n
            if (roundTrips(bumpedDigits, bumpedN, value)) return "$bumpedDigits e$bumpedN"
        }
        return null
    }

    private fun checkValue(value: Double) {
        if (value.isNaN() || value.isInfinite() || value == 0.0) return
        val magnitude = if (value < 0) -value else value
        val (digits, n) = significantDigits(magnitude)

        assertTrue(digits.isNotEmpty(), "no digits for $magnitude")
        assertTrue(digits[0] != '0', "leading zero in $digits for $magnitude")
        assertTrue(digits.length == 1 || digits.last() != '0', "trailing zero in $digits")

        assertTrue(
            roundTrips(digits, n, magnitude),
            "0.${digits}e$n does not round-trip to ${magnitude.toRawBits()}",
        )
        val shorter = shorterCandidateRoundTrips(digits, n, magnitude)
        assertTrue(
            shorter == null,
            "not shortest for ${magnitude.toRawBits()}: emitted $digits e$n but $shorter also round-trips",
        )
    }

    @Test
    fun randomDoublesRoundTripAndAreShortest() {
        val rng = Lcg(20260816)
        var checked = 0
        repeat(20_000) {
            val value = Double.fromBits(rng.next())
            if (value.isNaN() || value.isInfinite()) return@repeat
            checkValue(value)
            checked++
        }
        assertTrue(checked > 15_000, "too few usable values: $checked")
    }

    /** The region where a current JVM stops being shortest. */
    @Test
    fun subnormalsRoundTripAndAreShortest() {
        for (bits in 1L..4_000L) checkValue(Double.fromBits(bits))
        val rng = Lcg(7)
        repeat(4_000) {
            checkValue(Double.fromBits(rng.next() and 0x000FFFFFFFFFFFFFL))
        }
    }

    /** Powers of two exercise the asymmetric gap to the neighbour below. */
    @Test
    fun powersOfTwoRoundTripAndAreShortest() {
        for (exponent in 1..2046) {
            checkValue(Double.fromBits(exponent.toLong() shl 52))
        }
    }

    /** Values with short decimal forms, where "shortest" bites hardest. */
    @Test
    fun simpleDecimalsRoundTripAndAreShortest() {
        for (i in 1..2_000) {
            checkValue(i.toDouble())
            checkValue(i / 10.0)
            checkValue(i / 1000.0)
            checkValue(i * 1e18)
            checkValue(i * 1e-20)
        }
        var p = 1e-300
        while (p < 1e300) {
            checkValue(p)
            p *= 10
        }
    }

    /** The layout rules must reproduce the digits they were given. */
    @Test
    fun layoutPreservesTheDigits() {
        val rng = Lcg(99)
        repeat(5_000) {
            val value = Double.fromBits(rng.next())
            if (value.isNaN() || value.isInfinite() || value == 0.0) return@repeat
            val text = value.toEcmaString()
            assertEquals(
                value.toRawBits(),
                text.toDouble().toRawBits(),
                "formatted as $text but that parses to something else",
            )
        }
    }
}
