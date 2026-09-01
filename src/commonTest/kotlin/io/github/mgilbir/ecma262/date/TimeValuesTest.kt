package io.github.mgilbir.ecma262.date

import io.github.mgilbir.ecma262.number.toEcmaString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The five time value operations, at two levels.
 *
 * The explicit cases are the ones a consumer gets wrong by hand, each verified
 * against node. The hashes then sweep 20,000 field tuples through all five
 * composed, because `Date.UTC` is precisely
 * `TimeClip(MakeDate(MakeDay(MakeFullYear(y), mo, d), MakeTime(h, mi, s, ms)))`
 * and hashing it covers combinations no hand-written list would reach.
 */
class TimeValuesTest {

    /** node's own generator, so the Kotlin sweep visits the same tuples in the same order. */
    private class Mulberry32(private var a: Int) {
        fun next(): Double {
            a += 0x6d2b79f5
            var t = (a xor (a ushr 15)) * (1 or a)
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            return (t xor (t ushr 14)).toUInt().toDouble() / 4294967296.0
        }
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

    /** What `Date.UTC` does, in terms of the operations under test. */
    private fun dateUtc(y: Double, mo: Double, d: Double, h: Double, mi: Double, s: Double, ms: Double): Double =
        timeClip(makeDate(makeDay(makeFullYear(y), mo, d), makeTime(h, mi, s, ms)))

    @Test
    fun rolloverMatchesTheSpecification() {
        // Month 12 is the thirteenth month, so it is January of the next year.
        assertEquals(dateUtc(2013.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0), dateUtc(2012.0, 12.0, 1.0, 0.0, 0.0, 0.0, 0.0))
        // February 2012 has 29 days, so the 30th is 1 March.
        assertEquals(dateUtc(2012.0, 2.0, 1.0, 0.0, 0.0, 0.0, 0.0), dateUtc(2012.0, 1.0, 30.0, 0.0, 0.0, 0.0, 0.0))
        // Day 0 is the day before the first.
        assertEquals(dateUtc(2023.0, 11.0, 31.0, 0.0, 0.0, 0.0, 0.0), dateUtc(2024.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(dateUtc(2023.0, 11.0, 30.0, 0.0, 0.0, 0.0, 0.0), dateUtc(2024.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0))
        // Month -1 is December of the previous year.
        assertEquals(dateUtc(2023.0, 11.0, 1.0, 0.0, 0.0, 0.0, 0.0), dateUtc(2024.0, -1.0, 1.0, 0.0, 0.0, 0.0, 0.0))
        // Hour 24 is the next midnight; hour -1 is the hour before.
        assertEquals(dateUtc(2024.0, 2.0, 2.0, 0.0, 0.0, 0.0, 0.0), dateUtc(2024.0, 2.0, 1.0, 24.0, 0.0, 0.0, 0.0))
        assertEquals(dateUtc(2024.0, 1.0, 29.0, 23.0, 0.0, 0.0, 0.0), dateUtc(2024.0, 2.0, 1.0, -1.0, 0.0, 0.0, 0.0))
        // A millisecond before the start of 2024.
        assertEquals(1704067199999.0, dateUtc(2024.0, 0.0, 1.0, 0.0, 0.0, 0.0, -1.0))
    }

    @Test
    fun twoDigitYearRule() {
        assertEquals(1999.0, makeFullYear(99.0))
        assertEquals(1900.0, makeFullYear(0.0))
        assertEquals(1980.0, makeFullYear(80.0))
        // 100 is the year 100, not 2000 - the rule stops at 99.
        assertEquals(100.0, makeFullYear(100.0))
        assertEquals(-1.0, makeFullYear(-1.0))
        assertEquals(2024.0, makeFullYear(2024.0))
        // Truncation happens before the comparison.
        assertEquals(1999.0, makeFullYear(99.9))
        assertTrue(makeFullYear(Double.NaN).isNaN())

        // node: Date.UTC(99, 0, 1) is 1999 and Date.UTC(100, 0, 1) is the year 100.
        assertEquals(915148800000.0, dateUtc(99.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(-59011459200000.0, dateUtc(100.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(-2208988800000.0, dateUtc(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0))
        // MakeDay itself takes the year literally, which is what Date.UTC's caller sees.
        assertEquals(-719162.0, makeDay(1.0, 0.0, 1.0))
    }

    @Test
    fun timeClipBounds() {
        assertEquals(8.64e15, timeClip(8.64e15))
        assertEquals(-8.64e15, timeClip(-8.64e15))
        assertTrue(timeClip(8.64e15 + 1.0).isNaN(), "one millisecond past the end is an Invalid Date")
        assertTrue(timeClip(-8.64e15 - 1.0).isNaN())
        assertTrue(timeClip(Double.NaN).isNaN())
        assertTrue(timeClip(Double.POSITIVE_INFINITY).isNaN())
        assertTrue(timeClip(Double.NEGATIVE_INFINITY).isNaN())
        // Truncation is toward zero, and the result never carries a negative zero.
        assertEquals(0.0, timeClip(0.5))
        assertEquals(0.0, timeClip(-0.9))
        assertEquals(1.0, timeClip(1.5))
        assertEquals(-1.0, timeClip(-1.5))
        assertFalse(timeClip(-0.5).toEcmaString() == "-0", "TimeClip must not produce a negative zero")

        // The edges of the range, as calendar dates.
        assertEquals(8.64e15, dateUtc(275760.0, 8.0, 13.0, 0.0, 0.0, 0.0, 0.0))
        assertTrue(dateUtc(275760.0, 8.0, 14.0, 0.0, 0.0, 0.0, 0.0).isNaN())
        assertEquals(-8.64e15, dateUtc(-271821.0, 3.0, 20.0, 0.0, 0.0, 0.0, 0.0))
        assertTrue(dateUtc(-271821.0, 3.0, 19.0, 0.0, 0.0, 0.0, 0.0).isNaN())
    }

    /**
     * MakeDay computes the day number arithmetically rather than failing when
     * the year alone is out of range, so a large enough negative date argument
     * brings the result back into range. That is what node does:
     * `Date.UTC(300000, 0, 1 - 108853222)` is the epoch, not an Invalid Date.
     */
    @Test
    fun makeDayDoesNotClampBeforeTheDateIsApplied() {
        assertTrue(dateUtc(300000.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0).isNaN(), "year 300000 alone is out of range")
        assertEquals(0.0, dateUtc(300000.0, 0.0, 1.0 - 108853222.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(-2208988800000.0, dateUtc(1970.0, 0.0, 1.0 - 25567.0, 0.0, 0.0, 0.0, 0.0))
    }

    @Test
    fun nonFiniteArgumentsGiveNaN() {
        assertTrue(makeTime(Double.NaN, 0.0, 0.0, 0.0).isNaN())
        assertTrue(makeTime(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0).isNaN())
        assertTrue(makeDay(Double.NaN, 0.0, 1.0).isNaN())
        assertTrue(makeDay(2024.0, Double.NaN, 1.0).isNaN())
        assertTrue(makeDay(2024.0, 0.0, Double.NaN).isNaN())
        assertTrue(makeDay(Double.POSITIVE_INFINITY, 0.0, 1.0).isNaN())
        assertTrue(makeDate(Double.NaN, 0.0).isNaN())
        assertTrue(makeDate(0.0, Double.NaN).isNaN())
        // MakeTime can overflow to infinity, which MakeDate then rejects.
        assertTrue(makeDate(0.0, makeTime(1e308, 0.0, 0.0, 0.0)).isNaN())
    }

    /** Neither MakeTime nor MakeDay clips: the rolling has to survive to MakeDate. */
    @Test
    fun intermediateResultsAreNotClipped() {
        assertEquals(86400000.0, makeTime(24.0, 0.0, 0.0, 0.0))
        assertEquals(-3600000.0, makeTime(-1.0, 0.0, 0.0, 0.0))
        assertEquals(5400000.0, makeTime(0.0, 90.0, 0.0, 0.0))
        // Truncation toward zero, per ToIntegerOrInfinity.
        assertEquals(3600000.0, makeTime(1.9, 0.0, 0.0, 0.0))
        assertEquals(-3600000.0, makeTime(-1.9, 0.0, 0.0, 0.0))
        assertEquals(0.0, makeDay(1970.0, 0.0, 1.0))
        assertEquals(-1.0, makeDay(1969.0, 11.0, 31.0))
    }

    @Test
    fun dateUtcSweepMatchesNode() {
        var hash = 2166136261u
        var counted = 0
        fun add(y: Double, mo: Double, d: Double, h: Double, mi: Double, s: Double, ms: Double) {
            hash = fnv1a(hash, dateUtc(y, mo, d, h, mi, s, ms).toEcmaString())
            counted++
        }

        for (y in doubleArrayOf(1970.0, 2024.0, 1899.0, 0.0, 99.0, 100.0, -1.0, 275760.0, -271821.0, 300000.0, -300000.0)) {
            for (mo in doubleArrayOf(0.0, 1.0, 11.0, 12.0, -1.0, 24.0)) {
                for (d in doubleArrayOf(1.0, 0.0, -1.0, 28.0, 29.0, 30.0, 31.0, 32.0)) add(y, mo, d, 0.0, 0.0, 0.0, 0.0)
            }
        }
        val wild = doubleArrayOf(0.0, 1.0, -1.0, 11.0, 12.0, 13.0, -12.0, 24.0, 31.0, 32.0, 60.0, 99.0, 100.0, 365.0, 1000.0, -1000.0, 1e6, -1e6)
        for (v in wild) {
            add(2024.0, 0.0, 1.0, v, 0.0, 0.0, 0.0)
            add(2024.0, 0.0, 1.0, 0.0, v, 0.0, 0.0)
            add(2024.0, 0.0, 1.0, 0.0, 0.0, v, 0.0)
            add(2024.0, 0.0, 1.0, 0.0, 0.0, 0.0, v)
        }
        val rnd = Mulberry32(0x51ced)
        fun r(lo: Int, hi: Int): Double = (lo + (rnd.next() * (hi - lo + 1)).toInt()).toDouble()
        repeat(20000) { add(r(-300000, 300000), r(-30, 30), r(-40, 40), r(-30, 30), r(-90, 90), r(-90, 90), r(-2000, 2000)) }
        for (v in doubleArrayOf(0.5, -0.5, 1.9, -1.9, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e308)) {
            add(2024.0, 0.0, 1.0, v, 0.0, 0.0, 0.0)
            add(v, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
            add(2024.0, v, 1.0, 0.0, 0.0, 0.0, 0.0)
        }

        assertEquals(DateFixture.UTC_SAMPLES, counted)
        assertEquals(DateFixture.UTC_HASH, hash, "Date.UTC composition differs from node")
    }

    @Test
    fun timeClipSweepMatchesNode() {
        var hash = 2166136261u
        var counted = 0
        fun add(v: Double) {
            hash = fnv1a(hash, timeClip(v).toEcmaString())
            counted++
        }
        for (v in doubleArrayOf(0.0, -0.0, 0.5, -0.5, -0.9, 1.5, -1.5, 8.64e15, 8.64e15 + 1.0, -8.64e15, -8.64e15 - 1.0,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300, -1e300, 4503599627370497.0)) add(v)
        val rnd = Mulberry32(0xc11d)
        repeat(5000) { add((rnd.next() - 0.5) * 2.2e16) }
        repeat(2000) { add((rnd.next() - 0.5) * 1e10) }

        assertEquals(DateFixture.CLIP_SAMPLES, counted)
        assertEquals(DateFixture.CLIP_HASH, hash, "TimeClip differs from node")
    }
}
