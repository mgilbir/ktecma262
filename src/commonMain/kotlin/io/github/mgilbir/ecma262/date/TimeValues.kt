package io.github.mgilbir.ecma262.date

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.truncate

/** Milliseconds in a day, as ECMA-262 21.4.1.2 defines it: every day has exactly this many. */
public const val MS_PER_DAY: Double = 86400000.0

/** Milliseconds in an hour. */
public const val MS_PER_HOUR: Double = 3600000.0

/** Milliseconds in a minute. */
public const val MS_PER_MINUTE: Double = 60000.0

/** Milliseconds in a second. */
public const val MS_PER_SECOND: Double = 1000.0

/**
 * The largest magnitude a time value may have - ECMA-262 21.4.1.1.
 *
 * Exactly 100,000,000 days either side of the epoch. Anything beyond is an
 * Invalid Date rather than an error, which is what [timeClip] enforces.
 */
public const val MAX_TIME_VALUE: Double = 8.64e15

/**
 * `MakeTime` - ECMA-262 21.4.1.29.
 *
 * Combines a clock reading into an offset within a day. Nothing is range
 * checked and nothing is clipped, deliberately: every field rolls, in both
 * directions, and the rolling is the whole point.
 *
 * ```kotlin
 * makeTime(24.0, 0.0, 0.0, 0.0)   // 86400000.0 - the next midnight
 * makeTime(-1.0, 0.0, 0.0, 0.0)   // -3600000.0 - an hour before
 * makeTime(0.0, 90.0, 0.0, 0.0)   // 5400000.0  - an hour and a half
 * ```
 *
 * Each argument is truncated toward zero first, so `makeTime(1.9, ...)` is one
 * hour. A non-finite argument gives `NaN`.
 */
public fun makeTime(hour: Double, min: Double, sec: Double, ms: Double): Double {
    if (!hour.isFinite() || !min.isFinite() || !sec.isFinite() || !ms.isFinite()) return Double.NaN
    return truncate(hour) * MS_PER_HOUR +
        truncate(min) * MS_PER_MINUTE +
        truncate(sec) * MS_PER_SECOND +
        truncate(ms)
}

/**
 * `MakeDay` - ECMA-262 21.4.1.28.
 *
 * The day number for a calendar date, where month is **zero based** and day of
 * month is one based - the asymmetry JavaScript is famous for.
 *
 * Out-of-range fields roll rather than fail, which is the behaviour worth
 * having and the one that is easy to get wrong:
 *
 * ```kotlin
 * makeDay(2012.0, 12.0, 1.0)   // January 2013 - month 12 is the 13th month
 * makeDay(2012.0, 1.0, 30.0)   // 1 March 2012 - February has 29 days that year
 * makeDay(2024.0, 0.0, 0.0)    // 31 December 2023 - day 0 is "the day before the 1st"
 * makeDay(2024.0, -1.0, 1.0)   // 1 December 2023
 * ```
 *
 * Note that the year is used as given: [makeFullYear] applies the two-digit
 * rule, and the `Date` constructor applies it before calling this.
 *
 * A non-finite argument gives `NaN`, as does a year so large that adding the
 * month offset overflows.
 */
public fun makeDay(year: Double, month: Double, date: Double): Double {
    if (!year.isFinite() || !month.isFinite() || !date.isFinite()) return Double.NaN
    val y = truncate(year)
    val m = truncate(month)
    val dt = truncate(date)

    // Months beyond either end of the year carry into the year number.
    val ym = y + floor(m / 12.0)
    if (!ym.isFinite()) return Double.NaN
    val mn = m.mod(12.0)

    val firstOfMonth = dayFromYear(ym) + dayOfYearForMonth(mn.toInt(), inLeapYear(ym))
    return firstOfMonth + dt - 1.0
}

/**
 * `MakeDate` - ECMA-262 21.4.1.30: a day number and a time within that day,
 * combined into a time value.
 *
 * Still not clipped - [timeClip] is the step that decides whether the result is
 * a date at all.
 */
public fun makeDate(day: Double, time: Double): Double {
    if (!day.isFinite() || !time.isFinite()) return Double.NaN
    val tv = day * MS_PER_DAY + time
    if (!tv.isFinite()) return Double.NaN
    return tv
}

/**
 * `TimeClip` - ECMA-262 21.4.1.31, the step that turns arithmetic into a date.
 *
 * Anything beyond [MAX_TIME_VALUE] in either direction becomes `NaN` - an
 * Invalid Date - rather than raising. Sub-millisecond precision is truncated
 * toward zero, and a negative zero is normalised away.
 *
 * ```kotlin
 * timeClip(8.64e15)   // 8.64e15 - the last representable instant
 * timeClip(8.64e15+1) // NaN
 * timeClip(-0.5)      // 0.0, not -0.0
 * ```
 */
public fun timeClip(time: Double): Double {
    if (!time.isFinite()) return Double.NaN
    if (abs(time) > MAX_TIME_VALUE) return Double.NaN
    // Adding zero turns -0.0 into 0.0 and leaves every other value alone; the
    // specification asks for a mathematical integer, which has no signed zero.
    return truncate(time) + 0.0
}

/**
 * `MakeFullYear` - ECMA-262 21.4.1.27, the two-digit year rule.
 *
 * ```kotlin
 * makeFullYear(99.0)   // 1999.0
 * makeFullYear(100.0)  // 100.0  - the year 100, not 2000
 * makeFullYear(0.0)    // 1900.0
 * makeFullYear(-1.0)   // -1.0
 * ```
 *
 * It applies to the `Date` constructor's year argument, and to nothing else:
 * `Date.UTC` and [makeDay] both take the year literally.
 */
public fun makeFullYear(year: Double): Double {
    if (year.isNaN()) return Double.NaN
    val truncated = truncate(year)
    if (truncated >= 0.0 && truncated <= 99.0) return 1900.0 + truncated
    return truncated
}

/**
 * `DayFromYear` - ECMA-262 21.4.1.3: the day number of 1 January of [y].
 *
 * The three correction terms are the Gregorian leap rule, and they are written
 * with offsets rather than the year itself so that the flooring works for years
 * before the epoch too.
 */
internal fun dayFromYear(y: Double): Double =
    365.0 * (y - 1970.0) +
        floor((y - 1969.0) / 4.0) -
        floor((y - 1901.0) / 100.0) +
        floor((y - 1601.0) / 400.0)

/** `InLeapYear` - ECMA-262 21.4.1.4, on the year number rather than a time value. */
internal fun inLeapYear(y: Double): Boolean =
    y.mod(4.0) == 0.0 && (y.mod(100.0) != 0.0 || y.mod(400.0) == 0.0)

/** Days from 1 January to the first of month [mn], which is zero based. */
internal fun dayOfYearForMonth(mn: Int, leap: Boolean): Double {
    val base = MONTH_START[mn]
    return if (leap && mn >= 2) base + 1.0 else base
}

private val MONTH_START = doubleArrayOf(
    0.0, 31.0, 59.0, 90.0, 120.0, 151.0, 181.0, 212.0, 243.0, 273.0, 304.0, 334.0,
)
