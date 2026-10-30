package com.example.nfctransit.data.repo

import com.example.nfctransit.CardProfiles
import com.example.nfctransit.data.db.PerAppMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripReaderProtocolTest {
    private val shanghaiHex = "00540006A4000000000920001109311120260823150028"
    private val transaction = TripReaderTransaction(0, shanghaiHex, "", false)
    private val cuCard = TripReaderCard("test", "", 0, "", false, true, false, false, false,
        listOf(transaction))

    // tripreader_20261031.sqlite3 实测行：岭南通 + TU 双协议卡 / 纯 TU 卡
    private val dualCard = TripReaderCard(
        cdNo = "3104870395346358827", cdNo2 = "9534635882", cdBalance = 3840, cdTitle = "Yang Cheng Tong",
        isTU = true, isCU = false, isSZT = false, isYCT = true, isLNT = null, transactions = emptyList()
    )
    private val tuCard = TripReaderCard(
        cdNo = "3104870320108442147", cdNo2 = "", cdBalance = 1544, cdTitle = "Yang Cheng Tong",
        isTU = true, isCU = false, isSZT = false, isYCT = false, isLNT = null, transactions = emptyList()
    )
    private val lntFare = TripReaderTransaction(
        1723438200000, "0001000000000000000901000022193700000812125011", "",
        isCU = true, isTU18 = false, isYCT = true, sfi = 0x18
    )
    private val tuFare = TripReaderTransaction(
        1752454889000, "0020000000000007D00288012003268620250714090129", "",
        isCU = false, isTU18 = true, isYCT = false, sfi = 0x18
    )

    @Test
    fun cuFareWithFullYearRemainsCuForSingleAndDualProtocolCards() {
        for (isTu in listOf(false, true)) {
            for (isLnt in listOf(false, null)) {
                assertEquals("CU", tripReaderFareProtocol(cuCard.copy(isTU = isTu),
                    transaction.copy(isLNT = isLnt)))
            }
        }
    }

    @Test
    fun otherWalletProtocolsKeepTheirImportRules() {
        assertEquals("SZT", tripReaderFareProtocol(cuCard.copy(isSZT = true), transaction))
        assertEquals("TU", tripReaderFareProtocol(cuCard.copy(isCU = false, isTU = true), transaction))
        val lntCard = cuCard.copy(isCU = false, isYCT = true, isLNT = true)
        assertEquals("LNT", tripReaderFareProtocol(lntCard, transaction.copy(isLNT = true)))
        assertEquals("TU", tripReaderFareProtocol(lntCard.copy(isTU = true), transaction))
    }

    @Test
    fun perTransactionFlagsPickTheApplication() {
        assertEquals(CardProfiles.YCT_TICL_AID, tripReaderFareAid(dualCard, lntFare))
        assertEquals(CardProfiles.TU_EP_AID, tripReaderFareAid(tuCard, tuFare))
        assertEquals(CardProfiles.CU_AID, tripReaderFareAid(cuCard, transaction.copy(isCU = true, isTU18 = false)))
        // 苏州卡有 SUXIN/SZTK 两个 AID，来源标记无法确定应用
        assertNull(tripReaderFareAid(tuCard.copy(isTU = false, isSUZ = true), lntFare.copy(isYCT = false)))
    }

    @Test
    fun cardNumbersFollowTripReaderTuAndCitySides() {
        assertEquals(
            listOf(CardProfiles.TU_EP_AID to "3104870395346358827", CardProfiles.YCT_APPY_AID to "9534635882"),
            tripReaderCardNumbers(dualCard)
        )
        assertEquals(listOf(CardProfiles.TU_EP_AID to "3104870320108442147"), tripReaderCardNumbers(tuCard))
        assertEquals("YCT", tripReaderCardType(dualCard))
        assertEquals("TU", tripReaderCardType(tuCard))
    }

    @Test
    fun totalBalanceOnlyAssignedWhenTheCardHasOneWallet() {
        assertEquals(listOf(CardProfiles.TU_EP_AID to 1544L), tripReaderBalances(tuCard))
        assertEquals(emptyList<Pair<String, Long>>(), tripReaderBalances(dualCard))
        assertEquals(listOf(CardProfiles.YCT_TICL_AID to 500L), tripReaderBalances(dualCard.copy(cdCUBalance = 500)))
    }

    @Test
    fun migrationPrefersRawEvidenceThenLegacyLabels() {
        fun aid(cardType: String, protocol: String, sfi: Int = 0x18, ec: Boolean = false,
                raw: List<String> = emptyList(), importOnly: Boolean = false) =
            PerAppMigration.legacyArchiveAid(cardType, protocol, sfi, ec, raw, importOnly)
        assertEquals(CardProfiles.TU_EC_AID, aid("TU", "TU", 0x1E, raw = listOf(CardProfiles.TU_EC_AID)))
        assertEquals(CardProfiles.TU_EC_AID, aid("TU", "TU", 0x0B, ec = true))
        assertEquals(CardProfiles.YCT_TICL_AID, aid("YCT", "LNT"))
        assertEquals(CardProfiles.CU_AID, aid("CU", ""))
        // 旧版 TripReader 导入把住建部主交易标成 TU：只修复没有读卡数据的卡
        assertEquals(CardProfiles.CU_AID, aid("CU", "TU", importOnly = true))
        assertEquals(CardProfiles.TU_EP_AID, aid("CU", "TU", importOnly = false))
        assertEquals(CardProfiles.TU_EP_AID, aid("CU", "TU", 0x1E, importOnly = true))
    }
}
