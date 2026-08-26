package com.example.nfctransit.model

/**
 * 内部标准交易模型：位于「协议解析（RecordDecoder）」与「UI 格式化（TransactionMapper）」之间。
 * 站点映射、文案、图标改动时只需重新映射为 UiTransaction，不影响 NFC 原始数据。
 * 金额/余额统一为 Long 分，UI 层再转元。
 */
data class RawHexBlock(
    val sfi: Int,
    val protocol: String,
    val hex: String
)

data class CanonicalTransaction(
    /** 内容去重键 = content_hash（SHA-256(hex)），不含 rec_no（rec_no 只是卡内循环槽位） */
    val identity: String,
    val sequence: Int?,
    val amountFen: Long?,
    val balanceAfterFen: Long?,
    val typeHex: String,
    val terminal: String,
    val cityCode: String? = null,
    /** 原始记录中的设备/卡城市码，不能被跨城站点匹配结果覆盖。 */
    val rawCityCode: String? = null,
    val lineId: Long? = null,
    val stationId: Long? = null,
    val stationName: String = "",
    /** 独立的进出站方向；不再编码到站名。 */
    val direction: TransitDirection? = null,
    val lineName: String = "",
    val lineColor: String? = null,
    val transitType: String = "未知",
    val date: String,   // "yyyyMMdd"
    val time: String,   // "HHmmss"
    val sfi: Int,
    val protocol: String,  // "LNT"/"TU"/""
    val hex: String,
    /** 命中的 device_code（站名解析匹配到的数据字符串，如 581000140019）；仅内存，不落库 */
    val deviceCode: String? = null,
    /** 特殊匹配规则标记（广佛跨城/深圳终端号派生，如 "(SP Rule: Guangzhou/Foshan)"）；详情页 Match 行展示；仅内存，不落库 */
    val spRule: String? = null,
    /** 1E 旅程记录对应的原始 hex（TU 卡 1E+18 合并展示时携带，详情页原始数据显示两份）；仅内存，不落库 */
    val journeyHex: String? = null,
    /** 合并展示交易的其他原始记录（如跨应用的另一份 0x18）；仅内存，不落库 */
    val rawVariants: List<RawHexBlock> = emptyList(),
    /** 展示用协议并集（双协议卡同一笔在 LNT+TU 钱包都有时）；仅内存，不落库 */
    val protocols: Set<String> = emptySet()
)
