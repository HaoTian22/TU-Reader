package com.example.nfctransit.data.route

import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

sealed class TransitHttpResult {
    data class Success(val body: String) : TransitHttpResult()
    data class Failure(val message: String, val transient: Boolean) : TransitHttpResult()
}

/** 腾讯 Direction WebService 的轻量客户端；只负责 HTTP，不解释业务响应。 */
class TencentTransitRouteClient(private val context: Context) {

    fun fetch(query: TransitRouteQuery, includeDepartureTime: Boolean): TransitHttpResult {
        val key = readTencentKey()
        if (key.isBlank()) return TransitHttpResult.Failure("未配置腾讯地图 Key", transient = false)
        val params = linkedMapOf(
            "from" to formatCoordinate(query.fromLat, query.fromLng),
            "to" to formatCoordinate(query.toLat, query.toLng),
            "policy" to query.policy,
            "output" to "json",
            "key" to key
        )
        if (includeDepartureTime && query.departureTimeSeconds > 0) {
            params["departure_time"] = query.departureTimeSeconds.toString()
        }
        val queryString = params.entries.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }
        val connection = URL("$ENDPOINT?$queryString").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "TU-Reader/${appVersionName()}")
        return try {
            connection.connect()
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299 && body.isNotBlank()) {
                TransitHttpResult.Success(body)
            } else {
                TransitHttpResult.Failure(
                    message = "路线服务 HTTP $code",
                    transient = code == 408 || code == 429 || code >= 500
                )
            }
        } catch (e: IOException) {
            TransitHttpResult.Failure(e.message ?: "路线网络请求失败", transient = true)
        } catch (e: Exception) {
            TransitHttpResult.Failure(e.message ?: "路线请求失败", transient = false)
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun readTencentKey(): String {
        return runCatching {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA
            )
            appInfo.metaData?.getString(META_DATA_KEY).orEmpty()
        }.getOrDefault("")
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun formatCoordinate(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.6f,%.6f", lat, lng)

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val ENDPOINT = "https://apis.map.qq.com/ws/direction/v1/transit/"
        private const val META_DATA_KEY = "TencentMapSDK"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
    }
}
