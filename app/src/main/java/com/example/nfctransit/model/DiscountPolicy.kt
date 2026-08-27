package com.example.nfctransit.model

/**
 * 公交/地铁累计票款优惠政策，按消费城市自动切换。
 *
 * 政策按"当月累计实际支出票款"分档，用升序档位表（[Tier]）描述，评估时取
 * 当乘次后累计金额命中的最后一档：
 *
 *   广州：当月累计实际支出票款满 80 元（不含满 200 元）部分享受 8 折；超出 200 元部分享受 5 折。
 *   佛山：自然月内乘坐佛山/广州两市公交、地铁、有轨电车累计满 60 元（不含满 150 元）后再乘佛山公交享 8 折；
 *        满 150 元及以上再乘佛山公交享 5 折。
 *   杭州：自然月内乘坐公共交通线路累计消费金额 50 元以内的每乘次享 9 折；
 *        累计消费在 50（含）至 100 元的每乘次享 7 折；100 元（含）以上每乘次享 5 折。
 *
 * 其他城市未定义时返回 null（首页不展示优惠卡片）。
 */
data class DiscountPolicy(
    val cityZh: String,
    /** 档位表（升序）：首档通常为 0 起算的基础档，末档为最高档折扣 */
    val tiers: List<Tier>
) {
    /**
     * 单个折扣档位：
     * @param minFen 起算的月累计门槛（分）
     * @param minInclusive 月累计恰等于门槛时是否落入本档（杭州「50（含）/100（含）」= true，
     *                     广佛「满 X 后的部分」= false）
     * @param discountPercent 折扣率（%），如 80 = 8 折、90 = 9 折、100 = 无折扣
     */
    data class Tier(
        val minFen: Long,
        val minInclusive: Boolean,
        val discountPercent: Int
    )

    /** 单笔交易后月累计为 cumulativeAfterFen 时适用的折扣率（%）；未达任何更高档返回基础档 */
    fun discountPercentFor(cumulativeBeforeFen: Long, fareFen: Long): Int =
        discountPercentAt(cumulativeBeforeFen + fareFen)

    /** 月累计为 cumulativeFen 时所处档位的折扣率（%） */
    fun discountPercentAt(cumulativeFen: Long): Int = tierAt(cumulativeFen).discountPercent

    private fun tierAt(cumulativeFen: Long): Tier =
        tiers.lastOrNull { if (it.minInclusive) cumulativeFen >= it.minFen else cumulativeFen > it.minFen }
            ?: tiers.first()

    companion object {
        /** 按消费城市返回政策；未定义的城市返回 null（首页隐藏优惠卡片）。政策定义统一在 DiscountRegistry */
        fun policyFor(cityZh: String?): DiscountPolicy? = DiscountRegistry.find(cityZh.orEmpty())?.policy
    }
}
