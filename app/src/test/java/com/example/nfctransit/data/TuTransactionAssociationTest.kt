package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.example.nfctransit.data.db.ArchivedTransactionEntity
import com.example.nfctransit.model.CanonicalTransaction
import com.example.nfctransit.model.TransitDirection
import org.junit.Assert.*
import org.junit.Test

class TuTransactionAssociationTest {
    private fun tx(id: String, terminal: String, time: String = "120000", balance: Long? = null, sfi: Int = 0x18) = CanonicalTransaction(
        identity = id, sequence = 1, amountFen = 200, balanceAfterFen = balance,
        typeHex = "06", terminal = terminal, stationName = id,
        date = "20260101", time = time, sfi = sfi, protocol = "TU", hex = "00"
    )

    @Test fun timestampMatchesDifferentTerminalsButEachJourneyIsUsedOnce() {
        val journeys = listOf(tx("j-a", "actual-terminal", balance = 300, sfi = 0x1E), tx("j-b", "b", "120002", balance = 400, sfi = 0x1E))
        val fares = listOf(tx("fare-a", "issuer-terminal"), tx("fare-other-time", "b", "120001"), tx("fare-c", "c", "120003"), tx("duplicate-a", "issuer-terminal"))
        val result = RecordDecoder.mergeJourneyAndFare(journeys, fares)
        assertEquals(5, result.size)
        assertEquals(300L, result.first { it.identity == "fare-a" }.balanceAfterFen)
        assertNull(result.first { it.identity == "duplicate-a" }.balanceAfterFen)
        assertNull(result.first { it.identity == "fare-other-time" }.balanceAfterFen)
        assertNull(result.first { it.identity == "fare-c" }.balanceAfterFen)
        assertTrue(result.any { it.identity == "j-b" })
    }

    @Test fun sameTerminalCandidateIsPreferredAtAnIdenticalTimestamp() {
        val journeys = listOf(tx("j-b", "b", balance = 400, sfi = 0x1E), tx("j-a", "a", balance = 300, sfi = 0x1E))
        val result = RecordDecoder.mergeJourneyAndFare(journeys, listOf(tx("fare-a", "a"), tx("fare-b", "b")))
        assertEquals(300L, result.first { it.identity == "fare-a" }.balanceAfterFen)
        assertEquals(400L, result.first { it.identity == "fare-b" }.balanceAfterFen)
    }

    @Test fun historicalFareNeverBorrowsAnotherTimestampBalanceOrOldArchiveFill() {
        val fareHex = "0001000000000001000612345678901220260101120000"
        val journey = "041111123456789012020001000200000000000100000003E82026010212000099000000000000000000000000000000"
        val records = listOf(RecordDecoder.ZoneRecord(0x18, 1, "TU", fareHex), RecordDecoder.ZoneRecord(0x1E, 1, "TU", journey))
        val decoded = RecordDecoder.decodeCard("TU", records, null, 2026)
        assertNull(decoded.display.single { it.sfi == 0x18 }.balanceAfterFen)
        val row = ArchivedTransactionEntity(cardId = "c", sfi = "0x18", protocol = "TU", hex = fareHex,
            contentHash = RecordDecoder.contentHash(fareHex), resolvedDate = "20260101", balanceAfterFen = 9999,
            firstSeenAt = 1, lastSeenAt = 1)
        assertNull(RecordDecoder.decodeArchive("TU", listOf(row)).single().balanceAfterFen)
    }

    @Test fun tuFullAmountsArePreservedAndCuExistingLayoutIsRetained() {
        val fare = "0001000000010000010612345678901220260101120000"
        assertEquals(16777217L, RecordDecoder.decodeCard("TU", listOf(RecordDecoder.ZoneRecord(0x18, 1, "TU", fare)), null, 2026).display.single().amountFen)
        assertEquals(1L, RecordDecoder.decodeCard("CU", listOf(RecordDecoder.ZoneRecord(0x18, 1, "CU", fare)), null, 2026).display.single().amountFen)
        val data = ByteArray(48)
        data[0] = 4
        ApduUtil.hexToBytes("00010001").copyInto(data, 17)
        ApduUtil.hexToBytes("20260101120000").copyInto(data, 25)
        assertEquals(65537L, RecordDecoder.decodeCard("TU", listOf(RecordDecoder.ZoneRecord(0x1E, 1, "TU", ApduUtil.bytesToHex(data))), null, 2026).display.single().amountFen)
    }

    @Test fun ecDirectionsAreAddedWithoutChangingEpCompatibilityRules() {
        assertEquals(TransitDirection.ENTRY, RecordDecoder.tuDirectionForType(1))
        assertEquals(TransitDirection.EXIT, RecordDecoder.tuDirectionForType(2))
        assertEquals(TransitDirection.ENTRY, RecordDecoder.tuDirectionForType(6, "地铁"))
        assertNull(RecordDecoder.tuDirectionForType(6, "公交"))
    }

    @Test fun differentSecondsAreNeverMergedAcrossApplications() {
        val first = tx("cu", "a", "120000").copy(protocol = "CU")
        val second = tx("tu", "a", "120001")
        assertEquals(2, RecordDecoder.mergeForDisplay(listOf(first, second)).size)
    }

    @Test fun sharedEpEcJourneyCopiesDoNotLeaveAnUnmatchedDuplicate() {
        val fare = "0001000000000001000612345678901220260101120000"
        val journey = "041111123456789012020001000200000000000100000003E82026010112000099000000000000000000000000000000"
        val records = listOf(RecordDecoder.ZoneRecord(0x18, 1, "TU", fare),
            RecordDecoder.ZoneRecord(0x1E, 1, "TU", journey), RecordDecoder.ZoneRecord(0x1E, 1, "TU", journey))
        val decoded = RecordDecoder.decodeCard("TU", records, null, 2026)
        assertEquals(1, decoded.display.size)
        assertEquals(1000L, decoded.display.single().balanceAfterFen)
    }
}
