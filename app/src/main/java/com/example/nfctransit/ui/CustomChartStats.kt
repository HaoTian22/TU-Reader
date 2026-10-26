package com.example.nfctransit.ui

import android.graphics.Color
import com.example.nfctransit.model.CustomChartConfig
import com.example.nfctransit.model.CustomChartData
import com.example.nfctransit.model.CustomChartItem
import com.example.nfctransit.model.MetricValues
import com.example.nfctransit.model.StatsChartType
import com.example.nfctransit.model.StatsDimension
import com.example.nfctransit.model.StatsMetric
import com.example.nfctransit.model.UiTransaction
import com.example.nfctransit.model.currency
import com.example.nfctransit.model.isSpending
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 分类/城市等无固有颜色的分组共用调色板 */
internal val CHART_PALETTE = listOf(
    0xFF4CAF50.toInt(),  // 绿
    0xFFFB8C00.toInt(),  // 橙
    0xFF9C27B0.toInt(),  // 紫
    0xFF00BCD4.toInt(),  // 青
    0xFFE91E63.toInt(),  // 玫红
    0xFF3F51B5.toInt(),  // 靛
    0xFF8BC34A.toInt(),  // 浅绿
    0xFF795548.toInt(),  // 棕
    0xFFF44336.toInt(),  // 红
    0xFF607D8B.toInt()   // 蓝灰
)

private const val OTHER_COLOR = 0xFFB0B6BE.toInt()

internal fun transitTypeColor(type: String): Int = when (type) {
    "地铁" -> 0xFF1A73E8.toInt()
    "公交" -> 0xFFFB8C00.toInt()
    "BRT" -> 0xFF8E24AA.toInt()
    "有轨电车" -> 0xFF26A69A.toInt()
    "城际" -> 0xFF5C6BC0.toInt()
    "消费" -> 0xFFE91E63.toInt()
    "便利店" -> 0xFFE91E63.toInt()
    else -> 0xFF78909C.toInt()
}

/** 分组键（含线路/车站所属城市）；null = 该交易不参与此维度 */
private data class GroupKey(val key: String, val city: String = "")

private fun UiTransaction.groupKey(dimension: StatsDimension): GroupKey? = when (dimension) {
    StatsDimension.TYPE -> GroupKey(transitType)
    StatsDimension.CITY -> GroupKey(cityName)
    StatsDimension.LINE -> lineName.takeIf { it.isNotEmpty() && it != "—" && it != "未知" }
        ?.let { GroupKey(it, cityName) }
    // 与常去车站统计同口径：排除未知站与"轨道交通/公共交通"等泛称
    StatsDimension.STATION -> stationName.takeIf {
        it.isNotEmpty() && it != "未知" &&
            !it.startsWith("轨道交通") && !it.startsWith("公共交通") && !it.startsWith("广州公交")
    }?.let { GroupKey(it, cityName) }
    StatsDimension.WEEKDAY -> weekdayIndex(date)?.let { GroupKey(it.toString()) }
    StatsDimension.HOUR -> time.take(2).toIntOrNull()?.takeIf { it in 0..23 }?.let { GroupKey(it.toString()) }
    StatsDimension.MONTH -> date.takeIf { it.length >= 7 }?.let { GroupKey(it.take(7)) }
    StatsDimension.DATE -> date.takeIf { it.length == 10 }?.let { GroupKey(it) }
}

/** 周一=0 … 周日=6；解析失败返回 null */
private fun weekdayIndex(date: String): Int? = try {
    val cal = Calendar.getInstance().apply { time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!! }
    (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
} catch (e: Exception) {
    null
}

/**
 * 自定义图表聚合（类似 Google Analytics 探索报告：一个维度 × 三个指标）。
 * - 乘车次数统计全部有金额的非充值记录；消费/平均票价只统计所选币种（不同币种不可相加）。
 * - 图表分组：时间类维度保持自然顺序并补 0（星期 7 格、时段 24 格、月份/日期连续：
 *   有统计周期时覆盖整个周期（当前周期截至今天），全部时间则取首末之间）；
 *   类别维度按所选指标降序，超出图表上限的部分（可相加指标）合并为"其他"。
 * - 表格分组：全部分组按所选指标降序（同值保持自然顺序）且不截断；日期/月份/星期含 0 值行，时段只含有数据的行。
 * [start]/[end] 为统计周期 "yyyy-MM-dd"，空 = 全部时间。
 */
internal fun computeCustomChart(
    txns: List<UiTransaction>,
    config: CustomChartConfig,
    currency: String,
    start: String = "",
    end: String = ""
): CustomChartData {
    val metric = config.metric
    val groups = linkedMapOf<GroupKey, MetricValues>()
    val lineColors = mutableMapOf<GroupKey, String>()
    for (t in txns) {
        if (!t.isSpending || t.typeHex == "02") continue
        val k = t.groupKey(config.dimension) ?: continue
        val money = t.currency == currency
        groups[k] = (groups[k] ?: MetricValues()) +
            MetricValues(1, if (money) t.amountYuan else 0.0, if (money) 1 else 0)
        if (k !in lineColors && !t.lineColor.isNullOrBlank()) t.lineColor?.let { lineColors[k] = it }
    }
    val totals = groups.values.fold(MetricValues()) { acc, v -> acc + v }
    if (groups.isEmpty()) return CustomChartData(config, totals = totals, currency = currency)

    val byValue = groups.entries.sortedByDescending { it.value.of(metric) }.map { it.key }
    // 颜色按所选指标排名分配，图表与表格同一分组颜色一致
    var paletteIndex = 0
    val colors = byValue.associateWith { k ->
        when {
            config.dimension == StatsDimension.TYPE -> transitTypeColor(k.key)
            config.dimension == StatsDimension.LINE && lineColors[k] != null ->
                runCatching { Color.parseColor(lineColors[k]) }.getOrNull() ?: CHART_PALETTE[paletteIndex++ % CHART_PALETTE.size]
            else -> CHART_PALETTE[paletteIndex++ % CHART_PALETTE.size]
        }
    }

    val chartGroups: List<Pair<GroupKey, MetricValues>> = if (config.dimension.isOrdered) {
        orderedKeys(config.dimension, groups.keys.map { it.key }, start, end).map { GroupKey(it) }
            .map { it to (groups[it] ?: MetricValues()) }
    } else {
        val sorted = byValue.map { it to groups.getValue(it) }
        val limit = when (config.effectiveChartType) {
            StatsChartType.DONUT -> 7
            StatsChartType.BAR -> 10
            else -> 12
        }
        if (sorted.size <= limit) sorted
        else if (metric == StatsMetric.AVG_FARE) sorted.take(limit)
        else sorted.take(limit - 1) + (GroupKey(CustomChartItem.OTHER_KEY) to
            sorted.drop(limit - 1).fold(MetricValues()) { acc, p -> acc + p.second })
    }
    // 表格按所选指标降序（表头排序图标所示）；sortedByDescending 稳定，同值保持时间顺序
    val tableGroups: List<Pair<GroupKey, MetricValues>> = when (config.dimension) {
        StatsDimension.HOUR -> chartGroups.filter { !it.second.isEmpty }
        else -> if (config.dimension.isOrdered) chartGroups else byValue.map { it to groups.getValue(it) }
    }.sortedByDescending { it.second.of(metric) }

    val additiveTotal = if (metric == StatsMetric.AVG_FARE) 0.0 else totals.of(metric)
    fun toItems(list: List<Pair<GroupKey, MetricValues>>): List<CustomChartItem> {
        val max = list.maxOfOrNull { it.second.of(metric) }?.takeIf { it > 0 } ?: 1.0
        return list.map { (k, m) ->
            val v = m.of(metric)
            CustomChartItem(
                key = k.key,
                metrics = m,
                value = v,
                percent = if (additiveTotal > 0) (v / additiveTotal).toFloat() else 0f,
                barPercent = (v / max).toFloat(),
                color = colors[k] ?: OTHER_COLOR,
                cityName = k.city,
                lineColor = lineColors[k]
            )
        }
    }
    return CustomChartData(config, toItems(chartGroups), toItems(tableGroups), totals, currency)
}

/**
 * 时间类维度的完整键序列：星期/时段固定格数；月份/日期连续，区间 = 统计周期（截至今天，且覆盖全部有数据的键），
 * 无周期时取首末之间。日期最多 3660 天（防止异常日期撑爆）。
 */
private fun orderedKeys(
    dimension: StatsDimension,
    present: Collection<String>,
    start: String,
    end: String
): List<String> = when (dimension) {
    StatsDimension.WEEKDAY -> (0..6).map { it.toString() }
    StatsDimension.HOUR -> (0..23).map { it.toString() }
    StatsDimension.MONTH -> {
        val today = SimpleDateFormat("yyyy-MM", Locale.US).format(Calendar.getInstance().time)
        val first = listOfNotNull(present.min(), start.take(7).takeIf { it.length == 7 }).min()
        val last = listOfNotNull(present.max(), end.take(7).takeIf { it.length == 7 }?.let { minOf(it, today) }).max()
        val out = mutableListOf<String>()
        var y = first.take(4).toInt()
        var m = first.substring(5, 7).toInt()
        while (true) {
            val key = "%04d-%02d".format(Locale.US, y, m)
            out.add(key)
            if (key >= last || out.size > 240) break
            if (++m > 12) { m = 1; y++ }
        }
        out
    }
    StatsDimension.DATE -> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sorted = present.sorted()
        val today = fmt.format(Calendar.getInstance().time)
        val first = listOfNotNull(sorted.first(), start.takeIf { it.length == 10 }).min()
        val last = listOfNotNull(sorted.last(), end.takeIf { it.length == 10 }?.let { minOf(it, today) }).max()
        try {
            val cal = Calendar.getInstance().apply { time = fmt.parse(first)!! }
            val out = mutableListOf<String>()
            while (out.size < 3660) {
                val key = fmt.format(cal.time)
                out.add(key)
                if (key >= last) break
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            out
        } catch (e: Exception) {
            sorted
        }
    }
    else -> present.toList()
}
