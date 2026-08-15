// GENERATED FILE - DO NOT EDIT.
// Produced by tools/difftest/gen-escape-fixture.mjs against node v26.5.0.

package io.github.mgilbir.ecma262

internal object EscapeFixture {
    internal const val CODE_POINTS: Int = 1114112

    /** FNV-1a over RegExp.escape(c) for every code point c. */
    internal const val SINGLE_HASH: UInt = 3235474977u

    /** The same, for RegExp.escape("x" + c) — the non-leading path. */
    internal const val TRAILING_HASH: UInt = 1771721655u

    private const val SPOTS: String = "0:0:1:a4:\\\\x613:abc6:\\\\x61bc5:a.b*c10:\\\\x61\\\\.b\\\\*c4:0abc7:\\\\x30abc4:_abc4:_abc4:.abc5:\\\\.abc1:-4:\\\\x2d3:a-b9:\\\\x61\\\\x2db11:hello world17:\\\\x68ello\\\\x20world3:\\0009\\000a\\000d6:\\\\t\\\\n\\\\r1:\\00a04:\\\\xa01:\\20286:\\\\u20282:\\d83d\\de002:\\d83d\\de001:\\d83d6:\\\\ud83d2:\$13:\\\\\$13:^a\$5:\\\\^a\\\\\$5:[a-z]10:\\\\[a\\\\x2dz\\\\]3:a/b7:\\\\x61\\\\/b7:c:\\\\path14:\\\\x63\\\\x3a\\\\\\\\path8:\\00abquoted\\00bb8:\\00abquoted\\00bb4:#tag7:\\\\x23tag3:~x~9:\\\\x7ex\\\\x7e"

    /** input -> expected escaped form, decoded from the ASCII transport. */
    internal val spotChecks: Map<String, String> by lazy {
        val s = StringBuilder(SPOTS.length).apply {
            var i = 0
            while (i < SPOTS.length) {
                val c = SPOTS[i]
                if (c != '\\') {
                    append(c); i++
                } else if (SPOTS[i + 1] == '\\') {
                    append('\\'); i += 2
                } else {
                    append(SPOTS.substring(i + 1, i + 5).toInt(16).toChar()); i += 5
                }
            }
        }.toString()

        val out = LinkedHashMap<String, String>()
        var i = 0
        fun read(): String {
            val colon = s.indexOf(':', i)
            val len = s.substring(i, colon).toInt()
            val v = s.substring(colon + 1, colon + 1 + len)
            i = colon + 1 + len
            return v
        }
        while (i < s.length) {
            val input = read()
            out[input] = read()
        }
        out
    }
}
