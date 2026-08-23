package com.example.nfctransit.data

import com.example.nfctransit.model.CanonicalTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordDecoderTest {

    @Test
    fun journeyAreaCity_replacesFareDeviceCityAfterMerge() {
        val journey = transaction(
            identity = "journey",
            cityCode = "0755",
            rawCityCode = "0755",
            stationName = "公共交通"
        )
        val fare = transaction(
            identity = "fare",
            cityCode = "4131",
            rawCityCode = "4131",
            stationName = "轨道交通"
        )

        val merged = RecordDecoder.mergeJourneyAndFare(listOf(journey), listOf(fare)).single()

        assertEquals("0755", merged.cityCode)
        assertEquals("0755", merged.rawCityCode)
    }

    private fun transaction(
        identity: String,
        cityCode: String,
        rawCityCode: String,
        stationName: String
    ) = CanonicalTransaction(
        identity = identity,
        sequence = 1,
        amountFen = 200,
        balanceAfterFen = null,
        typeHex = "06",
        terminal = "4131000000",
        cityCode = cityCode,
        rawCityCode = rawCityCode,
        stationName = stationName,
        date = "20260823",
        time = "120000",
        sfi = 0x18,
        protocol = "TU",
        hex = identity
    )
}
