package com.example.nfctransit.ui

import com.example.nfctransit.model.TransitDirection
import com.example.nfctransit.model.UiTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class MainViewModelStatsTest {

    @Test
    fun topStationsCountsZeroFareEntryAndExitVisits() {
        val transactions = listOf(
            transaction(typeHex = "03", direction = TransitDirection.ENTRY),
            transaction(typeHex = "04", direction = TransitDirection.EXIT),
            transaction(typeHex = "02", amountYuan = 50.0)
        )

        val result = computeTopStationsForStats(transactions)

        assertEquals(1, result.size)
        assertEquals("体育西路", result.single().name)
        assertEquals(2, result.single().count)
    }

    private fun transaction(
        typeHex: String,
        direction: TransitDirection? = null,
        amountYuan: Double = 0.0
    ) = UiTransaction(
        id = 1,
        seq = 1,
        typeHex = typeHex,
        amountYuan = amountYuan,
        amountText = "",
        transitType = "地铁",
        terminal = "",
        stationName = "体育西路",
        direction = direction,
        cityName = "广州",
        lineName = "1号线",
        date = "2026-08-23",
        time = "08:00:00",
        displayDateTime = "2026-08-23 08:00:00",
        balanceAfterYuan = null,
        balanceAfterText = null,
        icon = "",
        iconBgColor = 0L
    )
}
