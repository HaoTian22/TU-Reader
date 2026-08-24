package com.example.nfctransit.data

import com.example.nfctransit.model.CanonicalTransaction
import com.example.nfctransit.model.TransitDirection
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
