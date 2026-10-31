package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.example.nfctransit.CardProfiles
import com.example.nfctransit.data.db.StationResolution
import com.example.nfctransit.data.repo.TripReaderCard
import com.example.nfctransit.data.repo.TripReaderTransaction
import com.example.nfctransit.data.repo.tripReaderFareProtocol
import com.example.nfctransit.model.CanonicalTransaction
import com.example.nfctransit.model.TransitDirection
import com.example.nfctransit.ui.RawHexFormatter
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordDecoderTest {
    private val nanningFare = RecordDecoder.ZoneRecord(
        0x18, 1, "TU", "0004000000000000B40941310607445120261006133925"
    )

    private fun nanningMapping() = lntMapping("地铁").copy(
        cityId = 219, cityCode = "6110", cityName = "南宁", standard = "TU",
        deviceCode = "6110413106074451", lineId = 3032, stationId = 7004,
        lineName = "3号线", stationName = "青秀山"
    )

    @Test
    fun tuFareWithoutJourneyResolvesUniqueFullTerminalForFreshAndArchivedRecords() {
        withDeviceMappings(listOf(nanningMapping()), prefix = "6110") {
            val fresh = RecordDecoder.decodeCard("TU", listOf(nanningFare), null, 2026).display.single()
            val saved = RecordDecoder.ArchivedRecord(0x18, CardProfiles.TU_EP_AID, nanningFare.hex, fresh.date)
            for (tx in listOf(fresh, RecordDecoder.decodeArchive("TU", listOf(saved)).single())) {
                assertEquals("6110413106074451", tx.deviceCode)
                assertEquals("6110", tx.cityCode)
                assertEquals("4131", tx.rawCityCode)
                assertEquals("3号线", tx.lineName)
                assertEquals("青秀山", tx.stationName)
                assertEquals(3032L, tx.lineId)
                assertEquals(7004L, tx.stationId)
                assertEquals("地铁", tx.transitType)
                assertEquals("20261006", tx.date)
                assertEquals("133925", tx.time)
            }
        }
    }

    @Test
    fun tuFullTerminalRejectsCityOrTransitTypeCollisionsInEitherLoadOrder() {
        val mapping = nanningMapping()
        val conflicts = listOf(
            mapping.copy(deviceCode = "3320413106074451", cityCode = "3320", cityId = 1),
            mapping.copy(transitType = "公交")
        )
        for (conflict in conflicts) {
            for (rows in listOf(listOf(mapping, conflict), listOf(conflict, mapping))) {
                withDeviceMappings(rows, prefix = "6110") {
                    val tx = RecordDecoder.decodeCard("TU", listOf(nanningFare), null, 2026).display.single()
                    assertNull(tx.deviceCode)
                    assertEquals("公共交通", tx.stationName)
                }
            }
        }
    }

    @Test
    fun tuFullTerminalFiltersOtherProtocolsAndRetainsShenzhenCuSharing() {
        val mapping = nanningMapping()
        val unrelatedCu = mapping.copy(standard = "CU", deviceCode = "3140413106074451")
        withDeviceMappings(listOf(mapping, unrelatedCu), prefix = "6110") {
            assertEquals("6110413106074451", TransitData.resolveByStandard(
                "TU", "4131", "413106074451", "413106074451"
            )?.code)
            assertNull(TransitData.resolveByStandard("CU", "4131", "413106074451", "413106074451"))
        }
        withDeviceMappings(listOf(unrelatedCu), prefix = "3140") {
            assertNull(TransitData.resolveByStandard("TU", "4131", "413106074451", "413106074451"))
        }
        val shenzhen = unrelatedCu.copy(deviceCode = "5180413106074451", cityCode = "5840")
        withDeviceMappings(listOf(shenzhen), prefix = "5180") {
            val entry = TransitData.resolveByStandard("TU", "4131", "413106074451", "413106074451")
            assertEquals("5180413106074451", entry?.code)
            assertEquals("5840", entry?.cityCode)
        }
    }

    @Test
    fun tuFullTerminalDoesNotUsePartialCodesOrOverrideExistingPrefixMatch() {
        val mapping = nanningMapping()
        withDeviceMappings(listOf(
            mapping.copy(deviceCode = "61104131060744"),
            mapping.copy(deviceCode = "611000413106074451")
        ), prefix = "6110") {
            assertNull(TransitData.resolveByStandard("TU", "4131", "413106074451", "413106074451"))
        }
        val prefixMatch = mapping.copy(deviceCode = "4131060744", stationName = "前缀命中站")
        withDeviceMappings(listOf(mapping, prefixMatch), prefix = "4131") {
            assertEquals("前缀命中站", TransitData.resolveByStandard(
                "TU", "4131", "413106074451", "413106074451"
            )?.station)
        }
    }

    @Test
    fun importedShanghaiCuFareResolvesLineAndStationWithCuProtocol() {
        val hex = "00540006A4000000000920001109311120260823150028"
        val sourceTx = TripReaderTransaction(0, hex, "", false)
        val sourceCard = TripReaderCard("test", "", 0, "", false, true, false, false,
            false, listOf(sourceTx))
        val mapping = lntMapping("地铁").copy(
            cityCode = "2900", cityName = "上海", standard = "CU", deviceCode = "2000110931",
            lineName = "9号线", stationName = "漕河泾开发区"
        )
        withDeviceMappings(listOf(mapping), prefix = "2000") {
            val protocol = tripReaderFareProtocol(sourceCard, sourceTx)
            val record = RecordDecoder.ZoneRecord(0x18, 1, protocol, hex)
            val fresh = RecordDecoder.decodeCard("CU", listOf(record), null, 2026).display.single()
            val saved = RecordDecoder.ArchivedRecord(
                0x18, CardProfiles.defaultAidForProtocol(protocol, "CU"), hex, fresh.date
            )
            for (tx in listOf(fresh, RecordDecoder.decodeArchive("CU", listOf(saved)).single())) {
                assertEquals("CU", tx.protocol)
                assertEquals("20260823", tx.date)
                assertEquals("150028", tx.time)
                assertEquals("2900", tx.cityCode)
                assertEquals("9号线", tx.lineName)
                assertEquals("漕河泾开发区", tx.stationName)
                assertEquals("2000110931", tx.deviceCode)
            }
        }
    }


    @Test
    fun lntNonZeroFifthTerminalDigitWithoutMetroMappingUsesSeconds() {
        for (digit in listOf('1', '3', '9')) {
            for (type in listOf(null, "公交", "便利店")) {
                val mappings = type?.let { listOf(lntMapping(it).copy(deviceCode = "9900${digit}018")) }
                    ?: emptyList()
                withDeviceMappings(mappings) {
                    for (seconds in listOf(0x00, 0x11, 0x25, 0x59)) {
                        val record = lntRecord(1, 1, "0101", 0x09, seconds, "9900${digit}0180001")
                        val decoded = RecordDecoder.decodeCard("YCT", listOf(record), 202601, 2026)
                        val tx = decoded.display.single()
                        assertEquals("1200${ApduUtil.bytesToHex(byteArrayOf(seconds.toByte()))}", tx.time)
                        assertEquals("09", tx.typeHex)
                        assertNull(tx.direction)
                        assertEquals(type ?: "公共交通", tx.transitType)
                        val field = RawHexFormatter.fieldsFor(0x18, 23, "LNT", record.hex).last()
                        assertEquals("Seconds", field.label)
                        assertEquals("BCD", field.method)
                        assertEquals(RawHexFormatter.TIMESTAMP, field.color)
                        assertFalse(RawHexFormatter.copyText(listOf(RawRecord(0x18, 1, "LNT", record.hex)))
                            .contains("[Subtype"))

                        val row = RecordDecoder.ArchivedRecord(0x18, CardProfiles.YCT_TICL_AID, record.hex, tx.date)
                        val restored = RecordDecoder.decodeArchive("YCT", listOf(row)).single()
                        assertEquals(tx.time, restored.time)
                        assertEquals(tx.transitType, restored.transitType)
                        assertNull(restored.direction)
                    }
                }
            }
        }
    }

    @Test
    fun lntNonZeroFifthTerminalDigitWithMetroMappingKeepsSubtype() =
        withDeviceMappings(listOf(lntMapping("地铁").copy(deviceCode = "99003018"))) {
            for (subtype in listOf(0x11, 0x17, 0x31)) {
                val record = lntRecord(1, 1, "0101", 0x09, subtype, "990030180001")
                val tx = RecordDecoder.decodeCard("YCT", listOf(record), 202601, 2026).display.single()
                assertEquals("120000", tx.time)
                assertEquals("地铁", tx.transitType)
                assertEquals(if (subtype == 0x11) TransitDirection.ENTRY else TransitDirection.EXIT, tx.direction)
                assertEquals("Subtype", RawHexFormatter.fieldsFor(0x18, 23, "LNT", record.hex).last().label)
            }
        }

    @Test
    fun lntZeroFifthTerminalDigitKeepsSubtypeWithoutMapping() = withDeviceMappings(emptyList()) {
        val tx = decodeLnt(subtype = 0x31)
        assertEquals("120000", tx.time)
        assertEquals("地铁", tx.transitType)
        assertEquals(TransitDirection.EXIT, tx.direction)
    }

    @Test
    fun lntNonZeroFifthTerminalDigitKeeps17And31SubtypeWithoutMetroMapping() {
        for (type in listOf(null, "公交", "便利店")) {
            val mappings = type?.let { listOf(lntMapping(it).copy(deviceCode = "99003018")) }
                ?: emptyList()
            withDeviceMappings(mappings) {
                for (subtype in listOf(0x17, 0x31)) {
                    val record = lntRecord(1, 1, "0101", 0x09, subtype, "990030180001")
                    val tx = RecordDecoder.decodeCard("YCT", listOf(record), 202601, 2026).display.single()
                    assertEquals("120000", tx.time)
                    assertEquals(type ?: "地铁", tx.transitType)
                    assertEquals(if (type == null) TransitDirection.EXIT else null, tx.direction)
                    assertEquals("Subtype", RawHexFormatter.fieldsFor(0x18, 23, "LNT", record.hex).last().label)
                }
            }
        }
    }

    @Test
    fun lntNonZeroFifthTerminalDigitKeepsConvenienceSubtype17() = withDeviceMappings(emptyList()) {
        val record = lntRecord(1, 1, "0101", 0x06, 0x17, "990030180001")
        val tx = RecordDecoder.decodeCard("YCT", listOf(record), 202601, 2026).display.single()
        assertEquals("120000", tx.time)
        assertEquals("便利店", tx.transitType)
        assertNull(tx.direction)
    }

    @Test
    fun lntExitSubtypesUseBusMappingWithoutRailDirection() = withDeviceMappings(listOf(lntMapping("公交"))) {
        for (subtype in listOf(0x17, 0x31)) {
            val tx = decodeLnt(subtype = subtype)
            assertEquals("公交", tx.transitType)
            assertEquals("测试公交", tx.lineName)
            assertEquals("99000018", tx.deviceCode)
            assertEquals("09", tx.typeHex)
            assertNull(tx.direction)
        }
    }

    @Test
    fun lntExitSubtypesKeepRailExitWhenLongerRailMappingWins() = withDeviceMappings(listOf(
        lntMapping("公交"),
        lntMapping("地铁").copy(deviceCode = "990000180001")
    )) {
        for (subtype in listOf(0x17, 0x31)) {
            val tx = decodeLnt(subtype = subtype)
            assertEquals("地铁", tx.transitType)
            assertEquals("测试站", tx.stationName)
            assertEquals("990000180001", tx.deviceCode)
            assertEquals(TransitDirection.EXIT, tx.direction)
        }
    }

    @Test
    fun lntExitSubtypesWithoutMappingKeepMetroExitFallback() = withDeviceMappings(emptyList()) {
        for (subtype in listOf(0x17, 0x31)) {
            val tx = decodeLnt(subtype = subtype)
            assertEquals("地铁", tx.transitType)
            assertNull(tx.deviceCode)
            assertEquals(TransitDirection.EXIT, tx.direction)
        }
    }

    @Test
    fun lntExitSubtypesKeepNonRailMapping() = withDeviceMappings(listOf(lntMapping("便利店"))) {
        for (subtype in listOf(0x17, 0x31)) {
            val tx = decodeLnt(subtype = subtype)
            assertEquals("便利店", tx.transitType)
            assertNull(tx.direction)
        }
    }

    @Test
    fun lntEntryAndRetailFallbacksRemainWithoutMapping() = withDeviceMappings(emptyList()) {
        val entry = decodeLnt(subtype = 0x11)
        assertEquals("地铁", entry.transitType)
        assertEquals(TransitDirection.ENTRY, entry.direction)
        val retail = decodeLnt(type = 0x06, subtype = 0x17)
        assertEquals("便利店", retail.transitType)
        assertNull(retail.direction)
    }

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

    @Test
    fun tuSubtypeDeterminesTransitFamily() {
        assertEquals(TransitData.TuTransitFamily.RAIL, TransitData.tuTransitFamilyForSubtype(0x01))
        assertEquals(TransitData.TuTransitFamily.BUS, TransitData.tuTransitFamilyForSubtype(0x02))
        assertNull(TransitData.tuTransitFamilyForSubtype(0x06))
    }

    @Test
    fun tuFamilyMatchingIncludesRailVariantsAndBusVariants() {
        assertTrue(TransitData.matchesTuTransitFamily("地铁", TransitData.TuTransitFamily.RAIL))
        assertTrue(TransitData.matchesTuTransitFamily("有轨电车", TransitData.TuTransitFamily.RAIL))
        assertTrue(TransitData.matchesTuTransitFamily("train", TransitData.TuTransitFamily.RAIL))
        assertFalse(TransitData.matchesTuTransitFamily("公交", TransitData.TuTransitFamily.RAIL))
        assertTrue(TransitData.matchesTuTransitFamily("公交", TransitData.TuTransitFamily.BUS))
        assertTrue(TransitData.matchesTuTransitFamily("BRT", TransitData.TuTransitFamily.BUS))
        assertTrue(TransitData.matchesTuTransitFamily("有轨电车", TransitData.TuTransitFamily.BUS))
        assertFalse(TransitData.matchesTuTransitFamily("地铁", TransitData.TuTransitFamily.BUS))
    }

    @Test
    fun tuCandidatePriorityUsesLengthAlignmentPositionThenNonZero() {
        assertTrue(
            TransitData.isBetterTuCandidate(
                length = 8,
                aligned = false,
                nonZeroLength = 1,
                index = 10,
                bestLength = 7,
                bestAligned = true,
                bestNonZeroLength = 7,
                bestIndex = 2
            )
        )
        assertTrue(
            TransitData.isBetterTuCandidate(
                length = 8,
                aligned = true,
                nonZeroLength = 1,
                index = 10,
                bestLength = 8,
                bestAligned = false,
                bestNonZeroLength = 8,
                bestIndex = 2
            )
        )
        assertFalse(
            TransitData.isBetterTuCandidate(
                length = 8,
                aligned = true,
                nonZeroLength = 8,
                index = 10,
                bestLength = 8,
                bestAligned = true,
                bestNonZeroLength = 7,
                bestIndex = 2
            )
        )
        assertTrue(
            TransitData.isBetterTuCandidate(
                length = 8,
                aligned = true,
                nonZeroLength = 8,
                index = 2,
                bestLength = 8,
                bestAligned = true,
                bestNonZeroLength = 1,
                bestIndex = 2
            )
        )
    }

    @Test
    fun tuTailAlignmentAnchorsAtBcdByteEnd() {
        assertEquals(1, TransitData.tuTailAlignedPatternIndex(pattern = "069", body = "30690000000000"))
        assertEquals(-1, TransitData.tuTailAlignedPatternIndex(pattern = "306", body = "30690000000000"))
        assertEquals(1, TransitData.tuTailAlignedPatternIndex(pattern = "069", body = "306901000"))
    }

    @Test
    fun tuType03And04AreEntryAndExitForAnyTransitFamily() {
        for (type in listOf(null, "地铁", "公交", "城际", "有轨电车")) {
            assertEquals(TransitDirection.ENTRY, RecordDecoder.tuDirectionForType(0x03, type))
            assertEquals(TransitDirection.EXIT, RecordDecoder.tuDirectionForType(0x04, type))
        }
    }

    @Test
    fun tuType06UsesMappedMetroTypeWithUnknownSubtype() {
        for (type in listOf("地铁", "公交", "城际", "有轨电车")) {
            withDeviceMappings(listOf(lntMapping(type).copy(standard = "TU"))) {
                val tx = RecordDecoder.decodeCard("TU", listOf(tuJourneyRecord(0x06, 0x00)), null, 2026)
                    .display.single()
                assertEquals("06", tx.typeHex)
                assertEquals("99000018", tx.deviceCode)
                assertEquals(type, tx.transitType)
                assertEquals(if (type == "地铁") TransitDirection.ENTRY else null, tx.direction)
            }
        }
    }

    @Test
    fun tuType06UsesSubtypeFallbackWhenMappingIsMissing() = withDeviceMappings(emptyList()) {
        for (subtype in listOf(0x01, 0x02, 0x00)) {
            val tx = RecordDecoder.decodeCard("TU", listOf(tuJourneyRecord(0x06, subtype, "00181011")), null, 2026)
                .display.single()
            assertNull(tx.deviceCode)
            assertEquals(if (subtype == 0x01) TransitDirection.ENTRY else null, tx.direction)
        }
    }

    @Test
    fun tuRailFallbackRequiresNonZeroLineAndStation() = withDeviceMappings(emptyList()) {
        for ((code, expected) in listOf("00181011" to "地铁", "01000000" to "公交", "00001011" to "公交")) {
            val tx = RecordDecoder.decodeCard("TU", listOf(tuJourneyRecord(0x03, 0x01, code)), null, 2026)
                .display.single()
            assertNull(tx.deviceCode)
            assertEquals(code, expected, tx.transitType)
        }
    }

    @Test
    fun fareType06KeepsJourneyDirectionWhenMerged() = withDeviceMappings(emptyList()) {
        for (type in listOf(0x06, 0x04)) {
            val data = ByteArray(0x17)
            data[1] = 1
            data[8] = 0x64
            data[9] = 0x06
            ApduUtil.hexToBytes("990000180001").copyInto(data, destinationOffset = 10)
            ApduUtil.hexToBytes("20260101120000").copyInto(data, destinationOffset = 16)
            val fare = RecordDecoder.ZoneRecord(0x18, 1, "TU", ApduUtil.bytesToHex(data))
            val decoded = RecordDecoder.decodeCard("TU", listOf(tuJourneyRecord(type, 0x01, "00181011"), fare), null, 2026)
            val tx = decoded.display.single()
            assertEquals("06", tx.typeHex)
            assertEquals(if (type == 0x06) TransitDirection.ENTRY else TransitDirection.EXIT, tx.direction)
        }
    }

    @Test
    fun lntYearInferenceUsesPhysicalRecordOrderWhenCountersAreIndependent() {
        val records = listOf(
            lntRecord(1, 0x0101, "0413"),
            lntRecord(2, 0x0002, "1224", type = 0x02),
            lntRecord(3, 0x0100, "0718")
        )

        val decoded = RecordDecoder.decodeCard("YCT", records, 202604, 2026)

        assertEquals(
            listOf("20260413", "20251224", "20250718"),
            decoded.archive.map { it.date }
        )
    }

    @Test
    fun lntYearInferenceUsesCurrentMonthAnchorWhenStatsMonthIsMissing() {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val records = listOf(
            lntRecord(1, 0x0101, "0101"),
            lntRecord(2, 0x0002, "1231"),
            lntRecord(3, 0x0100, "1201")
        )

        val decoded = RecordDecoder.decodeCard("YCT", records, null, currentYear)

        assertEquals(
            listOf(
                "${currentYear}0101",
                "${currentYear - 1}1231",
                "${currentYear - 1}1201"
            ),
            decoded.archive.map { it.date }
        )
    }

    @Test
    fun cuAndTu18CopiesWithSameTransactionFieldsMerge() {
        val records = listOf(
            RecordDecoder.ZoneRecord(
                sfi = 0x18,
                recNo = 1,
                protocol = "CU",
                hex = "00070000C8000001900941310088943720260606194202"
            ),
            RecordDecoder.ZoneRecord(
                sfi = 0x18,
                recNo = 1,
                protocol = "TU",
                hex = "0007000320000001900941310088943720260606194202"
            ),
            RecordDecoder.ZoneRecord(
                sfi = 0x1E,
                recNo = 1,
                protocol = "TU",
                hex = "040000000263033114010000000000001600000190000000C820260606194202584012215840FFFF"
            )
        )
        val decoded = RecordDecoder.decodeCard("CU", records, null, 2026)

        // The complete timestamp associates the journey even when its terminal differs.
        assertEquals(1, decoded.display.size)
        val fare = decoded.display.single { it.sfi == 0x18 }
        assertEquals("194202", fare.time)
        assertEquals(setOf("CU", "TU"), fare.protocols)
        // 每条原始记录各自带来源：TU 0x18 + 合并的 TU 1E + CU 应用的 0x18
        assertEquals(
            setOf(
                "TU" to "0007000320000001900941310088943720260606194202",
                "TU" to "040000000263033114010000000000001600000190000000C820260606194202584012215840FFFF",
                "CU" to "00070000C8000001900941310088943720260606194202"
            ),
            fare.sources.map { it.protocol to it.hex }.toSet()
        )
        assertEquals(3, decoded.archive.size)
    }

    @Test
    fun identicalCuTuFareCopiesKeepTheMatchedJourney() {
        val fareHex = "00070000C8000001900941310088943720260606194202"
        val journeyHex = "040000000263033114010000000000001600000190000000C820260606194202584012215840FFFF"
        val records = listOf(
            RecordDecoder.ZoneRecord(0x18, 1, "CU", fareHex),
            RecordDecoder.ZoneRecord(0x18, 1, "TU", fareHex),
            RecordDecoder.ZoneRecord(0x1E, 1, "TU", journeyHex)
        )
        val decoded = RecordDecoder.decodeCard("CU", records, null, 2026)
        val merged = decoded.display.single()
        assertEquals(journeyHex, merged.journeyHex)
        assertEquals(200L, merged.balanceAfterFen)
        assertEquals(setOf("CU", "TU"), merged.protocols)
        assertEquals(3, decoded.archive.size)
        val archived = decoded.archive.map { t ->
            RecordDecoder.ArchivedRecord(
                t.sfi, CardProfiles.defaultAidForProtocol(t.protocol, "CU"), t.hex, t.date
            )
        }
        val restored = RecordDecoder.decodeArchive("CU", archived).single()
        assertEquals(journeyHex, restored.journeyHex)
        assertEquals(200L, restored.balanceAfterFen)
    }

    @Test
    fun mergeForDisplayMergesSameTransactionAcrossProtocols() {
        val cu = transaction("cu").copy(protocol = "CU")
        val tu = transaction("tu").copy(protocol = "TU")

        val merged = RecordDecoder.mergeForDisplay(listOf(cu, tu))

        assertEquals(1, merged.size)
        assertEquals(setOf("CU", "TU"), merged.single().protocols)
    }

    @Test
    fun mergeForDisplayKeepsTransactionsWhenDisplayKeyDiffers() {
        val base = transaction("base")
        val variants = listOf(
            base.copy(identity = "time", time = "120001"),
            base.copy(identity = "amount", amountFen = 300),
            base.copy(identity = "terminal", terminal = "4131000001"),
            base.copy(identity = "type", typeHex = "02")
        )

        assertEquals(5, RecordDecoder.mergeForDisplay(listOf(base) + variants).size)
    }

    private fun lntRecord(
        recNo: Int,
        sequence: Int,
        mmdd: String,
        type: Int = 0x06,
        subtype: Int = 0x17,
        terminal: String = "000000000000"
    ): RecordDecoder.ZoneRecord {
        val data = ByteArray(0x17)
        data[0] = (sequence shr 8).toByte()
        data[1] = sequence.toByte()
        data[6] = 0x00
        data[7] = 0x00
        data[8] = 0x64
        data[9] = type.toByte()
        ApduUtil.hexToBytes(terminal).copyInto(data, destinationOffset = 10)
        data[18] = bcd(mmdd.substring(0, 2))
        data[19] = bcd(mmdd.substring(2, 4))
        data[20] = bcd("12")
        data[21] = bcd("00")
        data[22] = subtype.toByte()
        return RecordDecoder.ZoneRecord(0x18, recNo, "LNT", ApduUtil.bytesToHex(data))
    }

    @Test
    fun lntYearsAlignAfterLatestArchivedRow() {
        fun lnt(date: String) = CanonicalTransaction(
            identity = date, sequence = 0, amountFen = 0, balanceAfterFen = null, typeHex = "09",
            terminal = "", date = date, time = "120000", sfi = 0x18, protocol = "LNT", hex = ""
        )
        // 统计月份锚点误把一年以上空窗后的记录推成旧年份：以最后落库的 20250310 为下界逐条前推（含跨年）
        val aligned = RecordDecoder.alignLntYears(
            listOf(lnt("20250305"), lnt("20251220"), lnt("20250102")), "20250310", today = "20270615"
        )
        assertEquals(listOf("20260305", "20261220", "20270102"), aligned.map { it.date })
        // 越过今天的候选视为矛盾，保留原推断；无下界时原样返回
        val bounded = RecordDecoder.alignLntYears(listOf(lnt("20250305"), lnt("20250401")), "20250310", today = "20260320")
        assertEquals(listOf("20260305", "20250401"), bounded.map { it.date })
        assertEquals(listOf("20250305"), RecordDecoder.alignLntYears(listOf(lnt("20250305")), null).map { it.date })
    }

    private fun bcd(value: String): Byte =
        ((value[0] - '0') shl 4 or (value[1] - '0')).toByte()

    private fun decodeLnt(type: Int = 0x09, subtype: Int) = RecordDecoder.decodeCard(
        "YCT", listOf(lntRecord(1, 1, "0101", type, subtype, "990000180001")), 202601, 2026
    ).display.single()

    private fun lntMapping(type: String) = StationResolution(
        cityId = 1, cityCode = "9900", cityName = "测试城市", cityNameEn = null,
        lineId = 1, lineName = if (type == "地铁") "测试地铁" else "测试公交", lineNameEn = null,
        lineColor = null, stationId = if (type == "地铁") 20 else null,
        stationName = if (type == "地铁") "测试站" else null, stationNameEn = null,
        standard = "YCT", transitType = type, deviceCode = "99000018"
    )

    private fun tuJourneyRecord(type: Int, subtype: Int, lineStation: String = "00180000"): RecordDecoder.ZoneRecord {
        val data = ByteArray(48)
        data[0] = type.toByte()
        ApduUtil.hexToBytes("990000180001").copyInto(data, destinationOffset = 3)
        data[9] = subtype.toByte()
        ApduUtil.hexToBytes(lineStation + "000000").copyInto(data, destinationOffset = 10)
        data[20] = 0x64
        ApduUtil.hexToBytes("20260101120000").copyInto(data, destinationOffset = 25)
        ApduUtil.hexToBytes("9900").copyInto(data, destinationOffset = 32)
        return RecordDecoder.ZoneRecord(0x1E, 1, "TU", ApduUtil.bytesToHex(data))
    }

    private fun withDeviceMappings(mappings: List<StationResolution>, prefix: String = "9900", block: () -> Unit) {
        val loaded = TransitData::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        val candidates = TransitData::class.java.getDeclaredField("candidatesByCityAndFamily").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val index = candidates.get(TransitData) as MutableMap<Pair<String, TransitData.TuTransitFamily?>, List<StationResolution>>
        val key = prefix to null
        val previous = index[key]
        val rawBuckets = TransitData::class.java.getDeclaredField("resolutionsByCity").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val rawIndex = rawBuckets.get(TransitData) as MutableMap<String, MutableList<StationResolution>>
        val previousRaw = rawIndex[prefix]
        val terminals = TransitData::class.java.getDeclaredField("byTuFullTerminal").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val terminalIndex = terminals.get(TransitData) as MutableMap<String, StationResolution>
        val previousTerminals = terminalIndex.toMap()
        val wasLoaded = loaded.getBoolean(TransitData)
        try {
            loaded.setBoolean(TransitData, true)
            index[key] = mappings
            rawIndex[prefix] = mappings.toMutableList()
            terminalIndex.clear()
            terminalIndex.putAll(TransitData.uniqueTuFullTerminals(mappings))
            block()
        } finally {
            if (previous == null) index.remove(key) else index[key] = previous
            if (previousRaw == null) rawIndex.remove(prefix) else rawIndex[prefix] = previousRaw
            terminalIndex.clear()
            terminalIndex.putAll(previousTerminals)
            loaded.setBoolean(TransitData, wasLoaded)
        }
    }

    private fun transaction(
        identity: String,
        cityCode: String = "0755",
        rawCityCode: String = "0755",
        stationName: String = "公共交通"
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

    // 实卡 TU 钱包（A000000632010105）同一次读卡的四个文件
    private val tuFare18 = "051D000000000000640941310084760620261030215013"
    private val tuJourney1E = "060000413100847606020000000000F1C300000064000006D020261030215013585011355850FFFFFFFF000000000000"
    private val tuStats19 = "012E0026080B2F3A071C4B32524E00000000000000000000000000000000000000000000000000000000000000000000"
    private val tuModeSlot1A = "27017D01010000000000000000780258105810122058100000000012205810000000000500000000000000030000000000000000004131" +
        "017373610000413101330560202608292153072026082922010800000064010100000000000003B0000000640000000000000000000000000000000000000000000000000000000000"

    private fun tuWalletRecords() = listOf(
        RawRecord(0x18, 1, "TU", tuFare18, CardProfiles.TU_EP_AID),
        RawRecord(0x1E, 1, "TU", tuJourney1E, CardProfiles.TU_EP_AID),
        RawRecord(0x19, 1, "TU", tuStats19, CardProfiles.TU_EP_AID),
        RawRecord(0x1A, 1, "TU", tuModeSlot1A, CardProfiles.TU_EP_AID)
    )

    @Test
    fun tuWalletDecodesOnlyItsCyclicTransactionFiles() {
        val zones = RecordDecoder.transactionRecords("TU", tuWalletRecords())
        assertEquals(setOf(0x18, 0x1E), zones.map { it.sfi }.toSet())
    }

    @Test
    fun transactionSfisFollowTheRecordsApplicationNotTheCardType() {
        // 双协议 CU 卡上 CU 应用的 0x1A 是交易区，但 TU 应用的 0x1A 是各交通方式定槽文件
        val zones = RecordDecoder.transactionRecords("CU", tuWalletRecords())
        assertEquals(setOf(0x18, 0x1E), zones.map { it.sfi }.toSet())
        assertEquals(setOf(0x18, 0x10, 0x06, 0x1A), CardProfiles.transactionSfisForAid(CardProfiles.CU_AID))
        assertEquals(setOf(0x18, 0x1E), CardProfiles.transactionSfisForAid(CardProfiles.TU_EP_AID))
        assertEquals(setOf(0x18), CardProfiles.transactionSfisForAid(CardProfiles.YCT_TICL_AID))
    }
}
