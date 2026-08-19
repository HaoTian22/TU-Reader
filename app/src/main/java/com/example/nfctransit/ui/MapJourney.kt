package com.example.nfctransit.ui

import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.route.TransitFamily
import com.example.nfctransit.model.UiTransaction
import com.example.nfctransit.util.CoordTransform
import java.util.Locale

/**
 * 地图轨迹的行程模型：从交易记录（数据库）构建时间线事件 + 站间出行线段。
 *
 * 每笔地铁/公交交易是一个「事件」（进站 ↓ / 出站 ↑），进出站配对成一条「线段」（行程），
 * 地图上画贝塞尔曲线表示站间出行。坐标取 WGS84 后转 GCJ-02（腾讯地图坐标系）；
 * 坐标缺失的站点直接丢弃，不出现在地图上。
 */
enum class MapDirection { ENTRY, EXIT, NONE }

data class MapEvent(
    val stationId: Long,
    val name: String,          // 站名（已去掉方向箭头）
    val lineName: String,
    val lineColor: String?,    // "#RRGGBB"，可空
    val lng: Double,           // GCJ-02（腾讯坐标系）
    val lat: Double,
    val timeMillis: Long,
    val direction: MapDirection,
    val transitFamily: TransitFamily = TransitFamily.ANY,
    val cityName: String = ""
)

data class MapSegment(
    val from: MapEvent,
    val to: MapEvent?,         // null = 孤点（只有一端有坐标/无配对出站）
    val lineName: String,
    val lineColor: String?,
    val startTime: Long,
    val endTime: Long
) {
    val hasCurve: Boolean get() = to != null
    val requiredTransitFamily: TransitFamily get() {
        val destinationFamily = to?.transitFamily ?: return TransitFamily.ANY
        return from.transitFamily.takeIf {
            it != TransitFamily.ANY && it == destinationFamily
        } ?: TransitFamily.ANY
    }
}

internal fun transitFamilyOf(value: String): TransitFamily {
    val type = value.trim().lowercase(Locale.ROOT)
    return when {
        type in setOf("地铁", "轻轨", "单轨", "快轨", "磁浮") ||
            type.contains("metro") || type.contains("subway") ||
            type.contains("light rail") || type.contains("monorail") ||
            type.contains("maglev") -> TransitFamily.SUBWAY
        type in setOf("公交", "有轨电车", "brt") ||
            type.contains("bus") || type.contains("tram") -> TransitFamily.BUS
        type == "城际" || type.contains("intercity") ||
            type.contains("rail") || type.contains("express") -> TransitFamily.RAIL
        else -> TransitFamily.ANY
    }
}

/** Direction transit 必须有可靠的同城、同类起终点；单点及混合模式记录不参与路线请求。 */
internal fun isTransitRouteEligible(
    segment: MapSegment,
    fromCityId: Long?,
    toCityId: Long?
): Boolean {
    val to = segment.to ?: return false
    val family = segment.from.transitFamily
    return fromCityId != null &&
        fromCityId == toCityId &&
        family != TransitFamily.ANY &&
        family == to.transitFamily
}

class JourneyModel private constructor(
    val events: List<MapEvent>,      // 按时间升序（播放时间线）
    val segments: List<MapSegment>,  // 站间线段（含孤点）
    val startTime: Long,
    val endTime: Long
) {
    val isEmpty: Boolean get() = events.isEmpty()

    companion object {
        /** 是否算作出行（充值/消费不算） */
        private fun isTransit(tx: UiTransaction): Boolean =
            transitFamilyOf(tx.transitType) != TransitFamily.ANY

        private fun parseTime(date: String, time: String): Long {
            // 本地时间（交易时间即本地时间），默认时区解析
            val cal = java.util.Calendar.getInstance()
            cal.set(
                date.substring(0, 4).toInt(),
                date.substring(5, 7).toInt() - 1,
                date.substring(8, 10).toInt(),
                time.substring(0, 2).toInt(),
                time.substring(3, 5).toInt(),
                time.substring(6, 8).toInt()
            )
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun build(txns: List<UiTransaction>): JourneyModel {
            // 1. 收集有坐标的出行事件
            val events = mutableListOf<MapEvent>()
            for (tx in txns) {
                if (!isTransit(tx)) continue
                val stationId = tx.stationId ?: continue
                val direction = when {
                    tx.stationName.trimEnd().endsWith("↓") -> MapDirection.ENTRY
                    tx.stationName.trimEnd().endsWith("↑") -> MapDirection.EXIT
                    else -> MapDirection.NONE
                }
                val name = tx.stationName.trim().removeSuffix("↓").removeSuffix("↑").trim()
                val coords = TransitData.coordsByStationName(name, tx.cityName, tx.lineName)
                    ?: TransitData.coordsOf(stationId)
                    ?: continue
                val (gcjLng, gcjLat) = CoordTransform.wgs84ToGcj02(coords.first, coords.second)
                events.add(
                    MapEvent(
                        stationId = stationId,
                        name = name,
                        lineName = tx.lineName,
                        lineColor = tx.lineColor,
                        lng = gcjLng,
                        lat = gcjLat,
                        timeMillis = parseTime(tx.date, tx.time),
                        direction = direction,
                        transitFamily = transitFamilyOf(tx.transitType),
                        cityName = tx.cityName
                    )
                )
            }
            events.sortBy { it.timeMillis }

            // 2. 入站→出站 配对成线段：不按线路匹配，上一条入站 + 下一条出站即配对；
            //    中间的公交等无方向事件不打断配对；入站没等到出站就作孤点
            val segments = mutableListOf<MapSegment>()
            var openEntry: MapEvent? = null
            for (ev in events) {
                when (ev.direction) {
                    MapDirection.ENTRY -> {
                        openEntry?.let { segments.add(MapSegment(it, null, it.lineName, it.lineColor, it.timeMillis, it.timeMillis)) }
                        openEntry = ev
                    }
                    MapDirection.EXIT -> {
                        val pending = openEntry
                        if (pending != null && pending.timeMillis <= ev.timeMillis) {
                            segments.add(MapSegment(pending, ev, pending.lineName, pending.lineColor, pending.timeMillis, ev.timeMillis))
                            openEntry = null
                        } else {
                            segments.add(MapSegment(ev, null, ev.lineName, ev.lineColor, ev.timeMillis, ev.timeMillis))
                        }
                    }
                    MapDirection.NONE -> {
                        segments.add(MapSegment(ev, null, ev.lineName, ev.lineColor, ev.timeMillis, ev.timeMillis))
                    }
                }
            }
            openEntry?.let { segments.add(MapSegment(it, null, it.lineName, it.lineColor, it.timeMillis, it.timeMillis)) }

            val start = events.firstOrNull()?.timeMillis ?: 0L
            val end = events.lastOrNull()?.timeMillis ?: 0L
            return JourneyModel(events, segments, start, end)
        }
    }
}
