package com.example.nfctransit.data

import com.example.nfctransit.CardProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 实卡数据：岭南通 …5882（LNT PAY.APPY/PAY.TICL + TU EP，PSE 另列出 TU EC）。 */
class YctRealCardTest {
    private val appy = "5041592E41505059"
    private val ticl = "5041592E5449434C"
    private val ep = CardProfiles.TU_EP_AID

    private val lntInfo = "FFFFFFFFFFFFFFFF5100009534635882030002FFFFFFFF202406212034123120240621438188010100000000052B000101015100009534635882030000000000FF8000FFFFFF"
    private val epInfo = "02205810FFFFFFFF02010310487039534635882720240715204012150000"
    private val ecSelect = "6F708408A000000632010106A564500A4D4F545F545F434153488701019F381EDF60019F66049F02069F03069F1A0295055F2A029A039C019F21039F3704BF0C31DF6101829F4D020B0ADF4D020C0ADF112002205810FFFFFFFF013104870395346358827F01010000015658005810000100"
    private val epFare = "0001000000000000640941310177670320241027212555"
    private val journey = "06000041310177670302030100007835090000006400000F6420241027212555783511355850FFFFFFFF000000000000"
    private val lntFares = listOf(
        "000B000000000000640601003006868700641027211308",
        "000A000000000000640601003000878000641027203357",
        "0009000000000000640601003006810200641027194808",
        "00080000000000008C0901003005324200C80823144018",
        "0007000000000002580901000022891902580823125617",
        "0006000000000000000901000022889800000823124011",
        "0004000000000007D00288012003262807D00823122814",
        "00050000000000008C0901003005358000C80823105759",
        "0004000000000001180901003005201601900823101017",
        "0003000000000000640901000022029500640816134817",
        "0002000000000001F40901000022193101F40812132617",
        "0001000000000000000901000022193700000812125011"
    )
    private val empty18 = "0".repeat(46)
    private val empty1E = "0".repeat(96)

    private fun records(): List<RawRecord> = buildList {
        add(RawRecord(0x15, 0, "LNT", lntInfo, appy))
        lntFares.forEachIndexed { index, hex -> add(RawRecord(0x18, index + 1, "LNT", hex, ticl)) }
        add(RawRecord(0x15, 0, "TU", epInfo, ep))
        add(RawRecord(0x18, 1, "TU", epFare, ep))
        for (recNo in 2..10) add(RawRecord(0x18, recNo, "TU", empty18, ep))
        add(RawRecord(0x19, 1, "TU", "012E" + "0".repeat(92), ep))
        add(RawRecord(0x1E, 1, "TU", journey, ep))
        for (recNo in 2..30) add(RawRecord(0x1E, recNo, "TU", empty1E, ep))
    }

    @Test fun lntApplicationMetadataComesFromTheLntInfoFile() {
        val lnt = CardMetadataParser.parse("LNT", records())
        assertEquals("01015100", lnt.issuer)
        assertEquals("2024-06-21", lnt.issueDate)
        assertEquals("2034-12-31", lnt.validUntil)
    }

    @Test fun tuApplicationMetadataCombinesEpInfoAndEcDf11() {
        val tu = CardMetadataParser.parse("TU", records(), ecSelect)
        assertEquals("02205810FFFFFFFF", tu.issuer)
        assertEquals("2024-07-15", tu.issueDate)
        assertEquals("2040-12-15", tu.validUntil)
        assertEquals("01", tu.applicationVersion)
        // 无 0x17 管理文件时由 EC DF11 补齐。
        assertEquals("5810", tu.cityCode)
        assertEquals(1, tu.cardKind)
        assertEquals("00000156", tu.countryCode)
        assertEquals("5800", tu.provinceCode)
        assertEquals("0001", tu.interoperabilityCode)
        assertEquals(true, tu.interoperabilityEnabled)
    }

    @Test fun decodesLntFaresAndMergesTuFareWithItsJourney() {
        val zone = RecordDecoder.transactionRecords("YCT", records())
        assertTrue(zone.none { it.sfi == 0x15 || it.sfi == 0x19 })
        val decoded = RecordDecoder.decodeCard("YCT", zone, statsMonth = 202410, currentYear = 2026)

        assertEquals(13, decoded.display.size)
        val tu = decoded.display.single { it.protocol == "TU" }
        assertEquals("20241027", tu.date)
        assertEquals("212555", tu.time)
        assertEquals(100L, tu.amountFen)
        assertEquals(0xF64L, tu.balanceAfterFen)
        assertEquals(journey, tu.journeyHex)

        val lnt = decoded.display.filter { it.protocol == "LNT" }
        assertEquals(12, lnt.size)
        assertTrue(lnt.all { it.balanceAfterFen == null })
        assertEquals(setOf("20241027", "20240823", "20240816", "20240812"), lnt.map { it.date }.toSet())
        assertEquals(0x7D0L, lnt.single { it.typeHex == "02" }.amountFen)
        // 归档：12 条 LNT + TU 0x18 + TU 0x1E 旅程。
        assertEquals(14, decoded.archive.size)
        assertNull(decoded.archive.firstOrNull { it.protocol == "TU" && it.sfi == 0x18 && it.amountFen != 100L })
    }

    /**
     * 实卡统计记录 0x08 #1 = 18140024080100…（2024-08）：统计月份只表示该月有广州交易，
     * 10 月外地交易不刷新它。旧算法以它为最新交易的上界回推得 2023-10-27（早于发卡日期 2024-06-21）；
     * 现在要求至少一条记录落在统计月份：8 月记录 = 2024-08，10 月在其后 = 2024-10。
     * TU 侧同一晚的记录 20241027 212555 证实应为 2024。
     */
    @Test fun lntYearPutsATransactionInTheStatsMonth() {
        val raw = records()
        assertEquals("20240621", RecordDecoder.lntIssueDate(raw))
        val zone = RecordDecoder.transactionRecords("YCT", raw)
        val expected = setOf("20241027", "20240823", "20240816", "20240812")

        val withoutIssue = RecordDecoder.decodeCard("YCT", zone, statsMonth = 202408, currentYear = 2026)
        assertEquals(expected, withoutIssue.display.filter { it.protocol == "LNT" }.map { it.date }.toSet())

        val decoded = RecordDecoder.decodeCard(
            "YCT", zone, statsMonth = 202408, currentYear = 2026,
            lntIssueDate = RecordDecoder.lntIssueDate(raw)
        )
        val lnt = decoded.display.filter { it.protocol == "LNT" }
        assertEquals(setOf("20241027", "20240823", "20240816", "20240812"), lnt.map { it.date }.toSet())
    }

    /** 手机归档里旧版推断落库的 2023 日期，按发卡日期上调后展示。 */
    @Test fun archivedLntDatesBeforeIssueDateAreRaised() {
        val rows = lntFares.map { hex ->
            RecordDecoder.ArchivedRecord(0x18, ticl, hex, "2023" + hex.substring(36, 40))
        }
        val decoded = RecordDecoder.decodeArchive("YCT", rows, "20240621")
        assertEquals(setOf("20241027", "20240823", "20240816", "20240812"), decoded.map { it.date }.toSet())
    }

    @Test fun alignmentLowerBoundIsRaisedToTheIssueDate() {
        val zone = RecordDecoder.transactionRecords("YCT", records())
        val fresh = RecordDecoder.decodeCard(
            "YCT", zone, statsMonth = 202408, currentYear = 2026, lntIssueDate = "20240621"
        ).archive.filter { it.protocol == "LNT" && it.date == "20241027" }
        // 已归档最新 LNT 行为旧版推断的 20231027：下界上调为 20241027，新记录不被拉回 2023。
        val aligned = RecordDecoder.alignLntYears(fresh, "20231027", today = "20261031", lntIssueDate = "20240621")
        assertTrue(aligned.all { it.date == "20241027" })
    }
}
