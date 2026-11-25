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

    /** 东莞实卡 TU 1E 记录（线路站点字段 = 0747 0101 5930 → 747） */
    private val dongguanHex =
        "04000041310085482602074701015930090000000000001A4A20251127162215602011616020FFFFFFFF018018004700"
    private val dongguanRecord = ApduUtil.hexToBytes(dongguanHex)

    private val dongguanWhen = """{"target": "6020"},
        {"rec": "1E", "nibbles": [20, 21], "in": ["0", "1"]}, {"rec": "1E", "nibbles": [24, 28], "eq": "0101"}"""

    private fun dongguanLine(id: String = "dg", line: String = """{"rec": "1E", "nibbles": [21, 24], "radix": 10, "min": 1}""") =
        """{"id": "$id", "when": [$dongguanWhen], "then": {"line": $line}}"""

    private fun ruleFile(vararg rules: String, format: Int = TuLineRules.FORMAT, version: String = "20260101000000") =
        """{"format": $format, "version": "$version", "rules": [${rules.joinToString(",")}]}"""

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
        assertEquals("dongguan-line", match?.ruleId)
        assertEquals("(SP Rule: dongguan-line)", TuLineRules.label(TuLineRules.solve(dongguanRecord, null), match))
    }

    @Test
    fun lineValueAppendsOptionalPrefixAndSuffix() {
        val cases = listOf(
            """{"rec": "1E", "nibbles": [21, 24], "prefix": "专", "append": "路"}""" to "专747路",
            """{"rec": "1E", "nibbles": [21, 24], "append": "路"}""" to "747路",
            "\"固定名称\"" to "固定名称"
        )
        for ((line, expected) in cases) {
            val set = TuLineRules.parse(ruleFile(dongguanLine(line = line)))
            assertTrue(set.dropped.isEmpty())
            TuLineRules.install(set)
            assertEquals(expected, TuLineRules.match(dongguanRecord)?.line)
        }
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
                assertEquals("3610", TuLineRules.solve(record, null)?.city)
                assertEquals("128", TuLineRules.match(record)?.line)
            }
        }
        val taian = journey("0199", "11994630FFFFFFFF", "0933", subtype = 0x13)
        assertEquals("4630", TuLineRules.solve(taian, null)?.city)
        assertEquals("33", TuLineRules.match(taian)?.line)
        assertNull(TuLineRules.solve(taian.copyOf(41), null)?.city)
        assertNull(TuLineRules.solve(journey("3180", "12273180FFFFFFFF", "0128"), null)?.city)
        assertNull(TuLineRules.solve(journey("3180", "12273610FFFFFFFF", "0128", subtype = 0x01), null)?.city)
        assertEquals("128", TuLineRules.match(journey("3630", "12293630FFFFFFFF", "0128"))?.line)

        val jining = journey("3630", "13694610FFFFFFFF", "026B")
        assertEquals("4610", TuLineRules.solve(jining, null)?.city)
        assertNull(TuLineRules.match(jining)?.line)
    }

    @Test
    fun ruleWithUnknownKeyOrOpIsDroppedOthersKept() {
        val set = TuLineRules.parse(ruleFile(
            """{"id": "a", "future": true, "when": [{"target": "6020"}], "then": {"line": "x"}}""",
            dongguanLine("b", """{"rec": "1E", "nibbles": [21, 24], "ops": [["pow", 2]]}"""),
            dongguanLine("c")
        ))
        assertEquals(setOf("a", "b"), set.droppedIds.keys)
        TuLineRules.install(set)
        assertEquals("747", TuLineRules.match(dongguanRecord)?.line)
    }

    @Test
    fun ruleWhoseSampleFailsIsDropped() {
        val rule = """{"id": "dg", "when": [$dongguanWhen],
            "then": {"line": {"rec": "1E", "nibbles": [21, 24], "radix": 10}},
            "samples": [{"1E": "$dongguanHex", "expect": {"line": "74"}}]}"""
        val set = TuLineRules.parse(ruleFile(rule))
        assertTrue(set.dropped.single().endsWith("sample mismatch"))
        TuLineRules.install(set)
        assertNull(TuLineRules.match(dongguanRecord))
    }

    @Test
    fun partialSampleUsesOnlyKnownNibbles() {
        // 只知线路字段与城市码的样本：其余半字节为 '?'，读到未知半字节的条件不成立
        val partial = "?".repeat(20) + "074701015930" + "?".repeat(32) + "6020" + "?".repeat(28)
        val ok = """{"id": "dg", "when": [$dongguanWhen],
            "then": {"line": {"rec": "1E", "nibbles": [21, 24], "radix": 10}},
            "samples": [{"1E": "$partial", "expect": {"line": "747"}}]}"""
        assertEquals(emptyList<String>(), TuLineRules.parse(ruleFile(ok)).dropped)
        val readsUnknown = ok.replace(""""eq": "0101"}""", """"eq": "0101"}, {"rec": "1E", "bytes": [0, 1], "eq": "04"}""")
        assertTrue(TuLineRules.parse(ruleFile(readsUnknown)).dropped.single().endsWith("sample mismatch"))
    }

    @Test
    fun lineRulesTryTargetsInPlanOrder() {
        // 求解规则把目标改为 5180 → 6020：线路号按目标顺序查，命中 6020 的规则
        val route = """{"id": "route", "when": [{"rec": "1E", "bytes": [32, 34], "eq": "6020"}],
            "then": {"targets": [{"prefix": "5180"}, {"prefix": "6020"}]}}"""
        TuLineRules.install(TuLineRules.parse(ruleFile(route, dongguanLine())))
        val match = TuLineRules.match(dongguanRecord)
        assertEquals("dg", match?.ruleId)
        assertEquals("(SP Rule: route, dg)", TuLineRules.label(TuLineRules.solve(dongguanRecord, null), match))
    }

    @Test
    fun negatedConditionHoldsWhenSliceIsMissing() {
        // byte 36–37 = 3052 时不成立；nibbles 越界（记录只有 96 位 hex）视为不匹配 → not 成立
        val rule = """{"id": "dg", "when": [{"target": "6020"},
            {"rec": "1E", "bytes": [36, 38], "eq": "3052", "not": true},
            {"rec": "1E", "nibbles": [200, 204], "eq": "0000", "not": true}],
            "then": {"line": {"rec": "1E", "nibbles": [21, 24], "radix": 10}}}"""
        TuLineRules.install(TuLineRules.parse(ruleFile(rule)))
        assertEquals("747", TuLineRules.match(dongguanRecord)?.line)
        val wujiang = dongguanRecord.copyOf().also { it[36] = 0x30; it[37] = 0x52 }
        assertNull(TuLineRules.match(wujiang))
    }

    @Test
    fun rawSliceConditionsReadBytesNibblesAndMissingFare() {
        val rule = """{"id": "dg", "when": [
            {"rec": "1E", "bytes": [32, 34], "eq": "6020"},
            {"rec": "1E", "nibbles": [21, 22], "eq": "7"},
            {"rec": "18", "bytes": [0, 1], "regex": ".", "not": true}], "then": {"family": "bus"}}"""
        TuLineRules.install(TuLineRules.parse(ruleFile(rule)))
        assertEquals("(SP Rule: dg)", TuLineRules.solve(dongguanRecord, null)?.label)
        // 有配对 0x18 时 not 条件不再成立
        assertNull(TuLineRules.solve(dongguanRecord, ByteArray(23) { 1 })?.label)
        // 只有 0x18：1E 条件不成立，且无 targets → 无计划
        assertNull(TuLineRules.solve(null, ByteArray(23) { 1 }))
    }

    @Test
    fun matchingRulesLayerFieldByField() {
        val targets = """{"id": "t", "when": [{"rec": "1E", "bytes": [32, 34], "eq": "6020"}],
            "then": {"targets": [{"prefix": "6020"}, {"prefix": "5180"}], "family": "rail"}}"""
        val city = """{"id": "c", "when": [{"rec": "1E", "bytes": [36, 38], "eq": "6020"}],
            "then": {"city": "6020", "family": "bus"},
            "samples": [{"1E": "$dongguanHex", "expect": {"city": "6020", "targets": ["6020", "5180"]}}]}"""
        val set = TuLineRules.parse(ruleFile(targets, city))
        assertEquals(emptyList<String>(), set.dropped)
        TuLineRules.install(set)
        val plan = TuLineRules.solve(dongguanRecord, null)!!
        assertEquals(listOf("t", "c"), plan.ruleIds)
        assertEquals(listOf("6020", "5180"), plan.targets.map { it.code })
        assertEquals("6020", plan.city)
        assertEquals(TransitData.TuTransitFamily.RAIL, plan.family)
        assertEquals("(SP Rule: t, c)", plan.label)
    }

    @Test
    fun droppedRuleFallsBackToBundledRuleWithSameIdInPlace() {
        val a = """{"id": "a", "when": [{"rec": "1E", "bytes": [32, 34], "eq": "6020"}], "then": {"city": "1001"}}"""
        val b = """{"id": "b", "when": [{"rec": "1E", "bytes": [32, 34], "eq": "6020"}], "then": {"city": "1002"}}"""
        val badA = """{"id": "a", "when": [{"rec": "1E", "bytes": [32, 34], "eq": "6020"}], "then": {"city": "1003"},
            "samples": [{"1E": "$dongguanHex", "expect": {"city": "9999"}}]}"""
        val bundledSet = TuLineRules.parse(ruleFile(a, b, version = "1"))
        val downloaded = TuLineRules.parse(ruleFile(badA, b, version = "2"))
        assertEquals(listOf("b"), downloaded.rules.map { it.id })
        TuLineRules.install(downloaded.withFallback(bundledSet))
        assertEquals(listOf("a", "b"), TuLineRules.solve(dongguanRecord, null)?.ruleIds)
        assertEquals("1001", TuLineRules.solve(dongguanRecord, null)?.city)
    }

    @Test
    fun malformedRulesAreDropped() {
        val set = TuLineRules.parse(ruleFile(
            """{"id": "a", "when": [{"rec": "1F", "bytes": [0, 1], "eq": "00"}], "then": {"family": "bus"}}""",
            """{"id": "b", "when": [{"rec": "1E", "slice": [0, 1], "eq": "00"}], "then": {"family": "bus"}}""",
            """{"id": "c", "when": [{"rec": "1E", "bytes": [0, 1], "eq": "00"}], "then": {}}""",
            """{"id": "d", "when": [{"rec": "1E", "bytes": [0, 1], "eq": "00"}], "then": {"targets": [{"prefix": "6020", "fallbackBelow": 8}]}}""",
            """{"id": "e", "when": [{"target": "6020"}], "then": {"city": "6020"}}""",
            """{"id": "f", "when": [{"rec": "1E", "bytes": [0, 1], "eq": "00"}], "then": {"targets": [{"prefix": "6020", "city": "6020"}]}}""",
            """{"id": "a", "when": [{"rec": "1E", "bytes": [0, 1], "eq": "00"}], "then": {"family": "bus"}}"""
        ))
        assertEquals(7, set.dropped.size)
        assertEquals(setOf("a", "b", "c", "d", "e", "f"), set.droppedIds.keys)
    }

    @Test(expected = IllegalArgumentException::class)
    fun otherFormatIsRejectedWhole() {
        TuLineRules.parse(ruleFile(dongguanLine(), format = 1))
    }
}
