package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuLineRulesTest {
    private val bundled = File("src/main/assets/data/line_rules.json")

    /** 东莞实卡 TU 1E 记录（line = 0747 0101 5930 → 747） */
    private val dongguanRecord = ApduUtil.hexToBytes(
        "04000041310085482602074701015930090000000000001A4A20251127162215602011616020FFFFFFFF018018004700"
    )

    private val dongguanRule =
        """{"city": "6020", "when": [{"slice": [0, 1], "in": ["0", "1"]}, {"slice": [4, 8], "eq": "0101"}],
            "value": {"slice": [1, 4], "radix": 10, "min": 1}"""

    private fun ruleFile(vararg rules: String, format: Int = 1) =
        """{"format": $format, "version": "20260101000000", "rules": [${rules.joinToString(",")}]}"""

    @After
    fun reset() = TuLineRules.install(TuLineRules.RuleSet.EMPTY)

    @Test
    fun bundledRulesAllLoadAndPassTheirSamples() {
        val set = TuLineRules.parse(bundled.readText())
        assertEquals(emptyList<String>(), set.dropped)
        assertEquals(14, set.version.length)
    }

    @Test
    fun bundledRulesDecodeRealDongguanRecord() {
        TuLineRules.install(TuLineRules.parse(bundled.readText()))
        val match = TuLineRules.match(dongguanRecord)
        assertEquals("747", match?.line)
        assertEquals("(Line Rule: 6020 #1)", match?.tag)
    }

    @Test
    fun cityCorrectionRequiresMatchingAcquirerAndBusSubtype() {
        TuLineRules.install(TuLineRules.parse(bundled.readText()))

        // 按用户提供的字段构造记录，验证城市纠正与线路规则的组合。
        fun journey(city: String, acquirer: String, line: String, subtype: Int = 0x02) =
            dongguanRecord.copyOf().also {
                it[9] = subtype.toByte()
                ApduUtil.hexToBytes(line).copyInto(it, 10)
                ApduUtil.hexToBytes(city + acquirer).copyInto(it, 32)
            }

        for (city in listOf("2300", "3180", "0000")) {
            for (acquirer in listOf("12273610FFFFFFFF", "0227361000000000")) {
                val record = journey(city, acquirer, "0128")
                assertEquals("3610", TuLineRules.correctedBusCity(record))
                assertEquals("128", TuLineRules.match(record)?.line)
            }
        }
        val taian = journey("0199", "11994630FFFFFFFF", "0933", subtype = 0x13)
        assertEquals("4630", TuLineRules.correctedBusCity(taian))
        assertEquals("33", TuLineRules.match(taian)?.line)
        assertNull(TuLineRules.correctedBusCity(taian.copyOf(41)))
        assertNull(TuLineRules.correctedBusCity(journey("3180", "12273180FFFFFFFF", "0128")))
        assertNull(TuLineRules.correctedBusCity(journey("3180", "12273610FFFFFFFF", "0128", subtype = 0x01)))
        assertEquals("128", TuLineRules.match(journey("3630", "12293630FFFFFFFF", "0128"))?.line)

        val jining = journey("3630", "13694610FFFFFFFF", "026B")
        assertEquals("4610", TuLineRules.correctedBusCity(jining))
        assertNull(TuLineRules.match(jining)?.line)
        for (named in listOf("5E2C", "5E54", "32D3", "F247")) {
            assertNull(TuLineRules.match(journey("1000", "11001000FFFFFFFF", named))?.line)
        }
    }

    @Test
    fun ruleWithUnknownKeyOrOpIsSkippedOthersKept() {
        val set = TuLineRules.parse(ruleFile(
            """{"city": "6020", "future": true, "value": {"slice": [1, 4]}}""",
            """{"city": "6020", "value": {"slice": [1, 4], "ops": [["pow", 2]]}}""",
            "$dongguanRule}"
        ))
        assertEquals(2, set.dropped.size)
        TuLineRules.install(set)
        assertEquals("747", TuLineRules.match(dongguanRecord)?.line)
    }

    @Test
    fun ruleWhoseSampleFailsIsDropped() {
        val set = TuLineRules.parse(ruleFile("""$dongguanRule, "samples": [{"line": "074701015930", "out": "74"}]}"""))
        assertTrue(set.dropped.single().endsWith("sample mismatch"))
        TuLineRules.install(set)
        assertNull(TuLineRules.match(dongguanRecord)?.line)
    }

    @Test
    fun multiCityRuleMatchesEachCityAndTagsTheRecordCity() {
        val rule = dongguanRule.replace("\"city\": \"6020\"", "\"city\": [\"5180\", \"6020\"]") +
            """, "samples": [{"line": "074701015930", "city": "6020", "out": "747"}]}"""
        val set = TuLineRules.parse(ruleFile(rule))
        assertEquals(emptyList<String>(), set.dropped)
        TuLineRules.install(set)
        assertEquals("(Line Rule: 6020 #0)", TuLineRules.match(dongguanRecord)?.tag)
        val otherCity = dongguanRecord.copyOf().also { it[32] = 0x51; it[33] = 0x80.toByte() }
        assertEquals("(Line Rule: 5180 #0)", TuLineRules.match(otherCity)?.tag)
    }

    @Test
    fun city2GuardAndNegatedMissingSlice() {
        // not 条件：city2 = 3052 时不命中；slice 越界（line 只有 12 位）视为不匹配 → not 成立
        val rule = """{"city": "6020", "when": [{"field": "city2", "eq": "3052", "not": true},
            {"slice": [10, 14], "eq": "0000", "not": true}], "value": {"slice": [1, 4], "radix": 10}}"""
        TuLineRules.install(TuLineRules.parse(ruleFile(rule)))
        assertEquals("747", TuLineRules.match(dongguanRecord)?.line)
        val wujiang = dongguanRecord.copyOf().also { it[36] = 0x30; it[37] = 0x52 }
        assertNull(TuLineRules.match(wujiang))
    }

    @Test(expected = IllegalArgumentException::class)
    fun newerFormatIsRejectedWhole() {
        TuLineRules.parse(ruleFile("$dongguanRule}", format = TuLineRules.FORMAT + 1))
    }

    @Test
    fun otherCityOrUnmatchedLayoutGivesNothing() {
        TuLineRules.install(TuLineRules.parse(ruleFile("$dongguanRule}")))
        val otherCity = dongguanRecord.copyOf().also { it[32] = 0x51; it[33] = 0x80.toByte() }
        assertNull(TuLineRules.match(otherCity)?.line)
        val not0101 = dongguanRecord.copyOf().also { it[13] = 0x02 }
        assertNull(TuLineRules.match(not0101)?.line)
    }
}
