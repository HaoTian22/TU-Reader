package com.example.nfctransit

import org.junit.Assert.assertEquals
import org.junit.Test

class EpBalanceTest {
    private fun parse(hex: String) = ApduUtil.parseEpBalance(ApduUtil.hexToBytes(hex))

    @Test fun plainBalance() = assertEquals(3840L, parse("00000F00"))

    @Test fun sztFlaggedBalance() = assertEquals(1760L, parse("800006E0"))

    @Test fun negativeBalance() = assertEquals(-256L, parse("FFFFFF00"))
}
