package com.example.nfctransit.data.route

import android.content.Context
import com.example.nfctransit.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

sealed class TransitHttpResult {
    data class Success(val body: String) : TransitHttpResult()
    data class Failure(
        val message: String,
        val transient: Boolean,
        val body: String? = null,
        val status: Int? = null,
        val requestId: String? = null
    ) : TransitHttpResult()
}

/** 纯函数请求构造器，便于在不访问网络的单元测试中验证腾讯参数约定。 */
internal object TencentTransitRequestBuilder {
    private const val BASE_URL = "https://apis.map.qq.com"
    const val REQUEST_PATH = "/ws/direction/v1/transit/"
    const val ENDPOINT = BASE_URL + REQUEST_PATH

    fun buildUrl(
        query: TransitRouteQuery,
        key: String,
        secretKey: String,
        includeDepartureTime: Boolean
    ): String {
        val effectivePolicy = when (query.requiredTransitFamily) {
            // ONLY_SUBWAY 在部分换乘组合中只返回一条“长距离步行以减少换乘”的方案。
            // SUBWAY_FIRST 配合 get_mp 返回多个候选，再由解析器剔除混合公交方案。
            TransitFamily.SUBWAY -> query.policy.withConstraint("SUBWAY_FIRST")
            TransitFamily.BUS -> query.policy.withConstraint("NO_SUBWAY")
            TransitFamily.ANY, TransitFamily.RAIL -> query.policy
        }
        val params = linkedMapOf(
            "from" to formatCoordinate(query.fromLat, query.fromLng),
            "to" to formatCoordinate(query.toLat, query.toLng),
            "policy" to effectivePolicy,
            "get_mp" to "1",
            "added_fields" to "line_color",
            "output" to "json",
            "key" to key
        )
        if (includeDepartureTime && query.departureTimeSeconds > 0) {
            params["departure_time"] = query.departureTimeSeconds.toString()
        }
        return TencentWebServiceSigner.buildGetUrl(BASE_URL, REQUEST_PATH, params, secretKey)
    }

    private fun formatCoordinate(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.6f,%.6f", lat, lng)

    private fun String.withConstraint(constraint: String): String {
        val values = split(',').map(String::trim).filter(String::isNotEmpty)
        return if (constraint in values) this else (values + constraint).joinToString(",")
    }
}

/** 腾讯 Direction WebService 的轻量客户端；只负责 HTTP，不解释业务响应。 */
class TencentTransitRouteClient(private val context: Context) {

    fun fetch(query: TransitRouteQuery, includeDepartureTime: Boolean): TransitHttpResult {
        val key = BuildConfig.TENCENT_MAP_WEB_SERVICE_KEY
        val secretKey = BuildConfig.TENCENT_MAP_SECRET_KEY
        if (key.isBlank()) {
            return TransitHttpResult.Failure("未配置腾讯地图 WebService Key", transient = false)
        }
        if (secretKey.isBlank()) {
            return TransitHttpResult.Failure("未配置腾讯地图 SecretKey", transient = false)
        }
        val requestUrl = TencentTransitRequestBuilder.buildUrl(
            query,
            key,
            secretKey,
            includeDepartureTime
        )
        return execute(requestUrl, "路线服务")
    }

    fun searchStation(query: StationSearchQuery): TransitHttpResult {
        val key = BuildConfig.TENCENT_MAP_WEB_SERVICE_KEY
        val secretKey = BuildConfig.TENCENT_MAP_SECRET_KEY
        if (key.isBlank()) {
            return TransitHttpResult.Failure("未配置腾讯地图 WebService Key", transient = false)
        }
        if (secretKey.isBlank()) {
            return TransitHttpResult.Failure("未配置腾讯地图 SecretKey", transient = false)
        }
        val requestUrl = TencentStationSearchRequestBuilder.buildUrl(query, key, secretKey)
        return execute(requestUrl, "站点搜索")
    }

    private fun execute(requestUrl: String, serviceName: String): TransitHttpResult {
        val connection = URL(requestUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "TU-Reader/${appVersionName()}")
        connection.setRequestProperty("x-legacy-url-decode", "no")
        return try {
            connection.connect()
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299 && body.isNotBlank()) {
                TransitHttpResult.Success(body)
            } else {
                TransitHttpResult.Failure(
                    message = "$serviceName HTTP $code",
                    transient = code == 408 || code == 429 || code >= 500,
                    body = body.takeIf { it.isNotBlank() }
                )
            }
        } catch (e: IOException) {
            TransitHttpResult.Failure(e.message ?: "$serviceName 网络请求失败", transient = true)
        } catch (e: Exception) {
            TransitHttpResult.Failure(e.message ?: "$serviceName 请求失败", transient = false)
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
    }
}
