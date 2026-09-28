package com.example.nfctransit.data

import com.example.nfctransit.data.db.StationResolution
import org.junit.Assert.assertEquals
import org.junit.Test

class TransitDataMatchingTest {
    @Test
    fun ningboSharedCodesResolveBySubtypeInEitherLoadOrder() {
        val cases = listOf(
            Triple("0110", "11", "鼓楼"),
            Triple("0120", "12", "东环南路"),
            Triple("0250", "25", "三官堂"),
            Triple("0340", "34", "儿童公园")
        )
        for ((code, busLine, station) in cases) {
            val bus = resolution(code, "公交", busLine, null)
            val metro = resolution(code, "地铁", "${code.take(2).toInt()}号线", station)
            for (candidates in listOf(listOf(bus, metro), listOf(metro, bus))) {
                val rawCode = "${code}0406000000"
                val railMatch = match(candidates, rawCode, TransitData.TuTransitFamily.RAIL)
                assertEquals(station, railMatch?.stationName)
                assertEquals("3320$code", railMatch?.deviceCode)
                val busMatch = match(candidates, rawCode, TransitData.TuTransitFamily.BUS)
                assertEquals(busLine, busMatch?.lineName)
                assertEquals("公交", busMatch?.transitType)
            }
        }
    }

    @Test
    fun unknownSubtypeFallsBackInsteadOfChoosingAConflictingCode() {
        val candidates = listOf(
            resolution("0120", "公交", "12", null),
            resolution("0120", "地铁", "1号线", "东环南路"),
            resolution("01", "地铁", "1号线", null)
        )
        assertEquals("332001", match(candidates, "01200406000000", null)?.deviceCode)
        assertEquals("332001", match(candidates.reversed(), "01200406000000", null)?.deviceCode)
    }

    @Test
    fun preciseKnownTypeBeatsLineFallback() {
        val candidates = listOf(
            resolution("01", "地铁", "1号线", null),
            resolution("0120", "地铁", "1号线", "东环南路"),
            resolution("0120", "公交", "12", null)
        )
        assertEquals("东环南路", match(candidates, "01200406000000", TransitData.TuTransitFamily.RAIL)?.stationName)
    }

    private fun match(
        candidates: List<StationResolution>,
        body: String,
        family: TransitData.TuTransitFamily?
    ) = TransitData.longestTuMatch("3320", body, family, null, candidates = candidates)?.resolution

    private fun resolution(code: String, type: String, line: String, station: String?) = StationResolution(
        cityId = 1, cityCode = "3320", cityName = "宁波", cityNameEn = "Ningbo",
        lineId = if (type == "公交") 12 else 1, lineName = line, lineNameEn = null,
        lineColor = null, stationId = if (station == null) null else 20,
        stationName = station, stationNameEn = null, standard = "TU", transitType = type,
        deviceCode = "3320$code", deviceLocation = if (station == null) "3320" else null, matchKey = null
    )
}
