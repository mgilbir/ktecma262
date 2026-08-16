package io.github.mgilbir.ecma262

import io.github.mgilbir.ecma262.number.toEcmaString
import java.util.regex.Pattern as JdkPattern
import kotlin.system.measureNanoTime

/**
 * Micro-benchmarks for the engine, with `java.util.regex` alongside as a
 * reference point.
 *
 * The JDK engine is not an apples-to-apples comparison — it implements a
 * different dialect and has no ReDoS bound — but it is a useful sanity check
 * that this engine is in the same order of magnitude rather than accidentally
 * quadratic somewhere.
 *
 *     ./gradlew bench
 */
object BenchmarkMain {

    private const val WARMUP = 5
    private const val ROUNDS = 7

    private class Result(val name: String, val ours: Double, val jdk: Double?, val unit: String)

    private val results = mutableListOf<Result>()

    /** Median nanoseconds per iteration over [ROUNDS] rounds of [iterations]. */
    private fun timeOne(iterations: Int, body: () -> Unit): Double {
        repeat(WARMUP) { repeat(iterations) { body() } }
        val samples = DoubleArray(ROUNDS) {
            measureNanoTime { repeat(iterations) { body() } }.toDouble() / iterations
        }
        samples.sort()
        return samples[ROUNDS / 2]
    }

    private fun bench(name: String, iterations: Int, unit: String = "ns/op", jdk: (() -> Unit)? = null, ours: () -> Unit) {
        val o = timeOne(iterations, ours)
        // java.util.regex recurses per repetition and overflows the stack on some
        // of these inputs; that is a data point, not a reason to abort the run.
        var jdkNote: String? = null
        val j = if (jdk == null) {
            null
        } else {
            try {
                timeOne(iterations, jdk)
            } catch (_: StackOverflowError) {
                jdkNote = "jdk StackOverflowError"
                null
            }
        }
        results += Result(name, o, j, unit)
        val jdkText = when {
            jdkNote != null -> "   $jdkNote"
            j == null -> ""
            else -> "   jdk ${fmt(j)}   ratio ${"%.2f".format(o / j)}x"
        }
        println("  ${name.padEnd(42)} ${fmt(o).padStart(12)}$jdkText")
    }

    private fun fmt(ns: Double): String = when {
        ns >= 1_000_000 -> "%.2f ms".format(ns / 1_000_000)
        ns >= 1_000 -> "%.2f us".format(ns / 1_000)
        else -> "%.0f ns".format(ns)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val text = buildString {
            repeat(2_000) { append("The quick brown fox jumps over the lazy dog 12345. ") }
        }
        val emails = List(1_000) { "user$it.name+tag@example-$it.co.uk" }

        println("input: ${text.length} chars, ${emails.size} emails\n")

        println("compile:")
        bench("compile /\\d+/", 20_000, jdk = { JdkPattern.compile("\\d+") }) {
            RegExp.compile("\\d+")
        }
        bench("compile email pattern", 5_000, jdk = { JdkPattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+") }) {
            RegExp.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
        }
        bench("compile /\\p{L}+/u (property resolve)", 2_000) {
            RegExp.compile("\\p{L}+", "u")
        }
        bench("compile /[a-z]/i (case closure)", 5_000) {
            RegExp.compile("[a-z]", "i")
        }

        println("\nmatch (pre-compiled):")
        run {
            val re = RegExp.compile("needle")
            val jdkRe = JdkPattern.compile("needle")
            val hay = text + "needle"
            bench("literal scan, 100k chars", 200, jdk = { jdkRe.matcher(hay).find() }) {
                re.exec(hay)
            }
        }
        run {
            val re = RegExp.compile("\\d+")
            val jdkRe = JdkPattern.compile("\\d+")
            bench("\\d+ first match", 2_000, jdk = { jdkRe.matcher(text).find() }) {
                re.exec(text)
            }
        }
        run {
            val re = RegExp.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
            val jdkRe = JdkPattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
            bench("email match x1000", 20, jdk = { for (e in emails) jdkRe.matcher(e).find() }) {
                for (e in emails) re.exec(e)
            }
        }
        run {
            val re = RegExp.compile("(\\d{4})-(\\d{2})-(\\d{2})")
            val jdkRe = JdkPattern.compile("(\\d{4})-(\\d{2})-(\\d{2})")
            val s = "logged on 2024-03-15 at noon"
            bench("date with 3 captures", 200_000, jdk = { jdkRe.matcher(s).find() }) {
                re.exec(s)
            }
        }
        run {
            val re = RegExp.compile("\\b\\w+\\b", "g")
            val jdkRe = JdkPattern.compile("\\b\\w+\\b")
            bench("findAll words, 100k chars", 20, jdk = {
                val m = jdkRe.matcher(text); var n = 0; while (m.find()) n++
            }) {
                re.findAll(text)
            }
        }
        run {
            val re = RegExp.compile("\\p{L}+", "gu")
            bench("findAll \\p{L}+ unicode, 100k chars", 20) { re.findAll(text) }
        }
        run {
            val re = RegExp.compile("(a|b)*c")
            val jdkRe = JdkPattern.compile("(a|b)*c")
            val s = "abab".repeat(400) + "c"
            bench("alternation loop, 1600 chars", 500, jdk = { jdkRe.matcher(s).find() }) {
                re.exec(s)
            }
        }
        run {
            val re = RegExp.compile("(?<=\\$)\\d+(?:\\.\\d+)?", "g")
            val s = "costs \$12.50 and \$7 and \$1234.56 ".repeat(500)
            bench("lookbehind findAll", 50) { re.findAll(s) }
        }
        run {
            val re = RegExp.compile("([a-z]+)\\s+\\1", "gi")
            val s = "the the quick quick brown fox ".repeat(500)
            bench("backreference findAll", 50) { re.findAll(s) }
        }

        println("\nstring operations:")
        run {
            val re = RegExp.compile("\\d+", "g")
            bench("replace all digits, 100k chars", 20) { re.replace(text, "#") }
        }
        run {
            val re = RegExp.compile("\\s+", "g")
            bench("split on whitespace, 100k chars", 20) { re.split(text) }
        }

        println("\nnumber formatting:")
        run {
            // A spread across the whole exponent range, so the scaling loops are
            // exercised rather than just the common case near 1.
            val values = DoubleArray(2_000)
            var state = 12345L
            for (i in values.indices) {
                state = state * 6364136223846793005L + 1442695040888963407L
                val d = Double.fromBits(state)
                values[i] = if (d.isNaN() || d.isInfinite()) 1.5 else d
            }
            // The JDK does not produce the same string - it lays the digits out
            // differently and is not shortest for the smallest subnormals - so
            // this is a scale reference, not an equivalence.
            bench(
                "toEcmaString, mixed exponents",
                20,
                jdk = { for (v in values) java.lang.Double.toString(v) },
            ) {
                for (v in values) v.toEcmaString()
            }
        }
        run {
            val values = DoubleArray(2_000) { (it + 1) / 10.0 }
            bench(
                "toEcmaString, short decimals",
                20,
                jdk = { for (v in values) java.lang.Double.toString(v) },
            ) {
                for (v in values) v.toEcmaString()
            }
        }

        println("\n${results.size} benchmarks completed")
    }
}
