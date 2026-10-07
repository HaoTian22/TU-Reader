package com.example.nfctransit

import com.example.nfctransit.data.RecordDecoder
import com.example.nfctransit.data.db.ArchivedTransactionEntity
import com.example.nfctransit.data.toSfiHex
import org.junit.Assert.*
import org.junit.Test

class TuElectronicCashTest {
    private fun tlv(tag: String, value: String) = tag + "%02X".format(value.length / 2) + value
    private val format = "9F02069A039F21035F2A029F1C089F3602"
    private val log = "0000000012342601071234560156" + "3132333435363738" + "0007"
    private class FakeChannel(val reply: (String) -> String) : CardChannel {
        override var timeout = 3000
        val commands = mutableListOf<String>()
        override fun connect() {}
        override fun close() {}
        override fun transceive(command: ByteArray): ByteArray {
            val hex = ApduUtil.bytesToHex(command)
            commands.add(hex)
            return ApduUtil.hexToBytes(reply(hex))
        }
    }

    private fun channel(ep: Boolean = false, balance: String = "9F79060000000043219000"): FakeChannel {
        val ecSelect = tlv("6F", tlv("A5", tlv("BF0C", tlv("9F4D", "0B01") + tlv("DF4D", "0C01")))) + "9000"
        return FakeChannel { cmd -> when (cmd) {
            ApduUtil.bytesToHex(ApduUtil.buildSelectByName(CardProfiles.TU_EP_AID)) -> if (ep) "9000" else "6A82"
            ApduUtil.bytesToHex(ApduUtil.buildSelectByName(CardProfiles.TU_EC_AID)) -> ecSelect
            "00B0950000" -> if (ep) "00010001FFFFFFFF020162112233445566778899202501012035010100009000" else "6A82"
            "805C000204" -> if (ep) "000010E19000" else "6A82"
            "00B2011400" -> tlv("70", tlv("5A", "6211223344556677889F") + tlv("5F25", "250101") + tlv("5F24", "350101")) + "9000"
            "80CA9F7900" -> balance
            "80CA9F4F00" -> tlv("9F4F", format) + "9000"
            "80CADF4F00" -> tlv("DF4F", format) + "9000"
            "00B2015C00", "00B2016400" -> log + "9000"
            else -> "6A82"
        } }
    }

    @Test fun ecOnlyReadsPanDatesBalanceAndBothDeclaredLogs() {
        val channel = channel()
        val result = TransitCardReader(channel).read()
        assertEquals("6211223344556677889", result.cardInfo?.cardNumber)
        assertEquals("20250101", result.cardInfo?.validFrom)
        assertEquals("20350101", result.cardInfo?.validTo)
        assertEquals(4321L, result.balanceFen)
        assertFalse(channel.commands.contains("805C000204"))
        assertFalse(channel.commands.contains("00B0950000"))
        assertTrue(result.rawRecords.all { it.selectedAid == CardProfiles.TU_EC_AID })
        val records = RecordDecoder.transactionRecords("TU", result.rawRecords)
        val decoded = RecordDecoder.decodeCard("TU", records, null, 2026)
        assertEquals(2, decoded.archive.size)
        for (transaction in decoded.archive) {
            assertEquals(1234L, transaction.amountFen)
            assertEquals("20260107", transaction.date)
            assertEquals("123456", transaction.time)
            assertEquals("12345678", transaction.terminal)
            assertEquals(7, transaction.sequence)
            assertNull(transaction.balanceAfterFen)
            assertEquals(if (transaction.sfi == 0x0C) "02" else "06", transaction.typeHex)
        }
        val rows = decoded.archive.map { ArchivedTransactionEntity(
            cardId = "card", sfi = it.sfi.toSfiHex(), protocol = it.protocol, hex = it.hex,
            contentHash = it.identity, resolvedDate = it.date, logFormat = it.logFormat,
            firstSeenAt = 1, lastSeenAt = 1
        ) }
        assertEquals(decoded.display, RecordDecoder.decodeArchive("TU", rows))
    }

    @Test fun epAndEcAreReadAndSnapshotUnderTheirOwnAids() {
        val result = TransitCardReader(channel(ep = true)).read()
        assertEquals(4321L, result.balanceFen)
        assertTrue(result.rawRecords.any { it.selectedAid == CardProfiles.TU_EP_AID && it.sfi == 0x15 })
        assertTrue(result.rawRecords.any { it.selectedAid == CardProfiles.TU_EC_AID && it.sfi == 0x0B && it.recNo == 1 })
        assertEquals(4321L, result.appReads.first { it.selectedAid == CardProfiles.TU_EC_AID }.balanceFen)
        assertEquals(4321L, result.appReads.first { it.selectedAid == CardProfiles.TU_EP_AID }.balanceFen)
    }

    @Test fun invalidEcBcdBalanceRemainsUnknown() {
        val result = TransitCardReader(channel(balance = "9F79060000000043FA9000")).read()
        assertNull(result.balanceFen)
    }

    @Test fun fullEpBalanceAndMalformedResponseAreDistinguished() {
        for ((response, expected) in listOf("010000019000" to 16777217L, "0000009000" to null, "9000" to null)) {
            val base = channel(ep = true)
            val card = FakeChannel { if (it == "805C000204") response else ApduUtil.bytesToHex(base.transceive(ApduUtil.hexToBytes(it))) }
            assertEquals(expected, TransitCardReader(card).read().balanceFen)
        }
    }
}
