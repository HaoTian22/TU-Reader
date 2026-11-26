package com.example.nfctransit.ui

import com.example.nfctransit.R
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.util.AppLanguage
import com.example.nfctransit.util.L10n

/**
 * UI 模型里的交通类型 / 城市是中文数据键（参与匹配、配色、统计分组），只在显示时翻译。
 */
object TransitLabels {

    private val TYPE_RES = mapOf(
        "地铁" to R.string.transit_metro,
        "公交" to R.string.transit_bus,
        "充值" to R.string.transit_recharge,
        "消费" to R.string.transit_purchase,
        "便利店" to R.string.transit_convenience,
        "有轨电车" to R.string.transit_tram,
        "城际" to R.string.transit_intercity,
        "轨道交通" to R.string.transit_rail,
        "公共交通" to R.string.transit_public,
        "其他" to R.string.transit_other
    )

    /** 交通类型键 -> 显示名；大类走字符串资源，其余数据库类型走 TransitData 英文对照，未知原样返回 */
    fun type(key: String): String {
        if (key.isEmpty() || key == "BRT" || AppLanguage.isChinese()) return key
        TYPE_RES[key]?.let { return L10n.str(it) }
        return TransitData.transitTypeLabel(key)
    }

    /** 站名位置可能是兜底的类型串（充值 / 轨道交通 / 公共交通…），这些按类型翻译；真实站名原样返回 */
    fun station(name: String): String = if (name in TYPE_RES) type(name) else name

    /** 交易类型显示名（详情页「交易类型」行与长按抽屉共用）：票务处理 / 地铁进出站 / 公交乘车 / 小额消费 / 其余按类型 */
    fun transactionType(txn: com.example.nfctransit.model.UiTransaction): String {
        if (txn.ticketProcessing) return L10n.str(R.string.amount_ticket_processing)
        return when (txn.transitType) {
            "地铁" -> L10n.str(when (txn.direction) {
                com.example.nfctransit.model.TransitDirection.ENTRY -> R.string.txn_type_metro_entry
                com.example.nfctransit.model.TransitDirection.EXIT -> R.string.txn_type_metro_exit
                else -> R.string.transit_metro
            })
            "公交" -> L10n.str(R.string.txn_type_bus_ride)
            "消费" -> L10n.str(R.string.txn_type_small_purchase)
            else -> type(txn.transitType)
        }
    }

    /**
     * 协议/应用代码 -> 显示代码。岭南通对外称 Lingnan Pass，显示为 LNP；
     * 内部数据键仍为 "LNT"（用户库 raw_records、transit.db protocol_city_code、line_rules、CSV 均以此为键），不改。
     */
    fun protocol(code: String): String = when (code) {
        "LNT" -> "LNP"
        else -> code
    }

    /** 中文城市名 -> 显示名 */
    fun city(cityZh: String?): String = TransitData.cityLabel(cityZh.orEmpty())
}
