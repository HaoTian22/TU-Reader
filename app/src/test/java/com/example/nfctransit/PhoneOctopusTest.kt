package com.example.nfctransit

import org.junit.Assert.*
import org.junit.Test

class PhoneOctopusTest {
    private class FakeChannel(val reply: (String) -> ByteArray) : CardChannel {
        val sent = mutableListOf<String>()
        override var timeout = 3000
        override fun connect() {}
        override fun close() {}
        override fun transceive(command: ByteArray): ByteArray =
            ApduUtil.bytesToHex(command).also(sent::add).let(reply)
    }

    private fun sel(aid: String) = ApduUtil.bytesToHex(ApduUtil.buildSelectByName(aid))
    private val ppse = sel("325041592E5359532E4444463031")

    // 实测 2026-10-31 Google Wallet 手机八达通：交通 AID 均 6A82，八达通 SELECT 响应两次读取一致；PPSE 会落到默认支付卡。
    @Test fun phoneOctopusIsDetectedWithoutEnumeratingPaymentApps() {
        val channel = FakeChannel { command ->
            when (command) {
                sel(CardProfiles.PHONE_OCTOPUS_AID) ->
                    ApduUtil.hexToBytes("0101000332785F6EA0FAF31A2FBFD82B612626C2815E8F38012811029E249E0DA5DA9000")
                ppse -> ApduUtil.hexToBytes("9000")
                else -> ApduUtil.hexToBytes("6A82")
            }
        }
        val result = TransitCardReader(channel).read()
        assertTrue(result.phoneOctopus)
        assertNull(result.matchedProfile)
        assertNull(result.readError)
        assertFalse(ppse in channel.sent)
    }
}
