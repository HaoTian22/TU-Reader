package com.example.nfctransit.data.route

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RouteCacheStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun routeAndStationEntries_roundTripAndOverwrite() {
        val store = RouteCacheStore(temporaryFolder.root)
        val routeKey = "a".repeat(64)
        val firstRoute = RouteCacheEntry(
            status = RouteCacheEntry.STATUS_SUCCESS,
            responseJson = "{\"status\":0,\"result\":\"first\"}",
            isEstimate = true,
            fetchedAt = 1_000L,
            expiresAt = 2_000L
        )
        store.saveRoute(routeKey, firstRoute)

        assertEquals(firstRoute, store.loadRoute(routeKey, now = 1_500L))
        assertTrue(cacheFile("route_${routeKey}.json").exists())

        val updatedRoute = firstRoute.copy(
            responseJson = "{\"status\":0,\"result\":\"updated\"}",
            fetchedAt = 1_500L,
            expiresAt = 3_000L
        )
        store.saveRoute(routeKey, updatedRoute)
        assertEquals(updatedRoute, store.loadRoute(routeKey, now = 2_000L))
        assertFalse(cacheDirectory().listFiles().orEmpty().any { it.extension == "tmp" })

        val stationKey = "b".repeat(64)
        val station = StationCoordinateCacheEntry(
            lat = 22.543096,
            lng = 114.057865,
            matchedTitle = "罗湖站",
            expiresAt = 5_000L
        )
        store.saveStation(stationKey, station)

        assertEquals(station, store.loadStation(stationKey, now = 4_000L))
        assertTrue(cacheFile("station_${stationKey}.json").exists())
    }

    @Test
    fun invalidJsonAndVersionMismatch_areDeletedAndTreatedAsMisses() {
        val store = RouteCacheStore(temporaryFolder.root)
        val corruptKey = "c".repeat(64)
        val corruptFile = cacheFile("route_${corruptKey}.json").apply {
            parentFile?.mkdirs()
            writeText("not-json")
        }

        assertNull(store.loadRoute(corruptKey, now = 1_000L))
        assertFalse(corruptFile.exists())

        val oldVersionKey = "d".repeat(64)
        val oldVersionFile = cacheFile("route_${oldVersionKey}.json").apply {
            writeText(
                """{"status":"SUCCESS","responseJson":"{}","isEstimate":false,"fetchedAt":1000,"expiresAt":2000,"formatVersion":6}"""
            )
        }

        assertNull(store.loadRoute(oldVersionKey, now = 1_500L))
        assertFalse(oldVersionFile.exists())
    }

    @Test
    fun expiryAndValidation_preserveOnlyUsableFallbacks() {
        val store = RouteCacheStore(temporaryFolder.root)
        val expiredSuccessKey = "e".repeat(64)
        val expiredSuccess = RouteCacheEntry(
            status = RouteCacheEntry.STATUS_SUCCESS,
            responseJson = "{}",
            fetchedAt = 1_000L,
            expiresAt = 2_000L
        )
        store.saveRoute(expiredSuccessKey, expiredSuccess)

        assertEquals(expiredSuccess, store.loadRoute(expiredSuccessKey, now = 2_001L))
        assertTrue(cacheFile("route_${expiredSuccessKey}.json").exists())

        val expiredNoRouteKey = "f".repeat(64)
        store.saveRoute(
            expiredNoRouteKey,
            RouteCacheEntry(
                status = RouteCacheEntry.STATUS_NO_ROUTE,
                fetchedAt = 1_000L,
                expiresAt = 2_000L
            )
        )
        assertNull(store.loadRoute(expiredNoRouteKey, now = 2_001L))
        assertFalse(cacheFile("route_${expiredNoRouteKey}.json").exists())

        val expiredStationKey = "1".repeat(64)
        store.saveStation(
            expiredStationKey,
            StationCoordinateCacheEntry(22.5, 114.1, "测试站", expiresAt = 2_000L)
        )
        assertNull(store.loadStation(expiredStationKey, now = 2_001L))
        assertFalse(cacheFile("station_${expiredStationKey}.json").exists())

        val invalidStationKey = "2".repeat(64)
        val invalidStationFile = cacheFile("station_${invalidStationKey}.json").apply {
            writeText(
                """{"lat":91.0,"lng":114.1,"matchedTitle":"测试站","expiresAt":3000,"formatVersion":1}"""
            )
        }
        assertNull(store.loadStation(invalidStationKey, now = 2_000L))
        assertFalse(invalidStationFile.exists())

        store.saveRoute("../unsafe", expiredSuccess)
        assertFalse(File(temporaryFolder.root, "unsafe.json").exists())
    }

    @Test
    fun clearAll_removesBothCacheNamespaces() {
        val store = RouteCacheStore(temporaryFolder.root)
        store.saveRoute(
            "3".repeat(64),
            RouteCacheEntry(
                status = RouteCacheEntry.STATUS_SUCCESS,
                responseJson = "{}",
                fetchedAt = 1_000L,
                expiresAt = 2_000L
            )
        )
        store.saveStation(
            "4".repeat(64),
            StationCoordinateCacheEntry(22.5, 114.1, "测试站", expiresAt = 2_000L)
        )
        assertNotNull(cacheDirectory().listFiles())

        store.clearAll()

        assertFalse(cacheDirectory().exists())
    }

    private fun cacheDirectory(): File = File(temporaryFolder.root, "route_cache")
    private fun cacheFile(name: String): File = File(cacheDirectory(), name)
}
