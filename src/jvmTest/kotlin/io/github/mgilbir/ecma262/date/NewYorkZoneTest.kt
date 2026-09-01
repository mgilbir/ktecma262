package io.github.mgilbir.ecma262.date

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The zone parameter against a real zone with real transitions.
 *
 * Everything else about [parseDateTimeString] is tested with fixed offsets, so
 * that the suite never depends on which tzdata the machine happens to ship.
 * This one test deliberately does, because a fixed offset cannot exercise the
 * two days a year when the question is genuinely hard - the local time that
 * never happens, and the one that happens twice. It lives in `jvmTest` rather
 * than `commonTest` because it needs a time zone database to be a real test.
 *
 * The expectations were recorded from node under `TZ=America/New_York`. They
 * are all in 2024: an expectation pinned in a political zone before that zone's
 * first recorded transition moves when tzdata is updated, which is exactly the
 * trap that motivated keeping the database out of this library.
 *
 * What it demonstrates is that the interface asks a question `java.time`
 * already answers. `ZoneRules.getOffset(LocalDateTime)` is
 * [EcmaTimeZone.offsetMinutesAtLocalTime] one for one, gap and overlap included,
 * so a consumer's implementation is the three lines below and nothing more.
 */
class NewYorkZoneTest {

    private val newYork: EcmaTimeZone = ZoneId.of("America/New_York").rules.let { rules ->
        EcmaTimeZone { localTimeValue ->
            val seconds = floor(localTimeValue / 1000.0).toLong()
            val wallClock = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC)
            rules.getOffset(wallClock).totalSeconds / 60
        }
    }

    @Test
    fun matchesNodeAcrossBothTransitions() {
        val inputs = DateFixture.NEW_YORK_INPUT
        val expected = DateFixture.NEW_YORK_EXPECTED
        assertEquals(inputs.size, expected.size)
        for (i in inputs.indices) {
            assertEquals(
                expected[i],
                parseDateTimeString(inputs[i], newYork),
                "parseDateTimeString(\"${inputs[i]}\", America/New_York)",
            )
        }
    }

    /**
     * Both hard cases resolve with the offset in force *before* the transition,
     * which is what JavaScript does and what `java.time` happens to agree on.
     */
    @Test
    fun gapAndOverlapUseThePreTransitionOffset() {
        // 02:30 on 10 March does not exist: the clocks go 01:59:59 EST to 03:00 EDT.
        // Resolved at EST, -05:00, it is 07:30 UTC. Resolved at EDT it would be 06:30.
        assertEquals(
            parseDateTimeString("2024-03-10T07:30:00Z"),
            parseDateTimeString("2024-03-10T02:30:00", newYork),
        )
        // 01:30 on 3 November happens twice. The earlier one, EDT at -04:00, is 05:30 UTC.
        assertEquals(
            parseDateTimeString("2024-11-03T05:30:00Z"),
            parseDateTimeString("2024-11-03T01:30:00", newYork),
        )
    }

    /** The example in README.md, run rather than asserted on paper. */
    @Test
    fun readmeExample() {
        assertEquals(
            parseDateTimeString("2024-07-01T16:00:00Z"),
            parseDateTimeString("2024-07-01T12:00:00", newYork),
        )
    }

    /** Ordinary days still differ by season, which is the whole reason the parameter is a function. */
    @Test
    fun theOffsetDependsOnTheDate() {
        assertEquals(-300, newYork.offsetMinutesAtLocalTime(parseDateTimeString("2024-01-15T12:00:00")))
        assertEquals(-240, newYork.offsetMinutesAtLocalTime(parseDateTimeString("2024-07-15T12:00:00")))
        assertTrue(
            parseDateTimeString("2024-01-15T12:00:00", newYork) -
                parseDateTimeString("2024-01-15T12:00:00Z") == 5 * 3600000.0,
        )
        assertTrue(
            parseDateTimeString("2024-07-15T12:00:00", newYork) -
                parseDateTimeString("2024-07-15T12:00:00Z") == 4 * 3600000.0,
        )
    }
}
