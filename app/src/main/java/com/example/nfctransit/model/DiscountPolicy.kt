package com.example.nfctransit.model

/**
 * 公交/地铁累计票款优惠政策，按消费城市自动切换。
 *
 * 政策按"当月累计实际支出票款"分档：
 *   - 第一档起始门槛 reachAfter，达到后该档区间内享受 8 折
 *   - 超出高门槛的部分享受 5 折
 *
 * 目前仅有广州/佛山政策（参考两地公共交通票价优惠规则）：
 *   广州：当月累计实际支出票款满 80 元（不含满 200 元）部分享受 8 折；超出 200 元部分享受 5 折。
 *   佛山：自然月内乘坐佛山/广州两市公交、地铁、有轨电车累计满 60 元（不含满 150 元）后再乘佛山公交享 8 折；
 *        满 150 元及以上再乘佛山公交享 5 折。
 * 其他城市未定义时返回 null（首页不展示优惠卡片）。
 */
data class DiscountPolicy(
    val cityZh: String,
    val tier1ThresholdFen: Long,    // 满此金额后进入 8 折档（广州 8000 分 = ¥80）
    val tier2ThresholdFen: Long,    // 超出此金额后进入 5 折档（广州 20000 分 = ¥200）
    val tier1Discount: Int,         // 档 1 折扣率，如 80 = 8 折
    val tier2Discount: Int          // 档 2 折扣率，如 50 = 5 折
) {
    /** 单笔交易金额超过此门槛后可享受的折扣率（%）；未达门槛返回 100（无折扣） */
    fun discountPercentFor(cumulativeBeforeFen: Long, fareFen: Long): Int {
        val after = cumulativeBeforeFen + fareFen
        return when {
            after <= tier1ThresholdFen -> 100
            after <= tier2ThresholdFen -> tier1Discount
            else -> tier2Discount
        }
    }

    companion object {
        /** 按消费城市返回政策；未定义的城市返回 null（首页隐藏优惠卡片） */
        fun policyFor(cityZh: String?): DiscountPolicy? {
            return when (cityZh?.trim()) {
                "广州" -> DiscountPolicy(
                    cityZh = "广州",
                    tier1ThresholdFen = 8000,
                    tier2ThresholdFen = 20000,
                    tier1Discount = 80,
                    tier2Discount = 50
                )
                "佛山" -> DiscountPolicy(
                    cityZh = "佛山",
                    tier1ThresholdFen = 6000,
                    tier2ThresholdFen = 15000,
                    tier1Discount = 80,
                    tier2Discount = 50
                )
                else -> null
            }
        }
    }
}
