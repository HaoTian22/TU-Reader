package com.example.nfctransit.ui

import com.example.nfctransit.data.RawRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class RawHexHeaderTest {
    // 5882 卡（岭南通 + TU 双协议）实际读到的应用
    @Test fun headerNamesTheApplicationAfterTheProtocol() {
        assertEquals(
            "SFI 0x15  LNT (PAY.APPY)  Rec 0",
            RawHexFormatter.header(RawRecord(0x15, 0, "LNT", "00", "5041592E41505059"))
        )
        assertEquals(
            "SFI 0x18  LNT (PAY.TICL)  Rec 1",
            RawHexFormatter.header(RawRecord(0x18, 1, "LNT", "00", "5041592E5449434C"))
        )
        assertEquals(
            "SFI 0x19  TU (A000000632010105)  Rec 1",
            RawHexFormatter.header(RawRecord(0x19, 1, "TU", "00", "A000000632010105"))
        )
        assertEquals(
            "SFI 0x1E  TU (A000000632010106)  Rec 1",
            RawHexFormatter.header(RawRecord(0x1E, 1, "TU", "00", "A000000632010106"))
        )
    }

    @Test fun transactionDetailBlocksUseTheSameApplicationLabel() {
        assertEquals("SFI 0x18  LNT (PAY.TICL)", RawHexFormatter.blockTitle(0x18, "LNT", "5041592E5449434C"))
        assertEquals("SFI 0x1E  TU (A000000632010105)", RawHexFormatter.blockTitle(0x1E, "TU", "A000000632010105"))
        assertEquals("SFI 0x18  TU", RawHexFormatter.blockTitle(0x18, "TU", null))
        assertEquals("SFI 0x18", RawHexFormatter.blockTitle(0x18, "", null))
    }

    // 5882 卡 TU 应用实际读到的 0x19 rec1 / 0x17
    private val tuStats = "012E" + "00".repeat(46)
    private val tuManagement = "000001565800581000010100" + "00".repeat(48)

    @Test fun monthStatsLabelledOnlyOnRecordOne() {
        val size = tuStats.length / 2
        val labels = RawHexFormatter.fieldsFor(0x19, size, "TU", tuStats, recNo = 1).map { it.label }
        assertEquals(
            listOf("Record Identifier", "Record Length", "Stats Year", "Stats Month", "Trip Count", "Month Total (fen)"),
            labels
        )
        assertEquals(emptyList<String>(), RawHexFormatter.fieldsFor(0x19, size, "TU", tuStats, recNo = 2).map { it.label })
        // 岭南通 PAY.TICL 0x08 rec1 同一格式；0x08 其它记录（如 #17）不标注
        assertEquals(labels, RawHexFormatter.fieldsFor(0x08, 22, "LNT", "00".repeat(22), recNo = 1).map { it.label })
        assertEquals(emptyList<String>(), RawHexFormatter.fieldsFor(0x08, 86, "LNT", "00".repeat(86), recNo = 17).map { it.label })
    }

    @Test fun tuManagementFieldsMatchMetadataParser() {
        val fields = RawHexFormatter.fieldsFor(0x17, tuManagement.length / 2, "TU", tuManagement, recNo = 0)
        assertEquals("00000156", RawHexFormatter.hexRange(tuManagement, fields[0].start, fields[0].end))  // Country
        assertEquals("5800", RawHexFormatter.hexRange(tuManagement, fields[1].start, fields[1].end))      // Province
        assertEquals("5810", RawHexFormatter.hexRange(tuManagement, fields[2].start, fields[2].end))      // City
        assertEquals("0001", RawHexFormatter.hexRange(tuManagement, fields[3].start, fields[3].end))      // Interoperability
        assertEquals("01", RawHexFormatter.hexRange(tuManagement, fields[4].start, fields[4].end))        // Card kind
    }

    @Test fun recordsWithoutApplicationKeepThePlainHeader() {
        assertEquals("SFI 0x18  GEN  Rec 1", RawHexFormatter.header(RawRecord(0x18, 1, "", "00", "")))
    }
}
