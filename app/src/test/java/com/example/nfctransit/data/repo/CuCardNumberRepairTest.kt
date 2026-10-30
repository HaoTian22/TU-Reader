package com.example.nfctransit.data.repo

import com.example.nfctransit.CardProfiles
import com.example.nfctransit.data.RawRecord
import org.junit.Assert.*
import org.junit.Test

class CuCardNumberRepairTest {
    private fun info(aid: String, hex: String) = RawRecord(0x15, 0, "", hex, aid)
    @Test fun cuRepairIgnoresEarlierTuInfoAndRequiresCuAid() {
        val tu = info(CardProfiles.TU_EP_AID, "00".repeat(12) + "FFFFFFFFFFFFFFFF")
        val cu = info(CardProfiles.CU_AID.lowercase(), "00".repeat(12) + "000000000000007B")
        assertEquals("123", cuCardNumberFromRaw(listOf(tu, cu)))
        assertNull(cuCardNumberFromRaw(listOf(tu)))
        assertNull(cuCardNumberFromRaw(listOf(cu.copy(hex = "0000"))))
    }
}
