package com.example.nfctransit

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitCardReaderCardNumberTest {

    @Test
    fun shenzhenCardNumberReversesCardBytesBeforeDecimalConversion() {
        val data = ApduUtil.hexToBytes(
            "0000000000000000FD58000051800000C2398534202012292050122910100000"
        )

        assertEquals("881146306", parseSztCardNumber(data))
    }

    @Test
    fun legacyCuCardNumberMatchesPreviousParserOutput() {
        val data = ApduUtil.hexToBytes(
            "0000000000000000FD58000051800000C2398534202012292050122910100000"
        )

        assertEquals("51800000122398534", parseLegacyCuCardNumber(data))
    }

    @Test
    fun cuCardNumberUsesEightHexBytesFromOffset12() {
        val data = ByteArray(12) + ApduUtil.hexToBytes("0123456789ABCDEF")

        assertEquals("81985529216486895", parseCuCardNumber(data))
    }

    @Test
    fun cuCardNumberRemainsPositiveWhenHighestBitIsSet() {
        val data = ByteArray(12) + ApduUtil.hexToBytes("FFFFFFFFFFFFFFFF")

        assertEquals("18446744073709551615", parseCuCardNumber(data))
    }
}
