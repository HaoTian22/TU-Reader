package com.example.nfctransit.data.route

import android.content.Context
import android.os.SystemClock
import com.example.nfctransit.data.db.RouteCacheEntity
import com.example.nfctransit.data.db.UserDatabase
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** 缓存优先的路线仓库，同时限制客户端直连腾讯服务的并发与请求频率。 */
class TransitRouteRepository(context: Context) {

    private val dao = UserDatabase.get(context.applicationContext).userDao()
    private val client = TencentTransitRouteClient(context.applicationContext)

    @Volatile
    private var serviceDisabled = false

    suspend fun resolve(query: TransitRouteQuery): RouteLoadState {
        val cacheKey = cacheKey(query)
        val now = System.currentTimeMillis()
        val cached = dao.getRouteCache(cacheKey)
        var stalePlan: RoutePlan? = null
        if (cached != null) {
            if (cached.status == RouteCacheEntity.STATUS_NO_ROUTE && cached.expiresAt >= now) {
                return RouteLoadState.Unavailable("未找到同城公交路线")
            }
            if (cached.status == RouteCacheEntity.STATUS_SUCCESS && !cached.responseJson.isNullOrBlank()) {
                val parsed = TencentTransitParser.parse(cached.responseJson, query, cached.isEstimate)
                if (parsed is TransitParseResult.Ready) {
                    val plan = parsed.plan.copy(
                        fromCache = true,
                        stale = cached.expiresAt < now
                    )
                    if (cached.expiresAt >= now) return RouteLoadState.Ready(plan)
                    stalePlan = plan
                }
            }
        }
        // 199 只停止本页后续网络请求；已缓存的路线仍可以继续显示。
        if (serviceDisabled) {
            return stalePlan?.let { RouteLoadState.Ready(it) }
                ?: RouteLoadState.Error("腾讯路线服务未启用", serviceDisabled = true)
        }

        val historical = fetchWithRetry(query, includeDepartureTime = true)
        val historicalParsed = parseHttp(historical, query, estimated = false)
        when (historicalParsed) {
            is TransitParseResult.Ready -> return cacheSuccess(cacheKey, query, historical, historicalParsed.plan)
            is TransitParseResult.PermissionDenied -> {
                serviceDisabled = true
                return stalePlan?.let { RouteLoadState.Ready(it) }
                    ?: RouteLoadState.Error(historicalParsed.message, serviceDisabled = true)
            }
            is TransitParseResult.Failure -> {
                return stalePlan?.let { RouteLoadState.Ready(it) }
                    ?: RouteLoadState.Error(historicalParsed.message)
            }
            is TransitParseResult.NoRoute -> Unit
        }

        // 历史时刻无方案时，用当前运营网络再推算一次，并在 UI 中明确标记为估算。
        val current = fetchWithRetry(query, includeDepartureTime = false)
        return when (val parsed = parseHttp(current, query, estimated = true)) {
            is TransitParseResult.Ready -> cacheSuccess(cacheKey, query, current, parsed.plan)
            is TransitParseResult.PermissionDenied -> {
                serviceDisabled = true
                stalePlan?.let { RouteLoadState.Ready(it) }
                    ?: RouteLoadState.Error(parsed.message, serviceDisabled = true)
            }
            is TransitParseResult.Failure -> stalePlan?.let { RouteLoadState.Ready(it) }
                ?: RouteLoadState.Error(parsed.message)
            is TransitParseResult.NoRoute -> {
                dao.upsertRouteCache(
                    RouteCacheEntity(
                        cacheKey = cacheKey,
                        fromStationId = query.fromStationId,
                        toStationId = query.toStationId,
                        departureTime = query.departureTimeSeconds,
                        policy = query.policy,
                        status = RouteCacheEntity.STATUS_NO_ROUTE,
                        fetchedAt = now,
                        expiresAt = now + NO_ROUTE_TTL_MS
                    )
                )
                stalePlan?.let { RouteLoadState.Ready(it) }
                    ?: RouteLoadState.Unavailable(parsed.message)
            }
        }
    }

    private suspend fun cacheSuccess(
        cacheKey: String,
        query: TransitRouteQuery,
        httpResult: TransitHttpResult,
        plan: RoutePlan
    ): RouteLoadState.Ready {
        val body = (httpResult as? TransitHttpResult.Success)?.body.orEmpty()
        val now = System.currentTimeMillis()
        dao.upsertRouteCache(
            RouteCacheEntity(
                cacheKey = cacheKey,
                fromStationId = query.fromStationId,
                toStationId = query.toStationId,
                departureTime = query.departureTimeSeconds,
                policy = query.policy,
                status = RouteCacheEntity.STATUS_SUCCESS,
                responseJson = body,
                isEstimate = plan.estimatedCurrentNetwork,
                fetchedAt = now,
                expiresAt = now + SUCCESS_TTL_MS
            )
        )
        return RouteLoadState.Ready(plan)
    }

    private fun parseHttp(
        result: TransitHttpResult,
        query: TransitRouteQuery,
        estimated: Boolean
    ): TransitParseResult = when (result) {
        is TransitHttpResult.Success -> TencentTransitParser.parse(result.body, query, estimated)
        is TransitHttpResult.Failure -> TransitParseResult.Failure(result.message)
    }

    private suspend fun fetchWithRetry(
        query: TransitRouteQuery,
        includeDepartureTime: Boolean
    ): TransitHttpResult {
        var result = throttledFetch(query, includeDepartureTime)
        if (result is TransitHttpResult.Failure && result.transient) {
            delay(RETRY_DELAY_MS)
            result = throttledFetch(query, includeDepartureTime)
        }
        return result
    }

    private suspend fun throttledFetch(
        query: TransitRouteQuery,
        includeDepartureTime: Boolean
    ): TransitHttpResult {
        if (serviceDisabled) return serviceDisabledFailure()
        return networkSemaphore.withPermit {
            // 等待并发信号量时，另一请求可能已返回 199。
            if (serviceDisabled) return@withPermit serviceDisabledFailure()
            rateMutex.withLock {
                val now = SystemClock.elapsedRealtime()
                val wait = MIN_REQUEST_INTERVAL_MS - (now - lastRequestAt)
                if (wait > 0) delay(wait)
                lastRequestAt = SystemClock.elapsedRealtime()
            }
            if (serviceDisabled) return@withPermit serviceDisabledFailure()
            withContext(Dispatchers.IO) { client.fetch(query, includeDepartureTime) }
        }
    }

    private fun serviceDisabledFailure() =
        TransitHttpResult.Failure("腾讯路线服务未启用", transient = false)

    companion object {
        private const val FORMAT_VERSION = 1
        private const val SUCCESS_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val NO_ROUTE_TTL_MS = 24L * 60 * 60 * 1000
        private const val RETRY_DELAY_MS = 500L
        private const val MIN_REQUEST_INTERVAL_MS = 250L

        private val networkSemaphore = Semaphore(2)
        private val rateMutex = Mutex()
        private var lastRequestAt = 0L

        fun cacheKey(query: TransitRouteQuery): String {
            val raw = String.format(
                Locale.US,
                "%d|%d|%.6f,%.6f|%.6f,%.6f|%d|%s|v%d",
                query.fromStationId,
                query.toStationId,
                query.fromLat,
                query.fromLng,
                query.toLat,
                query.toLng,
                query.departureTimeSeconds,
                query.policy,
                FORMAT_VERSION
            )
            return MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
