package io.github.mgilbir.ecma262.date

import io.github.mgilbir.ecma262.number.toEcmaString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Differential fuzzing for the Date operations against a running node.
 *
 * ```
 * ./gradlew dateFuzz -Pcount=200000 -Pseed=7
 * ```
 *
 * Two things are being tested, and only one of them can use node as an oracle.
 *
 * **Acceptance.** For a string inside the Date Time String Format, node's
 * `Date.parse` is authoritative and must agree exactly.
 *
 * **Rejection.** Outside the format node is allowed to fall back to its own
 * parser, and does - it reads `March 1, 2024`. So node cannot be asked whether
 * a string is in the format. The oracle decides that from the grammar, written
 * out separately in JavaScript, and the only requirement on the library is that
 * it returns NaN. That second implementation is still mine, so it guards
 * against a slip rather than against a misreading; the recorded fixtures and
 * the explicit tests are what guard the reading.
 *
 * Most generated strings are near misses rather than random noise, because the
 * interesting failures are one character away from valid: a missing colon, a
 * lowercase `z`, four fractional digits, hour 24 with a minute attached.
 */
internal object DateFuzzMain {

    private class Rng(seed: Long) {
        private var state = seed * 6364136223846793005L + 1442695040888963407L
        fun next(): Long {
            state = state * 6364136223846793005L + 1442695040888963407L
            return state
        }
        fun int(bound: Int): Int = ((next() ushr 17) % bound).toInt()
        fun bool(oneIn: Int): Boolean = int(oneIn) == 0
    }

    private fun encodeUtf16(s: String): String {
        val bytes = ByteArray(s.length * 2)
        for (i in s.indices) {
            bytes[i * 2] = (s[i].code and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s[i].code ushr 8) and 0xFF).toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun pad(n: Int, width: Int): String = n.toString().padStart(width, '0')

    /** A string that is in the format, before any mutation is applied. */
    private fun wellFormed(rng: Rng): String {
        val sb = StringBuilder()
        if (rng.bool(6)) {
            sb.append(if (rng.bool(2)) '+' else '-')
            sb.append(pad(rng.int(300000), 6))
        } else {
            sb.append(pad(rng.int(10000), 4))
        }
        val shape = rng.int(6)
        if (shape >= 1) sb.append('-').append(pad(1 + rng.int(12), 2))
        if (shape >= 2) sb.append('-').append(pad(1 + rng.int(31), 2))
        if (shape >= 2 && !rng.bool(4)) {
            val endOfDay = rng.bool(12)
            // An hour of 24 usually zeroes the rest, because that is the only
            // spelling the grammar allows - but a third of the time it does not,
            // so the fuzzer actually reaches strings that must be rejected for
            // that reason alone. Without this the generator cannot produce
            // 24:30 at all, and a missing end-of-day check goes unnoticed.
            val zeroTail = endOfDay && !rng.bool(3)
            val hour = if (endOfDay) 24 else rng.int(24)
            sb.append('T').append(pad(hour, 2)).append(':').append(pad(if (zeroTail) 0 else rng.int(60), 2))
            val depth = rng.int(3)
            if (depth >= 1) sb.append(':').append(pad(if (zeroTail) 0 else rng.int(60), 2))
            if (depth >= 2) sb.append('.').append(pad(if (zeroTail) 0 else rng.int(1000), 3))
            when (rng.int(4)) {
                0 -> sb.append('Z')
                1, 2 -> sb.append(if (rng.bool(2)) '+' else '-')
                    .append(pad(rng.int(24), 2)).append(':').append(pad(rng.int(60), 2))
            }
        }
        return sb.toString()
    }

    /** One edit, chosen from the ways a date string is usually wrong. */
    private fun mutate(rng: Rng, s: String): String {
        if (s.isEmpty()) return s
        val i = rng.int(s.length)
        return when (rng.int(12)) {
            0 -> s.substring(0, i) + s.substring(i + 1)
            1 -> s.substring(0, i) + "0123456789"[rng.int(10)] + s.substring(i)
            2 -> s.substring(0, i) + s[i].lowercaseChar() + s.substring(i + 1)
            3 -> s.substring(0, i) + s[i].uppercaseChar() + s.substring(i + 1)
            4 -> s.substring(0, i) + "-+:.TZ /,"[rng.int(9)] + s.substring(i + 1)
            5 -> s.substring(0, i)
            6 -> s + "0123456789TZ+-:."[rng.int(16)]
            7 -> s.replaceFirst(":", "")
            8 -> s.replaceFirst("-", "/")
            9 -> s.replaceFirst("T", " ")
            10 -> " $s"
            else -> "$s "
        }
    }

    private fun describe(s: String) =
        s.map { if (it.code in 32..126) it.toString() else "\\u" + it.code.toString(16).padStart(4, '0') }
            .joinToString("")

    private class Case(val request: String, val describe: () -> String, val check: (String) -> String?)

    private fun parseCase(rng: Rng): Case {
        var s = wellFormed(rng)
        // Some strings stay valid; the rest get one or two edits.
        repeat(rng.int(3)) { s = mutate(rng, s) }
        val text = s
        return Case("p " + encodeUtf16(text), { "parseDateTimeString(\"${describe(text)}\")" }) { answer ->
            val ours = parseDateTimeString(text)
            if (answer == "x") {
                if (!ours.isNaN()) "outside the grammar, but we returned ${ours.toEcmaString()}" else null
            } else {
                val expected = answer.removePrefix("g ")
                val got = ours.toEcmaString()
                if (got != expected) "node=$expected ours=$got" else null
            }
        }
    }

    private fun utcCase(rng: Rng): Case {
        fun field(range: Int): Double = when (rng.int(10)) {
            0 -> Double.NaN
            1 -> if (rng.bool(2)) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
            2 -> (rng.int(range * 2) - range).toDouble() + 0.5
            else -> (rng.int(range * 2) - range).toDouble()
        }
        val y = if (rng.bool(3)) field(300000) else field(3000)
        val mo = field(30)
        val d = field(45)
        val h = field(40)
        val mi = field(120)
        val s = field(120)
        val ms = field(5000)
        val args = listOf(y, mo, d, h, mi, s, ms).joinToString(",") { it.toEcmaString() }
        return Case("u $args", { "Date.UTC($args)" }) { answer ->
            val ours = timeClip(makeDate(makeDay(makeFullYear(y), mo, d), makeTime(h, mi, s, ms))).toEcmaString()
            if (ours != answer) "node=$answer ours=$ours" else null
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val count = args.getOrNull(0)?.toInt() ?: 20_000
        val seed = args.getOrNull(1)?.toLong() ?: 1L
        val oracle = args.getOrNull(2) ?: "tools/date/fuzz-oracle.mjs"

        println("fuzzing $count Date cases (seed=$seed) against node ...")
        val rng = Rng(seed)
        val cases = ArrayList<Case>(count)
        repeat(count) { cases.add(if (rng.bool(3)) utcCase(rng) else parseCase(rng)) }

        val builder = ProcessBuilder("node", oracle).redirectError(ProcessBuilder.Redirect.INHERIT)
        // The bare date-time strings are local time, so node has to be in the
        // same zone the library defaults to.
        builder.environment()["TZ"] = "UTC"
        val process = builder.start()
        Runtime.getRuntime().addShutdownHook(Thread { process.destroyForcibly() })

        val writer = Thread {
            process.outputStream.bufferedWriter().use { out ->
                for (case in cases) {
                    out.write(case.request)
                    out.write("\n")
                }
            }
        }
        writer.start()

        val failures = ArrayList<String>()
        var compared = 0
        var rejected = 0
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            for (case in cases) {
                val answer = reader.readLine() ?: break
                compared++
                if (answer == "x") rejected++
                val problem = case.check(answer)
                if (problem != null) failures.add("${case.describe()}: $problem")
                if (failures.size >= 20) break
            }
        }
        writer.join()
        process.destroy()

        if (failures.isEmpty()) {
            println("OK: all $compared cases agree with node ($rejected outside the grammar and NaN on both sides)")
        } else {
            println("FAIL: ${failures.size} of $compared cases disagree")
            failures.forEach { println("    $it") }
            exitProcess(1)
        }
    }
}
