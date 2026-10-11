package com.example.nfctransit

import com.example.nfctransit.data.RecordDecoder
import org.junit.Assert.*
import org.junit.Test

class UnknownAppDiscoveryTest {
    private class FakeChannel(override val uid: ByteArray?, val reply: (String) -> ByteArray) : CardChannel {
        override var timeout = 3000
        override fun connect() {}
        override fun close() {}
        override fun transceive(command: ByteArray): ByteArray = reply(ApduUtil.bytesToHex(command))
    }

    private val unknownAid = "A0000099990001"
    private val ppse = "315041592E5359532E4444463031"
    private fun ok(hex: String) = ApduUtil.hexToBytes(hex + "9000")
    private fun tlv(tag: String, value: String) = tag + (value.length / 2).toString(16).padStart(2, '0') + value
    private fun sel(aid: String) = ApduUtil.bytesToHex(ApduUtil.buildSelectByName(aid))
    private val trade = "0001000000000000640600000000000120261011120000"

    private fun channel(knownTu: Boolean) = FakeChannel(byteArrayOf(0x04, 0x11, 0x22, 0x33)) { command ->
        when (command) {
            sel(CardProfiles.TU_EP_AID) -> if (knownTu) ok("") else ApduUtil.hexToBytes("6A82")
            sel(ppse) -> ok(tlv("6F", tlv("84", ppse) + tlv("A5", tlv("BF0C", tlv("61", tlv("4F", unknownAid))))))
            sel(unknownAid) -> ok(tlv("6F", tlv("84", unknownAid)))
            "805C000204" -> ok("00000064")
            // 未知应用里有一个形如交易记录的 SFI 0x18 文件：只能进原始数据，不能被解码
            ApduUtil.bytesToHex(ApduUtil.buildReadRecord(0x18, 1, 0)) -> ok(trade)
            else -> ApduUtil.hexToBytes("6A82")
        }
    }

    @Test fun cardWithoutKnownAppIsKeptAsRawOnlyUnknownCard() {
        val result = TransitCardReader(channel(knownTu = false)).read()
        assertEquals("UNKNOWN", result.matchedProfile?.cardType)
        assertEquals("04112233", result.cardInfo?.cardNumber)
        val app = result.appReads.single { it.selectedAid == unknownAid }
        assertEquals("00000064", app.balanceResp)
        assertNull(app.balanceFen)
        assertTrue(result.rawRecords.any { it.selectedAid == unknownAid && it.sfi == 0x18 && it.hex == trade })
        assertTrue(RecordDecoder.transactionRecords("UNKNOWN", result.rawRecords).isEmpty())
    }

    @Test fun unknownAppOnKnownCardIsProbedButNotDecoded() {
        val result = TransitCardReader(channel(knownTu = true)).read()
        assertEquals("TU", result.matchedProfile?.cardType)
        assertTrue(result.rawRecords.any { it.selectedAid == unknownAid && it.sfi == 0x18 })
        val zones = RecordDecoder.transactionRecords("TU", result.rawRecords)
        assertTrue(zones.none { it.hex == trade && it.protocol.isBlank() })
        assertTrue(CardProfiles.isUnknownApp(unknownAid))
        assertFalse(CardProfiles.isUnknownApp(CardProfiles.MPASS_AID))
        assertFalse(CardProfiles.isUnknownApp(""))
    }
}
