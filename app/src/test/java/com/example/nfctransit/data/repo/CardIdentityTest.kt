package com.example.nfctransit.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardIdentityTest {

    @Test
    fun identityNumbersExcludeBlanksAndTrimUnexpectedWhitespace() {
        assertEquals(
            setOf("3104870320108442147", "9546025350"),
            cardIdentityNumbers(" 3104870320108442147 ", null, "", "9546025350")
        )
    }

    @Test
    fun identityNumbersRetainBothNumbersForDualProtocolCards() {
        assertEquals(
            setOf("9534635882", "3104870395346358827"),
            cardIdentityNumbers("9534635882", "3104870395346358827")
        )
    }

    @Test
    fun sharedPrimaryOrSecondaryNumberIdentifiesTheSameCard() {
        assertTrue(
            sharesCardIdentity(
                cardIdentityNumbers("9534635882", "3104870395346358827"),
                cardIdentityNumbers("3104870395346358827")
            )
        )
    }

    @Test
    fun matchingLastFourAloneDoesNotIdentifyTheSameCard() {
        assertFalse(
            sharesCardIdentity(
                cardIdentityNumbers("3104870320108442147"),
                cardIdentityNumbers("3104870395460252147")
            )
        )
    }
}
