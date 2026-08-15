package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A match ending at `$` may still begin before an astral character.
 *
 * Under `u` and `v` the input is a list of code points, so `$` holds exactly
 * when the position is the end of that list. Nothing about a surrogate pair
 * changes where a match may start.
 *
 * V8 disagrees under `u`, because a non-multiline `$` lets it begin scanning
 * near the end of the input and the offset it jumps to is a minimum match
 * length counted in code points but applied to a UTF-16 index. An astral tail
 * pushes the scan past a position that matches. It contradicts itself in the
 * process — the same class and character match when the pattern is anchored,
 * made sticky, written with an equivalent lookahead, given the `m` flag, or
 * switched to `v`.
 *
 * Found by the nightly fuzzer on `/([^\w]??){1,2}$\B(?!(?<=\b))/sug`.
 */
class EndAnchorAstralTest {

    private val pair = "😀" // U+1F600
    private val high = "\uD83D" // lone high surrogate
    private val vs = "️" // BMP, non-word

    private fun idx(pattern: String, flags: String, input: String): Int? =
        RegExp.compile(pattern, flags).exec(input)?.index

    @Test
    fun endAnchoredClassMatchesAnAstralTail() {
        // V8 returns null for every one of these under `u`.
        assertEquals(0, idx("[^\\w]$", "u", pair))
        assertEquals(0, idx("[^\\w]{2}$", "u", pair + pair))
        assertEquals(0, idx("[^\\w]{2}$", "u", high + pair))
        assertEquals(0, idx("[^\\w]{2}$", "u", vs + pair))
    }

    @Test
    fun astralFreeAndBmpTailsAreUnaffected() {
        assertEquals(0, idx("[^\\w]{2}$", "u", pair + vs))
        assertEquals(0, idx("[^\\w]{2}$", "u", high + vs))
        assertEquals(0, idx("[^\\w]{2}$", "u", high + high))
    }

    /** Every form V8 itself gets right, so the expectations are not in doubt. */
    @Test
    fun equivalentFormsAgree() {
        assertEquals(0, idx("^[^\\w]$", "u", pair)) // start-anchored
        assertEquals(0, idx("[^\\w](?![\\s\\S])", "u", pair)) // `$` written as a lookahead
        assertEquals(0, idx("[^\\w]$", "um", pair)) // multiline
        assertEquals(0, idx("[^\\w]$", "v", pair)) // v mode
    }

    /** The case the fuzzer produced, kept whole. */
    @Test
    fun theFuzzCase() {
        val input = "y1ca" + vs + high + pair
        val matches = RegExp.compile("([^\\w]??){1,2}$\\B(?!(?<=\\b))", "sug").findAll(input)
        // The earliest start that can reach the end is 5: the lone high
        // surrogate and U+1F600 are two code points, which `{1,2}` can cover.
        assertEquals(listOf(5, 8), matches.map { it.index })
        assertEquals(high + pair, matches[0][0])
    }
}
