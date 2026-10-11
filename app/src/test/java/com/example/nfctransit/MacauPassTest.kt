package com.example.nfctransit

import com.example.nfctransit.data.RecordDecoder
import org.junit.Assert.*
import org.junit.Test

/** 澳门通 + 空 TU 钱包联名卡（实卡 APDU 日志，2026-10-11）。 */
class MacauPassTest {
    private class FakeChannel(val reply: (String) -> ByteArray) : CardChannel {
        override var timeout = 3000
        override fun connect() {}
        override fun close() {}
        override fun transceive(command: ByteArray): ByteArray = reply(ApduUtil.bytesToHex(command))
    }

    private fun ok(hex: String) = ApduUtil.hexToBytes(hex + "9000")
    private fun sel(aid: String) = ApduUtil.bytesToHex(ApduUtil.buildSelectByName(aid))
    private fun rec(sfi: Int, n: Int) = ApduUtil.bytesToHex(ApduUtil.buildReadRecord(sfi, n, 0x17))

    private val info = "000108530000000001010000F6305140F63051402026072220460722" + "8600"
    private val trades = listOf(
        "00030000000000001E0900000001147620261011205703",
        "0002000000000000280900000002022820261011194413",
        "0001000000000000000900000004325020261011193202",
        "00020000000000009602FFFF0301055520261011162141",
        "0001000000000000640200000000000020260722125140"
    )

    private val channel = FakeChannel { command ->
        when (command) {
            sel(CardProfiles.MPASS_AID) -> ok("6F0C840AB0C4C3C5CDA8C7AEB0FC")
            "00B0950000" -> ok(info)
            "805C000204" -> ok("000000B4")
            else -> trades.indices.firstOrNull { command == rec(0x18, it + 1) }
                ?.let { ok(trades[it]) } ?: ApduUtil.hexToBytes("6A82")
        }
    }

    @Test fun readsMacauPassWalletInMop() {
        val result = TransitCardReader(channel).read()
        assertEquals("MPASS", result.matchedProfile?.cardType)
        assertEquals("F6305140", result.cardInfo?.cardNumber)
        assertEquals("20460722", result.cardInfo?.validTo)
        assertEquals(1800L, result.balanceFen)  // 0xB4 = 180 × 0.1 MOP = MOP 18.00

        val records = RecordDecoder.transactionRecords("MPASS", result.rawRecords)
        assertTrue(records.all { it.protocol == "MPASS" })
        val txns = RecordDecoder.decodeCard("MPASS", records, null, 2026).display
        assertEquals(5, txns.size)
        val latest = txns.first()
        assertEquals("20261011", latest.date)
        assertEquals("205703", latest.time)
        assertEquals(300L, latest.amountFen)  // MOP 3.0 巴士
        assertEquals("09", latest.typeHex)
        assertEquals("0446", latest.cityCode)
        val bus4 = txns.first { it.time == "194413" }
        assertEquals(400L, bus4.amountFen)
        assertEquals("000000020228", bus4.terminal)
        val topUp = txns.first { it.time == "162141" }
        assertEquals("02", topUp.typeHex)
        assertEquals(1500L, topUp.amountFen)
    }

    @Test fun currencyFollowsWalletProtocol() {
        assertEquals("MOP ", CardProfiles.currencySymbol("MPASS"))
        assertEquals("¥", CardProfiles.currencySymbol("TU"))
    }
}
