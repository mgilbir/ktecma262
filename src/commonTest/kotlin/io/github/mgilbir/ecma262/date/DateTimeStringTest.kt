package io.github.mgilbir.ecma262.date

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Date Time String Format.
 *
 * The recorded expectations come from node, but only for strings inside the
 * grammar - the generator refuses anything else, because outside the grammar
 * `Date.parse` may use an implementation-specific parser and recording that
 * would pin V8 rather than the specification.
 *
 * The rejections are therefore asserted here without an oracle, from the
 * grammar itself. That asymmetry is deliberate: node accepts `March 1, 2024`
 * and `2024/03/01`, so it cannot be asked whether a string is in the format.
 */
class DateTimeStringTest {

    private fun assertSame(expected: Double, actual: Double, what: String) {
        if (expected.isNaN()) assertTrue(actual.isNaN(), "$what: expected NaN, got $actual")
        else assertEquals(expected, actual, "$what")
    }

    @Test
    fun matchesNodeUnderUtc() {
        val inputs = DateFixture.PARSE_INPUT
        val expected = DateFixture.PARSE_UTC
        assertEquals(inputs.size, expected.size)
        for (i in inputs.indices) {
            assertSame(expected[i], parseDateTimeString(inputs[i]), "parseDateTimeString(\"${inputs[i]}\")")
        }
    }

    /**
     * The same strings under a fixed UTC+05:00 zone.
     *
     * The expectations were taken under `Etc/GMT-5`, which has no transitions
     * and so cannot drift when tzdata is updated. Only bare date-time strings
     * move; the date-only and explicit-offset ones must be identical to the UTC
     * run, and the generator checks that before recording anything.
     */
    @Test
    fun matchesNodeUnderAFixedOffsetZone() {
        val zone = EcmaTimeZone.fixed(300)
        val inputs = DateFixture.PARSE_INPUT
        val expected = DateFixture.PARSE_PLUS_5
        assertEquals(inputs.size, expected.size)
        for (i in inputs.indices) {
            assertSame(expected[i], parseDateTimeString(inputs[i], zone), "parseDateTimeString(\"${inputs[i]}\", +05:00)")
        }
    }

    /** The asymmetry that makes the zone parameter necessary at all. */
    @Test
    fun dateOnlyIsUtcAndDateTimeIsLocal() {
        val plusFive = EcmaTimeZone.fixed(300)
        val minusEight = EcmaTimeZone.fixed(-480)

        // A date-only string never consults the zone.
        val dateOnly = parseDateTimeString("2024-07-01")
        assertEquals(dateOnly, parseDateTimeString("2024-07-01", plusFive))
        assertEquals(dateOnly, parseDateTimeString("2024-07-01", minusEight))
        assertEquals(parseDateTimeString("2024-07-01T00:00:00Z"), dateOnly)

        // A date-time string with no offset does.
        val bare = "2024-07-01T00:00:00"
        assertEquals(parseDateTimeString("2024-07-01T00:00:00Z"), parseDateTimeString(bare))
        assertEquals(dateOnly - 300 * 60000.0, parseDateTimeString(bare, plusFive))
        assertEquals(dateOnly + 480 * 60000.0, parseDateTimeString(bare, minusEight))

        // An explicit offset answers the question itself, so the zone is ignored.
        for (zone in listOf(EcmaTimeZone.Utc, plusFive, minusEight)) {
            assertEquals(dateOnly, parseDateTimeString("2024-07-01T00:00:00Z", zone))
            assertEquals(dateOnly - 330 * 60000.0, parseDateTimeString("2024-07-01T00:00:00+05:30", zone))
        }
    }

    /** The zone is consulted with the wall clock, encoded as though it were UTC. */
    @Test
    fun theZoneIsAskedAboutTheWallClock() {
        var asked: Double? = null
        val recorder = EcmaTimeZone { localTimeValue ->
            asked = localTimeValue
            0
        }
        parseDateTimeString("2024-07-01T12:00:00", recorder)
        // 1719835200000 decodes, as UTC, to exactly the digits in the string.
        assertEquals(1719835200000.0, asked)

        // And it is asked once, only for the bare form.
        asked = null
        parseDateTimeString("2024-07-01", recorder)
        assertEquals(null, asked, "a date-only string must not consult the zone")
        asked = null
        parseDateTimeString("2024-07-01T12:00:00Z", recorder)
        assertEquals(null, asked, "an explicit offset must not consult the zone")
    }

    /** The offset the zone reports is subtracted, so a date-time string can move across the clip boundary. */
    @Test
    fun theZoneCanPushAValueOutOfRange() {
        assertEquals(8.64e15, parseDateTimeString("+275760-09-13T00:00:00.000"))
        assertEquals(8.64e15 - 60000.0, parseDateTimeString("+275760-09-13T00:00:00.000", EcmaTimeZone.fixed(1)))
        assertTrue(parseDateTimeString("+275760-09-13T00:00:00.000", EcmaTimeZone.fixed(-1)).isNaN())
    }

    @Test
    fun acceptedShapes() {
        for (s in listOf(
            "2024", "2024-07", "2024-07-01",
            "2024-07-01T00:00", "2024-07-01T00:00:00", "2024-07-01T00:00:00.000",
            "2024-07-01T00:00Z", "2024-07-01T00:00:00Z", "2024-07-01T00:00:00.000Z",
            "2024-07-01T00:00+05:30", "2024-07-01T00:00:00-05:30", "2024-07-01T00:00:00.000+00:00",
            "+002024-07-01", "-000001-07-01", "+275760-09-13T00:00:00.000Z",
            "0000-01-01", "2024-07-01T24:00", "2024-07-01T24:00:00.000",
        )) {
            assertTrue(!parseDateTimeString(s).isNaN(), "\"$s\" is in the format and should parse")
        }
    }

    @Test
    fun rejectedShapes() {
        for (s in listOf(
            // Not the format at all. node reads several of these with its own parser.
            "", " ", "2024-07-01 ", " 2024-07-01", "March 1, 2024", "2024/07/01",
            "2024-07-01 00:00:00", "2024-7-01", "2024-07-1", "24-07-01",
            // Case matters: the grammar spells them T and Z.
            "2024-07-01t00:00:00Z", "2024-07-01T00:00:00z",
            // Offsets need the colon and two digits either side.
            "2024-07-01T00:00:00+0530", "2024-07-01T00:00:00+05", "2024-07-01T00:00:00+5:30",
            // A date-only string may not carry an offset.
            "2024-07-01Z", "2024-07-01+05:30", "2024Z",
            // The time needs at least hours and minutes.
            "2024-07-01T", "2024-07-01T00", "2024-07-01T00:", "2024-07-01T0:00",
            // The fraction is exactly three digits.
            "2024-07-01T00:00:00.", "2024-07-01T00:00:00.1", "2024-07-01T00:00:00.12",
            "2024-07-01T00:00:00.1234", "2024-07-01T00:00:00.1234Z",
            // Fields outside their grammar ranges.
            "2024-13-01", "2024-00-01", "2024-07-32", "2024-07-00",
            "2024-07-01T25:00", "2024-07-01T00:60", "2024-07-01T00:00:60",
            "2024-07-01T00:00:00+24:00", "2024-07-01T00:00:00+00:60",
            // Hour 24 is the end of the day and cannot carry a remainder.
            "2024-07-01T24:01", "2024-07-01T24:00:01", "2024-07-01T24:00:00.001",
            // The expanded year is six digits and always signed, and -000000 is named as invalid.
            "-000000-01-01", "-000000", "+2024-07-01", "+00024-07-01", "+0000024-07-01",
            // Trailing text is not ignored.
            "2024-07-01T00:00:00Zx", "2024-07-01x", "2024-07-01T00:00:00+05:30x",
        )) {
            assertTrue(parseDateTimeString(s).isNaN(), "\"$s\" is not in the format and must be NaN")
        }
    }

    /**
     * Fields inside the grammar but outside the calendar roll, because the
     * parsed values go through [makeDay] like any others.
     */
    @Test
    fun inRangeFieldsStillRoll() {
        assertEquals(parseDateTimeString("2024-03-01"), parseDateTimeString("2024-02-30"))
        assertEquals(parseDateTimeString("2024-05-01"), parseDateTimeString("2024-04-31"))
        assertEquals(parseDateTimeString("2023-03-01"), parseDateTimeString("2023-02-29"))
        // 2024 is a leap year, so this one does not roll.
        assertEquals(parseDateTimeString("2024-02-29T00:00:00Z"), parseDateTimeString("2024-02-29"))
        // Hour 24 rolls into the next day.
        assertEquals(parseDateTimeString("2024-07-02"), parseDateTimeString("2024-07-01T24:00Z"))
    }

    /** Omitted components default to the start of their range. */
    @Test
    fun omittedComponentsDefault() {
        assertEquals(parseDateTimeString("2024-01-01"), parseDateTimeString("2024"))
        assertEquals(parseDateTimeString("2024-07-01"), parseDateTimeString("2024-07"))
        assertEquals(parseDateTimeString("2024-07-01T12:00:00.000Z"), parseDateTimeString("2024-07-01T12:00Z"))
        assertEquals(parseDateTimeString("2024-07-01T12:30:00.000Z"), parseDateTimeString("2024-07-01T12:30:00Z"))
    }
}
