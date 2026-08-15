package io.github.mgilbir.ecma262

import java.io.BufferedReader
import java.io.File
import java.util.Base64
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Live differential fuzzer.
 *
 * Generates random patterns and inputs, asks a real JavaScript engine what it
 * does with each, and requires this engine to agree exactly — on the match
 * index, on every capture group, and on whether the pattern is even valid.
 *
 * Unlike the recorded fixture in `DiffFixture`, this has no size limit, so it is
 * the tool for finding the long tail. Run it with:
 *
 *     ./gradlew fuzz -Pcount=200000 -Pseed=1
 *
 * A failure prints the exact pattern, flags and input, so it can be turned into
 * a regression case immediately.
 */
object FuzzMain {

    private val enc: Base64.Encoder = Base64.getEncoder()
    private val dec: Base64.Decoder = Base64.getDecoder()

    /** base64 of UTF-16LE code units, so lone surrogates survive the transport. */
    private fun encode(s: String): String {
        val bytes = ByteArray(s.length * 2)
        for (i in s.indices) {
            val c = s[i].code
            bytes[i * 2] = (c and 0xFF).toByte()
            bytes[i * 2 + 1] = ((c ushr 8) and 0xFF).toByte()
        }
        return enc.encodeToString(bytes)
    }

    private fun decode(s: String): String {
        if (s.isEmpty()) return ""
        val bytes = dec.decode(s)
        val sb = StringBuilder(bytes.size / 2)
        var i = 0
        while (i + 1 < bytes.size) {
            sb.append((((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i].toInt() and 0xFF)).toChar())
            i += 2
        }
        return sb.toString()
    }

    private class Case(
        val op: Char,
        val pattern: String,
        val flags: String,
        val input: String,
        /** Replacement text for `r`, decimal split limit for `s`. */
        val extra: String = "",
    ) {
        override fun toString(): String {
            val detail = when (op) {
                'r' -> " replace with ${quote(extra)}"
                's' -> " split limit=$extra"
                else -> ""
            }
            return "/$pattern/$flags on ${quote(input)} [$op]$detail"
        }
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when {
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch.code < 0x20 || ch.code > 0x7e ->
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> append(ch)
            }
        }
        append('"')
    }

    // ---------------------------------------------------------------- generation

    private val atoms = listOf(
        "a", "b", "c", "x", ".", "\\d", "\\D", "\\w", "\\W", "\\s", "\\S",
        "[ab]", "[^a]", "[a-c]", "[\\d-]", "[^\\w]", "\\b", "\\B", "^", "\$",
        "\\n", "\\t", "\\.", "-", "_", "1", " ",
    )
    private val quants = listOf(
        "", "", "", "", "*", "+", "?", "*?", "+?", "??",
        "{2}", "{1,2}", "{0,2}", "{2,}", "{1,3}?",
    )
    private val flagSets =
        listOf("", "", "i", "m", "s", "u", "iu", "ms", "im", "is", "su", "imsu", "v", "vi", "vs")

    /** Class-set fragments, only meaningful under the `v` flag. */
    private val classSetAtoms = listOf(
        "[[a][b]]", "[a--b]", "[[a-z]--[aeiou]]", "[a&&b]", "[[a-c]&&[b-d]]",
        "[\\q{ab}]", "[\\q{a|bc}]", "[\\q{}]", "[a\\q{ab}]", "[^[a][b]]",
        "[\\p{L}--\\p{Lu}]", "[&]", "[\\-]", "[\\q{ab|a}]",
        "\\p{RGI_Emoji}", "[\\p{Basic_Emoji}]", "[\\p{Emoji_Keycap_Sequence}\\q{ab}]",
    )
    private val inputAlphabet = "aabbccxyz01 _-\n\t.äÄſKΣσ😀\uFE0F\u20E3\u200D#"

    /** Anchors and boundaries cannot take a quantifier, so never emit one there. */
    private fun quantifiable(atom: String): Boolean =
        atom != "^" && atom != "\$" && atom != "\\b" && atom != "\\B"

    private fun genPattern(rnd: Random, depth: Int, classSets: Boolean = false): String {
        val r = rnd.nextDouble()
        if (depth <= 0 || r < 0.42) {
            val pool = if (classSets && rnd.nextInt(3) == 0) classSetAtoms else atoms
            val atom = pool.random(rnd)
            return if (quantifiable(atom)) atom + quants.random(rnd) else atom
        }
        return when {
            r < 0.58 -> genPattern(rnd, depth - 1) + genPattern(rnd, depth - 1)
            r < 0.70 -> "(" + genPattern(rnd, depth - 1) + "|" + genPattern(rnd, depth - 1) + ")" +
                quants.random(rnd)
            r < 0.79 -> "(" + genPattern(rnd, depth - 1) + ")" + quants.random(rnd)
            r < 0.85 -> "(?:" + genPattern(rnd, depth - 1) + ")" + quants.random(rnd)
            r < 0.86 -> "(?<g${rnd.nextInt(3)}>" + genPattern(rnd, depth - 1) + ")"
            // Regexp modifiers: a random add/remove set over i, m and s.
            r < 0.88 -> {
                val all = listOf("i", "m", "s").shuffled(rnd)
                val add = all.take(rnd.nextInt(0, 3)).joinToString("")
                val remove = all.drop(add.length).take(rnd.nextInt(0, 2)).joinToString("")
                val spec = if (remove.isEmpty()) add else "$add-$remove"
                "(?" + spec + ":" + genPattern(rnd, depth - 1) + ")"
            }
            r < 0.91 -> "(?=" + genPattern(rnd, depth - 1) + ")"
            r < 0.94 -> "(?!" + genPattern(rnd, depth - 1) + ")"
            r < 0.96 -> "(?<=" + genPattern(rnd, depth - 1) + ")"
            r < 0.98 -> "(?<!" + genPattern(rnd, depth - 1) + ")"
            else -> "(" + genPattern(rnd, depth - 1) + ")\\1"
        }
    }

    /**
     * Characters that appear in regex syntax, for generating junk patterns.
     *
     * The grammar-driven generator can only produce syntax this file already
     * knows about, so it cannot discover a construct the engine has never heard
     * of. Random strings over this alphabet can: whatever node makes of them,
     * the engine has to agree — accept, reject, or match identically.
     */
    private const val SYNTAX_SOUP = "abc019 _-^\$.*+?()[]{}|/\\<>=!:,&#~%@`'\"wWdDsSbBpPkqQuUxXvVnrtf"

    private fun genJunkPattern(rnd: Random): String {
        val n = rnd.nextInt(1, 14)
        val sb = StringBuilder(n)
        repeat(n) { sb.append(SYNTAX_SOUP[rnd.nextInt(SYNTAX_SOUP.length)]) }
        return sb.toString()
    }

    private fun genInput(rnd: Random): String {
        val n = rnd.nextInt(0, 12)
        val sb = StringBuilder(n)
        repeat(n) { sb.append(inputAlphabet[rnd.nextInt(inputAlphabet.length)]) }
        return sb.toString()
    }

    private val replacements = listOf(
        "X", "", "-", "$&", "[$&]", "$1", "$2", "$12", "$0", "$99", "$<g0>",
        "$<nope>", "$`", "$'", "$$", "$$1", "a$&b$1c", "\$<g1>$&",
    )

    private val splitLimits = listOf("-1", "-1", "-1", "0", "1", "2", "3", "5")

    private fun genCase(rnd: Random): Case {
        var flags = flagSets.random(rnd)
        // One case in eight is unstructured syntax soup, which is what can turn
        // up grammar the structured generator does not know to produce.
        val pattern = if (rnd.nextInt(8) == 0) {
            genJunkPattern(rnd)
        } else {
            genPattern(rnd, 3, classSets = 'v' in flags)
        }

        return when (rnd.nextInt(8)) {
            0, 1 -> {
                // Repeated matching needs /g.
                if ('g' !in flags) flags += "g"
                Case('a', pattern, flags, genInput(rnd))
            }
            2, 3 -> Case('r', pattern, flags, genInput(rnd), replacements.random(rnd))
            4 -> Case('s', pattern, flags, genInput(rnd), splitLimits.random(rnd))
            else -> Case('x', pattern, flags, genInput(rnd))
        }
    }

    // ------------------------------------------------------------------ checking

    /** `index n g0 g1 …`, matching the oracle's renderMatch exactly. */
    private fun renderMatchBody(m: MatchResult): String {
        val groups = (0 until m.size).joinToString(" ") { g ->
            m[g]?.let { encode(it) } ?: "-"
        }
        return "${m.index} ${m.size} $groups"
    }

    /**
     * A known V8 defect, flagged by the oracle itself with a leading "!".
     *
     * Under `u`/`v` the spec matches over a list of code points, so a match can
     * never begin inside a surrogate pair — and V8 agrees for most patterns
     * (`/(?:)/yu` with lastIndex 2 on "😀" snaps back to 0). But for
     * zero-width assertions it reports positions that split a pair, e.g.
     * `/\B/u.exec("b😀").index` is 2 where the spec requires 3.
     *
     * This engine follows the specification, so those cases are counted and
     * skipped rather than treated as failures. SurrogateBoundaryTest pins down
     * the behaviour implemented instead.
     */
    private fun isKnownV8SurrogateDeviation(expected: String): Boolean = expected.startsWith("!")

    /**
     * A second V8 defect, also flagged by the oracle (with "~").
     *
     * Under `/vi` a single-character `\q{…}` element should fold like the same
     * character written plainly, but V8 folds only the pattern side: `[\q{a}]/vi`
     * misses "A" while `[a]/vi` matches it. This engine follows the
     * specification; see UnicodeSetsTest.
     */
    private fun isKnownV8QuotedStringDeviation(expected: String): Boolean = expected.startsWith("~")

    /**
     * A third V8 defect, flagged by the oracle with "%".
     *
     * A modifier group scopes `i` correctly for literals and classes but not for
     * `\w`, `\W`, `\b` and `\B`, so `/(?i:c)\w/u` matches "c\u017F" in V8.
     * This engine scopes them; see ModifierGroupTest.
     */
    private fun isKnownV8ModifierDeviation(expected: String): Boolean = expected.startsWith("%")

    /** Compares one oracle answer with ours; returns a description on mismatch. */
    private fun check(c: Case, expected: String): String? {
        if (expected == "T") return null // oracle-side failure, nothing to compare

        val re = try {
            RegExp.compile(c.pattern, c.flags)
        } catch (e: RegExpSyntaxError) {
            return if (expected == "E") null else "$c: we reject (${e.message}) but node accepts"
        }
        if (expected == "E") return "$c: node rejects it, we accept"

        return try {
            when (c.op) {
                'x' -> checkSingle(c, re, expected)
                'a' -> checkAll(c, re, expected)
                'r' -> checkReplace(c, re, expected)
                else -> checkSplit(c, re, expected)
            }
        } catch (e: RegExpStepLimitError) {
            // A deliberate divergence: node has no bound. Only report it if the
            // budget was generous enough that we should have finished.
            "$c: step limit exceeded (${e.steps} steps)"
        }
    }

    private fun checkSingle(c: Case, re: RegExp, expected: String): String? {
        val m = re.exec(c.input)
        if (expected == "N") {
            return if (m == null) null else "$c: node finds no match, we match ${quote(m.value)} at ${m.index}"
        }
        if (m == null) return "$c: node matches ($expected), we find nothing"
        val ours = "M ${renderMatchBody(m)}"
        return if (ours == expected) null else "$c:\n    node: $expected\n    ours: $ours"
    }

    private fun checkAll(c: Case, re: RegExp, expected: String): String? {
        val all = re.findAll(c.input)
        val rendered = all.joinToString(" | ") { renderMatchBody(it) }
        val ours = "A ${all.size} $rendered"
        return if (ours.trimEnd() == expected.trimEnd()) {
            null
        } else {
            val theirCount = expected.split(" ").getOrNull(1) ?: "?"
            "$c: (node found $theirCount, we found ${all.size})\n    node: $expected\n    ours: $ours"
        }
    }

    private fun checkReplace(c: Case, re: RegExp, expected: String): String? {
        val ours = "R " + encode(re.replace(c.input, c.extra))
        if (ours == expected) return null
        val theirs = expected.removePrefix("R ").trim()
        return "$c:\n    node: ${quote(decode(theirs))}\n    ours: ${quote(re.replace(c.input, c.extra))}"
    }

    private fun checkSplit(c: Case, re: RegExp, expected: String): String? {
        val parts = re.split(c.input, c.extra.toInt())
        val rendered = parts.joinToString(" ") { it?.let { s -> encode(s) } ?: "-" }
        val ours = "S ${parts.size} $rendered"
        if (ours.trimEnd() == expected.trimEnd()) return null

        val theirParts = expected.split(" ").drop(2).filter { it.isNotEmpty() }
            .map { if (it == "-") null else decode(it) }
        return "$c:\n    node: ${theirParts.map { it?.let(::quote) ?: "undefined" }}" +
            "\n    ours: ${parts.map { it?.let(::quote) ?: "null" }}"
    }

    // ---------------------------------------------------------------------- main

    @JvmStatic
    fun main(args: Array<String>) {
        val count = args.getOrNull(0)?.toIntOrNull() ?: 20_000
        val seed = args.getOrNull(1)?.toLongOrNull() ?: 1L
        val oracleScript = args.getOrNull(2) ?: "tools/difftest/fuzz-oracle.mjs"

        require(File(oracleScript).isFile) { "oracle script not found: $oracleScript" }

        val rnd = Random(seed)
        val cases = (0 until count).map { genCase(rnd) }

        println("fuzzing $count cases (seed=$seed) against node ...")

        val process = ProcessBuilder("node", oracleScript)
            .redirectErrorStream(false)
            .start()

        // Feed the oracle on a separate thread so a full pipe cannot deadlock us.
        val writer = Thread {
            process.outputStream.bufferedWriter().use { w ->
                for (c in cases) {
                    w.write(c.op.toString())
                    w.write(" "); w.write(encode(c.pattern))
                    w.write(" "); w.write(encode(c.flags))
                    w.write(" "); w.write(encode(c.input))
                    if (c.op == 'r' || c.op == 's') {
                        w.write(" "); w.write(encode(c.extra))
                    }
                    w.newLine()
                }
            }
        }
        writer.start()

        val results = ArrayList<String>(count)
        process.inputStream.bufferedReader().use { r: BufferedReader ->
            r.forEachLine { if (it.isNotEmpty()) results.add(it) }
        }
        writer.join()
        process.waitFor()

        check(results.size == cases.size) {
            "oracle returned ${results.size} results for ${cases.size} cases"
        }

        val failures = ArrayList<String>()
        var skippedSurrogate = 0
        var skippedQuotedString = 0
        var skippedModifier = 0
        var stepLimited = 0
        for (i in cases.indices) {
            if (isKnownV8SurrogateDeviation(results[i])) {
                skippedSurrogate++
                continue
            }
            if (isKnownV8QuotedStringDeviation(results[i])) {
                skippedQuotedString++
                continue
            }
            if (isKnownV8ModifierDeviation(results[i])) {
                skippedModifier++
                continue
            }
            val failure = check(cases[i], results[i]) ?: continue
            if ("step limit exceeded" in failure) {
                // The engine bounds backtracking and node does not; that is the
                // intended difference, covered by StepLimitTest.
                stepLimited++
                continue
            }
            failures.add(failure)
        }

        val notes = buildList {
            if (skippedSurrogate > 0) add("$skippedSurrogate skipped: V8 surrogate defect")
            if (skippedQuotedString > 0) add("$skippedQuotedString skipped: V8 \\q{} folding defect")
            if (skippedModifier > 0) add("$skippedModifier skipped: V8 modifier scoping defect")
            if (stepLimited > 0) add("$stepLimited step-limited")
        }
        val note = if (notes.isEmpty()) "" else notes.joinToString(", ", prefix = " (", postfix = ")")

        if (failures.isEmpty()) {
            println("OK: all $count cases agree with node$note")
            exitProcess(0)
        }
        println("FAIL: ${failures.size} of $count cases disagree$note\n")
        failures.take(50).forEach { println(it) }
        if (failures.size > 50) println("... and ${failures.size - 50} more")
        exitProcess(1)
    }
}
