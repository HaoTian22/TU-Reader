package com.example.nfctransit.model

/**
 * UI-friendly data models for screen display.
 */
data class UiCard(
    val id: String,
    val name: String,
    val cardType: String,      // 当前展示名的历史兼容字段
    val protocolType: String = "", // "YCT"/"CU"/"TU" 等内部卡型
    val lastFour: String,
    val cardNumber: String = "",  // 完整卡号（应用序列号），首页展示；其他页面用 lastFour
    val secondCardNumber: String? = null,  // 双协议卡第二个卡号（如 LNT+TU 的 TU 卡号）；nullable 兼容旧版持久化数据（Gson 反序列化为 null）
    val balanceFen: Long? = null,  // 余额（分），内部统一 Long；UI 层用 balanceYuan
    val gradientStartColor: Long,
    val gradientEndColor: Long,
    val lastReadAt: Long = 0L  // 最近一次读卡时间（epoch millis），首页"上次读取"用数据库里的真实时间
) {
    /** 余额（元），仅 UI 展示用 */
    val balanceYuan: Double get() = (balanceFen ?: 0L) / 100.0
}

data class UiCardMetadata(
    val issuerCity: String? = null,
    val issuer: String? = null,
    val issueDate: String? = null,
    val validUntil: String? = null,
    val secondStandard: String? = null,
    val secondIssueDate: String? = null,
    val secondValidUntil: String? = null
)

data class UiTransaction(
    val id: Int,
    val seq: Int,
    val sfi: Int = 0,          // 来源文件 SFI（0x18/0x1E/附加区）
    val hex: String = "",      // transactions_archive 中该条原始 hex，详情页直接展示
    val cardType: String = "", // "YCT"/"CU"/"TU" 等解析卡型
    val protocol: String = "", // 该原始记录的来源协议（LNT/TU/空）
    val amountYuan: Double,
    val amountText: String,        // e.g. "-¥3.00" or "+¥50.00"
    val typeHex: String,
    val transitType: String,       // "地铁", "公交", "消费", "便利店", "充值"
    val terminal: String,
    val stationName: String,
    val cityName: String = "",     // 城市中文名（如 "广州"）
    val lineName: String,          // e.g. "2号线", "M373"
    val lineColor: String? = null, // 线路颜色（"#RRGGBB"，空白时界面保持灰色）
    val lineId: Long? = null,      // 线路数据库 ID（页面间以 ID 传递；切换语言时按 ID 重新解析名称）
    val stationId: Long? = null,   // 站点数据库 ID
    val date: String,              // "2024-09-15"
    val time: String,              // "08:23:15"
    val displayDateTime: String,   // "2024-09-15 08:23:15" or "09-15 08:23"
    val balanceAfterYuan: Double?, // 无余额数据为 null（详情页显示"无"）
    val balanceAfterText: String?, // "余额 ¥45.50"；无余额数据为 null（列表行隐藏）
    val icon: String,              // emoji: 🚇 🚌 💳 🛒
    val iconBgColor: Long,         // background color for icon circle
    val protocols: List<String> = emptyList(),  // 该记录被哪些协议写入（LNT/TU），排序后展示
    val journeyHex: String? = null, // TU 卡同笔交易的 1E 旅程原始 hex（详情页原始数据显示两份）；null=无配对 1E
    val deviceCode: String? = null,  // 站名解析命中的 device_code（如 581000140019）；null=未命中
    val spRule: String? = null      // 特殊匹配规则标记（广佛跨城/深圳），详情页 Match 行附加展示；null=普通命中
)

data class StationStat(
    val name: String,
    val count: Int,
    val barWidthPercent: Float,     // relative to max count (0..1)
    val cityName: String = ""   // 车站所属城市，不同城市同名车站分开统计
)

data class LineStat(
    val name: String,
    val count: Int,
    val barWidthPercent: Float,
    val cityName: String = "",   // 线路所属城市，不同城市同名线路分开统计
    val lineColor: String? = null   // 线路颜色（"#RRGGBB"），药丸着色用；空白保持灰色
)

data class DailySpending(
    val dayLabel: String,          // "周一"..."周日" / "1号"..."31号" / "1月"..."12月"
    val amountYuan: Double,
    val barHeightPercent: Float,   // relative to max (0..1)
    val isToday: Boolean = false,
    val date: String = ""          // "yyyy-MM-dd"，用于柱点击弹窗与日期范围显示
)

data class CategorySpending(
    val name: String,          // "地铁" / "公交" / "消费" ...
    val amountYuan: Double,
    val percent: Float,        // 占总支出的比例 (0..1)
    val color: Int
)

/** 柱状图金额标签：3.0 -> ¥3，5.50 -> ¥5.5，5.55 -> ¥5.55（去掉多余的 ".00"） */
fun DailySpending.amountLabel(): String =
    "¥" + String.format("%.2f", amountYuan).trimEnd('0').trimEnd('.')

data class StatsSummary(
    val totalSpendingYuan: Double,
    val rideCount: Int,
    val avgDailyYuan: Double
)
