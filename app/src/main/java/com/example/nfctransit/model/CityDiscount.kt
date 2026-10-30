package com.example.nfctransit.model

import com.example.nfctransit.CardProfiles

/**
 * 城市乘车优惠的完整配置 = 城市名 + 满额折扣档位（policy）+ 卡内月累乘统计的读取位置与字段区域（stats）。
 * 解析与首页展示由通用代码完成——新增/调整一座城市只需修改 [DiscountRegistry.schemes] 中的一条配置。
 */
data class CityDiscount(
    val cityZh: String,
    /** 满*打折档位表：按当月累计实际支出分档（详见 [DiscountPolicy]） */
    val policy: DiscountPolicy,
    /** 卡内本月累乘统计从哪个文件读、按什么字段区域解 */
    val stats: MonthAccumSpec
)

/**
 * 卡内"自然月累乘金额"统计的读取配置：
 * @param sources 候选读卡位置（SFI + 协议标签 + 记录号）；按顺序尝试，首个可解析的生效
 */
sealed class MonthAccumSpec {
    open val sources: List<Source> = emptyList()

    /**
     * 一个候选读卡位置。recNo 为 null 表示扫描该 SFI 下全部记录；protocol 为 null 表示不限协议；
     * aid 非空时只认该应用下读出的记录（同一 SFI 在不同卡种含义不同，如 CU 的 0x1A 是交易区）
     */
    data class Source(
        val sfi: Int,
        val protocol: String? = null,
        val recNo: Int? = null,
        val aid: String? = null
    )

    /**
     * 定长记录式（广州/佛山 TU 0x19 rec1、岭南通 PAY.APPY SFI 0x08 rec1、天府通 SFI 0x1A rec2 等 PBOC 月统计）：
     * 固定偏移直取 —— BCD 年（纪元 2000 起）+ BCD 月 + 大端无符号当月累计金额（分，默认 2 字节）。
     */
    data class Fixed(
        override val sources: List<Source>,
        val yearBcdOffset: Int,
        val monthBcdOffset: Int,
        val totalFenBeOffset: Int,
        val minDataSize: Int,
        val totalFenLength: Int = 2
    ) : MonthAccumSpec()

    /**
     * 锚点扫描式（杭州 CU SFI 0x17 / TU SFI 0x19 变长记录）：记录外层为 [tag][len] 包裹，
     * payload 内含若干 `[anchor][序号]` 开头条目；本配置的条目按相对锚点的偏移取
     * BCD YYYYMMDD 刷新日期与大端 u16 当月累计金额（分），多条命中取日期最新。
     */
    data class Anchored(
        override val sources: List<Source>,
        val anchorByte: Int,
        val dateBcdOffset: Int,
        val amountBeOffset: Int
    ) : MonthAccumSpec()
}

/** 城市优惠方案注册表；顺序即首页展示顺序 */
object DiscountRegistry {

    /**
     * 广佛/岭南通月统计记录（同一格式双来源）：优先 TU 钱包 SFI 0x19 rec1，
     * 失败回退岭南通 PAY.APPY SFI 0x08 rec1。广州/佛山两个方案共享同一实例以复用解析；
     * LNT 交易年份锚点（TransitRepository）也读同一配置。
     */
    val walletMonthStats = MonthAccumSpec.Fixed(
        sources = listOf(
            MonthAccumSpec.Source(sfi = 0x19, protocol = "TU", recNo = 1),
            MonthAccumSpec.Source(sfi = 0x08, protocol = "LNT", recNo = 1)
        ),
        yearBcdOffset = 3,
        monthBcdOffset = 4,
        totalFenBeOffset = 12,
        minDataSize = 14
    )

    /** 杭州：双标卡 CU 钱包 SFI 0x17 与 TU 钱包 SFI 0x19 都是同格式变长记录，两边都扫取最新 */
    private val hangzhouMonthStats = MonthAccumSpec.Anchored(
        sources = listOf(
            MonthAccumSpec.Source(sfi = 0x17),
            MonthAccumSpec.Source(sfi = 0x19)
        ),
        anchorByte = 0xFA,
        dateBcdOffset = 2,
        amountBeOffset = 9
    )

    /**
     * 成都天府通：TFT 应用 SFI 0x1A rec2（51B，READ RECORD `00 B2 02 D4 33`）。
     * [37..39) 大端 u16 本月乘次、[39..42) 大端 u24 本月累计金额（分）、[46] BCD 年 + [47] BCD 月。
     * 布局来自 TripReader 反编译（尚无实卡样本校验）。只有天府通应用有此记录，故用 AID 限定。
     */
    private val chengduMonthStats = MonthAccumSpec.Fixed(
        sources = listOf(
            MonthAccumSpec.Source(sfi = 0x1A, recNo = 2, aid = CardProfiles.TFT_AID)
        ),
        yearBcdOffset = 46,
        monthBcdOffset = 47,
        totalFenBeOffset = 39,
        minDataSize = 48,
        totalFenLength = 3
    )

    val schemes: List<CityDiscount> = listOf(
        CityDiscount(
            cityZh = "广州",
            policy = DiscountPolicy(
                "广州",
                listOf(
                    DiscountPolicy.Tier(0, true, 100),
                    DiscountPolicy.Tier(8000, false, 80),   // 满 ¥80 后的部分享 8 折
                    DiscountPolicy.Tier(20000, false, 50)   // 超 ¥200 的部分享 5 折
                )
            ),
            stats = walletMonthStats
        ),
        CityDiscount(
            cityZh = "佛山",
            policy = DiscountPolicy(
                "佛山",
                listOf(
                    DiscountPolicy.Tier(0, true, 100),
                    DiscountPolicy.Tier(6000, false, 80),
                    DiscountPolicy.Tier(15000, false, 50)
                )
            ),
            stats = walletMonthStats
        ),
        CityDiscount(
            cityZh = "杭州",
            policy = DiscountPolicy(
                "杭州",
                listOf(
                    DiscountPolicy.Tier(0, true, 90),      // 累计 50 元以内每乘次 9 折
                    DiscountPolicy.Tier(5000, true, 70),   // 50（含）至 100 元每乘次 7 折
                    DiscountPolicy.Tier(10000, true, 50)   // 100 元（含）以上每乘次 5 折
                )
            ),
            stats = hangzhouMonthStats
        ),
        CityDiscount(
            cityZh = "成都",
            policy = DiscountPolicy(
                "成都",
                listOf(
                    DiscountPolicy.Tier(0, true, 90),       // 累计 200 元以下每乘次 9 折
                    DiscountPolicy.Tier(20000, false, 60),  // 超过 200 元后 6 折
                    DiscountPolicy.Tier(40000, false, 90)   // 超过 400 元后恢复 9 折
                )
            ),
            stats = chengduMonthStats
        )
    )

    fun find(cityZh: String): CityDiscount? = schemes.firstOrNull { it.cityZh == cityZh.trim() }
}

/** 首页优惠卡片单城视图数据（已按"统计月份 = 当前月份"判过有效性） */
data class CityDiscountUi(
    val cityZh: String,
    val monthlyFen: Long,
    val monthLabel: String?   // "2026-08"，统计月份未知为 null
)
