package com.example.nfctransit.ui

import com.example.nfctransit.data.prefs.CurrentTripRouteDisplayMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTripRoutePresentationTest {

    @Test
    fun endpointsMode_neverExpandsResolvedTransferRoute() {
        assertFalse(
            shouldShowFullCurrentTripRoute(
                CurrentTripRouteDisplayMode.ENDPOINTS_ONLY,
                resolvedTransitLegCount = 3
            )
        )
    }

    @Test
    fun fullMode_expandsOnlyWhenResolvedTransitLegsExist() {
        assertTrue(
            shouldShowFullCurrentTripRoute(
                CurrentTripRouteDisplayMode.FULL_TRANSFERS,
                resolvedTransitLegCount = 3
            )
        )
        assertFalse(
            shouldShowFullCurrentTripRoute(
                CurrentTripRouteDisplayMode.FULL_TRANSFERS,
                resolvedTransitLegCount = 0
            )
        )
    }
}
