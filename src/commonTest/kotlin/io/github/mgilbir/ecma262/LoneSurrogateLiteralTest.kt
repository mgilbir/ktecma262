package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards a platform trap that silently corrupts surrogate test data.
 *
 * Kotlin/JS cannot carry an unpaired surrogate in a *compile-time string
 * constant*. Both a literal `"\uD83D"` and a constant-folded
 * `0xD83D.toChar().toString()` come back as "?" (U+003F), because the constant
 * is materialised into the generated JavaScript source, where a lone surrogate
 * is not representable. Kotlin/JVM keeps it. Values computed at run time are
 * unaffected on both platforms, so the engine itself returns lone surrogates
 * correctly everywhere.
 *
 * That matters here because matching JavaScript's UTF-16 semantics on unpaired
 * surrogates is the whole point. Two rules follow:
 *
 *  - derive lone surrogates from a well-formed pair at run time, never write
 *    them as constants;
 *  - keep generated test data in a pure-ASCII transport form and decode it at
 *    run time, which is what [DiffFixture] does.
 *
 * These tests fail loudly if either assumption stops holding.
 */
class LoneSurrogateLiteralTest {

    /** A well-formed pair is representable everywhere, so this literal is safe. */
    private val pair = "😀"

    @Test
    fun surrogatePairLiteralsAreExact() {
        assertEquals(2, pair.length)
        assertEquals(0xD83D, pair[0].code)
        assertEquals(0xDE00, pair[1].code)
    }

    /** Slicing a pair at run time yields usable lone surrogates on all targets. */
    @Test
    fun halvesDerivedAtRuntimeAreExact() {
        val high = pair.substring(0, 1)
        val low = pair.substring(1, 2)
        assertEquals(1, high.length)
        assertEquals(1, low.length)
        assertEquals(0xD83D, high[0].code)
        assertEquals(0xDE00, low[0].code)
        assertEquals(pair, high + low)
    }

    /** The engine must be able to return an unpaired surrogate. */
    @Test
    fun engineReturnsLoneSurrogates() {
        val half = RegExp.compile(".").exec(pair)?.value
        assertEquals(1, half?.length)
        assertEquals(0xD83D, half?.get(0)?.code)
    }

    /** The generated corpus must survive the round trip on every platform. */
    @Test
    fun fixtureRoundTripsLoneSurrogates() {
        // An *unpaired* surrogate is the thing at risk: had the transport lost
        // them, every one would have become '?' and this count would be zero.
        val unpaired = DiffFixture.all().count { c ->
            val s = c.input ?: return@count false
            s.indices.any { i ->
                val ch = s[i]
                when {
                    ch.isHighSurrogate() -> i + 1 >= s.length || !s[i + 1].isLowSurrogate()
                    ch.isLowSurrogate() -> i == 0 || !s[i - 1].isHighSurrogate()
                    else -> false
                }
            }
        }
        assertTrue(unpaired > 0, "fixture contains no unpaired surrogate inputs")

        // Well-formed pairs must survive intact as well.
        val pairs = DiffFixture.all().count { c ->
            val s = c.input ?: return@count false
            s.indices.any { i ->
                i + 1 < s.length && s[i].isHighSurrogate() && s[i + 1].isLowSurrogate()
            }
        }
        assertTrue(pairs > 0, "fixture contains no surrogate-pair inputs")
    }
}
