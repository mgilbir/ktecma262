// GENERATED FILE - DO NOT EDIT.
// Produced by tools/numbers/gen-radix-fixture.mjs against node v26.5.0.
//
// toString(radix) for radix != 10 is implementation-approximated: the
// specification defines no answer, so these recorded strings *are* the
// contract. If a future V8 changes them, this fixture is what will say so.

package io.github.mgilbir.ecma262.number

internal object RadixFixture {
    internal const val ORACLE: String = "node v26.5.0"
    internal const val SAMPLE_COUNT: Int = 21280
    internal const val SAMPLE_HASH: UInt = 308090740u
    internal const val VALUE_COUNT: Int = 3040
    internal val RADICES: IntArray = intArrayOf(2, 3, 7, 8, 16, 20, 36)

    /** (raw bits, radix) to what node prints. */
    internal val EXPLICIT: List<Pair<Pair<ULong, Int>, String>> = listOf(
        Pair(4643176031446892544uL, 16) to "ff",
        Pair(4661223415305207808uL, 16) to "fff",
        Pair(4602678819172646912uL, 2) to "0.1",
        Pair(4589168020290535424uL, 2) to "0.0001",
        Pair(4591870180066957722uL, 3) to "0.0022002200220022002200220022002201",
        Pair(13815242216921733530uL, 7) to "-0.04620462046204620463",
        Pair(4921056587992461136uL, 36) to "5v1j4f4ds7c000",
        Pair(4638387860618067575uL, 8) to "173.3513615237574734",
        Pair(4599676419421066581uL, 3) to "0.1",
        Pair(4611686018427387904uL, 2) to "10",
        Pair(4845873199050653696uL, 16) to "20000000000000",
        Pair(6103021453049119613uL, 36) to "2hqbczu2ow6000000000000000000000000000000000000000000000000000000",
        Pair(4652218406277629346uL, 2) to "1111111111.111111111011111001110110110010001011010001",
    )
}
