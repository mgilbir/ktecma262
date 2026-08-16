package io.github.mgilbir.ecma262.number

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Differential fuzzing for the number functions against a running node.
 *
 * ```
 * ./gradlew numberFuzz -Pcount=200000 -Pseed=7
 * ```
 *
 * The recorded fixtures replay a set of cases chosen once; this generates fresh
 * ones every run. That is the difference that matters for the parser in
 * particular, whose input space is text rather than a bounded set of doubles —
 * no fixture can cover the ways a string can almost be a numeric literal.
 *
 * Doubles cross the wire as raw bit patterns and strings as base64 of their
 * UTF-16 units, so nothing here depends on either side's decimal handling being
 * right, which is the thing under test.
 */
internal object NumberFuzzMain {

    private class Case(val request: String, val describe: () -> String, val ours: () -> String)

    /** Deterministic, so a failure is reproducible from its seed. */
    private class Rng(seed: Long) {
        private var state = seed * 6364136223846793005L + 1442695040888963407L
        fun next(): Long {
            state = state * 6364136223846793005L + 1442695040888963407L
            return state
        }
        fun int(bound: Int): Int = ((next() ushr 17) % bound).toInt()
    }

    private fun encodeUtf16(s: String): String {
        val bytes = ByteArray(s.length * 2)
        for (i in s.indices) {
            val c = s[i].code
            bytes[i * 2] = (c and 0xFF).toByte()
            bytes[i * 2 + 1] = ((c ushr 8) and 0xFF).toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun hex(value: Double): String = java.lang.Long.toHexString(value.toRawBits())

    /** A double drawn to hit the awkward regions as often as the ordinary ones. */
    private fun randomDouble(rng: Rng): Double = when (rng.int(10)) {
        0 -> Double.fromBits(rng.next() and 0x000FFFFFFFFFFFFFL) // subnormal
        1 -> Double.fromBits((rng.int(2047).toLong() + 1) shl 52) // power of two
        2 -> rng.int(1_000_000).toDouble() / listOf(1, 10, 100, 1000)[rng.int(4)]
        3 -> rng.int(1_000_000).toDouble()
        else -> {
            val d = Double.fromBits(rng.next())
            if (d.isNaN() || d.isInfinite()) 1.5 else d
        }
    }

    /** A string that is a numeric literal, or very nearly one. */
    private fun randomNumericText(rng: Rng): String {
        val sb = StringBuilder()
        // Leading and trailing whitespace, including the characters people forget.
        val spaces = charArrayOf(' ', '\t', '\n', '\r', '\u00A0', '\u2028', '\u3000', '\uFEFF')
        repeat(rng.int(3)) { sb.append(spaces[rng.int(spaces.size)]) }
        when (rng.int(12)) {
            0 -> sb.append(if (rng.int(2) == 0) "Infinity" else "-Infinity")
            // Radix literals, sometimes with a sign in front. The grammar
            // forbids that - NonDecimalIntegerLiteral takes no sign - so
            // generating it is how rejection gets exercised. Leaving it out
            // hid a planted bug that accepted "+0x10".
            1 -> radixLiteral(rng, sb, "0x", 16)
            2 -> radixLiteral(rng, sb, "0b", 2)
            3 -> radixLiteral(rng, sb, "0o", 8)
            4 -> {
                // Long digit strings, to reach the significant-digit cap.
                if (rng.int(2) == 0) sb.append('-')
                repeat(1 + rng.int(900)) { sb.append('0' + rng.int(10)) }
                if (rng.int(2) == 0) {
                    sb.append('.')
                    repeat(1 + rng.int(900)) { sb.append('0' + rng.int(10)) }
                }
            }
            5 -> sb.append(randomDouble(rng).toEcmaString()) // round-trip our own output
            else -> {
                if (rng.int(3) == 0) sb.append(if (rng.int(2) == 0) '+' else '-')
                repeat(rng.int(12)) { sb.append('0' + rng.int(10)) }
                if (rng.int(2) == 0) {
                    sb.append('.')
                    repeat(rng.int(12)) { sb.append('0' + rng.int(10)) }
                }
                if (rng.int(3) == 0) {
                    sb.append(if (rng.int(2) == 0) 'e' else 'E')
                    if (rng.int(2) == 0) sb.append(if (rng.int(2) == 0) '+' else '-')
                    repeat(1 + rng.int(4)) { sb.append('0' + rng.int(10)) }
                }
            }
        }
        // Occasionally corrupt it, so rejection is exercised as much as acceptance.
        if (rng.int(4) == 0 && sb.isNotEmpty()) {
            val junk = "abxz_,;$ .eE+-"
            sb.insert(rng.int(sb.length + 1), junk[rng.int(junk.length)])
        }
        repeat(rng.int(3)) { sb.append(spaces[rng.int(spaces.size)]) }
        return sb.toString()
    }

    private fun radixLiteral(rng: Rng, sb: StringBuilder, prefix: String, radix: Int) {
        when (rng.int(4)) {
            0 -> sb.append('+')
            1 -> sb.append('-')
            else -> {}
        }
        sb.append(if (rng.int(6) == 0) prefix.uppercase() else prefix)
        val digits = java.lang.Long.toString(rng.next() ushr rng.int(60), radix)
        sb.append(if (rng.int(4) == 0) digits.uppercase() else digits)
    }

    private fun buildCase(rng: Rng): Case {
        if (rng.int(3) == 0) {
            val text = randomNumericText(rng)
            return Case(
                "n " + encodeUtf16(text),
                { "Number(${text.replace("\n", "\\n").replace("\r", "\\r")})" },
                { java.lang.Long.toHexString(text.toEcmaDouble().toRawBits()) },
            )
        }
        val v = randomDouble(rng)
        return when (rng.int(5)) {
            0 -> Case("s ${hex(v)}", { "($v).toString()" }, { v.toEcmaString() })
            1 -> {
                val d = rng.int(101)
                Case("f ${hex(v)} $d", { "($v).toFixed($d)" }, { v.toEcmaFixed(d) })
            }
            2 -> {
                val d = if (rng.int(4) == 0) -1 else rng.int(101)
                Case("e ${hex(v)} $d", { "($v).toExponential($d)" }, {
                    v.toEcmaExponential(if (d < 0) null else d)
                })
            }
            3 -> {
                val d = 1 + rng.int(100)
                Case("p ${hex(v)} $d", { "($v).toPrecision($d)" }, { v.toEcmaPrecision(d) })
            }
            else -> {
                val radix = 2 + rng.int(35)
                Case("r ${hex(v)} $radix", { "($v).toString($radix)" }, { v.toEcmaString(radix) })
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val count = args.getOrNull(0)?.toInt() ?: 20_000
        val seed = args.getOrNull(1)?.toLong() ?: 1L
        val oracle = args.getOrNull(2) ?: "tools/numbers/fuzz-oracle.mjs"

        println("fuzzing $count number cases (seed=$seed) against node ...")
        val rng = Rng(seed)
        val cases = ArrayList<Case>(count)
        repeat(count) { cases.add(buildCase(rng)) }

        val process = ProcessBuilder("node", oracle)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
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
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            for (case in cases) {
                val expected = reader.readLine() ?: break
                compared++
                // A "!" answer means node threw; we must reject it too.
                if (expected.startsWith("!")) {
                    val threw = try {
                        case.ours()
                        false
                    } catch (_: IllegalArgumentException) {
                        true
                    }
                    if (!threw) failures.add("${case.describe()}: node threw $expected, we accepted")
                    continue
                }
                val ours = try {
                    case.ours()
                } catch (e: IllegalArgumentException) {
                    failures.add("${case.describe()}: we threw ${e.message}, node said $expected")
                    continue
                }
                if (ours != expected) {
                    failures.add("${case.describe()}: node=$expected ours=$ours")
                }
                if (failures.size >= 20) break
            }
        }
        writer.join()
        process.destroy()

        if (failures.isEmpty()) {
            println("OK: all $compared cases agree with node")
        } else {
            println("FAIL: ${failures.size} of $compared cases disagree")
            failures.forEach { println("    $it") }
            exitProcess(1)
        }
    }
}
