package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.example.nfctransit.model.CanonicalTransaction
import com.example.nfctransit.model.TransitDirection
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun tuCandidatePriorityUsesLengthAlignmentNonZeroThenPosition() {
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
        assertTrue(
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
                bestNonZeroLength = 8,
                bestIndex = 10
            )
        )
    }

    @Test
    fun tuType03And04AreEntryAndExitForAnyTransitFamily() {
        assertEquals(TransitDirection.ENTRY, RecordDecoder.tuDirectionForType(0x03))
        assertEquals(TransitDirection.EXIT, RecordDecoder.tuDirectionForType(0x04))
        assertNull(RecordDecoder.tuDirectionForType(0x06))
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
        type: Int = 0x06
    ): RecordDecoder.ZoneRecord {
        val data = ByteArray(0x17)
        data[0] = (sequence shr 8).toByte()
        data[1] = sequence.toByte()
        data[6] = 0x00
        data[7] = 0x00
        data[8] = 0x64
        data[9] = type.toByte()
        data[18] = bcd(mmdd.substring(0, 2))
        data[19] = bcd(mmdd.substring(2, 4))
        data[20] = bcd("12")
        data[21] = bcd("00")
        data[22] = 0x17
        return RecordDecoder.ZoneRecord(0x18, recNo, "LNT", ApduUtil.bytesToHex(data))
    }

    private fun bcd(value: String): Byte =
        ((value[0] - '0') shl 4 or (value[1] - '0')).toByte()

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
