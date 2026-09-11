package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The `s` flag governs exactly one thing - whether `.` matches a line
 * terminator - so on an input containing none it cannot change any result.
 *
 * V8 does not hold to that. `/(.*?\.^|)/.exec("")` is `""` at 0 and
 * `/(.*?\.^|)/s.exec("")` is null, and on the empty string there is not even a
 * character for `s` to reinterpret. Found by the nightly fuzz on 2026-09-11,
 * one case in 500,000.
 *
 * The right answer is not in doubt. In `(.*?\.^|)` the second alternative is
 * empty, so the group matches the empty string at position 0 whatever the first
 * alternative does - and the first cannot match at all here, since `\.` is a
 * literal dot and these inputs contain none.
 *
 * These are the cases the fuzzer now skips, so they are pinned here instead.
 */
class DotAllConsistencyTest {

    private val patterns = listOf(
        "(.*?\\.^b|)",
        "(.*?\\.^|)",
        "(.*?\\.^b|($)?(-{2})?)",
        "(.*?^|)",
        "(.*\\.^|)",
        "(.+\\.^|)",
    )

    /** No input here holds a line terminator, so both spellings must agree. */
    private val inputs = listOf("", "a", "abc", "a.b")

    @Test
    fun dotAllDoesNotChangeAMatchWhenThereIsNoLineTerminator() {
        for (pattern in patterns) {
            for (input in inputs) {
                val plain = RegExp.compile(pattern).exec(input)
                val dotAll = RegExp.compile(pattern, "s").exec(input)
                assertEquals(
                    plain?.let { "${it.value}@${it.index}" },
                    dotAll?.let { "${it.value}@${it.index}" },
                    "/$pattern/ and /$pattern/s disagree on ${quote(input)}",
                )
            }
        }
    }

    /** And the agreed answer is the empty match at 0, not "no match". */
    @Test
    fun theEmptyAlternativeMatchesAtZero() {
        for (pattern in patterns) {
            for (input in inputs) {
                for (flags in listOf("", "s")) {
                    val m = RegExp.compile(pattern, flags).exec(input)
                    assertNotNull(m, "/$pattern/$flags should match ${quote(input)}")
                    assertEquals("", m.value, "/$pattern/$flags on ${quote(input)}")
                    assertEquals(0, m.index, "/$pattern/$flags on ${quote(input)}")
                }
            }
        }
    }

    /** When the input does contain a line terminator, `s` is allowed to matter. */
    @Test
    fun dotAllStillChangesWhatDotMatches() {
        assertEquals(null, RegExp.compile("a.b").exec("a\nb"))
        assertEquals("a\nb", RegExp.compile("a.b", "s").exec("a\nb")?.value)
    }

    private fun quote(s: String) =
        "\"" + s.map { if (it.code in 32..126) it.toString() else "\\u" + it.code.toString(16).padStart(4, '0') }
            .joinToString("") + "\""
}
