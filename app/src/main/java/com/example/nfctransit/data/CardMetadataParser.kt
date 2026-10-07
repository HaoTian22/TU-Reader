package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.example.nfctransit.BerTlv
import com.example.nfctransit.CardProfiles

data class ApplicationMetadata(
    val issuer: String? = null,
    val cityCode: String? = null,
    val issueDate: String? = null,
    val validUntil: String? = null,
    val interoperabilityCode: String? = null,
    val interoperabilityEnabled: Boolean? = null,
    val cardKind: Int? = null,
    val applicationVersion: String? = null,
    val countryCode: String? = null,
    val provinceCode: String? = null,
    val industryCode: String? = null,
    val algorithmSupport: String? = null
)

object CardMetadataParser {
    /** @param allowBlankFallback 无精确协议记录时使用空协议记录（无 selected_aid 的旧槽位）；双协议卡的第二应用不回退。 */
    fun parse(
        protocol: String, records: List<RawRecord>, ecSelectHex: String? = null,
        allowBlankFallback: Boolean = true
    ): ApplicationMetadata {
        fun bytes(sfi: Int, aid: String? = null): ByteArray? {
            val exact = records.filter {
                it.sfi == sfi && it.protocol == protocol && (aid == null || it.selectedAid.equals(aid, true))
            }
            val candidates = if (exact.isEmpty() && aid == null && allowBlankFallback && protocol.isNotEmpty()) {
                records.filter { it.sfi == sfi && it.protocol.isBlank() }
            } else exact
            return candidates.maxByOrNull { it.hex.length }?.hex
                ?.let { runCatching { ApduUtil.hexToBytes(it) }.getOrNull() }
        }
        fun hex(data: ByteArray?, start: Int, length: Int): String? = data
            ?.takeIf { start + length <= it.size }?.copyOfRange(start, start + length)?.let(ApduUtil::bytesToHex)
        fun code(data: ByteArray?, start: Int, length: Int): String? = hex(data, start, length)
            ?.takeIf { it.any { ch -> ch != '0' && ch != 'F' } }
        fun byte(data: ByteArray?, offset: Int): Int? = data?.getOrNull(offset)?.toInt()?.and(255)
        val info = bytes(0x15)
        if (protocol == "CU") return ApplicationMetadata(
            issuer = code(info, 0, 2), cityCode = code(info, 2, 2),
            issueDate = date(info, 20), validUntil = date(info, 24),
            interoperabilityCode = hex(info, 10, 2), interoperabilityEnabled = byte(info, 8)?.let { it != 0 },
            applicationVersion = hex(info, 9, 1), industryCode = hex(info, 5, 1), algorithmSupport = hex(info, 4, 1)
        )
        if (protocol != "TU") return ApplicationMetadata(
            issuer = if (protocol == "LNT") code(info, 48, 4) else null,
            issueDate = date(info, if (protocol == "LNT") 23 else 20),
            validUntil = date(info, if (protocol == "LNT") 27 else 24)
        )
        val epInfo = bytes(0x15, CardProfiles.TU_EP_AID)
            ?: info?.takeIf { records.none { it.sfi == 0x15 && it.selectedAid.equals(CardProfiles.TU_EC_AID, true) } }
        val management = bytes(0x17, CardProfiles.TU_EP_AID)
        val ecTags = linkedMapOf<Int, ByteArray>()
        ecSelectHex?.let { runCatching { BerTlv.values(ApduUtil.hexToBytes(it)) }.getOrNull() }
            ?.let(ecTags::putAll)
        records.filter { it.selectedAid.equals(CardProfiles.TU_EC_AID, true) && it.sfi in setOf(1, 2, 3, 4, 8) }
            .forEach { raw -> runCatching { BerTlv.values(ApduUtil.hexToBytes(raw.hex)) }.getOrNull()
                ?.forEach { (tag, value) -> ecTags.putIfAbsent(tag, value) } }
        val df11 = ecTags[0xDF11]?.takeIf { it.size == 32 }
        val interoperability = hex(management, 8, 2)
            ?: ecTags[0xDF22]?.let(ApduUtil::bytesToHex) ?: hex(df11, 29, 2)
        fun ecDate(tag: Int, managementTag: Int): String? = ecTags[managementTag]?.let { date(it, 0) }
            ?: ecTags[tag]?.takeIf { it.size == 3 }?.let { date(byteArrayOf(0x20) + it, 0) }
        return ApplicationMetadata(
            issuer = code(epInfo, 0, 8) ?: code(df11, 0, 8),
            cityCode = code(management, 6, 2) ?: code(df11, 27, 2),
            issueDate = date(epInfo, 20) ?: ecDate(0x5F25, 0xDF25),
            validUntil = date(epInfo, 24) ?: ecDate(0x5F24, 0xDF24),
            interoperabilityCode = interoperability,
            interoperabilityEnabled = interoperability?.let { it != "0000" },
            cardKind = byte(management, 10) ?: ecTags[0xDF23]?.singleOrNull()?.toInt()?.and(255) ?: byte(df11, 19),
            applicationVersion = hex(epInfo, 9, 1),
            countryCode = hex(management, 0, 4) ?: hex(df11, 21, 4),
            provinceCode = hex(management, 4, 2) ?: hex(df11, 25, 2)
        )
    }

    fun date(data: ByteArray?, offset: Int): String? {
        if (data == null || offset + 4 > data.size) return null
        val value = BerTlv.bcd(data.copyOfRange(offset, offset + 4)) ?: return null
        val parsed = runCatching { java.time.LocalDate.of(value.take(4).toInt(), value.substring(4, 6).toInt(), value.takeLast(2).toInt()) }.getOrNull()
            ?: return null
        return parsed.takeIf { it.year in 1900..2200 }?.toString()
    }
}
