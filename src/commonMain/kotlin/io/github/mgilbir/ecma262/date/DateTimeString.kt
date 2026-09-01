package io.github.mgilbir.ecma262.date

/**
 * Parses the Date Time String Format - ECMA-262 21.4.1.32 - into a time value.
 *
 * ```
 * YYYY-MM-DDTHH:mm:ss.sssZ
 * ```
 *
 * with the trailing parts droppable: `2024`, `2024-07`, `2024-07-01`,
 * `2024-07-01T12:00`, `2024-07-01T12:00:00`, `2024-07-01T12:00:00.123`, each
 * optionally followed by `Z` or an offset such as `+05:30`.
 *
 * ## The asymmetry
 *
 * A **date-only** string is UTC. A **date-time** string with no offset is local
 * time. That is not a quirk of one engine, it is the format's own rule, and the
 * two differ by up to a day at the extremes:
 *
 * ```kotlin
 * parseDateTimeString("2024-07-01")           // midnight UTC, whatever the zone
 * parseDateTimeString("2024-07-01T00:00:00")  // midnight local - [zone] decides
 * ```
 *
 * [zone] answers only that second case; see [EcmaTimeZone]. It defaults to UTC,
 * which is what an engine does under `TZ=UTC` - deterministic, and a real
 * answer rather than a refusal.
 *
 * ## Out-of-range fields roll, they do not fail
 *
 * The grammar bounds each field, but a date that survives the grammar is still
 * handed to [makeDay], so it rolls the way JavaScript rolls:
 *
 * ```kotlin
 * parseDateTimeString("2024-02-30")  // 1 March 2024, because February has 29 days
 * parseDateTimeString("2024-13-01")  // NaN - 13 is outside the grammar
 * ```
 *
 * `24:00` is accepted as the end of the day, and only when the minutes, seconds
 * and milliseconds are all zero.
 *
 * ## What this deliberately does not accept
 *
 * `Date.parse` is allowed to fall back to "an implementation-specific format"
 * for anything outside the grammar, and engines use that licence freely - V8
 * reads `March 1, 2024`, `2024/03/01`, `2024-3-01`, a space instead of the `T`,
 * a lowercase `z`, `+0530` without the colon, and one, two or four fractional
 * digits. None of that is specified, none of it is portable between engines,
 * and guessing at it here would make this function agree with one engine and
 * quietly disagree with the next.
 *
 * So the grammar is enforced strictly and everything else returns `NaN`. The
 * failure that leaves is loud - a missing date rather than a wrong one.
 *
 * @return the time value, or `NaN` if [text] is not in the format. `NaN` is the
 *   specification's own Invalid Date, and it composes with [timeClip].
 */
public fun parseDateTimeString(text: String, zone: EcmaTimeZone = EcmaTimeZone.Utc): Double {
    var i = 0

    fun at(c: Char): Boolean = i < text.length && text[i] == c

    // Exactly [n] digits, consumed together; -1 when they are not all there,
    // which every caller rejects through its own range check.
    fun digits(n: Int): Int {
        if (i + n > text.length) return -1
        var value = 0
        for (k in 0 until n) {
            val c = text[i + k]
            if (c < '0' || c > '9') return -1
            value = value * 10 + (c - '0')
        }
        i += n
        return value
    }

    val year: Double
    if (at('+') || at('-')) {
        // The expanded form is six digits, and always signed.
        val negative = at('-')
        i++
        val digits6 = digits(6)
        if (digits6 < 0) return Double.NaN
        // -000000 is excluded by name in the specification: there is no year
        // before year zero to be the negative of.
        if (negative && digits6 == 0) return Double.NaN
        year = if (negative) -digits6.toDouble() else digits6.toDouble()
    } else {
        val digits4 = digits(4)
        if (digits4 < 0) return Double.NaN
        year = digits4.toDouble()
    }

    var month = 1
    var day = 1
    if (at('-')) {
        i++
        month = digits(2)
        if (month < 1 || month > 12) return Double.NaN
        if (at('-')) {
            i++
            day = digits(2)
            if (day < 1 || day > 31) return Double.NaN
        }
    }

    var hour = 0
    var minute = 0
    var second = 0
    var milli = 0
    var hasTime = false
    var explicitOffset: Int? = null

    if (at('T')) {
        i++
        hasTime = true

        hour = digits(2)
        if (hour < 0 || hour > 24) return Double.NaN
        if (!at(':')) return Double.NaN
        i++
        minute = digits(2)
        if (minute < 0 || minute > 59) return Double.NaN

        if (at(':')) {
            i++
            second = digits(2)
            if (second < 0 || second > 59) return Double.NaN
            if (at('.')) {
                i++
                milli = digits(3)
                if (milli < 0) return Double.NaN
            }
        }

        // Hour 24 names the end of the day, so it cannot carry a remainder.
        if (hour == 24 && (minute != 0 || second != 0 || milli != 0)) return Double.NaN

        if (at('Z')) {
            i++
            explicitOffset = 0
        } else if (at('+') || at('-')) {
            val negative = at('-')
            i++
            val offsetHour = digits(2)
            if (offsetHour < 0 || offsetHour > 23) return Double.NaN
            if (!at(':')) return Double.NaN
            i++
            val offsetMinute = digits(2)
            if (offsetMinute < 0 || offsetMinute > 59) return Double.NaN
            val total = offsetHour * 60 + offsetMinute
            explicitOffset = if (negative) -total else total
        }
    }

    // Trailing text is not ignored; a partial match is not a match.
    if (i != text.length) return Double.NaN

    val dayNumber = makeDay(year, (month - 1).toDouble(), day.toDouble())
    val time = makeTime(hour.toDouble(), minute.toDouble(), second.toDouble(), milli.toDouble())
    val local = makeDate(dayNumber, time)
    if (!local.isFinite()) return Double.NaN

    val offsetMinutes = when {
        explicitOffset != null -> explicitOffset
        // A date-only string is UTC by definition, so the zone is never asked.
        !hasTime -> 0
        else -> zone.offsetMinutesAtLocalTime(local)
    }

    return timeClip(local - offsetMinutes * MS_PER_MINUTE)
}
