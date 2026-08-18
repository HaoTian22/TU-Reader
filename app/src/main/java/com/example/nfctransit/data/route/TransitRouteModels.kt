package com.example.nfctransit.data.route

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class RouteMode { WALKING, SUBWAY, BUS, RAIL, OTHER_TRANSIT }

data class RoutePoint(val lat: Double, val lng: Double)

data class RouteLeg(
    val mode: RouteMode,
    val title: String,
    val points: List<RoutePoint>,
    val fromName: String? = null,
    val toName: String? = null,
    val stationNames: List<String> = emptyList(),
    val distanceMeters: Int = 0,
    val durationMinutes: Int = 0,
    val internalTransfer: Boolean = false
) {
    val isTransit: Boolean get() = mode != RouteMode.WALKING
}

data class RouteBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
)

data class RoutePlan(
    val distanceMeters: Int,
    val durationMinutes: Int,
    val walkingDistanceMeters: Int,
    val legs: List<RouteLeg>,
    val bounds: RouteBounds?,
    val estimatedCurrentNetwork: Boolean,
    val fromCache: Boolean = false,
    val stale: Boolean = false
) {
    val transitLegs: List<RouteLeg> get() = legs.filter { it.isTransit }
    val transferCount: Int get() = (transitLegs.size - 1).coerceAtLeast(0)
    val points: List<RoutePoint> get() = legs.flatMap { it.points }
}

data class TransitRouteQuery(
    val fromStationId: Long,
    val toStationId: Long,
    val fromName: String,
    val toName: String,
    val fromLineName: String,
    val toLineName: String,
    val fromLat: Double,
    val fromLng: Double,
    val toLat: Double,
    val toLng: Double,
    val departureTimeSeconds: Long,
    val policy: String = "LEAST_TRANSFER"
)

sealed class RouteLoadState {
    data class Ready(val plan: RoutePlan) : RouteLoadState()
    data class Unavailable(val reason: String) : RouteLoadState()
    data class Error(
        val message: String,
        val serviceDisabled: Boolean = false
    ) : RouteLoadState()
}

sealed class TransitParseResult {
    data class Ready(val plan: RoutePlan) : TransitParseResult()
    data class NoRoute(val message: String) : TransitParseResult()
    data class PermissionDenied(val message: String) : TransitParseResult()
    data class Failure(val message: String) : TransitParseResult()
}

/** 解析腾讯公交路线 JSON，并完成候选线路与方案的自动匹配。 */
object TencentTransitParser {

    fun parse(
        body: String,
        query: TransitRouteQuery,
        estimatedCurrentNetwork: Boolean
    ): TransitParseResult {
        val root = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            return TransitParseResult.Failure("路线响应格式错误")
        }
        val status = root.int("status") ?: -1
        val message = root.string("message").orEmpty().ifBlank { "路线服务返回错误 $status" }
        if (status == 199) return TransitParseResult.PermissionDenied(message)
        if (status != 0) return TransitParseResult.Failure(message)

        val routes = root.obj("result")?.array("routes")
            ?: return TransitParseResult.NoRoute("未找到公交路线")
        val candidates = routes.mapNotNull { element ->
            element.asObjectOrNull()?.let { parseRoute(it, query, estimatedCurrentNetwork) }
        }
        if (candidates.isEmpty()) return TransitParseResult.NoRoute("未找到同城公交路线")
        return TransitParseResult.Ready(selectBest(candidates, query))
    }

    private fun parseRoute(
        route: JsonObject,
        query: TransitRouteQuery,
        estimated: Boolean
    ): RoutePlan? {
        val steps = route.array("steps") ?: return null
        val transitIndices = (0 until steps.size()).filter { index ->
            steps[index].asObjectOrNull()?.string("mode") == "TRANSIT"
        }
        if (transitIndices.isEmpty()) return null
        val firstTransit = transitIndices.first()
        val lastTransit = transitIndices.last()
        val legs = mutableListOf<RouteLeg>()

        for ((index, element) in steps.withIndex()) {
            val step = element.asObjectOrNull() ?: continue
            when (step.string("mode")) {
                "WALKING" -> parseWalking(step)?.let(legs::add)
                "TRANSIT" -> chooseTransitLine(
                    step = step,
                    query = query,
                    isFirst = index == firstTransit,
                    isLast = index == lastTransit
                )?.let(legs::add)
                // 跨城 TRANSIT_FOLDER 需要二次查询，本期明确不解析。
                else -> Unit
            }
        }
        if (legs.none { it.isTransit } || legs.none { it.points.size >= 2 }) return null
        val allPoints = legs.flatMap { it.points }
        return RoutePlan(
            distanceMeters = route.int("distance") ?: legs.sumOf { it.distanceMeters },
            durationMinutes = route.int("duration") ?: legs.sumOf { it.durationMinutes },
            walkingDistanceMeters = legs.filter { !it.isTransit }.sumOf { it.distanceMeters },
            legs = legs,
            bounds = boundsOf(allPoints),
            estimatedCurrentNetwork = estimated
        )
    }

    private fun parseWalking(step: JsonObject): RouteLeg? {
        val points = decodePolyline(step.array("polyline"))
        if (points.size < 2) return null
        val internal = step.string("tag") == "INTERNAL"
        return RouteLeg(
            mode = RouteMode.WALKING,
            title = if (internal) "站内换乘" else "步行",
            points = points,
            distanceMeters = step.int("distance") ?: 0,
            durationMinutes = step.int("duration") ?: 0,
            internalTransfer = internal
        )
    }

    private fun chooseTransitLine(
        step: JsonObject,
        query: TransitRouteQuery,
        isFirst: Boolean,
        isLast: Boolean
    ): RouteLeg? {
        val lines = step.array("lines")?.mapNotNull { line ->
            line.asObjectOrNull()?.let(::parseTransitLine)
        }.orEmpty()
        return lines.maxWithOrNull(
            compareBy<RouteLeg> {
                endpointMatchCount(it, query, isFirst, isLast)
            }.thenBy {
                lineMatchCount(it, query, isFirst, isLast)
            }.thenBy { it.points.size }
        )
    }

    private fun parseTransitLine(line: JsonObject): RouteLeg? {
        val vehicle = line.string("vehicle").orEmpty().uppercase(Locale.US)
        val mode = when (vehicle) {
            "SUBWAY" -> RouteMode.SUBWAY
            "BUS" -> RouteMode.BUS
            "RAIL" -> RouteMode.RAIL
            else -> RouteMode.OTHER_TRANSIT
        }
        val getOn = line.obj("geton")
        val getOff = line.obj("getoff")
        val stations = line.array("stations")?.mapNotNull { station ->
            station.asObjectOrNull()?.string("title")
        }.orEmpty()
        var points = decodePolyline(line.array("polyline"))
        if (points.size < 2) {
            points = buildList {
                getOn?.locationPoint()?.let(::add)
                line.array("stations")?.forEach { station ->
                    station.asObjectOrNull()?.locationPoint()?.let(::add)
                }
                getOff?.locationPoint()?.let(::add)
            }
        }
        if (points.size < 2) return null
        return RouteLeg(
            mode = mode,
            title = line.string("title").orEmpty().ifBlank { vehicle.ifBlank { "公共交通" } },
            points = points,
            fromName = getOn?.string("title"),
            toName = getOff?.string("title"),
            stationNames = stations,
            distanceMeters = line.int("distance") ?: 0,
            durationMinutes = line.int("duration") ?: 0
        )
    }

    private fun selectBest(candidates: List<RoutePlan>, query: TransitRouteQuery): RoutePlan {
        return candidates.maxWithOrNull(
            compareBy<RoutePlan> { planEndpointMatches(it, query) }
                .thenBy { planLineMatches(it, query) }
                .thenBy { -it.transferCount }
                .thenBy { -it.walkingDistanceMeters }
                .thenBy { -it.durationMinutes }
        ) ?: candidates.first()
    }

    private fun planEndpointMatches(plan: RoutePlan, query: TransitRouteQuery): Int {
        val transit = plan.transitLegs
        if (transit.isEmpty()) return 0
        return (if (namesMatch(transit.first().fromName, query.fromName)) 1 else 0) +
            (if (namesMatch(transit.last().toName, query.toName)) 1 else 0)
    }

    private fun planLineMatches(plan: RoutePlan, query: TransitRouteQuery): Int {
        val transit = plan.transitLegs
        if (transit.isEmpty()) return 0
        return (if (namesMatch(transit.first().title, query.fromLineName, line = true)) 1 else 0) +
            (if (namesMatch(transit.last().title, query.toLineName, line = true)) 1 else 0)
    }

    private fun endpointMatchCount(
        leg: RouteLeg,
        query: TransitRouteQuery,
        isFirst: Boolean,
        isLast: Boolean
    ): Int = (if (isFirst && namesMatch(leg.fromName, query.fromName)) 1 else 0) +
        (if (isLast && namesMatch(leg.toName, query.toName)) 1 else 0)

    private fun lineMatchCount(
        leg: RouteLeg,
        query: TransitRouteQuery,
        isFirst: Boolean,
        isLast: Boolean
    ): Int = (if (isFirst && namesMatch(leg.title, query.fromLineName, line = true)) 1 else 0) +
        (if (isLast && namesMatch(leg.title, query.toLineName, line = true)) 1 else 0)

    fun decodePolyline(encoded: JsonArray?): List<RoutePoint> {
        if (encoded == null || encoded.size() < 4 || encoded.size() % 2 != 0) return emptyList()
        val values = DoubleArray(encoded.size())
        for (i in 0 until encoded.size()) {
            values[i] = encoded[i].doubleOrNull() ?: return emptyList()
            if (!values[i].isFinite()) return emptyList()
            if (i >= 2) values[i] = values[i - 2] + values[i] / 1_000_000.0
        }
        val out = ArrayList<RoutePoint>(values.size / 2)
        for (i in values.indices step 2) {
            val lat = values[i]
            val lng = values[i + 1]
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return emptyList()
            val point = RoutePoint(lat, lng)
            if (out.lastOrNull() != point) out.add(point)
        }
        return out.takeIf { it.size >= 2 } ?: emptyList()
    }

    fun normalizeStation(value: String?): String = value.orEmpty()
        .lowercase(Locale.ROOT)
        .replace(Regex("[（(].*?[）)]"), "")
        .replace("地铁", "")
        .replace(Regex("[\\s·•_\\-]"), "")
        .removeSuffix("↑")
        .removeSuffix("↓")
        .removeSuffix("站")

    fun normalizeLine(value: String?): String = value.orEmpty()
        .lowercase(Locale.ROOT)
        .replace(Regex("[（(].*?[）)]"), "")
        .replace("轨道交通", "")
        .replace("地铁", "")
        .replace(Regex("[\\s·•_\\-]"), "")

    private fun namesMatch(a: String?, b: String?, line: Boolean = false): Boolean {
        val left = if (line) normalizeLine(a) else normalizeStation(a)
        val right = if (line) normalizeLine(b) else normalizeStation(b)
        if (left.isEmpty() || right.isEmpty()) return false
        return left == right || (minOf(left.length, right.length) >= 2 &&
            (left.contains(right) || right.contains(left)))
    }

    private fun boundsOf(points: List<RoutePoint>): RouteBounds? {
        if (points.isEmpty()) return null
        return RouteBounds(
            south = points.minOf { it.lat },
            west = points.minOf { it.lng },
            north = points.maxOf { it.lat },
            east = points.maxOf { it.lng }
        )
    }

    private fun JsonObject.locationPoint(): RoutePoint? {
        val location = obj("location") ?: return null
        val lat = location.double("lat") ?: return null
        val lng = location.double("lng") ?: return null
        return RoutePoint(lat, lng).takeIf { lat in -90.0..90.0 && lng in -180.0..180.0 }
    }

    private fun JsonObject.string(name: String): String? = get(name)?.let { element ->
        if (element.isJsonNull || !element.isJsonPrimitive) null else runCatching { element.asString }.getOrNull()
    }

    private fun JsonObject.int(name: String): Int? = get(name)?.let { element ->
        if (element.isJsonNull || !element.isJsonPrimitive) null else runCatching { element.asInt }.getOrNull()
    }

    private fun JsonObject.double(name: String): Double? = get(name)?.doubleOrNull()
    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.asObjectOrNull()
    private fun JsonObject.array(name: String): JsonArray? = get(name)?.let {
        if (it.isJsonArray) it.asJsonArray else null
    }
    private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun JsonElement.doubleOrNull(): Double? = if (isJsonNull || !isJsonPrimitive) null
    else runCatching { asDouble }.getOrNull()
}

fun routeDistanceMeters(points: List<RoutePoint>): Double {
    var total = 0.0
    for (i in 1 until points.size) total += haversineMeters(points[i - 1], points[i])
    return total
}

fun haversineMeters(a: RoutePoint, b: RoutePoint): Double {
    val radius = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val value = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
        sin(dLng / 2) * sin(dLng / 2)
    return 2 * radius * asin(sqrt(value.coerceIn(0.0, 1.0)))
}

/** 对折线建立累计距离索引，供地图播放按真实距离匀速插值。 */
class RouteGeometry(val points: List<RoutePoint>) {
    private val cumulative = DoubleArray(points.size)
    val totalMeters: Double

    init {
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + haversineMeters(points[i - 1], points[i])
        }
        totalMeters = cumulative.lastOrNull() ?: 0.0
    }

    fun pointAtFraction(fraction: Double): RoutePoint? {
        if (points.isEmpty()) return null
        if (points.size == 1 || totalMeters <= 0.0) return points.first()
        val target = totalMeters * fraction.coerceIn(0.0, 1.0)
        var high = cumulative.binarySearch(target)
        if (high >= 0) return points[high]
        high = (-high - 1).coerceIn(1, points.lastIndex)
        val low = high - 1
        val span = cumulative[high] - cumulative[low]
        val local = if (span <= 0.0) 0.0 else (target - cumulative[low]) / span
        val a = points[low]
        val b = points[high]
        return RoutePoint(
            lat = a.lat + (b.lat - a.lat) * local,
            lng = a.lng + (b.lng - a.lng) * local
        )
    }
}
