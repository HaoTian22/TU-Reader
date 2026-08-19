package com.example.nfctransit.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentTripRouteDisplayModeTest {

    @Test
    fun fromPersistedValue_restoresKnownModes() {
        assertEquals(
            CurrentTripRouteDisplayMode.ENDPOINTS_ONLY,
            CurrentTripRouteDisplayMode.fromPersistedValue("ENDPOINTS_ONLY")
        )
        assertEquals(
            CurrentTripRouteDisplayMode.FULL_TRANSFERS,
            CurrentTripRouteDisplayMode.fromPersistedValue("FULL_TRANSFERS")
        )
    }

    @Test
    fun fromPersistedValue_defaultsToEndpointsForMissingOrInvalidValue() {
        assertEquals(
            CurrentTripRouteDisplayMode.ENDPOINTS_ONLY,
            CurrentTripRouteDisplayMode.fromPersistedValue(null)
        )
        assertEquals(
            CurrentTripRouteDisplayMode.ENDPOINTS_ONLY,
            CurrentTripRouteDisplayMode.fromPersistedValue("UNKNOWN")
        )
    }
}
