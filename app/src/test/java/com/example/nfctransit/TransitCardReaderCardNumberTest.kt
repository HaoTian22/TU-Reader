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
}
