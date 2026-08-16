// GENERATED FILE - DO NOT EDIT.
// Produced by tools/identifier/gen-fixture.mjs against node v26.5.0.
//
// The hashes cover every code point using the composed rule from 12.7. The
// explicit cases are checked by actually parsing them, which is the real
// question and too slow to ask 1,114,112 times.
//
// Inputs are code unit arrays: some are lone surrogates, which Kotlin/JS
// rewrites inside a compile-time constant.

package io.github.mgilbir.ecma262.text

internal object IdentifierFixture {
    internal const val ORACLE: String = "node v26.5.0"

    internal const val CODE_POINTS: Int = 1112064
    internal const val START_COUNT: Int = 145918
    internal const val PART_COUNT: Int = 149241
    internal const val START_HASH: UInt = 2163840869u
    internal const val PART_HASH: UInt = 3021223372u

    /** [isName] is "obj.x parses"; [isBinding] is "var x parses". */
    internal class Case(val units: IntArray, val isName: Boolean, val isBinding: Boolean)

    internal val EXPLICIT: List<Case> = listOf(
        Case(intArrayOf(0x61), true, true),
        Case(intArrayOf(0x5F), true, true),
        Case(intArrayOf(0x24), true, true),
        Case(intArrayOf(0x61, 0x31), true, true),
        Case(intArrayOf(0x31, 0x61), false, false),
        Case(intArrayOf(0x61, 0x2D, 0x62), false, false),
        Case(intArrayOf(), false, false),
        Case(intArrayOf(0x61, 0x62), true, true),
        Case(intArrayOf(0x65E5, 0x672C, 0x8A9E), true, true),
        Case(intArrayOf(0xC0), true, true),
        Case(intArrayOf(0x2B0), true, true),
        Case(intArrayOf(0x61, 0xB7, 0x62), true, true),
        Case(intArrayOf(0xB7), false, false),
        Case(intArrayOf(0x69, 0x66), true, false),
        Case(intArrayOf(0x74, 0x72, 0x75, 0x65), true, false),
        Case(intArrayOf(0x6E, 0x75, 0x6C, 0x6C), true, false),
        Case(intArrayOf(0x61, 0x77, 0x61, 0x69, 0x74), true, true),
        Case(intArrayOf(0x79, 0x69, 0x65, 0x6C, 0x64), true, true),
        Case(intArrayOf(0x6C, 0x65, 0x74), true, true),
        Case(intArrayOf(0x73, 0x74, 0x61, 0x74, 0x69, 0x63), true, true),
        Case(intArrayOf(0x75, 0x6E, 0x64, 0x65, 0x66, 0x69, 0x6E, 0x65, 0x64), true, true),
        Case(intArrayOf(0x4E, 0x61, 0x4E), true, true),
        Case(intArrayOf(0x63, 0x6C, 0x61, 0x73, 0x73), true, false),
        Case(intArrayOf(0x65, 0x6E, 0x75, 0x6D), true, false),
        Case(intArrayOf(0x70, 0x75, 0x62, 0x6C, 0x69, 0x63), true, true),
        Case(intArrayOf(0x77, 0x69, 0x74, 0x68), true, false),
        Case(intArrayOf(0x24, 0x24), true, true),
        Case(intArrayOf(0x5F, 0x24, 0x5F), true, true),
        Case(intArrayOf(0x61, 0x200C, 0x62), true, true),
        Case(intArrayOf(0x61, 0x200D, 0x62), true, true),
        Case(intArrayOf(0x200C, 0x61, 0x62), false, false),
        Case(intArrayOf(0x20AC), false, false),
        Case(intArrayOf(0x1160), true, true),
        Case(intArrayOf(0x78, 0x200B, 0x79), false, false),
        Case(intArrayOf(0x61, 0xD835, 0xDC00), true, true),
        Case(intArrayOf(0xD835, 0xDC00), true, true),
        Case(intArrayOf(0xD800), false, false),
        Case(intArrayOf(0x61, 0xD800), false, false),
        Case(intArrayOf(0x2118), true, true),
        Case(intArrayOf(0x212C), true, true),
        Case(intArrayOf(0x41), true, true),
        Case(intArrayOf(0x61, 0x41, 0x30, 0x5F, 0x24), true, true),
        Case(intArrayOf(0x66, 0x6F, 0x72), true, false),
        Case(intArrayOf(0x6F, 0x66), true, true),
        Case(intArrayOf(0x61, 0x73), true, true),
        Case(intArrayOf(0x66, 0x72, 0x6F, 0x6D), true, true),
        Case(intArrayOf(0x61, 0x73, 0x79, 0x6E, 0x63), true, true),
        Case(intArrayOf(0x67, 0x65, 0x74), true, true),
        Case(intArrayOf(0x73, 0x65, 0x74), true, true),
    )
}
