package com.example.nfctransit

/**
 * 交通卡卡种档案：AID / 关键 SFI / 交易记录字段布局
 * 数据来源：NFC Wiki 智能卡手册 (wiki.nfc.im) 交通卡章节
 *        + tripreader-technical.md（Trip Reader 1.7.17 逆向：APDU 序列与识别顺序）
 * 覆盖：岭南通(YCT1/PAY.APPY、YCT2/PAY.TICL)、深圳通(SZT)、苏州(SUXIN/SZTK)、
 *       天津(TFT)、数字城市一卡通(CU)、交通联合卡(TU)
 * 识别顺序（首个 SELECT 成功即判定卡型）见 CardProfiles.known 的排列顺序。
 */
data class CardProfile(
    val name: String,
    val aidCandidates: List<String>,
    val infoSfi: Int,          // 公共应用基本信息文件 SFI
    val tradeSfi: Int,         // 电子钱包交易明细记录文件 SFI
    val tradeRecordLen: Int = 0x17,
    val stationSfi: Int? = null,  // 线路+站点信息文件 SFI (TU 卡用 0x1E)
    val cardType: String = "YCT"  // "TU"/"CU"/"YCT"/"SZT"/"SUXIN"/"SZTK"/"TFT"
) {
    /** 各卡附加交易区 SFI（CU/TFT 专有），与主交易区一样参与交易解析 */
    val extraTradeSfis: List<Int>
        get() = when (cardType) {
            "CU" -> listOf(0x10, 0x06, 0x1A)
            "TFT" -> listOf(0x10, 0x09)
            else -> emptyList()
        }

    /**
     * 参与交易解析的全部 SFI（主交易区 + TU 站点映射 + 附加交易区）。
     * 信息/统计/管理扇区（0x15/0x19/0x08/0x17…）只存 raw_records，不在此列、不做交易解析。
     */
    val transactionSfis: Set<Int>
        get() {
            val set = mutableSetOf(tradeSfi)
            stationSfi?.let { set.add(it) }
            // 0x1E 是 TU 旅程区：单协议 TU 卡与双协议 YCT 的 TU 钱包都有，参与交易解析
            if (cardType == "TU" || cardType == "YCT") set.add(0x1E)
            set.addAll(extraTradeSfis)
            return set
        }
}

object CardProfiles {

    val PSE_AID = "325041592E5359532E4444463031" // 2PAY.SYS.DDF01，PSE 目录

    /**
     * 卡型识别顺序（tripreader-technical.md §1.2）：
     * YCT1(APPY) → YCT2(TICL) → SZT → SUXIN → SZTK → TFT → CU → 通用 TU。
     * 除 TU 用 SFI 0x1E 终端映射表外，其余卡种共享 PBOC 电子钱包结构
     * （信息文件 SFI 0x15 / BALANCE CHECK / 交易明细 SFI 0x18）。
     */
    val known = listOf(
        CardProfile(
            name = "岭南通/羊城通 (YCT1)",
            aidCandidates = listOf("5041592E41505059"), // PAY.APPY
            infoSfi = 0x15,
            tradeSfi = 0x18,
            cardType = "YCT"
        ),
        CardProfile(
            name = "岭南通/羊城通 (YCT2)",
            aidCandidates = listOf("5041592E5449434C"), // PAY.TICL
            infoSfi = 0x15,
            tradeSfi = 0x18,
            cardType = "YCT"
        ),
        CardProfile(
            name = "深圳通 (SZT)",
            aidCandidates = listOf("5041592E535A54"), // PAY.SZT
            infoSfi = 0x15,
            tradeSfi = 0x18,
            cardType = "SZT"
        ),
        CardProfile(
            name = "苏州通 (SUXIN)",
            aidCandidates = listOf("535558494E2E4444463031"), // SUXIN.DDF01
            infoSfi = 0x15,
            tradeSfi = 0x18,
            cardType = "SUXIN"
        ),
        CardProfile(
            name = "苏州通 (SZTK)",
            aidCandidates = listOf("535A504B5F5A5959"), // SZTK_ZY
            infoSfi = 0x15,
            tradeSfi = 0x18,
            cardType = "SZTK"
        ),
        CardProfile(
            name = "天津通 (TFT)",
            aidCandidates = listOf("D156000015B9ABB9B2D3A6D3C3"),
            infoSfi = 0x15,
            tradeSfi = 0x18,
            cardType = "TFT"
        ),
        CardProfile(
            name = "数字城市一卡通 (CU)",
            aidCandidates = listOf("A00000000386980701"), // 住建部 CPU 钱包
            infoSfi = 0x15,
            tradeSfi = 0x18,
            cardType = "CU"
        ),
        // 交通联合卡（TU）：站点数据来自 transit.db standard='TU' 行，配合 SFI 0x1E 终端映射表
        CardProfile(
            name = "交通联合卡 (T-Union)",
            aidCandidates = listOf("A000000632010105", "A000000632010106"),
            infoSfi = 0x15,
            tradeSfi = 0x18,
            stationSfi = 0x1E,
            cardType = "TU"
        )
    )
}

/** 公共应用基本信息 */
data class CardInfo(
    val cardName: String,
    val validFrom: String,
    val validTo: String,
    val cardNumber: String = "",  // 应用序列号（卡号），来自 SFI 0x15 bytes 10-19 BCD
    val rawHex: String
)

/** 卡内当前线路+站点信息（从 SFI 0x1E 或其他来源解析） */
data class StationInfo(
    val cityCode: String = "未知",
    val cityName: String = "未知",
    val lineCode: String = "未知",
    val stationCode: String = "未知",
    val stationName: String = "未知",
    val rawHex: String = "",
    val balanceFen: Long = 0,       // 该记录对应的余额（分），0x96E=2414=¥24.14
    val direction: String = ""      // "入站" or "出站"
)
