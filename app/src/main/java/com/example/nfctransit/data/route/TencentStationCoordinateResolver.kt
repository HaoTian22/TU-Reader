package com.example.nfctransit.data.route

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 先按交易站名解析腾讯 POI 坐标，再交给 Direction。成功坐标持久缓存；搜索不可用时
 * 保留查询中由本地站点库提供的坐标，地图仍可降级工作。
 */
internal class TencentStationCoordinateResolver(
    context: Context,
    private val cacheStore: RouteCacheStore = RouteCacheStore(context.applicationContext.cacheDir)
) {

    private val appContext = context.applicationContext
    private val client = TencentTransitRouteClient(appContext)

    suspend fun resolve(query: TransitRouteQuery, allowNetwork: Boolean): TransitRouteQuery {
        val from = resolveOne(
            StationSearchQuery(
                stationName = query.fromName,
                cityName = query.fromCityName,
                lineName = query.fromLineName,
                family = query.requiredTransitFamily,
                biasLat = query.fromLat,
                biasLng = query.fromLng
            ),
            allowNetwork
        )
        val to = resolveOne(
            StationSearchQuery(
                stationName = query.toName,
                cityName = query.toCityName,
                lineName = query.toLineName,
                family = query.requiredTransitFamily,
                biasLat = query.toLat,
                biasLng = query.toLng
            ),
            allowNetwork
        )
        return query.copy(
            fromLat = from?.lat ?: query.fromLat,
            fromLng = from?.lng ?: query.fromLng,
            toLat = to?.lat ?: query.toLat,
            toLng = to?.lng ?: query.toLng
        )
    }

    private suspend fun resolveOne(
        query: StationSearchQuery,
        allowNetwork: Boolean
    ): StationCoordinate? {
        val cacheKey = cacheKey(query)
        readCache(cacheKey)?.let { return it }
        if (!allowNetwork || query.cityName.isBlank() || query.family == TransitFamily.ANY) return null
        if (!query.biasLat.isFinite() || !query.biasLng.isFinite() ||
            query.biasLat !in -90.0..90.0 || query.biasLng !in -180.0..180.0
        ) return null

        return searchMutex.withLock {
            readCache(cacheKey)?.let { return@withLock it }
            val now = System.currentTimeMillis()
            if (permissionDeniedForProcess || quotaBlockedUntilMillis > now ||
                SystemClock.elapsedRealtime() < networkBlockedUntilElapsed
            ) return@withLock null

            val elapsed = SystemClock.elapsedRealtime()
            val wait = MIN_REQUEST_INTERVAL_MS - (elapsed - lastSearchAtElapsed)
            if (wait > 0) delay(wait)
            lastSearchAtElapsed = SystemClock.elapsedRealtime()
            val http = withContext(Dispatchers.IO) { client.searchStation(query) }
            val parsed = when (http) {
                is TransitHttpResult.Success -> TencentStationSearchParser.parse(http.body, query)
                is TransitHttpResult.Failure -> http.body?.let {
                    TencentStationSearchParser.parse(it, query)
                } ?: StationSearchResult.Failure(http.message, http.status, http.requestId)
            }
            when (parsed) {
                is StationSearchResult.Found -> {
                    writeCache(cacheKey, parsed.coordinate)
                    parsed.coordinate
                }
                StationSearchResult.NoMatch -> null
                is StationSearchResult.Failure -> {
                    when (parsed.status) {
                        121 -> quotaBlockedUntilMillis = nextServiceMidnight()
                        199 -> permissionDeniedForProcess = true
                        null -> networkBlockedUntilElapsed =
                            SystemClock.elapsedRealtime() + NETWORK_RETRY_BACKOFF_MS
                    }
                    Log.w(
                        TAG,
                        "Tencent station search error status=${parsed.status ?: "unknown"} " +
                            "requestId=${parsed.requestId ?: "unknown"}: ${parsed.message}"
                    )
                    null
                }
            }
        }
    }

    private suspend fun readCache(key: String): StationCoordinate? = withContext(Dispatchers.IO) {
        cacheStore.loadStation(key)?.let { cached ->
            StationCoordinate(cached.lat, cached.lng, cached.matchedTitle)
        }
    }

    private suspend fun writeCache(key: String, coordinate: StationCoordinate) =
        withContext(Dispatchers.IO) {
            cacheStore.saveStation(
                key,
                StationCoordinateCacheEntry(
                    lat = coordinate.lat,
                    lng = coordinate.lng,
                    matchedTitle = coordinate.matchedTitle,
                    expiresAt = System.currentTimeMillis() + CACHE_TTL_MS
                )
            )
        }

    companion object {
        private const val CACHE_TTL_MS = 365L * 24 * 60 * 60 * 1000
        private const val MIN_REQUEST_INTERVAL_MS = 250L
        private const val NETWORK_RETRY_BACKOFF_MS = 60_000L
        private const val TAG = "StationCoordResolver"
        private val searchMutex = Mutex()
        private var lastSearchAtElapsed = 0L
        @Volatile private var permissionDeniedForProcess = false
        @Volatile private var quotaBlockedUntilMillis = 0L
        @Volatile private var networkBlockedUntilElapsed = 0L

        internal fun cacheKey(query: StationSearchQuery): String {
            val raw = listOf(
                TencentTransitParser.normalizeStation(query.stationName),
                query.cityName.trim().lowercase(Locale.ROOT),
                query.family.name,
                CACHE_FORMAT_VERSION.toString()
            ).joinToString("|")
            return MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        private fun nextServiceMidnight(): Long = Calendar.getInstance(
            TimeZone.getTimeZone("Asia/Shanghai")
        ).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        private const val CACHE_FORMAT_VERSION = STATION_CACHE_FORMAT_VERSION
    }
}
