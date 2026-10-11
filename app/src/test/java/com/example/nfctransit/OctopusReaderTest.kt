package com.example.nfctransit

import com.example.nfctransit.data.RecordDecoder
import org.junit.Assert.*
import org.junit.Test

class OctopusReaderTest {
    private class FakeFelica(val reply: (String) -> String) : FelicaChannel {
        override var timeout = 0
        val commands = mutableListOf<String>()
        override fun connect() {}
        override fun close() {}
        override fun transceive(command: ByteArray): ByteArray {
            val hex = ApduUtil.bytesToHex(command)
            commands.add(hex)
            return ApduUtil.hexToBytes(reply(hex))
        }
    }

    private val idm = "0101020304050607"
    private val pmm = "0120220427674EFF"

    private fun card(balanceRaw: String) = FakeFelica { command ->
        when {
            command == "0600800801" + "00" -> "1201$idm${pmm}8008"
            command.startsWith("1006$idm") -> "1D07${idm}000001${balanceRaw}000000000000000000000000"
            else -> ""
        }
    }

    @Test fun readsBalanceWithFiftyDollarOffset() {
        val channel = card("00000294")  // 660 - 500 = 160 × 0.1 = HK$16.0
        val result = OctopusReader(channel).read()
        assertEquals("1006${idm}011701018000", channel.commands[1])
        assertEquals("OCTOPUS", result.matchedProfile?.cardType)
        assertEquals(idm, result.cardInfo?.cardNumber)
        assertEquals(1600L, result.balanceFen)
        assertEquals(listOf(0x0117, 0x0118), result.rawRecords.map { it.sfi })
        assertTrue(result.rawRecords.all { it.selectedAid == "8008" })
        assertTrue(RecordDecoder.transactionRecords("OCTOPUS", result.rawRecords).isEmpty())
        assertEquals("HK$", CardProfiles.currencySymbol("OCTOPUS"))
    }

    @Test fun negativeBalanceBelowOffset() {
        val result = OctopusReader(card("000001C2")).read()  // 450 - 500 = -50 → -HK$5.0
        assertEquals(-500L, result.balanceFen)
    }

    @Test fun comboCardKeepsIsoWalletPrimaryAndOctopusAsExtraApp() {
        val octopus = OctopusReader(card("00000294")).read()
        val tuProfile = CardProfiles.known.single { it.cardType == "TU" }
        val iso = TransitCardReader.ReadResult(
            tuProfile, CardInfo(tuProfile.name, "", "", "3104123456789", ""), 1234L,
            rawRecords = listOf(com.example.nfctransit.data.RawRecord(0x18, 1, "TU", "00", CardProfiles.TU_EP_AID)),
            rawLog = listOf("iso"),
            appReads = listOf(TransitCardReader.AppRead(CardProfiles.TU_EP_AID, "", 1234L, "000004D2"))
        )
        for (merged in listOf(OctopusReader.merge(iso, octopus)!!)) {
            assertEquals("TU", merged.matchedProfile?.cardType)
            assertEquals(1234L, merged.balanceFen)
            assertEquals(listOf(CardProfiles.TU_EP_AID, "8008"), merged.appReads.map { it.selectedAid })
            assertEquals(idm, OctopusReader.idmFromPolling(merged.appReads.last().selectResp))
            assertTrue(merged.rawRecords.any { it.sfi == 0x0117 })
            assertTrue(RecordDecoder.transactionRecords("TU", merged.rawRecords).none { it.sfi == 0x0117 })
        }
        // ISO 侧不是已知卡型（未知卡）→ 八达通作主卡
        val unknownIso = iso.copy(matchedProfile = CardProfiles.UNKNOWN)
        assertEquals("OCTOPUS", OctopusReader.merge(unknownIso, octopus)?.matchedProfile?.cardType)
        assertSame(octopus, OctopusReader.merge(null, octopus))
    }

    @Test fun nonOctopusFelicaIsUnsupported() {
        val result = OctopusReader(FakeFelica { "" }).read()
        assertNull(result.matchedProfile)
    }
}
