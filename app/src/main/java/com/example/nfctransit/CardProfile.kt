package com.example.nfctransit

/**
 * 交通卡卡种档案：AID / 关键 SFI / 交易记录字段布局
 * 数据来源：NFC Wiki 智能卡手册 (wiki.nfc.im) 交通卡章节
 * 覆盖：T-Union 交通联合卡电子钱包、深圳通、北京市政一卡通(BMAC)
 * 首版先做"通用交易明细文件"解析，即多数卡种共用的 0x18 循环记录结构
 */
data class CardProfile(
    val name: String,
    val aidCandidates: List<String>,
    val infoSfi: Int,          // 公共应用基本信息文件 SFI
    val tradeSfi: Int,         // 电子钱包交易明细记录文件 SFI
    val tradeRecordLen: Int = 0x17,
    val stationSfi: Int? = null,  // 线路+站点信息文件 SFI (TU 卡用 0x1E)
    val cardType: String = "YCT", // "TU" = 交通联合, "YCT" = 羊城通/岭南通, "CU" = 数字城市一卡通
    val supported: Boolean = true // false = 暂不支持，仅识别 AID 后提示
)

object CardProfiles {

    val PSE_AID = "325041592E5359532E4444463031" // 2PAY.SYS.DDF01，PSE 目录

    val known = listOf(
        // 交通联合卡（TU）当前唯一支持的卡种，站点数据来自 assets/data/TU/<城市码>/*.csv
        CardProfile(
            name = "交通联合卡 (T-Union)",
            aidCandidates = listOf("A000000632010105", "A000000632010106"),
            infoSfi = 0x15,
            tradeSfi = 0x18,
            stationSfi = 0x1E,
            cardType = "TU",
            supported = true
        ),
        // 以下卡种先标注为暂不支持，后续版本逐个实现（数据已随 assets/data 一并拷贝）
        CardProfile(
            name = "深圳通 (Shenzhen Tong)",
            aidCandidates = listOf("5041592E535A54"),
            infoSfi = 0x15,
            tradeSfi = 0x18,
            supported = false
        ),
        CardProfile(
            name = "数字城市一卡通 (City Union)",
            aidCandidates = listOf("A00000000386980701"),
            infoSfi = 0x15,
            tradeSfi = 0x18,
            cardType = "CU",
            supported = false
        ),
        CardProfile(
            name = "岭南通/羊城通 (Lingnan Pass)",
            aidCandidates = listOf("5041592E41505059", "5041592E5449434C"),
            infoSfi = 0x15,
            tradeSfi = 0x18,
            stationSfi = 0x18,  // 羊城通从交易明细的终端号提取站点
            cardType = "YCT",
            supported = false
        )
    )
}

/** 一条交易记录的解析结果 */
data class TransactionRecord(
    val seq: Int,
    val amountYuan: Double,
    val typeHex: String,
    val transitType: String = "未知",
    val terminal: String,
    val stationName: String = "未知",
    val lineName: String = "",       // 线路名（如 "2号线" / "Line 2"），与 stationName 分开传递
    val lineId: Long? = null,        // 线路数据库 ID（页面间以 ID 传递，名称按语言即时解析）
    val stationId: Long? = null,     // 站点数据库 ID
    val cityName: String = "",      // 城市中文名（从 citylist.csv 获取）
    val date: String,
    val time: String,
    val balanceAfterFen: Long = 0   // 交易后余额（分），从 SFI 0x1E 匹配
)

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
