package com.example.nfctransit

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class TransitCardReaderSessionTest {
    private class FakeChannel(val reply: (String) -> ByteArray) : CardChannel {
        override var timeout = 3000
        var connectFailure: IOException? = null
        var closed = false
        val commands = mutableListOf<String>()
        override fun connect() { connectFailure?.let { throw it } }
        override fun close() { closed = true }
        override fun transceive(command: ByteArray): ByteArray {
            val hex = ApduUtil.bytesToHex(command)
            commands.add(hex)
            return reply(hex)
        }
    }

    private val tuAid = "A000000632010105"
    private val cuAid = "A00000000386980701"
    private fun success(data: ByteArray = byteArrayOf()) = data + ApduUtil.hexToBytes("9000")
    private fun missing() = ApduUtil.hexToBytes("6A82")
    private fun info() = success(ByteArray(30).apply { this[19] = 0x12 })
    private fun record() = success(ByteArray(23).apply { this[1] = 1 })

    @Test fun failedConnectStillClosesAndReturnsAnError() {
        val channel = FakeChannel { missing() }.apply { connectFailure = IOException("connect failed") }
        val result = TransitCardReader(channel).read()
        assertTrue(channel.closed)
        assertEquals("connect failed", result.readError)
        assertNull(result.matchedProfile)
        assertTrue(channel.commands.isEmpty())
    }

    @Test fun coreTransactionsPrecedeUnknownFileProbeAndAreNotReadTwice() {
        val channel = FakeChannel { command ->
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(tuAid)) -> success()
                "00B0950000" -> info()
                "805C000204" -> success(byteArrayOf(0, 0, 0, 12))
                ApduUtil.bytesToHex(ApduUtil.buildReadRecord(0x18, 1, 0x17)) -> record()
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        val trade = ApduUtil.bytesToHex(ApduUtil.buildReadRecord(0x18, 1, 0x17))
        val optional = ApduUtil.bytesToHex(ApduUtil.buildReadBinary(1, 0, 0))
        assertTrue(channel.commands.indexOf(trade) < channel.commands.indexOf(optional))
        assertEquals(1, channel.commands.count { it == trade })
        assertEquals(1, channel.commands.count { it == "00B0950000" })
        assertEquals(12L, result.balanceFen)
        assertNull(result.readError)
        assertTrue(result.rawRecords.any { it.sfi == 0x18 })
        assertTrue(channel.closed)
    }

    @Test fun disconnectWhileSwitchingSecondWalletKeepsFirstWallet() {
        val channel = FakeChannel { command ->
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(cuAid)) -> success()
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(tuAid)) -> throw IOException("tag lost")
                "00B0950000" -> info()
                "805C000204" -> success(byteArrayOf(0, 0, 0, 12))
                ApduUtil.bytesToHex(ApduUtil.buildReadRecord(0x18, 1, 0x17)) -> record()
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertEquals("CU", result.matchedProfile?.cardType)
        assertNotNull(result.cardInfo)
        assertEquals(12L, result.balanceFen)
        assertEquals("tag lost", result.readError)
        assertTrue(result.rawRecords.any { it.sfi == 0x18 && it.protocol == "CU" })
        assertEquals(ApduUtil.bytesToHex(ApduUtil.buildSelectByName(tuAid)), channel.commands.last())
        assertTrue(channel.closed)
    }

    @Test fun optionalProbeDisconnectDoesNotTurnCoreSuccessIntoFailure() {
        val channel = FakeChannel { command ->
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(tuAid)) -> success()
                "00B0950000" -> info()
                ApduUtil.bytesToHex(ApduUtil.buildReadBinary(1, 0, 0)) -> throw IOException("removed after core")
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertEquals("TU", result.matchedProfile?.cardType)
        assertNotNull(result.cardInfo)
        assertNull(result.readError)
        assertEquals(ApduUtil.bytesToHex(ApduUtil.buildReadBinary(1, 0, 0)), channel.commands.last())
    }

    @Test fun optionalProbeBudgetStopsBeforeAnotherCommand() {
        var clock = 0L
        lateinit var channel: FakeChannel
        channel = FakeChannel { command ->
            if (channel.timeout == 500) clock += 400
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(tuAid)) -> success()
                "00B0950000" -> info()
                else -> missing()
            }
        }
        val result = TransitCardReader(channel) { clock }.read()
        val first = ApduUtil.bytesToHex(ApduUtil.buildReadBinary(1, 0, 0))
        val second = ApduUtil.bytesToHex(ApduUtil.buildReadBinary(2, 0, 0))
        assertTrue(first in channel.commands)
        assertFalse(second in channel.commands)
        assertTrue(result.rawLog.any { it.contains("600 ms") })
        assertNull(result.readError)
    }

    @Test fun cuAdditionalTradeZonesAreReadAndArchivedAsRaw() {
        val channel = FakeChannel { command ->
            when {
                command == ApduUtil.bytesToHex(ApduUtil.buildSelectByName(cuAid)) -> success()
                command == "00B0950000" -> info()
                listOf(0x10, 0x06, 0x1A).any {
                    command == ApduUtil.bytesToHex(ApduUtil.buildReadRecord(it, 1, 0x17))
                } -> record()
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertTrue(result.rawRecords.filter { it.protocol == "CU" }.map { it.sfi }.containsAll(listOf(0x10, 0x06, 0x1A)))
    }

    @Test fun shenzhenDualWalletIncludesTuJourneyInDecoderInput() {
        val profile = CardProfiles.known.first { it.cardType == "SZT" }
        assertTrue(0x1E in profile.transactionSfis)
    }

    @Test fun expiredTagStopsFurtherCommandsAndKeepsCardIdentity() {
        val failedCommand = ApduUtil.bytesToHex(ApduUtil.buildReadRecord(0x1E, 1, 0))
        val channel = FakeChannel { command ->
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(tuAid)) -> success()
                "00B0950000" -> info()
                failedCommand -> throw SecurityException("Tag is out of date")
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertNotNull(result.cardInfo)
        assertEquals("Tag is out of date", result.readError)
        assertEquals(failedCommand, channel.commands.last())
        assertTrue(channel.closed)
    }

    @Test fun lingnanStatsAreSavedFromBasicAppAndNotOverwrittenByWalletProbe() {
        val appy = "5041592E41505059"
        val ticl = "5041592E5449434C"
        val stats = ByteArray(22).apply { this[3] = 0x26; this[4] = 0x10 }
        val statsCommand = ApduUtil.bytesToHex(ApduUtil.buildReadRecord(0x08, 1, 0x16))
        val channel = FakeChannel { command ->
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(appy)),
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(ticl)) -> success()
                "00B0950046" -> success(ByteArray(52).apply { this[15] = 0x12 })
                statsCommand -> success(stats)
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertEquals(202610, result.statsMonth)
        assertEquals(ApduUtil.bytesToHex(stats), result.rawRecords.single { it.sfi == 0x08 }.hex)
        assertEquals("LNT", result.rawRecords.single { it.sfi == 0x08 }.protocol)
        assertFalse(ApduUtil.bytesToHex(ApduUtil.buildReadBinary(0x08, 0, 0)) in channel.commands)
        assertTrue(result.appReads.any { it.selectedAid == ticl })
    }

    @Test fun secondWalletTriesAlternateTuAidWhenFirstIsAbsent() {
        val alternate = "A000000632010106"
        val channel = FakeChannel { command ->
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(cuAid)),
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(alternate)) -> success()
                "00B0950000" -> info()
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertNotNull(result.secondCardInfo)
        assertTrue(result.appReads.any { it.selectedAid == alternate })
        assertNull(result.readError)
    }
}
