package com.example.nfctransit.model

import com.example.nfctransit.data.LocationSource

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

/** 卡上一个钱包的余额（多钱包卡：澳门通/八达通 + TU 等）；label 为应用名，不翻译卡名 */
data class WalletBalance(val protocol: String, val label: String, val balanceFen: Long) {
    val text: String get() = com.example.nfctransit.CardProfiles.currencySymbol(protocol) +
        String.format(java.util.Locale.getDefault(), "%.2f", balanceFen / 100.0)
}

data class UiCardMetadata(
    /** 主应用（无则首个有城市的应用）的发卡城市，仅首页/头部摘要使用 */
    val issuerCity: String? = null,
    /** 卡上全部可用应用的协议（主应用在前），如 LNT / TU */
    val appProtocols: List<String> = emptyList(),
    val applications: List<UiApplicationInfo> = emptyList()
)

/** 单个应用的展示信息：发卡城市/机构等均归属该应用。 */
data class UiApplicationInfo(
    val protocol: String,
    /** 分区标题名称；null = 无专用名称 */
    val name: String?,
    val cardNumber: String?,
    val issuerCity: String?,
    val metadata: com.example.nfctransit.data.ApplicationMetadata,
    /** 该应用最近一次读到的余额（含币种），如联名卡的八达通 / TU 钱包 */
    val balance: String? = null
)

data class UiTransaction(
    val id: Int,
    val seq: Int,
    val sfi: Int = 0,          // 来源文件 SFI（0x18/0x1E/附加区）
    val hex: String = "",      // transactions_archive 中该条原始 hex，详情页直接展示
    val cardType: String = "", // "YCT"/"CU"/"TU" 等解析卡型
    val protocol: String = "", // 该原始记录的来源协议（LNT/TU/空）
    val amountYuan: Double,
    val amountText: String,        // e.g. "-¥3.00" or "+¥50.00"；0 元旅程事件为本地化标签（进站/出站/乘车/票务处理）
    val ticketProcessing: Boolean = false, // TU 0 元票务处理记录
    val typeHex: String,
    val transitType: String,       // "地铁", "公交", "消费", "便利店", "充值"
    val terminal: String,
    val stationName: String,
    val direction: TransitDirection? = null,
    val cityName: String = "",     // 城市中文名（如 "广州"）
    val cityCode: String? = null,   // 交易原始城市码，供反馈覆盖预填
    val lineName: String,          // e.g. "2号线", "M373"
    val lineColor: String? = null, // 线路颜色（"#RRGGBB"，空白时界面保持灰色）
    val lineId: Long? = null,      // 线路数据库 ID（页面间以 ID 传递；切换语言时按 ID 重新解析名称）
    val stationId: Long? = null,   // 站点数据库 ID
    val date: String,              // "2024-09-15"
    val time: String,              // "08:23:15"
    val displayDateTime: String,   // "2024-09-15 08:23:15" or "09-15 08:23"
    val balanceAfterYuan: Double?, // 无余额数据为 null（详情页显示"-"）
    val balanceAfterText: String?, // "余额 ¥45.50"；无余额数据为 null（列表行隐藏）
    val icon: String,              // Font Awesome 7 solid 字形（fa-solid-900.otf）
    val iconBgColor: Long,         // background color for icon circle
    val protocols: List<String> = emptyList(),  // 该记录被哪些协议写入（LNT/TU），排序后展示
    val deviceCode: String? = null,  // 站名解析命中的 device_code（如 581000140019）；null=未命中
    val spRule: String? = null,      // 特殊匹配规则标记（广佛跨城/深圳），详情页 Match 行附加展示；null=普通命中
    val actualCityCode: String? = null,
    val locationSource: LocationSource = LocationSource.DECLARED_CITY_FALLBACK,
    /** 组成该交易的全部原始记录（各带来源应用），详情页原始数据逐条展示 */
    val sources: List<RawHexBlock> = emptyList()
) {
    /**
     * 站名纠错按哪个协议匹配：双钱包卡的 TU 钱包记录按 TU 匹配（标卡型会被 TU 路径拒绝），
     * 两钱包合并的记录（CU+TU 等）只要含 TU 就标 TU；其余为记录自身协议。
     */
    val overrideStandard: String get() = "TU".takeIf { it in protocols } ?: protocol.ifBlank { cardType }

    /** 与本交易合并的 TU 1E 旅程记录（本交易自身为 1E 时为 null） */
    val journeyHex: String? get() = sources.firstOrNull { it.sfi == 0x1E && it.hex != hex }?.hex
}

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
    val dayLabel: String,          // 星期 / 日期 / 月份标签（按界面语言）
    val amountYuan: Double,
    val barHeightPercent: Float,   // relative to max (0..1)
    val isToday: Boolean = false,
    val date: String = "",         // "yyyy-MM-dd"，用于柱点击弹窗与日期范围显示
    val isMonth: Boolean = false,  // 柱代表整月（年视图 / 长自定义范围）
    val compactLabel: String = dayLabel,  // 稠密视图（整月 30+ 列）用的短标签
    val currency: String = "¥"     // 统计币种符号（多币种卡按所选币种统计）
)

data class CategorySpending(
    val name: String,          // "地铁" / "公交" / "消费" ...
    val amountYuan: Double,
    val percent: Float,        // 占总支出的比例 (0..1)
    val color: Int,
    val currency: String = "¥"
)

/** 柱状图金额标签：3.0 -> ¥3，5.50 -> ¥5.5，5.55 -> ¥5.55（去掉多余的 ".00"） */
fun DailySpending.amountLabel(): String =
    currency + String.format("%.2f", amountYuan).trimEnd('0').trimEnd('.')

data class StatsSummary(
    val totalSpendingYuan: Double,
    val rideCount: Int,
    val avgDailyYuan: Double,
    val currency: String = "¥"
)

/** 交易金额币种（按来源钱包协议：澳门通 MOP、八达通 HK$，其余 ¥）。 */
val UiTransaction.currency: String get() = com.example.nfctransit.CardProfiles.currencySymbol(protocol)

/** 计入消费的交易（与统计口径一致：原生充值 "+" 不计）。 */
val UiTransaction.isSpending: Boolean get() = !amountText.startsWith("+") && amountYuan > 0

/** 按币种分别合计消费（不同币种不能相加），保持首次出现顺序。 */
fun List<UiTransaction>.spendingByCurrency(): Map<String, Double> {
    val out = linkedMapOf<String, Double>()
    for (t in this) if (t.isSpending) out[t.currency] = (out[t.currency] ?: 0.0) + t.amountYuan
    return out
}

/** "MOP 13.00 + ¥5.00"；无消费返回空串。 */
fun Map<String, Double>.formatCurrencyTotals(): String =
    entries.joinToString(" + ") { (currency, amount) -> currency + String.format("%.2f", amount) }
