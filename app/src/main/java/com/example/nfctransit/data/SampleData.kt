package com.example.nfctransit.data

import com.example.nfctransit.model.*

/**
 * Singleton providing mock data matching the design mockup exactly.
 * All transaction data, card info, and aggregated statistics come from here.
 */
object SampleData {

    val cardShenzhenTong = UiCard(
        id = "szt_3821",
        name = "深圳通",
        cardType = "深圳通",
        lastFour = "3821",
        balanceYuan = 186.50,
        gradientStartColor = 0xFF1A73E8,
        gradientEndColor = 0xFF0D47A1
    )

    val cardLingnanTong = UiCard(
        id = "lnt_7294",
        name = "岭南通",
        cardType = "岭南通",
        lastFour = "7294",
        balanceYuan = 89.30,
        gradientStartColor = 0xFF2E7D32,
        gradientEndColor = 0xFF1B5E20
    )

    val allCards = listOf(cardShenzhenTong, cardLingnanTong)

    /**
     * All transactions matching the design mockup, sorted newest first.
     */
    val allTransactions: List<UiTransaction> = listOf(
        UiTransaction(
            id = 0, seq = 0, amountYuan = 3.00,
            amountText = "-¥3.00", typeHex = "06", transitType = "地铁",
            terminal = "SZ-MT-002381", stationName = "人民广场站",
            lineName = "2号线", date = "2024-09-15", time = "08:23:15",
            displayDateTime = "2024-09-15 08:23:15",
            balanceAfterYuan = 45.50, balanceAfterText = "余额 ¥45.50",
            icon = "🚇", iconBgColor = 0xFFE3F2FD
        ),
        UiTransaction(
            id = 1, seq = 1, amountYuan = 2.00,
            amountText = "-¥2.00", typeHex = "06", transitType = "公交",
            terminal = "SZ-BS-001892", stationName = "科技园站",
            lineName = "M373", date = "2024-09-14", time = "18:05:42",
            displayDateTime = "2024-09-14 18:05:42",
            balanceAfterYuan = 48.50, balanceAfterText = "余额 ¥48.50",
            icon = "🚌", iconBgColor = 0xFFFFF3E0
        ),
        UiTransaction(
            id = 2, seq = 2, amountYuan = -50.00,
            amountText = "+¥50.00", typeHex = "01", transitType = "充值",
            terminal = "SZ-RC-005621", stationName = "充值",
            lineName = "—", date = "2024-09-14", time = "10:30:08",
            displayDateTime = "2024-09-14 10:30:08",
            balanceAfterYuan = 50.50, balanceAfterText = "余额 ¥50.50",
            icon = "💳", iconBgColor = 0xFFE8F5E9
        ),
        UiTransaction(
            id = 3, seq = 3, amountYuan = 3.00,
            amountText = "-¥3.00", typeHex = "06", transitType = "地铁",
            terminal = "SZ-MT-001234", stationName = "老街站",
            lineName = "1号线", date = "2024-09-13", time = "07:55:33",
            displayDateTime = "2024-09-13 07:55:33",
            balanceAfterYuan = 0.50, balanceAfterText = "余额 ¥0.50",
            icon = "🚇", iconBgColor = 0xFFE3F2FD
        ),
        UiTransaction(
            id = 4, seq = 4, amountYuan = 5.50,
            amountText = "-¥5.50", typeHex = "02", transitType = "消费",
            terminal = "SZ-CV-009832", stationName = "便利店消费",
            lineName = "—", date = "2024-09-12", time = "12:15:20",
            displayDateTime = "2024-09-12 12:15:20",
            balanceAfterYuan = 3.50, balanceAfterText = "余额 ¥3.50",
            icon = "🛒", iconBgColor = 0xFFFCE4EC
        ),
        UiTransaction(
            id = 5, seq = 5, amountYuan = 3.00,
            amountText = "-¥3.00", typeHex = "06", transitType = "地铁",
            terminal = "SZ-MT-003214", stationName = "市民中心站",
            lineName = "2号线", date = "2024-09-12", time = "08:10:05",
            displayDateTime = "2024-09-12 08:10:05",
            balanceAfterYuan = 9.00, balanceAfterText = "余额 ¥9.00",
            icon = "🚇", iconBgColor = 0xFFE3F2FD
        ),
        UiTransaction(
            id = 6, seq = 6, amountYuan = 2.00,
            amountText = "-¥2.00", typeHex = "04", transitType = "公交",
            terminal = "SZ-BS-004561", stationName = "华强北站",
            lineName = "M191", date = "2024-09-11", time = "17:45:30",
            displayDateTime = "2024-09-11 17:45:30",
            balanceAfterYuan = 12.00, balanceAfterText = "余额 ¥12.00",
            icon = "🚌", iconBgColor = 0xFFFFF3E0
        ),
        UiTransaction(
            id = 7, seq = 7, amountYuan = 4.00,
            amountText = "-¥4.00", typeHex = "06", transitType = "地铁",
            terminal = "SZ-MT-002145", stationName = "深圳北站",
            lineName = "5号线", date = "2024-09-10", time = "09:30:00",
            displayDateTime = "2024-09-10 09:30:00",
            balanceAfterYuan = 14.00, balanceAfterText = "余额 ¥14.00",
            icon = "🚇", iconBgColor = 0xFFE3F2FD
        ),
        UiTransaction(
            id = 8, seq = 8, amountYuan = -100.00,
            amountText = "+¥100.00", typeHex = "01", transitType = "充值",
            terminal = "SZ-RC-003892", stationName = "充值",
            lineName = "—", date = "2024-09-10", time = "08:00:00",
            displayDateTime = "2024-09-10 08:00:00",
            balanceAfterYuan = 18.00, balanceAfterText = "余额 ¥18.00",
            icon = "💳", iconBgColor = 0xFFE8F5E9
        ),
        UiTransaction(
            id = 9, seq = 9, amountYuan = 2.00,
            amountText = "-¥2.00", typeHex = "06", transitType = "地铁",
            terminal = "SZ-MT-005678", stationName = "罗湖站",
            lineName = "1号线", date = "2024-09-09", time = "18:20:00",
            displayDateTime = "2024-09-09 18:20:00",
            balanceAfterYuan = -82.00, balanceAfterText = "余额 ¥-82.00",
            icon = "🚇", iconBgColor = 0xFFE3F2FD
        )
    )

    // Recent 4 transactions for home screen
    val recentTransactions: List<UiTransaction>
        get() = allTransactions.take(4)

    // Station stats (top 5)
    val topStations: List<StationStat> = listOf(
        StationStat("人民广场站", 15, 1.0f),
        StationStat("科技园站", 12, 0.8f),
        StationStat("老街站", 9, 0.6f),
        StationStat("深圳北站", 7, 0.47f),
        StationStat("市民中心站", 5, 0.33f)
    )

    // Line stats (top 5)
    val topLines: List<LineStat> = listOf(
        LineStat("2号线", 18, 1.0f),
        LineStat("1号线", 14, 0.78f),
        LineStat("5号线", 10, 0.56f),
        LineStat("M373", 7, 0.39f),
        LineStat("M191", 4, 0.22f)
    )

    // Daily spending for current week (bar chart)
    val weeklySpending: List<DailySpending> = listOf(
        DailySpending("周一", 35.0, 35f / 62f),
        DailySpending("周二", 52.0, 52f / 62f),
        DailySpending("周三", 28.0, 28f / 62f),
        DailySpending("周四", 45.0, 45f / 62f),
        DailySpending("周五", 38.0, 38f / 62f),
        DailySpending("周六", 62.0, 1.0f, isToday = true),
        DailySpending("周日", 48.0, 48f / 62f)
    )

    // Daily spending for home mini chart (short labels)
    val homeChartSpending: List<DailySpending> = listOf(
        DailySpending("一", 35.0, 48f / 70f),
        DailySpending("二", 32.0, 32f / 70f),
        DailySpending("三", 65.0, 65f / 70f),
        DailySpending("四", 28.0, 28f / 70f),
        DailySpending("五", 55.0, 55f / 70f),
        DailySpending("六", 42.0, 42f / 70f),
        DailySpending("日", 70.0, 1.0f, isToday = true)
    )

    // Summary stats for "本月"
    val statsSummary: StatsSummary = StatsSummary(
        totalSpendingYuan = 308.00,
        rideCount = 42,
        avgDailyYuan = 10.27
    )

    // Filter transactions
    fun getTransactionsByType(type: String): List<UiTransaction> {
        return when (type) {
            "地铁" -> allTransactions.filter { it.transitType == "地铁" }
            "公交" -> allTransactions.filter { it.transitType == "公交" }
            "消费" -> allTransactions.filter { it.transitType == "消费" }
            "充值" -> allTransactions.filter { it.transitType == "充值" }
            else -> allTransactions
        }
    }

    // Trip info for map trace page
    data class TripEntry(
        val route: String,         // "人民广场 → 科技园"
        val timeLine: String,      // "09-15 08:23 · 2号线"
        val amountText: String,    // "¥3.00", "+¥50.00"
        val amountColor: Long      // 0xFF_FF6B6B or 0xFF_34C759
    )

    val tripEntries: List<TripEntry> = listOf(
        TripEntry("人民广场 → 科技园", "09-15 08:23 · 2号线", "¥3.00", 0xFFFF6B6B),
        TripEntry("科技园 → 老街", "09-14 18:05 · M373", "¥2.00", 0xFFFF6B6B),
        TripEntry("充值", "09-14 10:30 · —", "+¥50.00", 0xFF34C759),
        TripEntry("老街 → 深圳北", "09-13 07:55 · 1号线", "¥3.00", 0xFFFF6B6B)
    )
}
