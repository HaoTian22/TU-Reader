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
    private fun tlv(tag: String, value: String) = tag + (value.length / 2).toString(16).padStart(2, '0') + value

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
        assertTrue(result.rawRecords.all { it.selectedAid == tuAid })
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
        assertTrue(result.appReads.any { it.selectedAid == tuAid })
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
                ApduUtil.bytesToHex(ApduUtil.buildReadRecord(0x18, 1, 0x17)) -> record()
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertEquals(202610, result.statsMonth)
        assertEquals(ApduUtil.bytesToHex(stats), result.rawRecords.single { it.sfi == 0x08 }.hex)
        assertEquals("LNT", result.rawRecords.single { it.sfi == 0x08 }.protocol)
        assertEquals(appy, result.rawRecords.single { it.sfi == 0x08 }.selectedAid)
        assertEquals(appy, result.rawRecords.single { it.sfi == 0x15 }.selectedAid)
        assertEquals(ticl, result.rawRecords.single { it.sfi == 0x18 }.selectedAid)
        assertFalse(ApduUtil.bytesToHex(ApduUtil.buildReadBinary(0x08, 0, 0)) in channel.commands)
        assertTrue(result.appReads.any { it.selectedAid == ticl })
        assertEquals(1, result.appReads.count { it.selectedAid == appy })
    }

    @Test fun secondWalletTriesAlternateTuAidWhenFirstIsAbsent() {
        val alternate = "A000000632010106"
        val channel = FakeChannel { command ->
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(cuAid)),
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(alternate)) -> success()
                "00B0950000" -> info()
                "00B2011400" -> success(ApduUtil.hexToBytes(tlv("70", tlv("5A", "6211223344556677889F"))))
                "80CA9F7900" -> success(ApduUtil.hexToBytes("9F7906000000001234"))
                ApduUtil.bytesToHex(ApduUtil.buildReadRecord(0x18, 1, 0x17)) -> record()
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertNotNull(result.secondCardInfo)
        assertEquals(alternate, result.rawRecords.single { it.sfi == 2 && it.protocol == "TU" }.selectedAid)
        assertEquals("6211223344556677889", result.secondCardInfo?.cardNumber)
        assertEquals(1234L, result.secondBalanceFen)
        assertEquals(cuAid, result.rawRecords.single { it.sfi == 0x15 && it.protocol == "CU" }.selectedAid)
        assertEquals(setOf(cuAid), result.rawRecords.filter { it.sfi == 0x18 }.map { it.selectedAid }.toSet())
        assertTrue(result.appReads.any { it.selectedAid == alternate })
        assertNull(result.readError)
    }

    @Test fun nestedDirectoryAddsUnknownAppsOnceAndKeepsWalletBalance() {
        val unknown = "F0000000010101"
        val entry = tlv("61", tlv("4F", unknown))
        val directory = tlv("6F", tlv("A5", tlv("BF0C", entry + entry + tlv("61", tlv("4F", tuAid)))))
        val unknownSelect = tlv("6F", tlv("84", unknown))
        val channel = FakeChannel { command ->
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(tuAid)) -> success()
                "00B0950000" -> info()
                "805C000204" -> success(byteArrayOf(0, 0, 0, 12))
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(CardProfiles.PSE_AID)) -> success(ApduUtil.hexToBytes(directory))
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(unknown)) -> success(ApduUtil.hexToBytes(unknownSelect))
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertEquals(unknownSelect, result.appReads.single { it.selectedAid == unknown }.selectResp)
        assertEquals(12L, result.appReads.single { it.selectedAid == tuAid }.balanceFen)
        assertEquals(1, channel.commands.count { it == ApduUtil.bytesToHex(ApduUtil.buildSelectByName(unknown)) })
        assertEquals(1, channel.commands.count { it == ApduUtil.bytesToHex(ApduUtil.buildSelectByName(tuAid)) })
        assertTrue(channel.commands.indexOf("00B0950000") < channel.commands.indexOf(ApduUtil.bytesToHex(ApduUtil.buildSelectByName(CardProfiles.PSE_AID))))
    }

    @Test fun pseDirectoryFileListsAppsThatCannotBeSelected() {
        val pse = "315041592E5359532E4444463031"
        val unknown = "F0000000020202"
        var selected = ""
        val directoryRecord = tlv("70", tlv("61", tlv("4F", unknown)))
        val channel = FakeChannel { command ->
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(tuAid)) -> { selected = tuAid; success() }
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(pse)) -> {
                    selected = pse
                    success(ApduUtil.hexToBytes(tlv("6F", tlv("A5", tlv("88", "01")))))
                }
                "00B0950000" -> info()
                ApduUtil.bytesToHex(ApduUtil.buildReadRecord(1, 1, 0)) ->
                    if (selected == pse) success(ApduUtil.hexToBytes(directoryRecord)) else missing()
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertTrue(result.appReads.any { it.selectedAid == pse })
        val listed = result.appReads.single { it.selectedAid == unknown }
        assertEquals("", listed.selectResp)
        assertNull(listed.balanceFen)
        assertNull(result.readError)
    }

    @Test fun discoveryKeepsOtherKnownAppsAndAppyWhenWalletSelectFails() {
        val appy = "5041592E41505059"
        val szt = "5041592E535A54"
        val channel = FakeChannel { command ->
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(appy)),
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(szt)) -> success()
                "00B0950046" -> success(ByteArray(52).apply { this[15] = 0x12 })
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertEquals(setOf(appy, szt), result.appReads.map { it.selectedAid }.toSet())
        assertEquals("YCT", result.matchedProfile?.cardType)
        assertNotNull(result.cardInfo)
        assertNull(result.balanceFen)
    }

    @Test fun directoryDiscoveryDisconnectRetainsAllAdvertisedAppsAndCoreRead() {
        val first = "F0000000010101"
        val second = "F0000000020202"
        val directory = tlv("6F", tlv("A5", tlv("BF0C", tlv("61", tlv("4F", first)) + tlv("61", tlv("4F", second)))))
        val channel = FakeChannel { command ->
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(tuAid)) -> success()
                "00B0950000" -> info()
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(CardProfiles.PSE_AID)) -> success(ApduUtil.hexToBytes(directory))
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(first)) -> throw IOException("removed during app discovery")
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertTrue(result.appReads.map { it.selectedAid }.containsAll(listOf(tuAid, first, second)))
        assertNotNull(result.cardInfo)
        assertNull(result.readError)
        assertEquals(ApduUtil.bytesToHex(ApduUtil.buildSelectByName(first)), channel.commands.last())
        assertTrue(channel.closed)
    }

    @Test fun malformedDirectoryKeepsItsSuccessfulSelectWithoutInventingApps() {
        val channel = FakeChannel { command ->
            when (command) {
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(tuAid)),
                ApduUtil.bytesToHex(ApduUtil.buildSelectByName(CardProfiles.PSE_AID)) -> success(ApduUtil.hexToBytes("6F8201"))
                "00B0950000" -> info()
                else -> missing()
            }
        }
        val result = TransitCardReader(channel).read()
        assertEquals(setOf(tuAid, CardProfiles.PSE_AID), result.appReads.map { it.selectedAid }.toSet())
        assertNull(result.readError)
    }
}
