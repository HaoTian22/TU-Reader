package com.example.nfctransit.data.route

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

data class StationSearchQuery(
    val stationName: String,
    val cityName: String,
    val lineName: String,
    val family: TransitFamily,
    val biasLat: Double,
    val biasLng: Double
)

data class StationCoordinate(
    val lat: Double,
    val lng: Double,
    val matchedTitle: String
)

sealed class StationSearchResult {
    data class Found(val coordinate: StationCoordinate) : StationSearchResult()
    data object NoMatch : StationSearchResult()
    data class Failure(
        val message: String,
        val status: Int? = null,
        val requestId: String? = null
    ) : StationSearchResult()
}

/** 站名搜索请求保持为纯函数，避免单测依赖真实网络。 */
internal object TencentStationSearchRequestBuilder {
    private const val BASE_URL = "https://apis.map.qq.com"
    const val REQUEST_PATH = "/ws/place/v1/suggestion/"
    const val ENDPOINT = BASE_URL + REQUEST_PATH

    fun buildUrl(query: StationSearchQuery, key: String, secretKey: String): String {
        val keyword = TencentTransitParser.normalizeStation(query.stationName) + when (query.family) {
            TransitFamily.SUBWAY -> "地铁站"
            TransitFamily.BUS -> "公交站"
            TransitFamily.RAIL -> "火车站"
            TransitFamily.ANY -> "站"
        }
        val params = linkedMapOf(
            "region" to query.cityName,
            "region_fix" to "1",
            "keyword" to keyword,
            "location" to String.format(Locale.US, "%.6f,%.6f", query.biasLat, query.biasLng),
            "page_index" to "1",
            "page_size" to "20",
            "output" to "json",
            "key" to key
        )
        return TencentWebServiceSigner.buildGetUrl(BASE_URL, REQUEST_PATH, params, secretKey)
    }
}

/** 从腾讯站点搜索结果中只接受同城、同交通类别、精确同名的站点。 */
internal object TencentStationSearchParser {

    fun parse(body: String, query: StationSearchQuery): StationSearchResult {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return StationSearchResult.Failure("站点搜索响应格式错误")
        val status = root.int("status") ?: -1
        val message = root.string("message").orEmpty().ifBlank { "站点搜索返回错误 $status" }
        val requestId = root.string("request_id")
        if (status != 0) return StationSearchResult.Failure(message, status, requestId)

        val wantedName = normalizeSearchStation(query.stationName)
        if (wantedName.isEmpty()) return StationSearchResult.NoMatch
        val candidates = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.asObjectOrNull() }
            .orEmpty()
            .filter { candidate ->
                normalizeSearchStation(candidate.string("title")) == wantedName &&
                    candidate.matchesFamily(query.family)
            }
            .mapNotNull { candidate ->
                val location = candidate.obj("location") ?: return@mapNotNull null
                val lat = location.double("lat") ?: return@mapNotNull null
                val lng = location.double("lng") ?: return@mapNotNull null
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
                SearchCandidate(
                    coordinate = StationCoordinate(lat, lng, candidate.string("title").orEmpty()),
                    lineMatch = lineNamesMatch(candidate.string("address"), query.lineName),
                    distanceMeters = haversineMeters(
                        RoutePoint(query.biasLat, query.biasLng),
                        RoutePoint(lat, lng)
                    )
                )
            }
        val best = candidates.maxWithOrNull(
            compareBy<SearchCandidate> { if (it.lineMatch) 1 else 0 }
                .thenBy { -it.distanceMeters }
        ) ?: return StationSearchResult.NoMatch
        return StationSearchResult.Found(best.coordinate)
    }

    private data class SearchCandidate(
        val coordinate: StationCoordinate,
        val lineMatch: Boolean,
        val distanceMeters: Double
    )

    private fun normalizeSearchStation(value: String?): String =
        TencentTransitParser.normalizeStation(value)
            .removeSuffix("公交车")
            .removeSuffix("公交")
            .removeSuffix("火车")
            .removeSuffix("铁路")

    private fun lineNamesMatch(a: String?, b: String?): Boolean {
        val left = TencentTransitParser.normalizeLine(a)
        val right = TencentTransitParser.normalizeLine(b)
        if (left.isEmpty() || right.isEmpty()) return false
        return left == right || left.contains(right) || right.contains(left)
    }

    private fun JsonObject.matchesFamily(family: TransitFamily): Boolean {
        val type = int("type")
        val category = string("category").orEmpty()
        return when (family) {
            TransitFamily.SUBWAY -> type == 2 || category.contains("地铁站")
            TransitFamily.BUS -> type == 1 || category.contains("公交站")
            TransitFamily.RAIL -> category.contains("火车站") || category.contains("铁路")
            TransitFamily.ANY -> true
        }
    }

    private fun JsonObject.string(name: String): String? = get(name)?.let { element ->
        if (element.isJsonNull || !element.isJsonPrimitive) null
        else runCatching { element.asString }.getOrNull()
    }

    private fun JsonObject.int(name: String): Int? = get(name)?.let { element ->
        if (element.isJsonNull || !element.isJsonPrimitive) null
        else runCatching { element.asInt }.getOrNull()
    }

    private fun JsonObject.double(name: String): Double? = get(name)?.let { element ->
        if (element.isJsonNull || !element.isJsonPrimitive) null
        else runCatching { element.asDouble }.getOrNull()
    }

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.asObjectOrNull()
    private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
}
