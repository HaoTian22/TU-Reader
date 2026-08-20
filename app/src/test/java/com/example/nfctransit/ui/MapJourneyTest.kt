package com.example.nfctransit.ui

import com.example.nfctransit.data.route.TransitFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapJourneyTest {

    private val boarding = MapEvent(
        stationId = 1,
        name = "公交上车站",
        lineName = "M100",
        lineColor = null,
        lng = 114.0,
        lat = 22.5,
        timeMillis = 1_700_000_000_000,
        direction = MapDirection.NONE,
        transitFamily = TransitFamily.BUS
    )

    @Test
    fun routeEligibility_requiresReliableSameCityEndpoints() {
        val singlePointBus = MapSegment(
            from = boarding,
            to = null,
            lineName = boarding.lineName,
            lineColor = boarding.lineColor,
            startTime = boarding.timeMillis,
            endTime = boarding.timeMillis
        )
        assertFalse(isTransitRouteEligible(singlePointBus, fromCityId = 1, toCityId = null))

        val destination = boarding.copy(
            stationId = 2,
            name = "公交下车站",
            timeMillis = boarding.timeMillis + 600_000
        )
        val completeTrip = singlePointBus.copy(to = destination, endTime = destination.timeMillis)
        assertTrue(isTransitRouteEligible(completeTrip, fromCityId = 1, toCityId = 1))
        assertFalse(isTransitRouteEligible(completeTrip, fromCityId = 1, toCityId = 2))
        assertFalse(isTransitRouteEligible(completeTrip, fromCityId = null, toCityId = 1))

        val mixedTrip = completeTrip.copy(
            to = destination.copy(transitFamily = TransitFamily.SUBWAY)
        )
        assertFalse(isTransitRouteEligible(mixedTrip, fromCityId = 1, toCityId = 1))

        val unknownTrip = completeTrip.copy(
            from = boarding.copy(transitFamily = TransitFamily.ANY),
            to = destination.copy(transitFamily = TransitFamily.ANY)
        )
        assertFalse(isTransitRouteEligible(unknownTrip, fromCityId = 1, toCityId = 1))
    }

    @Test
    fun transitFamily_keepsTransfersWithinRecordedMode() {
        assertEquals(TransitFamily.SUBWAY, transitFamilyOf("地铁"))
        assertEquals(TransitFamily.SUBWAY, transitFamilyOf("轻轨"))
        assertEquals(TransitFamily.BUS, transitFamilyOf("公交"))
        assertEquals(TransitFamily.BUS, transitFamilyOf("BRT"))
        assertEquals(TransitFamily.RAIL, transitFamilyOf("城际"))

        val subwayStart = boarding.copy(transitFamily = TransitFamily.SUBWAY)
        val subwayEnd = boarding.copy(stationId = 2, transitFamily = TransitFamily.SUBWAY)
        assertEquals(
            TransitFamily.SUBWAY,
            MapSegment(subwayStart, subwayEnd, "1号线", null, 0, 1).requiredTransitFamily
        )

        val busEnd = subwayEnd.copy(transitFamily = TransitFamily.BUS)
        assertEquals(
            TransitFamily.ANY,
            MapSegment(subwayStart, busEnd, "", null, 0, 1).requiredTransitFamily
        )
    }
}
