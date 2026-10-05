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

    /** 中文城市名 -> 显示名 */
    fun city(cityZh: String?): String = TransitData.cityLabel(cityZh.orEmpty())
}
