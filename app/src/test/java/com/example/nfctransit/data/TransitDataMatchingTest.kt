package com.example.nfctransit.data

import com.example.nfctransit.data.db.StationResolution
import com.example.nfctransit.ApduUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransitDataMatchingTest {
    @Test
    fun correctedDalianCsvCodesResolveProvidedJourneyTo40() {
        val hex = "040000413101519063020028000100000000000064000002BC20250720152720222000002220FFFFFFFF000000000000"
        val body = ApduUtil.bytesToHex(ApduUtil.hexToBytes(hex).copyOfRange(10, 17))
        val candidates = listOf("002800" to "40", "001000" to "17", "000100" to "1").map { (code, line) ->
            resolution(code, "公交", line, null).copy(cityCode = "2220", cityName = "大连",
                deviceCode = "2220$code")
        }
        for (rows in listOf(candidates, candidates.reversed())) {
            val result = TransitData.longestTuMatch("2220", body, TransitData.TuTransitFamily.BUS,
                null, candidates = rows)?.resolution
            assertEquals("2220002800", result?.deviceCode)
            assertEquals("40", result?.lineName)
        }
    }

    @Test
    fun shenzhenTuAndSztShareCuTerminalMappingsWithCanonicalCity() {
        val loaded = TransitData::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        val fields = listOf("resolutionsByCity", "candidatesByCityAndFamily", "byDeviceCode", "byFullTerminal")
            .associateWith { TransitData::class.java.getDeclaredField(it).apply { isAccessible = true } }
        @Suppress("UNCHECKED_CAST")
        val buckets = fields.getValue("resolutionsByCity").get(TransitData) as MutableMap<String, MutableList<StationResolution>>
        @Suppress("UNCHECKED_CAST")
        val cached = fields.getValue("candidatesByCityAndFamily").get(TransitData) as MutableMap<Pair<String, TransitData.TuTransitFamily?>, List<StationResolution>>
        @Suppress("UNCHECKED_CAST")
        val devices = fields.getValue("byDeviceCode").get(TransitData) as MutableMap<String, MutableList<StationResolution>>
        @Suppress("UNCHECKED_CAST")
        val fullTerminals = fields.getValue("byFullTerminal").get(TransitData) as MutableMap<String, Map<String, StationResolution>>
        val oldFullTerminals = fullTerminals.toMap()
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
        // 用户反馈的真实深圳通 0x18 记录：库中 518031 大类公交 vs 用户以完整 Terminal 登记的纠错
        val bus31 = genericBus.copy(deviceCode = "518031", lineName = null)
        val override358 = genericBus.copy(standard = "SZT", deviceCode = "5180000031010680", lineName = "358快线")
        try {
            loaded.setBoolean(TransitData, true)
            buckets["5180"] = mutableListOf(metro, bus, genericBus, bus31, override358)
            for (family in listOf(null, TransitData.TuTransitFamily.RAIL, TransitData.TuTransitFamily.BUS)) {
                cached["5180" to family] = TransitData.tuCandidates(buckets.getValue("5180"), family)
            }
            for (mapping in buckets.getValue("5180")) devices[mapping.deviceCode] = mutableListOf(mapping)
            fullTerminals["CU"] = TransitData.uniqueFullTerminals("CU", buckets.getValue("5180"))
            for (plan in listOf(shenzhenPlan, defaultPlan("5180"))) {
                val entry = TransitData.resolveTuPlan(plan, "000040011001", null, TransitData.TuTransitFamily.RAIL)
                assertEquals("福田口岸", entry?.station)
                assertEquals("5840", entry?.cityCode)
            }
            assertEquals("福田口岸", TransitData.resolveByStandard("SZT", "5180", "000040011001", "000040011001")?.station)
            val record = "0594000000000000780900003101068020261008155441"
            val terminal = record.substring(20, 32)
            assertEquals("358快线", TransitData.resolveByStandard("SZT", "5180", terminal, terminal)?.line)
            assertEquals("福田口岸", TransitData.resolveByStandard("TU", "5180", "518040011001", "518040011001")?.station)
            assertEquals("M337", TransitData.resolveTuPlan(shenzhenPlan, "", "0D310000000000",
                TransitData.TuTransitFamily.BUS)?.line)
            val generic = TransitData.resolveTuPlan(shenzhenPlan, "002099990001", null, TransitData.TuTransitFamily.BUS)
            assertEquals("东部公交", generic?.line)
            assertEquals("(SP Rule: shenzhen)", generic?.spRule)
            val location = TransitData.actualLocation(metro.stationId, metro.deviceCode, "5840", metro.lineId, "TU")
            assertEquals("5840", location.cityCode)
            assertEquals(LocationSource.PARENT_DIRECTORY, location.source)
        } finally {
            buckets.clear(); buckets.putAll(oldBuckets)
            cached.clear(); cached.putAll(oldCached)
            devices.clear(); devices.putAll(oldDevices)
            fullTerminals.clear(); fullTerminals.putAll(oldFullTerminals)
            loaded.setBoolean(TransitData, wasLoaded)
        }
    }

    @Test
    fun bundledRulesRouteRealShenzhenRecordTo5180() {
        // 深圳实卡 TU 1E（城市码 5840）+ 同一时间戳 TU 0x18
        TuLineRules.install(TuLineRules.parse(java.io.File("src/main/assets/data/line_rules.json").readText()))
        try {
            val plan = TuLineRules.solve(
                ApduUtil.hexToBytes("040000000263033114010000000000001600000190000000C820260606194202584012215840FFFF"),
                ApduUtil.hexToBytes("0007000320000001900941310088943720260606194202")
            )
            assertEquals(listOf("5180"), plan?.targets?.map { it.code })
            assertEquals("(SP Rule: shenzhen)", plan?.label)
            assertEquals(null, plan?.city)
        } finally {
            TuLineRules.install(TuLineRules.RuleSet.EMPTY)
        }
    }

    @Test
    fun multiTargetPlanPicksMorePreciseCityAndPrefersFirstOnTie() {
        val loaded = TransitData::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        val cachedField = TransitData::class.java.getDeclaredField("candidatesByCityAndFamily").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cached = cachedField.get(TransitData) as MutableMap<Pair<String, TransitData.TuTransitFamily?>, List<StationResolution>>
        val oldCached = cached.toMap()
        val wasLoaded = loaded.getBoolean(TransitData)
        // 广佛双城计划：同一 raw 码在两桶各有候选；更长的重叠胜出，同等时取第一个目标（广州）
        val gzLine = resolution("0101", "地铁", "广州线路", null).copy(cityCode = "5810", deviceCode = "58100101")
        val fsStation = resolution("010105", "地铁", "佛山线路", "佛山站点").copy(cityCode = "5880", deviceCode = "5880010105")
        val fsLine = fsStation.copy(deviceCode = "58800101", stationName = null, stationId = null)
        val plan = TuLineRules.Plan("5810", null, null,
            listOf(TuLineRules.Target("5810"), TuLineRules.Target("5880")), listOf("guangzhou-foshan"))
        try {
            loaded.setBoolean(TransitData, true)
            cached["5810" to null] = listOf(gzLine)
            cached["5880" to null] = listOf(fsStation)
            val precise = TransitData.resolveTuPlan(plan, "", "01010500000000", null)
            assertEquals("5880010105", precise?.code)
            assertEquals("(SP Rule: guangzhou-foshan)", precise?.spRule)
            cached["5880" to null] = listOf(fsLine)
            assertEquals("58100101", TransitData.resolveTuPlan(plan, "", "01010500000000", null)?.code)
        } finally {
            cached.clear(); cached.putAll(oldCached)
            loaded.setBoolean(TransitData, wasLoaded)
        }
    }

    @Test
    fun cityTargetSearchesEveryPrefixHoldingThatCitysDevicesOnly() {
        val loaded = TransitData::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        val cachedField = TransitData::class.java.getDeclaredField("candidatesByCityAndFamily").apply { isAccessible = true }
        val prefixesField = TransitData::class.java.getDeclaredField("tuPrefixesByCity").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cached = cachedField.get(TransitData) as MutableMap<Pair<String, TransitData.TuTransitFamily?>, List<StationResolution>>
        @Suppress("UNCHECKED_CAST")
        val prefixes = prefixesField.get(TransitData) as MutableMap<String, MutableSet<String>>
        val oldCached = cached.toMap()
        val oldPrefixes = prefixes.toMap()
        val wasLoaded = loaded.getBoolean(TransitData)
        // 库中济宁有 TU 设备落在 3000 前缀（前缀 ≠ 城市）；同桶另有他城设备，按城市查时不得命中
        val jiningInOtherPrefix = resolution("026B", "公交", "济宁线路", null).copy(cityCode = "4610", deviceCode = "3000026B")
        val otherCityLonger = jiningInOtherPrefix.copy(cityCode = "3000", deviceCode = "3000026B01", lineName = "他城线路")
        val jiningOwnPrefix = jiningInOtherPrefix.copy(deviceCode = "461002", lineName = "济宁短码")
        val byCity = TuLineRules.Plan("3630", "4610", null, listOf(TuLineRules.Target("4610", byCity = true)), emptyList())
        val byPrefix = TuLineRules.Plan("3630", "4610", null, listOf(TuLineRules.Target("4610")), emptyList())
        try {
            loaded.setBoolean(TransitData, true)
            cached["3000" to null] = listOf(jiningInOtherPrefix, otherCityLonger)
            cached["4610" to null] = listOf(jiningOwnPrefix)
            prefixes["4610"] = mutableSetOf("3000", "4610")
            val hit = TransitData.resolveTuPlan(byCity, "", "026B0101000000", null)
            assertEquals("3000026B", hit?.code)
            assertEquals("4610", hit?.cityCode)
            // 按前缀只查 4610 桶：只能命中较短的本前缀设备
            assertEquals("461002", TransitData.resolveTuPlan(byPrefix, "", "026B0101000000", null)?.code)
        } finally {
            cached.clear(); cached.putAll(oldCached)
            prefixes.clear(); prefixes.putAll(oldPrefixes)
            loaded.setBoolean(TransitData, wasLoaded)
        }
    }

    @Test
    fun macauPassOverrideMatchesFullTerminalDespiteZeroPrefix() {
        val loaded = TransitData::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        val field = TransitData::class.java.getDeclaredField("byFullTerminal").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val fullTerminals = field.get(TransitData) as MutableMap<String, Map<String, StationResolution>>
        val old = fullTerminals.toMap()
        val wasLoaded = loaded.getBoolean(TransitData)
        // 真机澳门通 0x18 记录 + 用户纠错 0446,000000011476,公交,25B（standard MPASS）
        val override25b = resolution("", "公交", "25B", null).copy(
            cityCode = "0446", cityName = "澳门", standard = "MPASS", deviceCode = "0446000000011476")
        try {
            loaded.setBoolean(TransitData, true)
            fullTerminals["MPASS"] = TransitData.uniqueFullTerminals("MPASS", listOf(override25b))
            val record = "00030000000000001E0900000001147620261011205703"
            val terminal = record.substring(20, 32)
            assertEquals("25B", TransitData.resolveByStandard("MPASS", "0446", terminal, terminal)?.line)
        } finally {
            fullTerminals.clear(); fullTerminals.putAll(old)
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

    private val shenzhenPlan = TuLineRules.Plan("5840", null, null, listOf(TuLineRules.Target("5180")), listOf("shenzhen"))

    private fun defaultPlan(city: String) = TuLineRules.Plan(city, null, null, listOf(TuLineRules.Target(city)), emptyList())

    private fun match(
        candidates: List<StationResolution>,
        body: String,
        family: TransitData.TuTransitFamily?
    ) = TransitData.longestTuMatch("3320", body, family, null, candidates = candidates)?.resolution

    @Test
    fun keyTargetMatchesSplitLineStationExactlyAndFallsBackToLineRow() {
        val loaded = TransitData::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        val cachedField = TransitData::class.java.getDeclaredField("candidatesByCityAndFamily").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cached = cachedField.get(TransitData) as MutableMap<Pair<String, TransitData.TuTransitFamily?>, List<StationResolution>>
        val oldCached = cached.toMap()
        val wasLoaded = loaded.getBoolean(TransitData)
        // 贵阳地铁实卡反馈：线路站点字段 05010117000000 = 1号线 中山西路（库码 0117）；最长重叠会同长命中 0101 窦官
        fun gy(code: String, station: String?) = resolution(code, "地铁", "1号线", station)
            .copy(cityCode = "7010", deviceCode = "7010$code", stationId = station?.let { it.length.toLong() })
        val rows = listOf(gy("01", null), gy("0101", "窦官"), gy("0117", "中山西路"))
        val rawCode = "05010117000000"
        fun plan(station: String, line: String) = TuLineRules.Plan("7010", null, null, listOf(
            TuLineRules.Target("7010", keySlices = listOf(22 until 24, 26 until 28), key = station),
            TuLineRules.Target("7010", fallbackBelow = 4, keySlices = listOf(22 until 24), key = line)
        ), listOf("guiyang-metro"))
        try {
            loaded.setBoolean(TransitData, true)
            cached["7010" to TransitData.TuTransitFamily.RAIL] = rows
            val hit = TransitData.resolveTuPlan(plan("0117", "01"), "", rawCode, TransitData.TuTransitFamily.RAIL)
            assertEquals("70100117", hit?.code)
            assertEquals("(SP Rule: guiyang-metro)", hit?.spRule)
            // 站点不在库：退到 2 位线路行，不按重叠猜站
            assertEquals("701001", TransitData.resolveTuPlan(plan("0199", "01"), "", rawCode,
                TransitData.TuTransitFamily.RAIL)?.code)
            // 切片不可得（key = null）：目标不查
            assertNull(TransitData.resolveTuPlan(plan("0117", "01").let {
                TuLineRules.Plan("7010", null, null, it.targets.map { t ->
                    TuLineRules.Target(t.code, fallbackBelow = t.fallbackBelow, keySlices = t.keySlices) }, it.ruleIds)
            }, "", rawCode, TransitData.TuTransitFamily.RAIL))
        } finally {
            cached.clear(); cached.putAll(oldCached)
            loaded.setBoolean(TransitData, wasLoaded)
        }
    }

    private fun resolution(code: String, type: String, line: String, station: String?) = StationResolution(
        cityId = 1, cityCode = "3320", cityName = "宁波", cityNameEn = "Ningbo",
        lineId = if (type == "公交") 12 else 1, lineName = line, lineNameEn = null,
        lineColor = null, stationId = if (station == null) null else 20,
        stationName = station, stationNameEn = null, standard = "TU", transitType = type,
        deviceCode = "3320$code"
    )
}
