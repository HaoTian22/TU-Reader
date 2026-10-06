package com.example.nfctransit.data.repo

import com.example.nfctransit.data.RecordDecoder
import com.example.nfctransit.data.db.ArchivedTransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripReaderProtocolTest {
    private val shanghaiHex = "00540006A4000000000920001109311120260823150028"
    private val transaction = TripReaderTransaction(0, shanghaiHex, "", false)
    private val cuCard = TripReaderCard("test", "", 0, "", false, true, false, false, false,
        listOf(transaction))

    @Test
    fun cuFareWithFullYearRemainsCuForSingleAndDualProtocolCards() {
        for (isTu in listOf(false, true)) {
            for (isLnt in listOf(false, null)) {
                assertEquals("CU", tripReaderFareProtocol(cuCard.copy(isTU = isTu),
                    transaction.copy(isLNT = isLnt)))
            }
        }
    }

    @Test
    fun otherWalletProtocolsKeepTheirImportRules() {
        assertEquals("SZT", tripReaderFareProtocol(cuCard.copy(isSZT = true), transaction))
        assertEquals("TU", tripReaderFareProtocol(cuCard.copy(isCU = false, isTU = true), transaction))
        val lntCard = cuCard.copy(isCU = false, isYCT = true, isLNT = true)
        assertEquals("LNT", tripReaderFareProtocol(lntCard, transaction.copy(isLNT = true)))
        assertEquals("TU", tripReaderFareProtocol(lntCard.copy(isTU = true), transaction))
    }

    @Test
    fun importedCuRepairOnlySelectsMislabeledMainTransactions() {
        val fare = archived("TU", "0x18", shanghaiHex)
        val journey = archived("TU", "0x1E", "00")
        val cu = fare.copy(protocol = "CU")
        assertEquals(listOf(fare), importedCuArchiveRepairs("CU", false, false, listOf(fare, journey, cu)))
        assertTrue(importedCuArchiveRepairs("CU", false, false, listOf(journey, cu)).isEmpty())
    }

    @Test
    fun repairPreservesTuWalletsWithNfcEvidenceAndOtherCardTypes() {
        val fare = archived("TU", "0x18", shanghaiHex)
        for ((type, raw, app) in listOf(Triple("TU", false, false), Triple("CU", true, false),
            Triple("CU", false, true))) {
            assertTrue(importedCuArchiveRepairs(type, raw, app, listOf(fare)).isEmpty())
        }
    }

    private fun archived(protocol: String, sfi: String, hex: String) = ArchivedTransactionEntity(
        rowId = 7, cardId = "test", protocol = protocol, sfi = sfi, hex = hex,
        contentHash = RecordDecoder.contentHash(hex), resolvedDate = "20260823",
        firstSeenAt = 1, lastSeenAt = 2
    )
}
