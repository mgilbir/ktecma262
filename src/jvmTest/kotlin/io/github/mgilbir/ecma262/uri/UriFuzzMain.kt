package io.github.mgilbir.ecma262.uri

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Differential fuzzing for the four URI functions against a running node.
 *
 * ```
 * ./gradlew uriFuzz -Pcount=200000 -Pseed=7
 * ```
 *
 * Encoding is already verified exhaustively over every code point, so the point
 * of this is **decoding**, whose input is text: the ways a percent escape can be
 * malformed are not enumerable, and rejecting the right ones is the security
 * property. The generator therefore spends most of its effort on escapes that
 * are truncated, overlong, out of range, or encode a surrogate.
 */
internal object UriFuzzMain {

    private class Case(val request: String, val describe: () -> String, val ours: () -> String)

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
            bytes[i * 2] = (s[i].code and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s[i].code ushr 8) and 0xFF).toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun decodeUtf16(b64: String): String {
        val bytes = Base64.getDecoder().decode(b64)
        val sb = StringBuilder(bytes.size / 2)
        var i = 0
        while (i + 1 < bytes.size) {
            sb.append((((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i].toInt() and 0xFF)).toChar())
            i += 2
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdefABCDEF"

    /** Text built to land on the boundaries of what a decoder must reject. */
    private fun randomText(rng: Rng): String {
        val sb = StringBuilder()
        repeat(1 + rng.int(12)) {
            when (rng.int(14)) {
                0, 1, 2 -> {
                    // A well-formed escape for a random code point.
                    val cp = when (rng.int(4)) {
                        0 -> rng.int(0x80)
                        1 -> 0x80 + rng.int(0x780)
                        2 -> 0x800 + rng.int(0xF800)
                        else -> 0x10000 + rng.int(0x100000)
                    }
                    if (cp in 0xD800..0xDFFF) sb.append("%41") else {
                        val s = if (cp <= 0xFFFF) cp.toChar().toString() else {
                            val v = cp - 0x10000
                            charArrayOf((0xD800 + (v ushr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar())
                                .concatToString()
                        }
                        sb.append(s.encodeUriComponent())
                    }
                }
                3, 4 -> {
                    // A raw percent group, valid hex or not.
                    sb.append('%')
                    repeat(rng.int(3)) {
                        sb.append(if (rng.int(4) == 0) "gxz@"[rng.int(4)] else HEX[rng.int(HEX.length)])
                    }
                }
                5 -> sb.append("%C0%80") // overlong
                6 -> sb.append("%E0%80%80") // overlong
                7 -> sb.append("%ED%A0%80") // encoded surrogate
                8 -> sb.append("%F5%80%80%80") // beyond U+10FFFF
                9 -> sb.append("%F8%88%80%80%80") // five-byte form
                10 -> sb.append(";/?:@&=+$,#"[rng.int(11)])
                11 -> sb.append("-_.!~*'()"[rng.int(9)])
                12 -> {
                    // Raw characters, including lone surrogates.
                    val c = rng.int(0x11000)
                    sb.append(c.toChar())
                }
                else -> sb.append(('a' + rng.int(26)))
            }
        }
        return sb.toString()
    }

    private fun buildCase(rng: Rng): Case {
        val text = randomText(rng)
        val payload = encodeUtf16(text)
        return when (rng.int(4)) {
            0 -> Case("c $payload", { "encodeURIComponent" }, { text.encodeUriComponent() })
            1 -> Case("u $payload", { "encodeURI" }, { text.encodeUri() })
            2 -> Case("C $payload", { "decodeURIComponent" }, { text.decodeUriComponent() })
            else -> Case("U $payload", { "decodeURI" }, { text.decodeUri() })
        }.let { case ->
            Case(case.request, { "${case.describe()}(${describe(text)})" }, case.ours)
        }
    }

    private fun describe(s: String) =
        s.map { if (it.code in 32..126) it.toString() else "\\u" + it.code.toString(16).padStart(4, '0') }
            .joinToString("")

    @JvmStatic
    fun main(args: Array<String>) {
        val count = args.getOrNull(0)?.toInt() ?: 20_000
        val seed = args.getOrNull(1)?.toLong() ?: 1L
        val oracle = args.getOrNull(2) ?: "tools/uri/fuzz-oracle.mjs"

        println("fuzzing $count URI cases (seed=$seed) against node ...")
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
        var rejections = 0
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            for (case in cases) {
                val answer = reader.readLine() ?: break
                compared++
                if (answer.startsWith("!")) {
                    rejections++
                    val threw = try {
                        case.ours()
                        false
                    } catch (_: UriError) {
                        true
                    }
                    if (!threw) failures.add("${case.describe()}: node threw, we accepted")
                    continue
                }
                val expected = decodeUtf16(answer)
                val ours = try {
                    case.ours()
                } catch (e: UriError) {
                    failures.add("${case.describe()}: we threw (${e.message}), node returned ${describe(expected)}")
                    continue
                }
                if (ours != expected) {
                    failures.add("${case.describe()}: node=${describe(expected)} ours=${describe(ours)}")
                }
                if (failures.size >= 20) break
            }
        }
        writer.join()
        process.destroy()

        if (failures.isEmpty()) {
            println("OK: all $compared cases agree with node ($rejections rejected by both)")
        } else {
            println("FAIL: ${failures.size} of $compared cases disagree")
            failures.forEach { println("    $it") }
            exitProcess(1)
        }
    }
}
