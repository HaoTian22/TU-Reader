package com.example.nfctransit.data

import com.example.nfctransit.data.db.StationResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TransitDataLocationTest {
    @Test
    fun stationWithoutCoordinatesUsesSourceCityAndSelectedLine() {
        val loaded = TransitData::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        val devices = TransitData::class.java.getDeclaredField("byDeviceCode").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val index = devices.get(TransitData) as MutableMap<String, MutableList<StationResolution>>
        val wasLoaded = loaded.getBoolean(TransitData)
        val code = "010030085684"
        val previous = index[code]
        val foshan = StationResolution(
            cityId = 1, cityCode = "5880", cityName = "佛山", cityNameEn = "Foshan",
            lineId = 64, lineName = "2号线", lineNameEn = "Line 2", lineColor = null,
            stationId = 6995, stationName = "新站点", stationNameEn = null,
            standard = "YCT", transitType = "地铁", deviceCode = code
        )
        try {
            loaded.setBoolean(TransitData, true)
            index[code] = mutableListOf(foshan, foshan.copy(lineId = 68, cityCode = "5810"))
            val actual = TransitData.actualLocation(foshan.stationId, code, "0100", foshan.lineId, "YCT")
            assertEquals("5880", actual.cityCode)
            assertEquals(LocationSource.PARENT_DIRECTORY, actual.source)
            assertFalse(actual.routeEligible)
            val ambiguous = TransitData.actualLocation(foshan.stationId, code, "0100", standard = "YCT")
            assertEquals("0100", ambiguous.cityCode)
            assertEquals(LocationSource.DECLARED_CITY_FALLBACK, ambiguous.source)
        } finally {
            if (previous == null) index.remove(code) else index[code] = previous
            loaded.setBoolean(TransitData, wasLoaded)
        }
    }
}
