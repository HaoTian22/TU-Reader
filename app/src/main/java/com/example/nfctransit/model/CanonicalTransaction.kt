package com.example.nfctransit.model

/**
 * 内部标准交易模型：位于「协议解析（RecordDecoder）」与「UI 格式化（TransactionMapper）」之间。
 * 站点映射、文案、图标改动时只需重新映射为 UiTransaction，不影响 NFC 原始数据。
 * 金额/余额统一为 Long 分，UI 层再转元。
 */
/** 组成一笔交易的一条原始记录及其来源：读出它的应用 AID，EC 日志另带该文件的 DOL。 */
data class RawHexBlock(
    val sfi: Int,
    val protocol: String,
    val hex: String,
    /** 读出该记录的应用 AID；无读卡来源为 null */
    val selectedAid: String? = null,
    /** EC 日志记录的 DOL（按字段解析需要）；其它记录为 null */
    val logFormat: String? = null
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
    /** 展示用协议并集（双协议卡同一笔在 LNT+TU 钱包都有时）；仅内存，不落库 */
    val protocols: Set<String> = emptySet(),
    /**
     * 组成该交易的全部原始记录，各自带来源：首条为解析出本交易的记录，
     * 与之合并的 TU 1E 旅程、其它应用写入的同一笔交易依次附加。仅内存，不落库。
     */
    val sources: List<RawHexBlock> = emptyList()
) {
    /** 与本交易合并的 TU 1E 旅程记录（本交易自身为 1E 时为 null） */
    val journeyHex: String? get() = sources.firstOrNull { it.sfi == 0x1E && it.hex != hex }?.hex
}
