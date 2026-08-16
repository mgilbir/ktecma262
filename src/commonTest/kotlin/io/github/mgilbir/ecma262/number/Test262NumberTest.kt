package io.github.mgilbir.ecma262.number

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test262's own Number formatting cases.
 *
 * Every other check in this package leans on node one way or another: the
 * differential fixtures record what it prints, and the property tests use its
 * parser to close a round trip. These expectations are literals from the
 * conformance suite, so they are the one source here that no engine produced.
 *
 * Their real contribution is the inputs. Corner cases picked by people who knew
 * where implementations go wrong are not the ones a random sweep finds, and not
 * the ones I would have thought to write down.
 */
class Test262NumberTest {

    @Test
    fun allCasesMatch() {
        var checked = 0
        for (case in Test262NumberFixture.CASES) {
            val value = Double.fromBits(case.bits.toLong())
            val actual = when (case.method) {
                "toFixed" -> value.toEcmaFixed(case.argument!!)
                "toExponential" -> value.toEcmaExponential(case.argument)
                "toPrecision" -> value.toEcmaPrecision(case.argument)
                "toString" ->
                    if (case.argument == null) value.toEcmaString() else value.toEcmaString(case.argument)
                else -> error("unknown method ${case.method}")
            }
            assertEquals(
                case.expected,
                actual,
                "test262: ($value).${case.method}(${case.argument ?: ""})",
            )
            checked++
        }
        assertTrue(checked > 150, "the fixture shrank unexpectedly: $checked cases")
    }

    /** The fixture is only worth having while its expectations are Test262's. */
    @Test
    fun expectationsComeFromTest262RatherThanAnEngine() {
        assertEquals(
            Test262NumberFixture.CASES.size,
            Test262NumberFixture.FROM_TEST262 + Test262NumberFixture.FROM_NODE,
        )
        assertTrue(
            Test262NumberFixture.FROM_TEST262 > Test262NumberFixture.CASES.size * 9 / 10,
            "only ${Test262NumberFixture.FROM_TEST262} of ${Test262NumberFixture.CASES.size} " +
                "expectations came from Test262; the rest fell back to node and are not independent",
        )
    }
}
