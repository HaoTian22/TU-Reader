package com.example.nfctransit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransitOverrideDuplicatesTest {
    private fun row(line: String, station: String, city: String? = "0756") =
        TransitOverrideRow("5850", "413100846791", "公交", line, station, city)

    private fun classify(row: TransitOverrideRow, line: String, station: String, city: String = "0756") =
        TransitOverrideDuplicates.classify(row, city, line, station)

    @Test
    fun identicalLineAndStationIsSame() {
        assertEquals(OverrideDuplicateKind.SAME, classify(row("1号线", "车公庙"), "1号线", "车公庙"))
        assertEquals(OverrideDuplicateKind.SAME, classify(row("14", ""), "14", ""))
    }

    @Test
    fun spellingVariantsAreSimilar() {
        assertEquals(OverrideDuplicateKind.SIMILAR, classify(row("1号线", "车公庙"), "地铁1号线", "车公庙站"))
        assertEquals(OverrideDuplicateKind.SIMILAR, classify(row("Ｚ53", "拱北口岸"), "Z53", "拱北 口岸"))
        // 映射只纠正了站名、未填线路：库里线路有值仍算相近
        assertEquals(OverrideDuplicateKind.SIMILAR, classify(row("", "车公庙"), "1号线", "车公庙"))
    }

    @Test
    fun realCorrectionsAreNotDuplicates() {
        // 站名不同
        assertNull(classify(row("1号线", "车公庙"), "1号线", "竹子林"))
        // 同站名但线路被纠正
        assertNull(classify(row("2号线", "车公庙"), "1号线", "车公庙"))
        // 指定了不同城市（纠正位置）
        assertNull(classify(row("14", "", city = "0757"), "14", ""))
        // 库里没有站名，映射补了站名
        assertNull(classify(row("14", "香洲"), "14", ""))
    }

    @Test
    fun missingLocationCityStillMatches() {
        assertEquals(OverrideDuplicateKind.SAME, classify(row("14", "", city = null), "14", ""))
    }
}
