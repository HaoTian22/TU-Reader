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

    @Test fun recordsWithoutApplicationKeepThePlainHeader() {
        assertEquals("SFI 0x18  GEN  Rec 1", RawHexFormatter.header(RawRecord(0x18, 1, "", "00", "")))
    }
}
