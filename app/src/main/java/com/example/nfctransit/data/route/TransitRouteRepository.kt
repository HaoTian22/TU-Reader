package com.example.nfctransit.data.route

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** 缓存优先的路线仓库，同时限制客户端直连腾讯服务的并发与请求频率。 */
class TransitRouteRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cacheStore = RouteCacheStore(appContext.cacheDir)
    private val client = TencentTransitRouteClient(appContext)
    private val coordinateResolver = TencentStationCoordinateResolver(appContext, cacheStore)

    suspend fun resolve(query: TransitRouteQuery): RouteLoadState {
        val now = System.currentTimeMillis()
        val initiallyBlocked = blockedLoadError(now)
        // 先按交易站名取得腾讯 POI 坐标（或读取持久缓存），再执行路线规划和线路匹配。
        val resolvedQuery = coordinateResolver.resolve(query, allowNetwork = initiallyBlocked == null)
        // 交易记录是归档数据；缓存仍按当前路网跨历史日期复用。实际请求固定到腾讯服务时区的
        // 下一个中午，避免凌晨打开页面时 API 按“当前时刻”过滤掉已停运线路并改成长距离步行。
        val cacheQuery = resolvedQuery.copy(departureTimeSeconds = 0L)
        val serviceQuery = resolvedQuery.copy(
            departureTimeSeconds = representativeDepartureTimeSeconds(now)
        )
        val cacheKey = currentNetworkCacheKey(resolvedQuery)
        val cached = withContext(Dispatchers.IO) { cacheStore.loadRoute(cacheKey, now) }
        var stalePlan: RoutePlan? = null
        if (cached != null) {
            if (cached.status == RouteCacheEntry.STATUS_NO_ROUTE && cached.expiresAt >= now) {
                return RouteLoadState.Unavailable("未找到同城公交路线")
            }
            if (cached.status == RouteCacheEntry.STATUS_SUCCESS && !cached.responseJson.isNullOrBlank()) {
                val parsed = TencentTransitParser.parse(cached.responseJson, resolvedQuery, cached.isEstimate)
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
        (initiallyBlocked ?: blockedLoadError(now))?.let { blocked ->
            return stalePlan?.let { RouteLoadState.Ready(it) } ?: blocked
        }

        val current = fetchWithRetry(serviceQuery, includeDepartureTime = true)
        return when (val parsed = parseHttp(current, serviceQuery, estimated = true)) {
            is TransitParseResult.Ready -> cacheSuccess(cacheKey, current, parsed.plan)
            is TransitParseResult.PermissionDenied -> {
                logServiceIssue(parsed)
                permissionDeniedForProcess = true
                stalePlan?.let { RouteLoadState.Ready(it) }
                    ?: parsed.toLoadError(serviceDisabled = true)
            }
            is TransitParseResult.QuotaExceeded -> {
                logServiceIssue(parsed)
                blockQuotaUntilNextDay(parsed.requestId)
                stalePlan?.let { RouteLoadState.Ready(it) } ?: parsed.toLoadError()
            }
            is TransitParseResult.Failure -> {
                logServiceIssue(parsed)
                stalePlan?.let { RouteLoadState.Ready(it) } ?: parsed.toLoadError()
            }
            is TransitParseResult.NoRoute -> {
                withContext(Dispatchers.IO) {
                    cacheStore.saveRoute(
                        cacheKey,
                        RouteCacheEntry(
                            status = RouteCacheEntry.STATUS_NO_ROUTE,
                            fetchedAt = now,
                            expiresAt = now + NO_ROUTE_TTL_MS
                        )
                    )
                }
                stalePlan?.let { RouteLoadState.Ready(it) }
                    ?: RouteLoadState.Unavailable(parsed.message)
            }
        }
    }

    private suspend fun cacheSuccess(
        cacheKey: String,
        httpResult: TransitHttpResult,
        plan: RoutePlan
    ): RouteLoadState.Ready {
        val body = (httpResult as? TransitHttpResult.Success)?.body.orEmpty()
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            cacheStore.saveRoute(
                cacheKey,
                RouteCacheEntry(
                    status = RouteCacheEntry.STATUS_SUCCESS,
                    responseJson = body,
                    isEstimate = plan.estimatedCurrentNetwork,
                    fetchedAt = now,
                    expiresAt = now + SUCCESS_TTL_MS
                )
            )
        }
        return RouteLoadState.Ready(plan)
    }

    private fun parseHttp(
        result: TransitHttpResult,
        query: TransitRouteQuery,
        estimated: Boolean
    ): TransitParseResult = when (result) {
        is TransitHttpResult.Success -> TencentTransitParser.parse(result.body, query, estimated)
        is TransitHttpResult.Failure -> result.body
            ?.let(TencentTransitParser::parseServiceError)
            ?: when (result.status) {
                121 -> TransitParseResult.QuotaExceeded(
                    result.message,
                    status = 121,
                    requestId = result.requestId
                )
                199 -> TransitParseResult.PermissionDenied(
                    result.message,
                    status = 199,
                    requestId = result.requestId
                )
                else -> TransitParseResult.Failure(
                    result.message,
                    status = result.status,
                    requestId = result.requestId
                )
            }
    }

    private fun TransitParseResult.PermissionDenied.toLoadError(serviceDisabled: Boolean) =
        RouteLoadState.Error(
            message = message,
            serviceDisabled = serviceDisabled,
            status = status,
            requestId = requestId
        )

    private fun TransitParseResult.QuotaExceeded.toLoadError() = RouteLoadState.Error(
        message = message,
        quotaExceeded = true,
        status = status,
        requestId = requestId
    )

    private fun TransitParseResult.Failure.toLoadError() = RouteLoadState.Error(
        message = message,
        status = status,
        requestId = requestId
    )

    private fun logServiceIssue(issue: TransitParseResult) {
        val details = when (issue) {
            is TransitParseResult.PermissionDenied -> Triple(issue.status, issue.requestId, issue.message)
            is TransitParseResult.QuotaExceeded -> Triple(issue.status, issue.requestId, issue.message)
            is TransitParseResult.Failure -> Triple(issue.status, issue.requestId, issue.message)
            else -> return
        }
        Log.w(
            TAG,
            "Tencent Direction error status=${details.first ?: "unknown"} " +
                "requestId=${details.second ?: "unknown"}: ${details.third}"
        )
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
        blockedHttpFailure()?.let { return it }
        return networkSemaphore.withPermit {
            // 等待并发信号量时，另一请求可能已触发权限/额度熔断。
            blockedHttpFailure()?.let { return@withPermit it }
            rateMutex.withLock {
                val now = SystemClock.elapsedRealtime()
                val wait = MIN_REQUEST_INTERVAL_MS - (now - lastRequestAt)
                if (wait > 0) delay(wait)
                lastRequestAt = SystemClock.elapsedRealtime()
            }
            blockedHttpFailure()?.let { return@withPermit it }
            withContext(Dispatchers.IO) { client.fetch(query, includeDepartureTime) }
        }
    }

    private fun blockedLoadError(now: Long = System.currentTimeMillis()): RouteLoadState.Error? {
        if (permissionDeniedForProcess) {
            return RouteLoadState.Error("腾讯路线服务未启用", serviceDisabled = true, status = 199)
        }
        val blockedUntil = quotaBlockedUntilMillis
        if (blockedUntil > now) {
            return RouteLoadState.Error(
                message = "腾讯路线服务当日调用额度已用完",
                quotaExceeded = true,
                status = 121,
                requestId = quotaRequestId
            )
        }
        if (blockedUntil != 0L) {
            quotaBlockedUntilMillis = 0L
            quotaRequestId = null
        }
        return null
    }

    private fun blockedHttpFailure(): TransitHttpResult.Failure? {
        val blocked = blockedLoadError() ?: return null
        return TransitHttpResult.Failure(
            message = blocked.message,
            transient = false,
            status = blocked.status,
            requestId = blocked.requestId
        )
    }

    private fun blockQuotaUntilNextDay(requestId: String?) {
        val nextMidnight = Calendar.getInstance(SERVICE_TIME_ZONE).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        quotaBlockedUntilMillis = nextMidnight
        quotaRequestId = requestId
    }

    companion object {
        internal const val CACHE_FORMAT_VERSION = ROUTE_CACHE_FORMAT_VERSION
        private const val SUCCESS_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val NO_ROUTE_TTL_MS = 24L * 60 * 60 * 1000
        private const val RETRY_DELAY_MS = 500L
        private const val MIN_REQUEST_INTERVAL_MS = 250L

        private val networkSemaphore = Semaphore(2)
        private val rateMutex = Mutex()
        private var lastRequestAt = 0L
        private const val TAG = "TransitRouteRepo"
        @Volatile private var permissionDeniedForProcess = false
        @Volatile private var quotaBlockedUntilMillis = 0L
        @Volatile private var quotaRequestId: String? = null

        private val SERVICE_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

        internal fun representativeDepartureTimeSeconds(nowMillis: Long): Long {
            val calendar = Calendar.getInstance(SERVICE_TIME_ZONE).apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_YEAR, 1)
            }
            return calendar.timeInMillis / 1000L
        }

        fun cacheKey(query: TransitRouteQuery): String {
            val raw = String.format(
                Locale.US,
                "%d|%d|%.6f,%.6f|%.6f,%.6f|%d|%s|%s|v%d",
                query.fromStationId,
                query.toStationId,
                query.fromLat,
                query.fromLng,
                query.toLat,
                query.toLng,
                query.departureTimeSeconds,
                query.policy,
                query.requiredTransitFamily.name,
                CACHE_FORMAT_VERSION
            )
            return MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        internal fun currentNetworkCacheKey(query: TransitRouteQuery): String =
            cacheKey(query.copy(departureTimeSeconds = 0L))
    }
}
