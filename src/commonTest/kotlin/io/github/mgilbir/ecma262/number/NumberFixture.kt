// GENERATED FILE - DO NOT EDIT.
// Produced by tools/numbers/gen-fixture.mjs against node v26.5.0.
//
// The sample is walked from an index on both sides rather than stored, so this
// records only a count, a hash of every string node produced, and an explicit
// list for diagnosis. See NumberToStringDifferentialTest.

package io.github.mgilbir.ecma262.number

internal object NumberFixture {
    internal const val ORACLE: String = "node v26.5.0"

    /** Number of strings covered by [SAMPLE_HASH]. */
    internal const val SAMPLE_COUNT: Int = 231948

    /** FNV-1a over every string node produced, in sequence order. */
    internal const val SAMPLE_HASH: UInt = 2046008459u

    /** Raw bit pattern to the string node prints for it. */
    internal val EXPLICIT: List<Pair<ULong, String>> = listOf(
        0UL to "0",
        9223372036854775808UL to "0",
        4607182418800017408UL to "1",
        13830554455654793216UL to "-1",
        4611686018427387904UL to "2",
        4636737291354636288UL to "100",
        13860109328209412096UL to "-100",
        4591870180066957722UL to "0.1",
        4599075939470750515UL to "0.3",
        13832806255468478464UL to "-1.5",
        4616583683022153318UL to "4.35",
        4599676419421066581UL to "0.3333333333333333",
        4696837146684686336UL to "1000000",
        4711630319722168320UL to "10000000",
        4906019910204099648UL to "100000000000000000000",
        4921056587992461136UL to "1e+21",
        4532020583610935537UL to "0.00001",
        4517329193108106637UL to "0.000001",
        4502148214488346440UL to "1e-7",
        4921056587992461135UL to "999999999999999900000",
        4907451598986591450UL to "123456789012345680000",
        4877717327635671425UL to "1234567890123456800",
        4845873199050653696UL to "9007199254740992",
        4845873199050653696UL to "9007199254740992",
        4841369599423283201UL to "4503599627370497",
        9218868437227405311UL to "1.7976931348623157e+308",
        1UL to "5e-324",
        4503599627370496UL to "2.2250738585072014e-308",
        1UL to "5e-324",
        2UL to "1e-323",
        10UL to "5e-323",
        5UL to "2.5e-323",
        9097811302482466869UL to "1.5e+300",
        6103021453049119613UL to "1e+100",
        3110860544497550640UL to "1e-100",
        4683220299150161609UL to "123456.789",
        4517329193108106637UL to "0.000001",
        4292743757239851855UL to "1e-21",
        4602678819172646912UL to "0.5",
        4602678819172646912UL to "0.5",
        9214364837600034816UL to "8.98846567431158e+307",
        4503599627370495UL to "2.225073858507201e-308",
        9218868437227405311UL to "1.7976931348623157e+308",
        1UL to "5e-324",
        4614256656552045848UL to "3.141592653589793",
        4613303445314885481UL to "2.718281828459045",
        4950912855330343670UL to "1e+23",
        4157519394783087270UL to "9.109383e-31",
        4962933279127225623UL to "6.02214076e+23",
        4607632778762754458UL to "1.1",
        4612136378390124954UL to "2.2",
        4614613358185178726UL to "3.3",
        4457293557087583675UL to "1e-10",
        4653144502051863213UL to "1234.5678",
    )
}
