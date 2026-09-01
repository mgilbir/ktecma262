package io.github.mgilbir.ecma262.date

/**
 * How to resolve a local wall-clock reading to an instant - the specification's
 * `LocalTZA(t, isUTC = false)`, ECMA-262 21.4.1.7.
 *
 * This library carries no time zone database, and never will: a tzdb is large,
 * it changes several times a year, and a consumer almost always has one already.
 * This interface is the seam. Supply it and the zone rules stay yours.
 *
 * Only one thing needs it. [parseDateTimeString] consults it for a date-time
 * string with **no offset**, such as `2024-07-01T12:00:00`, because the format
 * defines that form as local time. A date-only string is UTC by specification
 * and an explicit `Z` or `+05:30` answers the question itself, so neither
 * reaches the zone. Every other operation here is pure UTC arithmetic.
 *
 * A JVM implementation is one delegation, because `java.time` asks and answers
 * exactly this question:
 *
 * ```kotlin
 * val rules = ZoneId.of("America/New_York").rules
 * val zone = EcmaTimeZone { localTimeValue ->
 *     val seconds = floor(localTimeValue / 1000.0).toLong()
 *     val wallClock = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC)
 *     rules.getOffset(wallClock).totalSeconds / 60
 * }
 * ```
 */
public fun interface EcmaTimeZone {

    /**
     * The offset in minutes **east of UTC** in force at a given wall clock.
     *
     * [localTimeValue] is a time value - milliseconds since the epoch - but not
     * of a real instant. It carries the year, month, day, hour, minute, second
     * and millisecond that were written down, encoded as though they were UTC.
     * Decode it as UTC and you get the digits back:
     *
     * ```
     * "2024-07-01T12:00:00"  ->  1719835200000  ->  2024-07-01T12:00:00
     * ```
     *
     * It has to be phrased that way round. The real instant is what the caller
     * is trying to compute, and computing it needs the offset, so the offset
     * cannot be asked for by instant without circularity. That is why the
     * specification gives `LocalTZA` an `isUTC` flag.
     *
     * The price is that a wall clock is ambiguous when the clocks go back and
     * impossible when they go forward. JavaScript resolves both with the offset
     * in force **before** the transition, and `java.time` already agrees:
     * `ZoneRules.getOffset(LocalDateTime)` returns the pre-transition offset
     * inside a spring-forward gap and the earlier of the two in an overlap.
     *
     * Returning a value outside -1439..1439 is meaningless; [parseDateTimeString]
     * will apply it as given and let [timeClip] deal with the consequences.
     */
    public fun offsetMinutesAtLocalTime(localTimeValue: Double): Int

    public companion object {
        /**
         * Treats a bare date-time string as UTC.
         *
         * The default, because it is the only choice that is both total and
         * deterministic, and because it is what a JavaScript engine does when
         * the environment says `TZ=UTC`. It is a real answer rather than a
         * refusal: no engine returns an Invalid Date for `2024-07-01T12:00:00`.
         */
        public val Utc: EcmaTimeZone = EcmaTimeZone { 0 }

        /**
         * A zone with one offset for all time, for the places that genuinely
         * have one - `UTC+05:30`, `UTC-03:00` - and for tests.
         *
         * Do not reach for this to stand in for a named zone. Half of the world
         * changes offset twice a year, so a fixed offset chosen from today is
         * wrong for months at a time.
         */
        public fun fixed(offsetMinutes: Int): EcmaTimeZone = EcmaTimeZone { offsetMinutes }
    }
}
