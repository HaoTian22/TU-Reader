package com.example.nfctransit.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class RawRecordEntityTest {
    private fun row(aid: String, sfi: String = "0x18") = RawRecordEntity(
        cardId = "card", sfi = sfi, recNo = 1, selectedAid = aid,
        hex = "1234", contentHash = "hash", firstSeenAt = 1, lastSeenAt = 2
    )

    @Test fun applicationsRestoreWalletProtocolWithoutChangingAidOrContents() {
        val aids = mapOf(
            "5041592E41505059" to "LNT", "5041592E5449434C" to "LNT",
            "A000000632010105" to "TU", "A000000632010106" to "TU",
            "A00000000386980701" to "CU", "5041592E535A54" to "SZT"
        )
        for ((aid, protocol) in aids) {
            val raw = row(aid).toRawRecord()
            assertEquals(aid, raw.selectedAid)
            assertEquals(protocol, raw.protocol)
            assertEquals(0x18, raw.sfi)
            assertEquals(1, raw.recNo)
            assertEquals("1234", raw.hex)
        }
    }

    @Test fun genericWalletsKeepExistingArchiveProtocolAndMetadataLabels() {
        val aids = mapOf(
            "535558494E2E4444463031" to "SUXIN", "535A504B5F5A5959" to "SZTK",
            "D156000015B9ABB9B2D3A6D3C3" to "TFT"
        )
        for ((aid, cardType) in aids) {
            assertEquals("", row(aid).toRawRecord().protocol)
            assertEquals(cardType, row(aid, "0x15").toRawRecord().protocol)
        }
    }

    @Test fun unknownApplicationKeepsItsIdentityForLaterSupport() {
        val raw = row("UNKNOWN-AID").toRawRecord()
        assertEquals("UNKNOWN-AID", raw.selectedAid)
        assertEquals("", raw.protocol)
    }
}
