// GENERATED FILE - DO NOT EDIT.
// Produced by tools/semantics/gen-fixture.mjs against node v26.5.0.
//
// Whitespace is recorded exhaustively: which BMP characters trim() removes is a
// finite yes-or-no question, so there is no reason to sample it. The Math
// hashes cover a deterministic sweep of doubles, hashed over raw bits so that
// -0 and 0 are told apart.

package io.github.mgilbir.ecma262.text

internal object SemanticsFixture {
    internal const val ORACLE: String = "node v26.5.0"

    /** Every BMP code point that `trim()` strips. */
    internal val WHITESPACE: IntArray = intArrayOf(
        0x9, 0xA, 0xB, 0xC, 0xD, 0x20, 0xA0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
        0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF
    )

    /** Doubles the Math hashes were taken over. */
    internal const val SAMPLE_COUNT: Int = 48018

    /** The sweep itself, so drift is distinguishable from a wrong answer. */
    internal const val INPUT_HASH: UInt = 3012103928u

    internal const val ROUND_HASH: UInt = 46323446u
    internal const val TRUNC_HASH: UInt = 3618904970u
    internal const val SIGN_HASH: UInt = 3705724795u
    internal const val FROUND_HASH: UInt = 2297355817u
    internal const val CLZ32_HASH: UInt = 461788971u
    internal const val IMUL_HASH: UInt = 3005737669u

    internal class Case(val function: String, val argumentBits: ULong, val resultBits: ULong)

    internal val EXPLICIT: List<Case> = listOf(
        Case("round", 4602678819172646912uL, 4607182418800017408uL),
        Case("round", 13826050856027422720uL, 9223372036854775808uL),
        Case("round", 4612811918334230528uL, 4613937818241073152uL),
        Case("round", 13836183955189006336uL, 13835058055282163712uL),
        Case("round", 4602678819172646911uL, 0uL),
        Case("round", 13819745816549104026uL, 9223372036854775808uL),
        Case("round", 13826951575952896819uL, 13830554455654793216uL),
        Case("trunc", 13826050856027422720uL, 9223372036854775808uL),
        Case("trunc", 4602678819172646912uL, 0uL),
        Case("trunc", 13832806255468478464uL, 13830554455654793216uL),
        Case("sign", 9223372036854775808uL, 9223372036854775808uL),
        Case("sign", 0uL, 0uL),
        Case("sign", 13837309855095848960uL, 13830554455654793216uL),
        Case("sign", 4613937818241073152uL, 4607182418800017408uL),
        Case("fround", 4617371812956943155uL, 4617371813171691520uL),
        Case("fround", 5205425776111082661uL, 9218868437227405312uL),
        Case("fround", 9223372036854775808uL, 9223372036854775808uL),
    )
}
