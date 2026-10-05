package com.example.nfctransit.data

import com.example.nfctransit.data.db.StationResolution
import org.junit.Assert.assertEquals
import org.junit.Test

class TransitDataMatchingTest {
    @Test
    fun shenzhenTuAndSztShareCuTerminalMappingsWithCanonicalCity() {
        val loaded = TransitData::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        val fields = listOf("resolutionsByCity", "candidatesByCityAndFamily", "byDeviceCode")
            .associateWith { TransitData::class.java.getDeclaredField(it).apply { isAccessible = true } }
        @Suppress("UNCHECKED_CAST")
        val buckets = fields.getValue("resolutionsByCity").get(TransitData) as MutableMap<String, MutableList<StationResolution>>
        @Suppress("UNCHECKED_CAST")
        val cached = fields.getValue("candidatesByCityAndFamily").get(TransitData) as MutableMap<Pair<String, TransitData.TuTransitFamily?>, List<StationResolution>>
        @Suppress("UNCHECKED_CAST")
        val devices = fields.getValue("byDeviceCode").get(TransitData) as MutableMap<String, MutableList<StationResolution>>
        val oldBuckets = buckets.toMap()
        val oldCached = cached.toMap()
        val oldDevices = devices.toMap()
        val wasLoaded = loaded.getBoolean(TransitData)
        val metro = resolution("40011", "地铁", "10号线", "福田口岸").copy(
            cityId = 200, cityCode = "5840", cityName = "深圳", cityNameEn = "Shenzhen",
            standard = "CU", deviceCode = "518040011"
        )
        val bus = metro.copy(standard = "TU", deviceCode = "51800D3100", transitType = "公交",
            lineName = "M337", stationId = null, stationName = null)
        val genericBus = bus.copy(standard = "CU", deviceCode = "518020", lineName = "东部公交")
        try {
            loaded.setBoolean(TransitData, true)
            buckets["5180"] = mutableListOf(metro, bus, genericBus)
            for (family in listOf(null, TransitData.TuTransitFamily.RAIL, TransitData.TuTransitFamily.BUS)) {
                cached["5180" to family] = TransitData.tuCandidates(buckets.getValue("5180"), family)
            }
            for (mapping in buckets.getValue("5180")) devices[mapping.deviceCode] = mutableListOf(mapping)
            for (city in listOf("5840", "5180")) {
                val entry = TransitData.resolveTuStation(city, "0000", "0000", "000040011001",
                    expectedFamily = TransitData.TuTransitFamily.RAIL)
                assertEquals("福田口岸", entry?.station)
                assertEquals("5840", entry?.cityCode)
            }
            assertEquals("福田口岸", TransitData.resolveByStandard("SZT", "5180", "000040011001", "000040011001")?.station)
            assertEquals("福田口岸", TransitData.resolveByStandard("TU", "5180", "518040011001", "518040011001")?.station)
            assertEquals("M337", TransitData.resolveTuStation("5840", "0000", "0000", "",
                rawCode = "0D310000000000", expectedFamily = TransitData.TuTransitFamily.BUS)?.line)
            assertEquals("东部公交", TransitData.resolveTuStation("5840", "0000", "0000", "002099990001",
                expectedFamily = TransitData.TuTransitFamily.BUS)?.line)
            val location = TransitData.actualLocation(metro.stationId, metro.deviceCode, "5840", metro.lineId, "TU")
            assertEquals("5840", location.cityCode)
            assertEquals(LocationSource.PARENT_DIRECTORY, location.source)
        } finally {
            buckets.clear(); buckets.putAll(oldBuckets)
            cached.clear(); cached.putAll(oldCached)
            devices.clear(); devices.putAll(oldDevices)
            loaded.setBoolean(TransitData, wasLoaded)
        }
    }

    @Test
    fun tuDoesNotUseCuMappingsOutsideShenzhen() {
        val jiaxing = resolution("0123", "公交", "1路", null).copy(
            cityCode = "3350", cityName = "嘉兴", standard = "CU", deviceCode = "31400123")
        assertEquals(emptyList<StationResolution>(), TransitData.tuCandidates(listOf(jiaxing), null))
    }

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
        deviceCode = "3320$code"
    )
}
