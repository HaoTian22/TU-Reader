package com.example.nfctransit.model

/** 自定义图表的分组维度 */
enum class StatsDimension { TYPE, CITY, LINE, STATION, WEEKDAY, HOUR, MONTH, DATE;
    /** 有序维度（时间类）保持自然顺序并补 0；其余按数值降序 */
    val isOrdered: Boolean get() = this == WEEKDAY || this == HOUR || this == MONTH || this == DATE
}

/** 自定义图表的统计指标 */
enum class StatsMetric { SPENDING, RIDES, AVG_FARE }

enum class StatsChartType { LINE, COLUMN, BAR, DONUT }

data class CustomChartConfig(
    val dimension: StatsDimension = StatsDimension.DATE,
    val metric: StatsMetric = StatsMetric.SPENDING,
    val chartType: StatsChartType = StatsChartType.LINE
) {
    /**
     * 实际绘制的图表：折线只对时间类维度有意义（类别维度退化为柱状图）；
     * 平均票价不可相加，不能画占比环形图（退化为条形图）
     */
    val effectiveChartType: StatsChartType
        get() = when {
            chartType == StatsChartType.LINE && !dimension.isOrdered -> StatsChartType.COLUMN
            chartType == StatsChartType.DONUT && metric == StatsMetric.AVG_FARE -> StatsChartType.BAR
            else -> chartType
        }

    fun isChartTypeAvailable(type: StatsChartType): Boolean = when (type) {
        StatsChartType.LINE -> dimension.isOrdered
        StatsChartType.DONUT -> metric != StatsMetric.AVG_FARE
        else -> true
    }

    fun encode(): String = "${dimension.name},${metric.name},${chartType.name}"

    companion object {
        fun decode(raw: String?): CustomChartConfig {
            val parts = raw?.split(",") ?: return CustomChartConfig()
            val default = CustomChartConfig()
            return CustomChartConfig(
                StatsDimension.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: default.dimension,
                StatsMetric.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: default.metric,
                StatsChartType.entries.firstOrNull { it.name == parts.getOrNull(2) } ?: default.chartType
            )
        }
    }
}

/** 三个指标的一组取值（表格每行/合计行） */
data class MetricValues(
    val rides: Int = 0,
    val spending: Double = 0.0,
    /** 所选币种的消费笔数（平均票价分母） */
    val spendingCount: Int = 0
) {
    val avgFare: Double get() = if (spendingCount > 0) spending / spendingCount else 0.0

    fun of(metric: StatsMetric): Double = when (metric) {
        StatsMetric.SPENDING -> spending
        StatsMetric.RIDES -> rides.toDouble()
        StatsMetric.AVG_FARE -> avgFare
    }

    operator fun plus(o: MetricValues) =
        MetricValues(rides + o.rides, spending + o.spending, spendingCount + o.spendingCount)

    val isEmpty: Boolean get() = rides == 0 && spendingCount == 0
}

data class CustomChartItem(
    /**
     * 数据键（不翻译，显示时再按维度转换）：交通类型 / 城市中文名 / 线路名 / 站名 /
     * 星期序号 0(周一)..6 / 小时 0..23 / "yyyy-MM" / "yyyy-MM-dd"；[CustomChartItem.OTHER_KEY] = 其他
     */
    val key: String,
    val metrics: MetricValues,
    /** 所选指标的值 */
    val value: Double,
    val percent: Float,      // 所选指标占合计 (0..1)，平均票价为 0
    val barPercent: Float,   // 所选指标占最大值 (0..1)
    val color: Int,
    val cityName: String = "",       // 线路/车站所属城市
    val lineColor: String? = null
) {
    companion object { const val OTHER_KEY = "__other__" }
}

data class CustomChartData(
    val config: CustomChartConfig = CustomChartConfig(),
    /** 图表用的分组（类别维度已截断并合并"其他"；时间维度含补 0 的格） */
    val items: List<CustomChartItem> = emptyList(),
    /** 表格用的分组：只含有数据的行，类别维度不截断 */
    val tableItems: List<CustomChartItem> = emptyList(),
    val totals: MetricValues = MetricValues(),
    val currency: String = "¥"
) {
    /** 所选指标的合计（平均票价为整体平均） */
    val total: Double get() = totals.of(config.metric)
}
