package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.example.nfctransit.BerTlv
import com.example.nfctransit.CardProfiles
import com.example.nfctransit.model.CanonicalTransaction
import com.example.nfctransit.model.MonthAccumSpec
import com.example.nfctransit.model.RawHexBlock
import com.example.nfctransit.model.TransitDirection
import com.example.nfctransit.data.db.ArchivedTransactionEntity
import java.security.MessageDigest
import java.util.Calendar

/**
 * 交易解码器（纯函数）：从原始 hex 记录解码交易。
 * 读卡时（decodeCard）与启动渲染（decodeArchive）共用同一解析路径，避免两套解析不一致。
 *
 * 解析逻辑迁移自 TransitCardReader：SFI 0x1E 建 终端→站点 映射表、0x18 主交易解析、
 * LNT 无年份字段的年份推断（统计月份锚点 + 记录连续性）、1E/18 按时间戳合并去重、
 * 充值/空槽过滤、城市码与站点名解析（TransitData）。
 */
object RecordDecoder {

    /** 读卡时带槽位的原始记录；recNo 是卡内物理循环槽位，LNT 年份推断按 recNo 顺序处理 */
    data class ZoneRecord(
        val sfi: Int,
        val recNo: Int,
        val protocol: String,  // "LNT"/"SZT"/"TU"/"" — 双协议卡区分钱包
        val hex: String,
        val logFormat: String? = null
    )

    /** EC recNo=0 retains GET DATA log format, rather than a transaction. */
    fun transactionRecords(cardType: String, records: List<RawRecord>): List<ZoneRecord> {
        val sfis = CardProfiles.known.firstOrNull { it.cardType == cardType }?.transactionSfis ?: setOf(0x18)
        val formats = records.filter { it.selectedAid.equals(CardProfiles.TU_EC_AID, true) && it.recNo == 0 }
            .associate { (it.selectedAid.uppercase() to it.sfi) to it.hex }
        return records.mapNotNull { raw ->
            if (raw.selectedAid.equals(CardProfiles.TU_EC_AID, true)) {
                if (raw.recNo == 0) return@mapNotNull null
                if (raw.sfi == 0x1E) return@mapNotNull ZoneRecord(raw.sfi, raw.recNo, "TU", raw.hex)
                val format = formats[raw.selectedAid.uppercase() to raw.sfi] ?: return@mapNotNull null
                ZoneRecord(raw.sfi, raw.recNo, "TU", raw.hex, format)
            } else if (raw.sfi in sfis && raw.sfi !in setOf(0x0B, 0x0C)) {
                ZoneRecord(raw.sfi, raw.recNo, raw.protocol, raw.hex)
            } else null
        }
    }

    /** 站点解析条目（站点/线路/方向/ID/命中设备码）。 */
    private data class StationRef(
        val station: String,
        val line: String,
        val transitType: String,
        val direction: TransitDirection? = null,
        /** 命中 reader_device 的原始 Type，用于与 LNT type/subtype 进行语义兼容判断。 */
        val mappingTransitType: String? = null,
        val lineColor: String? = null,
        val lineId: Long? = null,
        val stationId: Long? = null,
        val cityCode: String? = null,
        val deviceCode: String? = null,
        val spRule: String? = null
    )

    /** 设备映射与 LNT type/subtype 的类型兼容类别；仅闸机轨道交通有进出站方向。 */
    private enum class TransitCategory { GATED_RAIL, BUS, CONVENIENCE }

    /** LNT 0x18 原始交易类型覆盖；type/subtype 均按记录中的十六进制字节比较。 */
    private data class LntType(
        val transitType: String,
        val category: TransitCategory,
        val direction: TransitDirection? = null
    )

    private fun resolveLntType(typeByte: Int, subtype: Int?, mappingTransitType: String?): LntType? {
        return when {
            // YCT 09/17、09/31 可用于公交：命中公交映射就按公交解释，不标出站。
            // 未命中时保留原有的地铁出站兜底；其它已命中类型仍由设备映射优先决定。
            typeByte == 0x09 && (subtype == 0x31 || subtype == 0x17) ->
                if (mappingCategory(mappingTransitType) == TransitCategory.BUS) {
                    LntType("公交", TransitCategory.BUS)
                } else {
                    LntType("地铁", TransitCategory.GATED_RAIL, TransitDirection.EXIT)
                }
            typeByte == 0x09 && subtype == 0x11 ->
                LntType("地铁", TransitCategory.GATED_RAIL, TransitDirection.ENTRY)
            typeByte == 0x06 && subtype == 0x17 ->
                LntType("便利店", TransitCategory.CONVENIENCE)
            else -> null
        }
    }

    private fun mappingCategory(type: String?): TransitCategory? = when (type) {
        "地铁", "城际" -> TransitCategory.GATED_RAIL
        "公交", "BRT", "bus" -> TransitCategory.BUS
        "便利店" -> TransitCategory.CONVENIENCE
        else -> null
    }

    internal fun lntFareUsesSeconds(terminal: String, mappingTransitType: String?, lastByte: Int): Boolean =
        terminal.length > 4 && terminal[4] != '0' && mappingTransitType != "地铁" &&
            lastByte != 0x17 && lastByte != 0x31

    /** TU 1E 终端映射及旅程；余额只保留在旅程自身。 */
    private class TuMap(
        val stationMap: Map<String, StationRef>,
        val journey: List<CanonicalTransaction>,
        /** 卡所在城市码（取时间戳最新的一条 0x1E 记录 [32..34)）；TU 0x18 记录城市码无效（其 [10..12) 是终端号前缀） */
        val cardCityCode: String? = null
    )

    /**
     * 解码结果：
     * display = 合并/排序后用于展示的交易；
     * archive = 全部交易区原始记录（0x1E 旅程 + 0x18/附加区，含各自解析日期/余额），
     *           用于落库归档——0x1E 即使与 0x18 合并展示也必须归档，否则渲染时无法重建终端映射/城市码。
     */
    data class DecodeResult(
        val display: List<CanonicalTransaction>,
        val archive: List<CanonicalTransaction>
    )

    /**
     * 读卡时解码：从本次读到的原始记录生成交易（含 LNT 年份推断与余额匹配）。
     * @param statsMonth LNT 统计月份 YYYYMM（SFI 08 rec1），年份锚点
     */
    fun decodeCard(
        cardType: String,
        records: List<ZoneRecord>,
        statsMonth: Int?,
        currentYear: Int
    ): DecodeResult {
        return when (cardType) {
            "TU" -> {
                val tu = buildTuMap(records)
                val fare = parseFareRecords(cardType, records, "TU", tu, currentYear, null)
                val display = mergeForDisplay(mergeJourneyAndFare(tu.journey, fare)).sortedWith(
                    compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence }
                )
                DecodeResult(display, tu.journey + fare)
            }
            "YCT" -> {
                // 双协议卡：LNT 钱包 + TU 钱包（协议标签区分）
                val lntRecords = records.filter { it.protocol == "LNT" }
                val tuRecords = records.filter { it.protocol == "TU" }

                // LNT 交易（无年份，年份用统计月份/连续性推断）
                val lnt = if (lntRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, lntRecords, "LNT", TuMap(emptyMap(), emptyList()),
                    currentYear, statsMonth
                )

                // TU 钱包：1E 映射表 + 18 主交易 + 旅程合并
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, tuRecords, "TU", tu, currentYear, null
                )
                val tuMerged = mergeJourneyAndFare(tu.journey, tuFare)

                val display = mergeForDisplay(lnt + tuMerged).sortedWith(
                    compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence }
                )
                DecodeResult(display, lnt + tu.journey + tuFare)
            }
            "CU" -> {
                val cuRecords = records.filter { it.protocol == "CU" || it.protocol.isBlank() }
                val tuRecords = records.filter { it.protocol == "TU" }
                val cu = parseFareRecords(
                    cardType, cuRecords, "", TuMap(emptyMap(), emptyList()), currentYear, null
                )
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    "TU", tuRecords, "TU", tu, currentYear, null
                )
                val tuMerged = mergeJourneyAndFare(tu.journey, tuFare)
                val display = mergeForDisplay(cu + tuMerged).sortedWith(
                    compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence }
                )
                DecodeResult(display, cu + tu.journey + tuFare)
            }
            "SZT" -> {
                val hasSztProtocol = records.any { it.protocol == "SZT" }
                val sztRecords = records.filter {
                    it.protocol == "SZT" || (!hasSztProtocol && it.protocol.isBlank())
                }
                val tuRecords = records.filter { it.protocol == "TU" }
                val szt = parseFareRecords(
                    cardType, sztRecords, "SZT", TuMap(emptyMap(), emptyList()),
                    currentYear, null
                )
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, tuRecords, "TU", tu, currentYear, null
                )
                val tuMerged = mergeJourneyAndFare(tu.journey, tuFare)
                val display = mergeForDisplay(szt + tuMerged).sortedWith(
                    compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence }
                )
                DecodeResult(display, szt + tu.journey + tuFare)
            }
            else -> {
                // 通用（CU/TFT/SUXIN/SZTK）：18 + 附加区，按内容去重后按时间倒序
                val fare = parseFareRecords(cardType, records, "", TuMap(emptyMap(), emptyList()), currentYear, null)
                val display = mergeForDisplay(fare).sortedWith(
                    compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence }
                )
                DecodeResult(display, fare)
            }
        }
    }

    /**
     * 启动渲染：从交易归档重建交易。
     * resolved_date 保留 LNT 推断年份；余额只取原始记录中实际存在的字段，
     * 不沿用旧版本归档时填充的余额。其余字段从 hex 解析。
     */
    fun decodeArchive(cardType: String, rows: List<ArchivedTransactionEntity>): List<CanonicalTransaction> {
        if (rows.isEmpty()) return emptyList()
        val records = rows.map { ZoneRecord(it.sfi.toSfiInt(), 0, it.protocol, it.hex, it.logFormat) }
        val storedDate = rows.associate { it.contentHash to it.resolvedDate }      // 归档：contentHash → 日期

        val base = when (cardType) {
            "TU" -> {
                val tu = buildTuMap(records)
                val fare = parseFareRecords(cardType, records, "TU", tu, 0, null, storedDate)
                mergeJourneyAndFare(tu.journey, fare)
            }
            "YCT" -> {
                val lntRecords = records.filter { it.protocol == "LNT" }
                val tuRecords = records.filter { it.protocol == "TU" }
                val lnt = if (lntRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, lntRecords, "LNT", TuMap(emptyMap(), emptyList()), 0, null, storedDate
                )
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, tuRecords, "TU", tu, 0, null, storedDate
                )
                lnt + mergeJourneyAndFare(tu.journey, tuFare)
            }
            "CU" -> {
                val cuRecords = records.filter { it.protocol == "CU" || it.protocol.isBlank() }
                val tuRecords = records.filter { it.protocol == "TU" }
                val cu = parseFareRecords(
                    cardType, cuRecords, "", TuMap(emptyMap(), emptyList()), 0, null,
                    storedDate
                )
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    "TU", tuRecords, "TU", tu, 0, null, storedDate
                )
                cu + mergeJourneyAndFare(tu.journey, tuFare)
            }
            "SZT" -> {
                val hasSztProtocol = records.any { it.protocol == "SZT" }
                val sztRecords = records.filter {
                    it.protocol == "SZT" || (!hasSztProtocol && it.protocol.isBlank())
                }
                val tuRecords = records.filter { it.protocol == "TU" }
                val szt = parseFareRecords(
                    cardType, sztRecords, "SZT", TuMap(emptyMap(), emptyList()), 0, null,
                    storedDate
                )
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, tuRecords, "TU", tu, 0, null, storedDate
                )
                szt + mergeJourneyAndFare(tu.journey, tuFare)
            }
            else -> parseFareRecords(
                cardType, records, "", TuMap(emptyMap(), emptyList()), 0, null, storedDate
            )
        }
        return mergeForDisplay(base).sortedWith(compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence })
    }

    /** 多个 canonical 的协议并集（优先用 protocols 集合，空则回退单 protocol），去重保持顺序 */
    fun unionProtocols(vararg ts: CanonicalTransaction): Set<String> {
        val set = LinkedHashSet<String>()
        for (t in ts) {
            if (t.protocols.isNotEmpty()) set.addAll(t.protocols)
            else if (t.protocol.isNotBlank()) set.add(t.protocol)
        }
        return set
    }

    /**
     * 同一内容（content_hash）在不同协议/扇区的变体合并为一条展示，协议取并集：
     * 双协议卡同一条内容在 LNT 0x18 / TU 0x1E 等各存一行，渲染时合并显示。
     */
    fun mergeByIdentity(list: List<CanonicalTransaction>): List<CanonicalTransaction> {
        if (list.size <= 1) return list
        val byId = LinkedHashMap<String, MutableList<CanonicalTransaction>>()
        for (t in list) byId.getOrPut(t.identity) { mutableListOf() }.add(t)
        if (byId.size == list.size) return list
        return byId.values.map { group ->
            if (group.size == 1) group[0]
            else group.reduce(::mergeDisplayVariants)
        }
    }

    /** 展示层合并：同内容或跨应用写入的同一笔交易只保留一条。 */
    fun mergeForDisplay(list: List<CanonicalTransaction>): List<CanonicalTransaction> {
        if (list.size <= 1) return list
        val byKey = LinkedHashMap<DisplayKey, CanonicalTransaction>()
        for (transaction in mergeByIdentity(list)) {
            val exactKey = displayKey(transaction)
            val exact = byKey[exactKey]
            if (exact != null) {
                byKey[exactKey] = mergeDisplayVariants(exact, transaction)
                continue
            }

            byKey[exactKey] = transaction
        }
        return byKey.values.toList()
    }

    private fun displayKey(transaction: CanonicalTransaction) = DisplayKey(
        date = transaction.date,
        time = transaction.time,
        amountFen = transaction.amountFen,
        terminal = transaction.terminal,
        typeHex = transaction.typeHex
    )

    private fun mergeDisplayVariants(
        first: CanonicalTransaction,
        second: CanonicalTransaction
    ): CanonicalTransaction {
        val preferred = if (first.journeyHex == null && second.journeyHex != null) second else first
        val primaryBlocks = buildList {
            add(RawHexBlock(preferred.sfi, preferred.protocol, preferred.hex))
            preferred.journeyHex?.let { add(RawHexBlock(0x1E, "TU", it)) }
        }.toSet()
        val allBlocks = buildList {
            addAll(first.rawVariants)
            addAll(second.rawVariants)
            add(RawHexBlock(first.sfi, first.protocol, first.hex))
            first.journeyHex?.let { add(RawHexBlock(0x1E, "TU", it)) }
            add(RawHexBlock(second.sfi, second.protocol, second.hex))
            second.journeyHex?.let { add(RawHexBlock(0x1E, "TU", it)) }
        }
        val variants = allBlocks.distinct().filterNot { it in primaryBlocks }
        return preferred.copy(
            rawVariants = variants,
            protocols = unionProtocols(first, second)
        )
    }

    private data class DisplayKey(
        val date: String,
        val time: String,
        val amountFen: Long?,
        val terminal: String,
        val typeHex: String
    )

    internal fun tuDirectionForType(typeByte: Int, transitType: String? = null): TransitDirection? = when (typeByte) {
        0x01, 0x03 -> TransitDirection.ENTRY
        0x02, 0x04 -> TransitDirection.EXIT
        0x06 -> TransitDirection.ENTRY.takeIf { transitType == "地铁" }
        else -> null
    }

    /**
     * 从 SFI 0x1E 循环记录建立终端→站点映射表及旅程交易（进站/出站事件）。
     * 空槽（整条全 0）跳过。返回按物理 recNo 顺序处理后的结果。
     */
    private fun buildTuMap(records: List<ZoneRecord>): TuMap {
        val stationMap = mutableMapOf<String, StationRef>()
        val journey = mutableListOf<CanonicalTransaction>()
        var latestTs = ""
        var latestCity = ""
        for (rec in records.filter { it.sfi == 0x1E && it.logFormat == null }.distinctBy { it.hex }.sortedBy { it.recNo }) {
            val data = ApduUtil.hexToBytes(rec.hex)
            if (data.size < 22) continue
            if (data.all { it.toInt() == 0 }) continue  // 空槽

            val terminal = ApduUtil.bcdToString(data.copyOfRange(3, 9))
            val lineCode = ApduUtil.bcdToString(data.copyOfRange(10, 12))
            val stationCode = ApduUtil.bcdToString(data.copyOfRange(12, 14))
            val cityCode = if (data.size >= 34) ApduUtil.bcdToString(data.copyOfRange(32, 34)) else ""
            // 原始代码切片 [10..17)：line+station+city+类型等连续片段，直接交给匹配层做最长重叠匹配，
            // 避免硬拆 line/station 位宽差异（如 16180101602009 → bus 618 / metro 01001B00 → 坝头）。
            // 用 bytesToHex 而非 bcdToString：站点码可含 hex 字母（东莞 1号线 0x1F=东城南、0x1B=坝头），
            // bcdToString 会把 0x1F 半字节展开成 "115"（append(15)），破坏重叠匹配，hex 站全部退化到大类。
            val rawCode = if (data.size >= 17) ApduUtil.bytesToHex(data.copyOfRange(10, 17)) else ""

            // TU 1E：byte 0 是记录类型，byte 9 是交通 subtype。
            // subtype 01/02 先决定轨道/公交，再约束设备映射候选；未知 subtype 不限制匹配。
            val subtype = data[9].toInt() and 0xFF
            val expectedFamily = TransitData.tuTransitFamilyForSubtype(subtype)
            val entry = TransitData.resolveTuStation(
                cityCode, lineCode, stationCode, terminal, rawCode,
                expectedFamily = expectedFamily
            )
            val fallbackStation = when (expectedFamily) {
                TransitData.TuTransitFamily.RAIL -> "轨道交通"
                else -> "公共交通"
            }
            val fallbackTransitType = when (expectedFamily) {
                TransitData.TuTransitFamily.RAIL -> "地铁"
                TransitData.TuTransitFamily.BUS -> "公交"
                null -> "公交"
            }
            val mappedRef = entry
            // 方向判断使用数据库原始类型或 subtype 兜底类型，不受显示语言影响。
            val direction = tuDirectionForType(
                data[0].toInt() and 0xFF,
                mappedRef?.type ?: fallbackTransitType
            )
            val ref = StationRef(
                station = mappedRef?.station ?: fallbackStation,
                line = mappedRef?.line ?: "",
                transitType = mappedRef?.type ?: fallbackTransitType,
                direction = direction,
                mappingTransitType = mappedRef?.type,
                lineColor = mappedRef?.lineColor,
                lineId = mappedRef?.lineId,
                stationId = mappedRef?.stationId,
                cityCode = mappedRef?.cityCode,
                deviceCode = mappedRef?.code,
                spRule = mappedRef?.spRule
            )
            val balanceFen = if (data.size >= 25) ApduUtil.hexToLong(data.copyOfRange(21, 25)) else null
            val amountFen = if (data.size >= 21) ApduUtil.hexToLong(data.copyOfRange(17, 21)) else 0L
            val timestamp = if (data.size >= 32) ApduUtil.bcdToString(data.copyOfRange(25, 32)) else ""

            stationMap[terminal] = ref
            // 卡所在城市：取时间戳最新记录的城市码（TU 0x18 记录自身城市码无效）
            if (timestamp > latestTs) {
                latestTs = timestamp
                if (cityCode.isNotEmpty()) latestCity = cityCode
            }
            if (timestamp.length >= 14) {
                journey.add(
                    buildTransaction(
                        sfi = 0x1E, protocol = rec.protocol, hex = rec.hex,
                        sequence = 0, amountFen = amountFen, typeHex = ApduUtil.bytesToHex(byteArrayOf(data[0])),
                        terminal = terminal,
                        stationName = ref.station, direction = ref.direction,
                        lineName = ref.line, lineColor = ref.lineColor,
                        lineId = ref.lineId, stationId = ref.stationId,
                        transitType = ref.transitType,
                        cityCode = entry?.cityCode ?: if (cityCode.isNotEmpty()) cityCode else null,
                        rawCityCode = cityCode,
                        date = timestamp.substring(0, 8), time = timestamp.substring(8, 14),
                        balanceAfterFen = balanceFen,
                        deviceCode = ref.deviceCode,
                        spRule = ref.spRule
                    )
                )
            }
        }
        return TuMap(stationMap, journey, latestCity.ifEmpty { null })
    }

    /**
     * 解析 18（+附加区）主交易记录。
     * @param lntStatsMonth  LNT 统计月份锚点（年份推断起点）
     * @param storedDateByHash 归档：contentHash → 已解析日期（yyyyMMdd），覆盖 hex 内日期（decodeArchive 用）
     */
    private fun parseFareRecords(
        cardType: String,
        records: List<ZoneRecord>,
        protocol: String,
        tu: TuMap,
        currentYear: Int,
        lntStatsMonth: Int?,
        storedDateByHash: Map<String, String>? = null
    ): List<CanonicalTransaction> {
        val isLnt = protocol == "LNT"
        val hasSubtype18 = cardType == "YCT" && isLnt
        val today = todayDate()
        val todayMonth = today.substring(4, 6).toInt()
        var relYear: Int? = if (isLnt) lntStatsMonth?.div(100) ?: currentYear else null
        var lastMonth: Int? = if (isLnt) lntStatsMonth?.mod(100) ?: todayMonth else null
        // LNT content sequence counters are independent across transaction types; only recNo is chronological.
        val orderedRecords = records.sortedBy { it.recNo }
        val results = mutableListOf<CanonicalTransaction>()

        for (rec in orderedRecords) {
            if (rec.logFormat != null) {
                parseEcLog(rec, tu)?.let(results::add)
                continue
            }
            if (rec.sfi == 0x1E) continue  // 旅程记录由 buildTuMap 处理
            if (rec.sfi in setOf(0x0B, 0x0C)) continue
            val data = ApduUtil.hexToBytes(rec.hex)
            if (data.size < 0x17) continue
            if (data.all { it.toInt() == 0 }) continue  // 空槽

            val seq = ApduUtil.hexToLong(data.copyOfRange(0, 2)).toInt()
            val amountFen = ApduUtil.hexToLong(data.copyOfRange(if (rec.protocol == "TU" || protocol == "TU") 5 else 6, 9))
            val typeHex = ApduUtil.bytesToHex(byteArrayOf(data[9]))
            val typeByte = data[9].toInt() and 0xFF
            val terminal = ApduUtil.bcdToString(data.copyOfRange(10, 16))
            val posHex = ApduUtil.bytesToHex(data.copyOfRange(10, 16))
            val isSubtype18 = rec.sfi == 0x18 && hasSubtype18

            // CU 有完整日期，直接重读 hex，修复旧解码器错误推断年份留下的归档日期。
            // LNT 仍优先使用归档中保存的推断年份。
            val hash = contentHash(rec.hex)
            val date = storedDateByHash?.get(hash)?.takeUnless { cardType == "CU" } ?: run {
                if (isLnt || isSubtype18) {
                    val mmdd = ApduUtil.bcdToString(data.copyOfRange(18, 20))
                    val thisMonth = mmdd.take(2).toIntOrNull()
                    var year: String? = null
                    if (relYear != null && thisMonth != null && lastMonth != null) {
                        if (thisMonth > lastMonth) relYear = relYear!! - 1
                        year = relYear.toString()
                        lastMonth = thisMonth
                    }
                    val resolvedYear = year?.toIntOrNull() ?: currentYear
                    val boundedYear = boundYearNotAfterToday(resolvedYear, mmdd, today)
                    boundedYear.toString().padStart(4, '0') + mmdd
                } else {
                    ApduUtil.bcdToString(data.copyOfRange(16, 20))
                }
            }

            // 0x18 记录城市码：其 [10..12) 实为终端号前缀（如 4131…），对 TU 卡无效；
            // 站点解析兜底仍用它（与旧实现一致），但展示用 1E 卡所在城市码
            val cityCode18 = String.format("%04d", bcdNibble(data[11]) + bcdNibble(data[10]) * 100)
            val displayCityCode = if (protocol == "TU") (tu.cardCityCode ?: cityCode18) else cityCode18

            val posIsRecharge = posHex == "20151031095400" || posHex == "00000000000000"
            // 设备映射是站点、城市和交通类型的权威来源。LNT type/subtype 只能在
            // 未命中映射时兜底类型，或在与命中设备类型严格兼容时提供进出站方向。
            // 映射命中充值类设备（如深圳 APP 充值终端）也与充值一样没有站点/方向/城市；
            // 但充值终端同样办理退款且卡内无专用类型字节，因此不改写 typeHex——
            // 是否真正入账由展示层按原生 type "02" 判定（"+" 绿色并豁免消费统计，
            // 非 "02" 视为退款/扣款，显示 "-" 并计入消费）。
            val resolvedRef =
                if (posIsRecharge || typeHex == "02") null
                else resolveStation(cardType, cityCode18, posHex, terminal, tu)
            val isRecharge = resolvedRef == null ||
                resolvedRef.mappingTransitType?.contains("充值") == true
            val effectiveTypeHex = if (posIsRecharge) "02" else typeHex
            val ref = if (!isRecharge && resolvedRef != null) resolvedRef
                else StationRef("", "", "充值")
            val mappingMatched = !isRecharge && ref.deviceCode != null
            // LNT 外地消费恢复秒数；命中地铁设备（包括佛山）或末字节为 17/31 仍保留 subtype。
            // 使用映射原始类型判断，避免显示语言或 subtype 兜底影响字段含义。
            val lastByte = data[22].toInt() and 0xFF
            val lntSeconds = isLnt && isSubtype18 && lntFareUsesSeconds(terminal, ref.mappingTransitType, lastByte)
            val subtype = if (isSubtype18 && !lntSeconds) lastByte else null
            val time = if (isSubtype18 && !lntSeconds) {
                ApduUtil.bcdToString(data.copyOfRange(20, 22)) + "00"
            } else {
                ApduUtil.bcdToString(data.copyOfRange(20, 23))
            }
            val lntType = if (isLnt && isSubtype18) {
                resolveLntType(typeByte, subtype, ref.mappingTransitType.takeIf { mappingMatched })
            } else null
            val lntDirection = lntType?.direction
            val direction = when {
                isRecharge -> null
                mappingMatched -> lntDirection?.takeIf {
                    mappingCategory(ref.mappingTransitType) == lntType?.category
                }
                else -> lntDirection
            }
            val transitType = if (mappingMatched) ref.transitType else lntType?.transitType ?: ref.transitType

            // 站点命中后优先使用设备所属城市；未命中时再沿用记录/钱包城市码。
            val cityForTx = when {
                isRecharge -> null
                ref.cityCode != null -> ref.cityCode
                cardType == "YCT" && protocol == "LNT" && ref.deviceCode == null -> null
                else -> displayCityCode
            }

            // LNT 未命中设备映射时，18 的终端号前缀不是可靠城市码，city/rawCity 都必须保持为空。
            val rawCityCodeForTx = when {
                isRecharge -> null
                protocol == "LNT" && !mappingMatched -> null
                else -> cityCode18
            }

            // EP fare records contain no balance. Only a matching journey may supply it during merge.
            // Ignore old archive balance values too: earlier versions filled these from other events.
            val balanceAfterFen: Long? = null

            results.add(
                buildTransaction(
                    sfi = rec.sfi, protocol = rec.protocol.ifBlank { protocol }, hex = rec.hex,
                    sequence = seq, amountFen = amountFen, typeHex = effectiveTypeHex,
                    terminal = terminal,
                    stationName = ref.station, direction = direction,
                    lineName = ref.line, lineColor = ref.lineColor,
                    lineId = ref.lineId, stationId = ref.stationId,
                    transitType = transitType,
                    cityCode = cityForTx,
                    rawCityCode = rawCityCodeForTx,
                    date = date, time = time,
                    balanceAfterFen = balanceAfterFen,
                    deviceCode = ref.deviceCode,
                    spRule = ref.spRule
                )
            )
        }
        return results
    }

    private fun parseEcLog(rec: ZoneRecord, tu: TuMap): CanonicalTransaction? {
        val formatHex = rec.logFormat ?: return null
        val formatTags = BerTlv.values(ApduUtil.hexToBytes(formatHex))
        val format = formatTags[0x9F4F] ?: formatTags[0xDF4F] ?: return null
        val fields = BerTlv.dol(format) ?: return null
        val data = ApduUtil.hexToBytes(rec.hex)
        if (data.all { it.toInt() == 0 } || fields.sumOf { it.length } != data.size) return null
        val values = fields.associate { it.tag to data.copyOfRange(it.offset, it.offset + it.length) }
        val shortDate = values[0x9A]?.takeIf { it.size == 3 }?.let(BerTlv::bcd) ?: return null
        val date = "20$shortDate"
        val time = values[0x9F21]?.takeIf { it.size == 3 }?.let(BerTlv::bcd) ?: return null
        if (runCatching { java.time.LocalDate.of(date.take(4).toInt(), date.substring(4, 6).toInt(), date.takeLast(2).toInt()) }.isFailure) return null
        if (time.take(2).toInt() > 23 || time.substring(2, 4).toInt() > 59 || time.takeLast(2).toInt() > 59) return null
        val amount = values[0x9F02]?.takeIf { it.size == 6 }?.let(BerTlv::bcd)?.toLongOrNull() ?: return null
        // Amounts are displayable in yuan only for CNY. Unrecognized currencies remain in raw data.
        if (values[0x5F2A]?.let(ApduUtil::bytesToHex)?.let { it != "0156" } == true) return null
        val rawTerminal = values[0x9F1C]
        val terminal = rawTerminal?.let { bytes ->
            val ascii = bytes.toString(Charsets.US_ASCII).trimEnd('\u0000', ' ')
            if (ascii.isNotEmpty() && ascii.all { it.code in 32..126 }) ascii else BerTlv.bcd(bytes).orEmpty()
        }.orEmpty()
        val recharge = formatTags.containsKey(0xDF4F) || values[0x9C]?.singleOrNull()?.toInt() == 0x60
        val ref = if (recharge || terminal.isEmpty()) UNMATCHED_STATION
            else resolveStation("TU", "", terminal, terminal, tu)
        val balance = values[0x9F79]?.takeIf { it.size == 6 }?.let(BerTlv::bcd)?.toLongOrNull()
        return buildTransaction(
            rec.sfi, "TU", rec.hex, values[0x9F36]?.let(ApduUtil::hexToLong)?.toInt() ?: 0,
            amount, if (recharge) "02" else "06", terminal,
            if (recharge) "" else ref.station, null, ref.line, ref.lineColor, ref.lineId, ref.stationId,
            if (recharge) "充值" else ref.transitType, if (recharge) null else ref.cityCode,
            date = date, time = time, balanceAfterFen = balance, deviceCode = ref.deviceCode, spRule = ref.spRule
        ).copy(identity = recordContentHash(rec), logFormat = formatHex)
    }

    fun recordContentHash(record: ZoneRecord): String = contentHash(record.hex + record.logFormat.orEmpty())

    private fun buildTransaction(
        sfi: Int,
        protocol: String,
        hex: String,
        sequence: Int,
        amountFen: Long,
        typeHex: String,
        terminal: String,
        stationName: String,
        direction: TransitDirection?,
        lineName: String,
        lineColor: String?,
        lineId: Long?,
        stationId: Long?,
        transitType: String,
        cityCode: String?,
        rawCityCode: String? = null,
        date: String,
        time: String,
        balanceAfterFen: Long?,
        deviceCode: String? = null,
        spRule: String? = null
    ): CanonicalTransaction {
        return CanonicalTransaction(
            identity = contentHash(hex),
            sequence = sequence,
            amountFen = amountFen,
            balanceAfterFen = balanceAfterFen,
            typeHex = typeHex,
            terminal = terminal,
            cityCode = cityCode,
            rawCityCode = rawCityCode,
            lineId = lineId,
            stationId = stationId,
            stationName = stationName,
            direction = direction,
            lineName = lineName,
            lineColor = lineColor,
            transitType = transitType,
            date = date,
            time = time,
            sfi = sfi,
            protocol = protocol,
            hex = hex,
            deviceCode = deviceCode,
            spRule = spRule
        )
    }

    /**
     * TU 1E（旅程）与主交易按完整时间戳一对一合并；同终端候选优先。
     * 两区可能记录不同终端编号，因此终端号不是合并的必要条件。
     *  - 同一时间戳两条都有 → 取主交易金额/类型/序号 + 1E 的站点/线路/方向/余额
     *  - 只有 18 → 原样
     *  - 只有 1E → 保留为旅程交易（金额可能为 0）
     */
    internal fun mergeJourneyAndFare(
        journey: List<CanonicalTransaction>,
        fare: List<CanonicalTransaction>
    ): List<CanonicalTransaction> {
        if (journey.isEmpty()) return fare
        val usedJourney = mutableSetOf<Int>()
        val out = mutableListOf<CanonicalTransaction>()
        for (f in fare) {
            val candidates = journey.indices.filter { index ->
                index !in usedJourney && journey[index].date == f.date && journey[index].time == f.time
            }
            val matchedIndex = candidates.firstOrNull { f.terminal.isNotEmpty() && journey[it].terminal == f.terminal }
                ?: candidates.firstOrNull()
            if (matchedIndex == null) {
                out.add(f)
                continue
            }
            usedJourney.add(matchedIndex)
            val j = journey[matchedIndex]
            out.add(f.copy(
                stationName = j.stationName, direction = j.direction, lineName = j.lineName,
                lineColor = j.lineColor, lineId = j.lineId, stationId = j.stationId,
                transitType = j.transitType, cityCode = j.cityCode ?: f.cityCode,
                rawCityCode = j.rawCityCode ?: f.rawCityCode,
                balanceAfterFen = j.balanceAfterFen ?: f.balanceAfterFen,
                journeyHex = j.hex, deviceCode = j.deviceCode, spRule = j.spRule
            ))
        }
        journey.forEachIndexed { index, j -> if (index !in usedJourney) out.add(j) }
        return out
    }

    /** 解析站点名称与交通类型（TU 用 1E 映射表，其余按 城市码+位置码/终端号 查 DB） */
    private fun resolveStation(
        cardType: String,
        cityCode: String,
        posHex: String,
        terminal: String,
        tu: TuMap
    ): StationRef {
        if (cardType == "TU") {
            tu.stationMap[terminal]?.let { return it }
            for ((key, value) in tu.stationMap) {
                if (terminal.endsWith(key) || key.endsWith(terminal)) return value
            }
            val fallback = TransitData.resolveByStandard("TU", cityCode, posHex, terminal)
            if (fallback != null) return fallback.toStationRef()
            return UNMATCHED_STATION
        }
        val entry = TransitData.resolveByStandard(cardType, cityCode, posHex, terminal)
        if (entry != null) return entry.toStationRef()
        return UNMATCHED_STATION
    }

    /** 映射表与其他规则均未命中：统一按公共交通兜底（站名位与类型一致） */
    private val UNMATCHED_STATION = StationRef("公共交通", "", "公共交通")

    private fun TransitData.StationEntry.toStationRef(): StationRef {
        return StationRef(
            station = station,
            line = line,
            transitType = type,
            mappingTransitType = type,
            lineColor = lineColor,
            lineId = lineId,
            stationId = stationId,
            cityCode = cityCode,
            deviceCode = code,
            spRule = spRule
        )
    }

    private fun todayDate(): String {
        val calendar = Calendar.getInstance()
        return String.format(
            "%04d%02d%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun boundYearNotAfterToday(year: Int, mmdd: String, today: String): Int {
        var boundedYear = year
        while (boundedYear > 0 && "${boundedYear.toString().padStart(4, '0')}$mmdd" > today) {
            boundedYear--
        }
        return boundedYear
    }

    /** BCD 半字节 → 十进制数（0x12 → 12） */
    private fun bcdNibble(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return (v shr 4) * 10 + (v and 0x0F)
    }

    /** 内容哈希（SHA-256 hex），用于归档去重键 */
    fun contentHash(hex: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(ApduUtil.hexToBytes(hex))
            .joinToString("") { String.format("%02x", it) }
    }

    /**
     * 通用月累乘统计解析：按 [MonthAccumSpec] 配置的读卡位置与字段区域，从卡原始记录提取
     * (月份 YYYYMM, 当月累计金额分)。城市差异全部由配置表达（见 DiscountRegistry），
     * 本函数不含任何城市/卡型分支：
     *  - Fixed 定长记录式：候选 Source 按 recNo 精确匹配、协议标签过滤，固定偏移直取
     *  - Anchored 锚点扫描式：扫描匹配 Source 的全部记录字节流找锚点条目，取刷新日期最新
     */
    fun parseMonthAccumulation(records: List<RawRecord>, spec: MonthAccumSpec): MonthlyAccumulation? {
        return when (spec) {
            is MonthAccumSpec.Fixed -> parseFixedAccumulation(records, spec)
            is MonthAccumSpec.Anchored -> parseAnchoredAccumulation(records, spec)
        }
    }

    /** 从候选位置读取定长统计记录；BCD 月份非法时 month=null（金额仍返回，展示层判月丢弃） */
    private fun parseFixedAccumulation(records: List<RawRecord>, spec: MonthAccumSpec.Fixed): MonthlyAccumulation? {
        for (source in spec.sources) {
            val rec = records.firstOrNull { raw ->
                raw.sfi == source.sfi &&
                    raw.recNo == source.recNo &&
                    (source.protocol == null || source.protocol.equals(raw.protocol, ignoreCase = true))
            } ?: continue
            val data = ApduUtil.hexToBytes(rec.hex)
            if (data.size < spec.minDataSize) continue
            val year = 2000 + bcdNibble(data[spec.yearBcdOffset])
            val month = bcdNibble(data[spec.monthBcdOffset])
            val totalFen = ApduUtil.hexToLong(
                data.copyOfRange(spec.totalFenBeOffset, spec.totalFenBeOffset + 2)
            )
            return MonthlyAccumulation(
                month = if (month in 1..12) year * 100 + month else null,
                totalFen = totalFen
            )
        }
        return null
    }

    /** 扫描候选位置的全部记录找锚点条目；多条命中（循环文件跨月残留）取刷新日期最新 */
    private fun parseAnchoredAccumulation(records: List<RawRecord>, spec: MonthAccumSpec.Anchored): MonthlyAccumulation? {
        var best: AnchorHit? = null
        for (source in spec.sources) {
            val matched = records.filter {
                it.sfi == source.sfi &&
                    (source.protocol == null || source.protocol.equals(it.protocol, ignoreCase = true))
            }
            for (rec in matched) {
                val data = ApduUtil.hexToBytes(rec.hex)
                // 锚点条目最短长度 = dateBcdOffset + 4B 日期；金额按 offset 独立校验边界
                for (f in 0..data.size - (spec.dateBcdOffset + 4)) {
                    if (data[f] != spec.anchorByte.toByte()) continue
                    val hit = anchoredAccumulationAt(data, f, spec) ?: continue
                    if (best == null || hit.dateYmd > best!!.dateYmd) best = hit
                }
            }
        }
        return best?.let { MonthlyAccumulation(it.month, it.fen) }
    }

    /** 锚点式解析的中间结果 */
    private data class AnchorHit(val dateYmd: String, val month: Int, val fen: Long)

    /** 校验 data[f] 起是否为锚点式月累乘条目并解析；不匹配返回 null。日期区域 = 4B BCD YYYYMMDD，金额 = 大端 u16 分 */
    private fun anchoredAccumulationAt(
        data: ByteArray,
        f: Int,
        spec: MonthAccumSpec.Anchored
    ): AnchorHit? {
        val d0 = f + spec.dateBcdOffset
        if (d0 < 0 || d0 + 4 > data.size) return null
        for (i in d0 until d0 + 4) if (!isBcdByte(data[i])) return null
        if (data[d0].toInt() != 0x20) return null  // 年份固定 20xx
        val year = bcdNibble(data[d0]) * 100 + bcdNibble(data[d0 + 1])
        val month = bcdNibble(data[d0 + 2])
        val day = bcdNibble(data[d0 + 3])
        if (month !in 1..12 || day !in 1..31) return null
        val a0 = f + spec.amountBeOffset
        if (a0 < 0 || a0 + 2 > data.size) return null
        val amountFen = ApduUtil.hexToLong(data.copyOfRange(a0, a0 + 2))
        // 金额合理性上限 ¥50,000/月，过滤纯巧合数据
        if (amountFen !in 1..5_000_000L) return null
        return AnchorHit(
            String.format("%04d%02d%02d", year, month, day),
            year * 100 + month,
            amountFen
        )
    }

    /** 字节两个 nibble 均为 BCD 数字（≤9），排除 A-F 的伪 BCD */
    private fun isBcdByte(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return (v shr 4) <= 9 && (v and 0x0F) <= 9
    }
}

/**
 * 卡内"自然月累乘金额"统计的解析结果（两种读取配置共用，见 model/MonthAccumSpec）：
 * month = 统计/刷新月份 YYYYMM，非法时为 null（展示层按当前月份判效后丢弃）；
 * totalFen = 当月累计消费金额（分）。
 */
data class MonthlyAccumulation(
    val month: Int?,    // YYYYMM；BCD 月份非法 / 锚点条目缺失时 null
    val totalFen: Long  // 当月累计消费金额（分）
)
