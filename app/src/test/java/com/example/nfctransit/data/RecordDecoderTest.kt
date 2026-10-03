package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.example.nfctransit.data.db.StationResolution
import com.example.nfctransit.data.db.ArchivedTransactionEntity
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
                        assertEquals(type?.let { TransitData.transitTypeLabel(it) } ?: "公共交通", tx.transitType)
                        val field = RawHexFormatter.fieldsFor(0x18, 23, "LNT", record.hex).last()
                        assertEquals("Seconds", field.label)
                        assertEquals("BCD", field.method)
                        assertEquals(RawHexFormatter.TIMESTAMP, field.color)
                        assertFalse(RawHexFormatter.copyText(listOf(RawRecord(0x18, 1, "LNT", record.hex)))
                            .contains("[Subtype"))

                        val row = ArchivedTransactionEntity(
                            cardId = "test", sfi = "0x18", protocol = "LNT", hex = record.hex,
                            contentHash = RecordDecoder.contentHash(record.hex), resolvedDate = tx.date,
                            firstSeenAt = 0, lastSeenAt = 0
                        )
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
                assertEquals(TransitData.transitTypeLabel("地铁"), tx.transitType)
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
                    assertEquals(type?.let { TransitData.transitTypeLabel(it) } ?: "地铁", tx.transitType)
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
            assertEquals(TransitData.transitTypeLabel("公交"), tx.transitType)
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
            assertEquals(TransitData.transitTypeLabel("地铁"), tx.transitType)
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
            assertEquals(TransitData.transitTypeLabel("便利店"), tx.transitType)
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
                assertEquals(TransitData.transitTypeLabel(type), tx.transitType)
                assertEquals(if (type == "地铁") TransitDirection.ENTRY else null, tx.direction)
            }
        }
    }

    @Test
    fun tuType06UsesSubtypeFallbackWhenMappingIsMissing() = withDeviceMappings(emptyList()) {
        for (subtype in listOf(0x01, 0x02, 0x00)) {
            val tx = RecordDecoder.decodeCard("TU", listOf(tuJourneyRecord(0x06, subtype)), null, 2026)
                .display.single()
            assertNull(tx.deviceCode)
            assertEquals(if (subtype == 0x01) TransitDirection.ENTRY else null, tx.direction)
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
            val decoded = RecordDecoder.decodeCard("TU", listOf(tuJourneyRecord(type, 0x01), fare), null, 2026)
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

        assertEquals(1, decoded.display.size)
        assertEquals("194202", decoded.display.single().time)
        assertEquals(setOf("CU", "TU"), decoded.display.single().protocols)
        assertEquals(
            listOf("00070000C8000001900941310088943720260606194202"),
            decoded.display.single().rawVariants.map { it.hex }
        )
        assertEquals(3, decoded.archive.size)
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
        standard = "YCT", transitType = type, deviceCode = "99000018", deviceLocation = null, matchKey = null
    )

    private fun tuJourneyRecord(type: Int, subtype: Int): RecordDecoder.ZoneRecord {
        val data = ByteArray(48)
        data[0] = type.toByte()
        ApduUtil.hexToBytes("990000180001").copyInto(data, destinationOffset = 3)
        data[9] = subtype.toByte()
        ApduUtil.hexToBytes("00180000000000").copyInto(data, destinationOffset = 10)
        data[20] = 0x64
        ApduUtil.hexToBytes("20260101120000").copyInto(data, destinationOffset = 25)
        ApduUtil.hexToBytes("9900").copyInto(data, destinationOffset = 32)
        return RecordDecoder.ZoneRecord(0x1E, 1, "TU", ApduUtil.bytesToHex(data))
    }

    private fun withDeviceMappings(mappings: List<StationResolution>, block: () -> Unit) {
        val loaded = TransitData::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        val candidates = TransitData::class.java.getDeclaredField("candidatesByCityAndFamily").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val index = candidates.get(TransitData) as MutableMap<Pair<String, TransitData.TuTransitFamily?>, List<StationResolution>>
        val key = "9900" to null
        val previous = index[key]
        val wasLoaded = loaded.getBoolean(TransitData)
        try {
            loaded.setBoolean(TransitData, true)
            index[key] = mappings
            block()
        } finally {
            if (previous == null) index.remove(key) else index[key] = previous
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
}
