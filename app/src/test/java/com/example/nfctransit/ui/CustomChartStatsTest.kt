package com.example.nfctransit.ui

import com.example.nfctransit.model.CustomChartConfig
import com.example.nfctransit.model.CustomChartItem
import com.example.nfctransit.model.StatsChartType
import com.example.nfctransit.model.StatsDimension
import com.example.nfctransit.model.StatsMetric
import com.example.nfctransit.model.UiTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomChartStatsTest {

    @Test
    fun spendingByCityExcludesTopUpsAndZeroFareEvents() {
        val txns = listOf(
            txn(city = "广州", amount = 3.0),
            txn(city = "广州", amount = 2.0),
            txn(city = "佛山", amount = 4.0),
            txn(city = "广州", amount = 0.0),                                   // 0 元旅程事件
            txn(city = "广州", amount = 50.0, typeHex = "02", amountText = "+¥50.00") // 充值
        )
        val data = computeCustomChart(txns, CustomChartConfig(StatsDimension.CITY, StatsMetric.SPENDING), "¥")

        assertEquals(listOf("广州", "佛山"), data.items.map { it.key })
        assertEquals(5.0, data.items[0].value, 1e-9)
        assertEquals(9.0, data.total, 1e-9)
        assertEquals(1f, data.items[0].barPercent, 1e-6f)
    }

    @Test
    fun averageFareIsPerGroupAndOverall() {
        val txns = listOf(
            txn(line = "1号线", amount = 2.0),
            txn(line = "1号线", amount = 4.0),
            txn(line = "3号线", amount = 5.0)
        )
        val data = computeCustomChart(
            txns, CustomChartConfig(StatsDimension.LINE, StatsMetric.AVG_FARE, StatsChartType.DONUT), "¥"
        )

        assertEquals(listOf("3号线", "1号线"), data.items.map { it.key })
        assertEquals(3.0, data.items[1].value, 1e-9)
        assertEquals(11.0 / 3, data.total, 1e-9)
        // 每行同时带三个指标（数据表用）
        assertEquals(2, data.items[1].metrics.rides)
        assertEquals(6.0, data.items[1].metrics.spending, 1e-9)
        // 平均票价不能画环形图；类别维度不能画折线
        assertEquals(StatsChartType.BAR, data.config.effectiveChartType)
        assertEquals(StatsChartType.COLUMN, CustomChartConfig(StatsDimension.CITY, chartType = StatsChartType.LINE).effectiveChartType)
    }

    @Test
    fun tableKeepsAllCategoriesWhileChartMergesTail() {
        val txns = (1..10).map { txn(station = "站$it", amount = it.toDouble()) }
        val data = computeCustomChart(
            txns, CustomChartConfig(StatsDimension.STATION, StatsMetric.RIDES, StatsChartType.DONUT), "¥"
        )
        assertEquals(10, data.tableItems.size)
        assertEquals(10, data.totals.rides)
        assertEquals(55.0, data.totals.spending, 1e-9)
    }

    @Test
    fun ridesCountOtherCurrenciesButSpendingDoesNot() {
        val txns = listOf(txn(amount = 3.0), txn(amount = 5.0).copy(protocol = "MPASS"))
        val data = computeCustomChart(txns, CustomChartConfig(StatsDimension.CITY, StatsMetric.RIDES), "¥")
        assertEquals(2, data.totals.rides)
        assertEquals(3.0, data.totals.spending, 1e-9)
        assertEquals(3.0, data.totals.avgFare, 1e-9)
    }

    @Test
    fun hourTableSkipsEmptySlots() {
        val txns = listOf(txn(time = "08:00:00", amount = 2.0), txn(time = "18:00:00", amount = 2.0))
        val data = computeCustomChart(txns, CustomChartConfig(StatsDimension.HOUR, StatsMetric.RIDES), "¥")
        assertEquals(24, data.items.size)
        assertEquals(listOf("8", "18"), data.tableItems.map { it.key })
    }

    @Test
    fun weekdayAndHourFillAllSlotsInOrder() {
        val txns = listOf(
            txn(date = "2026-10-19", time = "08:10:00", amount = 2.0), // 周一
            txn(date = "2026-10-25", time = "18:30:00", amount = 3.0)  // 周日
        )
        val weekday = computeCustomChart(txns, CustomChartConfig(StatsDimension.WEEKDAY, StatsMetric.RIDES), "¥")
        assertEquals((0..6).map { it.toString() }, weekday.items.map { it.key })
        assertEquals(1.0, weekday.items[0].value, 1e-9)
        assertEquals(1.0, weekday.items[6].value, 1e-9)

        val hour = computeCustomChart(txns, CustomChartConfig(StatsDimension.HOUR, StatsMetric.SPENDING), "¥")
        assertEquals(24, hour.items.size)
        assertEquals(3.0, hour.items[18].value, 1e-9)
    }

    @Test
    fun monthsAreContinuousAcrossGaps() {
        val txns = listOf(txn(date = "2025-11-03", amount = 1.0), txn(date = "2026-02-01", amount = 1.0))
        val data = computeCustomChart(txns, CustomChartConfig(StatsDimension.MONTH, StatsMetric.RIDES), "¥")
        assertEquals(listOf("2025-11", "2025-12", "2026-01", "2026-02"), data.items.map { it.key })
    }

    @Test
    fun donutMergesTailIntoOther() {
        val txns = (1..10).map { txn(station = "站$it", amount = it.toDouble()) }
        val data = computeCustomChart(
            txns, CustomChartConfig(StatsDimension.STATION, StatsMetric.SPENDING, StatsChartType.DONUT), "¥"
        )
        assertEquals(7, data.items.size)
        assertEquals(CustomChartItem.OTHER_KEY, data.items.last().key)
        assertEquals(1.0 + 2 + 3 + 4, data.items.last().value, 1e-9)
    }

    @Test
    fun configRoundTripsAndFallsBackOnGarbage() {
        val config = CustomChartConfig(StatsDimension.HOUR, StatsMetric.RIDES, StatsChartType.COLUMN)
        assertEquals(config, CustomChartConfig.decode(config.encode()))
        assertEquals(CustomChartConfig(), CustomChartConfig.decode("nope"))
        assertEquals(CustomChartConfig(), CustomChartConfig.decode(null))
    }

    private fun txn(
        city: String = "广州",
        line: String = "1号线",
        station: String = "体育西路",
        date: String = "2026-10-20",
        time: String = "08:00:00",
        amount: Double,
        typeHex: String = "06",
        amountText: String = "-¥%.2f".format(amount)
    ) = UiTransaction(
        id = 1,
        seq = 1,
        typeHex = typeHex,
        amountYuan = amount,
        amountText = amountText,
        transitType = "地铁",
        terminal = "",
        stationName = station,
        cityName = city,
        lineName = line,
        date = date,
        time = time,
        displayDateTime = "$date $time",
        balanceAfterYuan = null,
        balanceAfterText = null,
        icon = "",
        iconBgColor = 0L
    )
}
