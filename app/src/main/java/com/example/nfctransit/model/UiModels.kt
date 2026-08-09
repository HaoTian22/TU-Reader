package com.example.nfctransit.model

/**
 * UI-friendly data models for screen display.
 */
data class UiCard(
    val id: String,
    val name: String,
    val cardType: String,      // "深圳通" or "岭南通"
    val lastFour: String,
    val balanceYuan: Double,
    val gradientStartColor: Long,
    val gradientEndColor: Long
)

data class UiTransaction(
    val id: Int,
    val seq: Int,
    val amountYuan: Double,
    val amountText: String,        // e.g. "-¥3.00" or "+¥50.00"
    val typeHex: String,
    val transitType: String,       // "地铁", "公交", "消费", "充值"
    val terminal: String,
    val stationName: String,
    val cityName: String = "",     // 城市中文名（如 "广州"）
    val lineName: String,          // e.g. "2号线", "M373"
    val date: String,              // "2024-09-15"
    val time: String,              // "08:23:15"
    val displayDateTime: String,   // "2024-09-15 08:23:15" or "09-15 08:23"
    val balanceAfterYuan: Double,
    val balanceAfterText: String,  // "余额 ¥45.50"
    val icon: String,              // emoji: 🚇 🚌 💳 🛒
    val iconBgColor: Long          // background color for icon circle
)

data class StationStat(
    val name: String,
    val count: Int,
    val barWidthPercent: Float     // relative to max count (0..1)
)

data class LineStat(
    val name: String,
    val count: Int,
    val barWidthPercent: Float
)

data class DailySpending(
    val dayLabel: String,          // "周一"..."周日" / "1号"..."31号" / "1月"..."12月"
    val amountYuan: Double,
    val barHeightPercent: Float,   // relative to max (0..1)
    val isToday: Boolean = false,
    val date: String = ""          // "yyyy-MM-dd"，用于柱点击弹窗与日期范围显示
)

data class StatsSummary(
    val totalSpendingYuan: Double,
    val rideCount: Int,
    val avgDailyYuan: Double
)
