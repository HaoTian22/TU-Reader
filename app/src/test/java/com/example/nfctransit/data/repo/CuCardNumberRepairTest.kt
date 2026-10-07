package com.example.nfctransit.data.repo

import com.example.nfctransit.CardProfiles
import com.example.nfctransit.data.db.RawRecordEntity
import org.junit.Assert.*
import org.junit.Test

class CuCardNumberRepairTest {
    private fun info(aid: String, hex: String) = RawRecordEntity(
        cardId = "card", sfi = "0x15", recNo = 0, selectedAid = aid, hex = hex,
        contentHash = "hash", firstSeenAt = 1, lastSeenAt = 1
    )
    @Test fun cuRepairIgnoresEarlierTuInfoAndRequiresCuAid() {
        val tu = info(CardProfiles.TU_EP_AID, "00".repeat(12) + "FFFFFFFFFFFFFFFF")
        val cu = info(CardProfiles.CU_AID.lowercase(), "00".repeat(12) + "000000000000007B")
        assertEquals("123", cuCardNumberFromRaw(listOf(tu, cu)))
        assertNull(cuCardNumberFromRaw(listOf(tu)))
        assertNull(cuCardNumberFromRaw(listOf(cu.copy(hex = "0000"))))
    }
}
