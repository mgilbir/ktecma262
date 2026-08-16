// GENERATED FILE - DO NOT EDIT.
// Produced by tools/numbers/gen-format-fixture.mjs against node v26.5.0.

package io.github.mgilbir.ecma262.number

internal object FormatFixture {
    internal const val ORACLE: String = "node v26.5.0"
    internal const val SAMPLE_COUNT: Int = 97296
    internal const val SAMPLE_HASH: UInt = 1293763748u
    internal val VALUE_COUNT: Int = 4054

    /** (raw bits, "method:arg") to what node prints. */
    internal val EXPLICIT: List<Pair<Pair<ULong, String>, String>> = listOf(
        Pair(4607204936798154260uL, "fixed:2") to "1.00",
        Pair(4609209038632334131uL, "fixed:1") to "1.4",
        Pair(4620696032431896003uL, "fixed:2") to "8.01",
        Pair(4612811918334230528uL, "fixed:0") to "3",
        Pair(4602678819172646912uL, "fixed:0") to "1",
        Pair(4609434218613702656uL, "fixed:0") to "2",
        Pair(4636736939510915400uL, "fixed:2") to "100.00",
        Pair(4921056587992461136uL, "fixed:2") to "1e+21",
        Pair(4517329193108106637uL, "fixed:2") to "0.00",
        Pair(4653144502051863213uL, "fixed:2") to "1234.57",
        Pair(13830576973652930068uL, "fixed:2") to "-1.00",
        Pair(4653144502051863213uL, "exp:2") to "1.23e+3",
        Pair(4653144502051863213uL, "exp") to "1.2345678e+3",
        Pair(0uL, "exp:2") to "0.00e+0",
        Pair(4502148214488346440uL, "exp:3") to "1.000e-7",
        Pair(4638387860618067575uL, "prec:2") to "1.2e+2",
        Pair(4653144502051863213uL, "prec:6") to "1234.57",
        Pair(4548669923058963014uL, "prec:2") to "0.00012",
        Pair(4638355772470722560uL, "prec:5") to "123.00",
        Pair(0uL, "prec:3") to "0.00",
        Pair(4921056587992461136uL, "prec:3") to "1.00e+21",
        Pair(4502148214488346440uL, "prec:2") to "1.0e-7",
    )
}
