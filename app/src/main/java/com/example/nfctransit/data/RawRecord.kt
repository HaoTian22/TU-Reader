package com.example.nfctransit.data

/** 一条原始 SFI 记录（持久化用，槽位定位 + 内容去重） */
data class RawRecord(
    val sfi: Int,            // 来源区（0x18 / 0x1E / …）
    val recNo: Int,          // 记录号（只是卡内循环槽位，不作交易身份）
    val protocol: String = "",  // "LNT"/"TU"/"" — 双协议卡区分钱包
    val hex: String          // 原始字节 hex
) {
    /** 槽位身份：同 SFI 同记录号同内容视为同一槽位 */
    val identity: String get() = "$sfi:$recNo:$hex"
}
