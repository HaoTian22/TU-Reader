package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.example.nfctransit.model.CanonicalTransaction
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

    /** 读卡时带槽位的原始记录；recNo 是物理槽位，LNT 年份推断按内容开头的 Record No. 排序 */
    data class ZoneRecord(
        val sfi: Int,
        val recNo: Int,
        val protocol: String,  // "LNT"/"SZT"/"TU"/"" — 双协议卡区分钱包
        val hex: String
    )

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
        val deviceLocation: String? = null,
        val deviceCode: String? = null,
        val spRule: String? = null
    )

    /** 能由 LNT type/subtype 可靠判定进出站的闸机轨道交通类别。 */
    private enum class TransitCategory { GATED_RAIL, CONVENIENCE }

    /** LNT 0x18 原始交易类型覆盖；type/subtype 均按记录中的十六进制字节比较。 */
    private data class LntType(
        val transitType: String,
        val category: TransitCategory,
        val direction: TransitDirection? = null
    )

    private fun resolveLntType(typeByte: Int, subtype: Int?): LntType? {
        return when {
            typeByte == 0x09 && (subtype == 0x31 || subtype == 0x17) ->
                LntType("地铁", TransitCategory.GATED_RAIL, TransitDirection.EXIT)
            typeByte == 0x09 && subtype == 0x11 ->
                LntType("地铁", TransitCategory.GATED_RAIL, TransitDirection.ENTRY)
            typeByte == 0x06 && subtype == 0x17 ->
                LntType("便利店", TransitCategory.CONVENIENCE)
            else -> null
        }
    }

    private fun mappingCategory(type: String?): TransitCategory? = when (type) {
        "地铁", "城际" -> TransitCategory.GATED_RAIL
        "便利店" -> TransitCategory.CONVENIENCE
        else -> null
    }

    /** TU 1E 建表结果；balanceMap 值为 null = 该记录无余额数据 */
    private class TuMap(
        val stationMap: Map<String, StationRef>,
        val balanceMap: Map<String, Long?>,
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
                val display = mergeJourneyAndFare(tu.journey, fare).sortedWith(
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
                    cardType, lntRecords, "LNT", TuMap(emptyMap(), emptyMap(), emptyList()),
                    currentYear, statsMonth
                )

                // TU 钱包：1E 映射表 + 18 主交易 + 旅程合并
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, tuRecords, "TU", tu, currentYear, null
                )
                val tuMerged = mergeJourneyAndFare(tu.journey, tuFare)

                // 统计月份缺失时，用 TU 交易锚点兜底 LNT 年份（mmdd 对齐，否则出现最多的年份）
                val finalLnt = if (statsMonth == null && tuMerged.isNotEmpty() && lnt.isNotEmpty()) {
                    val anchor = tuMerged.mapNotNull { t ->
                        if (t.date.length >= 8) t.date.substring(4, 8) to t.date.take(4) else null
                    }.toMap()
                    val fallback = tuMerged.mapNotNull { t ->
                        if (t.date.length >= 8) t.date.take(4) else null
                    }.groupBy { it }.maxByOrNull { it.value.size }?.key
                    if (anchor.isNotEmpty() || fallback != null) {
                        lnt.map { t ->
                            val mmdd = t.date.takeLast(4)
                            val y = anchor[mmdd] ?: fallback
                            if (y != null && y != t.date.take(4)) t.copy(date = y + mmdd) else t
                        }
                    } else lnt
                } else lnt

                val display = (finalLnt + tuMerged).sortedWith(
                    compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence }
                )
                DecodeResult(display, finalLnt + tu.journey + tuFare)
            }
            "CU" -> {
                val cuRecords = records.filter { it.protocol == "CU" || it.protocol.isBlank() }
                val tuRecords = records.filter { it.protocol == "TU" }
                val cu = parseFareRecords(
                    cardType, cuRecords, "", TuMap(emptyMap(), emptyMap(), emptyList()), currentYear, null
                )
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    "TU", tuRecords, "TU", tu, currentYear, null
                )
                val tuMerged = mergeJourneyAndFare(tu.journey, tuFare)
                val display = (cu + tuMerged).sortedWith(
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
                    cardType, sztRecords, "SZT", TuMap(emptyMap(), emptyMap(), emptyList()),
                    currentYear, null
                )
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, tuRecords, "TU", tu, currentYear, null
                )
                val tuMerged = mergeJourneyAndFare(tu.journey, tuFare)
                val display = (szt + tuMerged).sortedWith(
                    compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence }
                )
                DecodeResult(display, szt + tu.journey + tuFare)
            }
            else -> {
                // 通用（CU/TFT/SUXIN/SZTK）：18 + 附加区，按内容去重后按时间倒序
                val fare = parseFareRecords(cardType, records, "", TuMap(emptyMap(), emptyMap(), emptyList()), currentYear, null)
                val display = fare.distinctBy { "${it.date}|${it.time}|${it.terminal}|${it.amountFen}|${it.typeHex}" }
                    .sortedWith(compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence })
                DecodeResult(display, fare)
            }
        }
    }

    /**
     * 启动渲染：从交易归档重建交易。
     * resolved_date（含 LNT 推断年份）与 balance_after_fen 随行存储，此处直接用；
     * 其余字段（金额/类型/终端/城市/站点）从 hex 解析。
     */
    fun decodeArchive(cardType: String, rows: List<ArchivedTransactionEntity>): List<CanonicalTransaction> {
        if (rows.isEmpty()) return emptyList()
        val records = rows.map { ZoneRecord(it.sfi.toSfiInt(), 0, it.protocol, it.hex) }
        val storedDate = rows.associate { it.contentHash to it.resolvedDate }      // 归档：contentHash → 日期
        val storedBalance = rows.associate { it.contentHash to it.balanceAfterFen }  // 归档：contentHash → 余额（null = 该记录无余额数据）

        val base = when (cardType) {
            "TU" -> {
                val tu = buildTuMap(records)
                val fare = parseFareRecords(cardType, records, "TU", tu, 0, null, storedDate, storedBalance)
                mergeJourneyAndFare(tu.journey, fare)
            }
            "YCT" -> {
                val lntRecords = records.filter { it.protocol == "LNT" }
                val tuRecords = records.filter { it.protocol == "TU" }
                val lnt = if (lntRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, lntRecords, "LNT", TuMap(emptyMap(), emptyMap(), emptyList()), 0, null, storedDate, storedBalance
                )
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, tuRecords, "TU", tu, 0, null, storedDate, storedBalance
                )
                lnt + mergeJourneyAndFare(tu.journey, tuFare)
            }
            "CU" -> {
                val cuRecords = records.filter { it.protocol == "CU" || it.protocol.isBlank() }
                val tuRecords = records.filter { it.protocol == "TU" }
                val cu = parseFareRecords(
                    cardType, cuRecords, "", TuMap(emptyMap(), emptyMap(), emptyList()), 0, null,
                    storedDate, storedBalance
                )
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    "TU", tuRecords, "TU", tu, 0, null, storedDate, storedBalance
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
                    cardType, sztRecords, "SZT", TuMap(emptyMap(), emptyMap(), emptyList()), 0, null,
                    storedDate, storedBalance
                )
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, tuRecords, "TU", tu, 0, null, storedDate, storedBalance
                )
                szt + mergeJourneyAndFare(tu.journey, tuFare)
            }
            else -> parseFareRecords(
                cardType, records, "", TuMap(emptyMap(), emptyMap(), emptyList()), 0, null, storedDate, storedBalance
            ).distinctBy { "${it.date}|${it.time}|${it.terminal}|${it.amountFen}|${it.typeHex}" }
        }
        return mergeByIdentity(base).sortedWith(compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence })
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
            else group[0].copy(protocols = unionProtocols(*group.toTypedArray()))
        }
    }

    /**
     * 从 SFI 0x1E 循环记录建立 终端→站点 映射表 + 余额映射表 + 旅程交易（进站/出站事件）。
     * 空槽（整条全 0）跳过。返回按记录号排序处理后的结果。
     */
    private fun buildTuMap(records: List<ZoneRecord>): TuMap {
        val stationMap = mutableMapOf<String, StationRef>()
        val balanceMap = mutableMapOf<String, Long?>()
        val journey = mutableListOf<CanonicalTransaction>()
        var latestTs = ""
        var latestCity = ""
        for (rec in records.filter { it.sfi == 0x1E }.sortedBy { it.recNo }) {
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

            val entry = TransitData.resolveTuStation(cityCode, lineCode, stationCode, terminal, rawCode)
            val direction = when {
                data[0] == 0x03.toByte() -> TransitDirection.ENTRY
                data[0] == 0x04.toByte() -> TransitDirection.EXIT
                else -> null
            }
            // 0x1E 类型字节 data[0]：0x06 = 公交。公交行程解析到地铁站或无站可解时一律回退公交，
            // 否则同时间戳 0x18 的地铁站会经 mergeJourneyAndFare 覆盖显示为地铁
            val isBusType = data[0] == 0x06.toByte()
            val busRef = if (isBusType && (entry == null || entry.type == "地铁")) null else entry
            val ref = StationRef(
                station = busRef?.station ?: "公共交通",
                line = busRef?.line ?: "",
                transitType = if (busRef != null) TransitData.transitTypeLabel(busRef.type) else "公交",
                direction = direction,
                mappingTransitType = busRef?.type,
                lineColor = busRef?.lineColor,
                lineId = busRef?.lineId,
                stationId = busRef?.stationId,
                cityCode = busRef?.cityCode,
                deviceLocation = busRef?.deviceLocation,
                deviceCode = busRef?.code,
                spRule = busRef?.spRule
            )
            val balanceFen = if (data.size >= 25) ApduUtil.hexToLong(data.copyOfRange(21, 25)) else null
            val amountFen = if (data.size >= 21) ApduUtil.hexToLong(data.copyOfRange(19, 21)) else 0L
            val timestamp = if (data.size >= 32) ApduUtil.bcdToString(data.copyOfRange(25, 32)) else ""

            stationMap[terminal] = ref
            // 卡所在城市：取时间戳最新记录的城市码（TU 0x18 记录自身城市码无效）
            if (timestamp > latestTs) {
                latestTs = timestamp
                if (cityCode.isNotEmpty()) latestCity = cityCode
            }
            if (terminal.isNotEmpty() && timestamp.isNotEmpty()) {
                balanceMap["$terminal|$timestamp"] = balanceFen
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
        return TuMap(stationMap, balanceMap, journey, latestCity.ifEmpty { null })
    }

    /**
     * 解析 18（+附加区）主交易记录。
     * @param lntStatsMonth  LNT 统计月份锚点（年份推断起点）
     * @param storedDateByHash 归档：contentHash → 已解析日期（yyyyMMdd），覆盖 hex 内日期（decodeArchive 用）
     * @param storedBalance    归档：contentHash → 已解析余额（null = 无余额数据，decodeArchive 用）
     */
    private fun parseFareRecords(
        cardType: String,
        records: List<ZoneRecord>,
        protocol: String,
        tu: TuMap,
        currentYear: Int,
        lntStatsMonth: Int?,
        storedDateByHash: Map<String, String>? = null,
        storedBalance: Map<String, Long?>? = null
    ): List<CanonicalTransaction> {
        val isLnt = protocol == "LNT"
        var relYear: Int? = if (isLnt) lntStatsMonth?.div(100) else null
        val hasSubtype18 = cardType == "CU" || (cardType == "YCT" && isLnt)
        var lastMonth: Int? = if (isLnt) lntStatsMonth?.mod(100) else null
        val today = todayDate()
        val orderedRecords = if (isLnt || hasSubtype18) {
            records.sortedWith(
                compareByDescending<ZoneRecord> { contentRecordNo(it.hex) ?: -1 }
                    .thenBy { it.recNo }
            )
        } else {
            records.sortedBy { it.recNo }
        }
        val results = mutableListOf<CanonicalTransaction>()

        for (rec in orderedRecords) {
            if (rec.sfi == 0x1E) continue  // 旅程记录由 buildTuMap 处理
            val data = ApduUtil.hexToBytes(rec.hex)
            if (data.size < 0x17) continue
            if (data.all { it.toInt() == 0 }) continue  // 空槽

            val seq = ApduUtil.hexToLong(data.copyOfRange(0, 2)).toInt()
            val amountFen = ApduUtil.hexToLong(data.copyOfRange(6, 9))
            val typeHex = ApduUtil.bytesToHex(byteArrayOf(data[9]))
            val typeByte = data[9].toInt() and 0xFF
            val terminal = ApduUtil.bcdToString(data.copyOfRange(10, 16))
            val posHex = ApduUtil.bytesToHex(data.copyOfRange(10, 16))
            val isSubtype18 = rec.sfi == 0x18 && hasSubtype18
            val subtype = if (isSubtype18) data[22].toInt() and 0xFF else null
            val lntType = if (isLnt && isSubtype18) resolveLntType(typeByte, subtype) else null
            val time = if (isSubtype18) {
                ApduUtil.bcdToString(data.copyOfRange(20, 22)) + "00"
            } else {
                ApduUtil.bcdToString(data.copyOfRange(20, 23))
            }

            // 日期：归档优先用已解析日期；否则 LNT 用年份推断，其余直接用记录内日期
            val hash = contentHash(rec.hex)
            val date = storedDateByHash?.get(hash) ?: run {
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
            val timestamp = date + time

            // 0x18 记录城市码：其 [10..12) 实为终端号前缀（如 4131…），对 TU 卡无效；
            // 站点解析兜底仍用它（与旧实现一致），但展示用 1E 卡所在城市码
            val cityCode18 = String.format("%04d", bcdNibble(data[11]) + bcdNibble(data[10]) * 100)
            val displayCityCode = if (protocol == "TU") (tu.cardCityCode ?: cityCode18) else cityCode18

            val posIsRecharge = posHex == "20151031095400" || posHex == "00000000000000"
            val isRecharge = posIsRecharge || typeHex == "02"
            val effectiveTypeHex = if (posIsRecharge) "02" else typeHex
            // 设备映射是站点、城市和交通类型的权威来源。LNT type/subtype 只能在
            // 未命中映射时兜底类型，或在与命中设备类型严格兼容时提供进出站方向。
            val ref = if (isRecharge) {
                StationRef("", "", "充值")
            } else {
                resolveStation(cardType, cityCode18, posHex, terminal, tu)
            }
            val mappingMatched = !isRecharge && ref.deviceCode != null
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

            // 无余额数据 = null（区别于真实的 ¥0.00）：
            // LNT 记录本身不含余额字段（旧实现把钱包级快照套到每条历史交易上，属捏造），一律 null；
            // 归档优先用已解析值（含 null，不重新推导）；TU 用 1E 嵌入余额匹配，匹配不到为 null
            val balanceAfterFen = when {
                isLnt -> null
                storedBalance != null && storedBalance.containsKey(hash) -> storedBalance[hash]
                else -> tu.balanceMap["$terminal|$timestamp"]
                    ?: findBalanceByTerminal(terminal, tu.balanceMap)
            }

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
                    rawCityCode = cityCode18,
                    date = date, time = time,
                    balanceAfterFen = balanceAfterFen,
                    deviceCode = ref.deviceCode,
                    spRule = ref.spRule
                )
            )
        }
        return results
    }

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
     * 1E（旅程）与 18（主交易）按时间戳去重合并：
     *  - 同一时间戳两条都有 → 取 18 的金额/类型/序号 + 1E 的站点/线路/方向/余额
     *  - 只有 18 → 原样
     *  - 只有 1E → 保留为旅程交易（金额可能为 0）
     */
    internal fun mergeJourneyAndFare(
        journey: List<CanonicalTransaction>,
        fare: List<CanonicalTransaction>
    ): List<CanonicalTransaction> {
        if (journey.isEmpty()) return fare
        val fareByTs = fare.groupBy { it.date + it.time }
        // 1E 旅程是 TU 站点权威来源：0x18 终端号是卡发行前缀（如广州卡 4131…），在外地（如深圳）时
        // 无法定位站，必须用同时间戳的 1E 记录取站点/线路/余额。journeyHex 也取 1E 的原始记录。
        val journeyByTs = journey.groupBy { it.date + it.time }
        val out = mutableListOf<CanonicalTransaction>()
        for (f in fare) {
            val ts = f.date + f.time
            val journeyAtTs = journeyByTs[ts]
            // 城市/原始数据独立于站名解析：只要有 1E 就用它的城市码与 hex（公交/异地行程的 18 城市码是卡归属城市，不可靠）
            val journeyCity = journeyAtTs?.firstOrNull()?.cityCode
            val journeyRawCity = journeyAtTs?.firstOrNull()?.rawCityCode
            val journeyHex = journeyAtTs?.firstOrNull()?.hex
            val city = journeyCity ?: f.cityCode
            val rawCity = journeyRawCity ?: f.rawCityCode
            // 站名解析单独处理：只取解析成功的旅程覆盖站名；城市与 hex 已在上方独立决定
            val j = journeyAtTs?.firstOrNull {
                it.stationName.isNotEmpty() && it.stationName != "未知"
            }
            if (j != null) {
                out.add(f.copy(
                    stationName = j.stationName,
                    direction = j.direction,
                    lineName = j.lineName,
                    lineColor = j.lineColor,
                    lineId = j.lineId,
                    stationId = j.stationId,
                    // 站名取自 1E 时，交通类型也必须跟 1E：0x18 用卡发行前缀终端号解析，
                    // 与 1E 解析结果可能不同（如深圳地铁 1E 命中前海湾，0x18 兜底到公交），
                    // 否则会出现「地铁站名 + 公交类型」的矛盾展示
                    transitType = j.transitType,
                    cityCode = city,
                    rawCityCode = rawCity,
                    balanceAfterFen = j.balanceAfterFen ?: f.balanceAfterFen,
                    journeyHex = journeyHex ?: j.hex,
                    deviceCode = j.deviceCode,
                    spRule = j.spRule
                ))
            } else {
                out.add(f.copy(journeyHex = journeyHex, cityCode = city, rawCityCode = rawCity))
            }
        }
        for (j in journey) {
            val ts = j.date + j.time
            if (ts !in fareByTs) out.add(j)
        }
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
            return StationRef("轨道交通", "", "轨道交通 (Metro)")
        }
        val entry = TransitData.resolveByStandard(cardType, cityCode, posHex, terminal)
        if (entry != null) return entry.toStationRef()
        return StationRef(
            station = when (cardType) {
                "CU" -> "轨道交通"
                "YCT" -> "公共交通"
                "SZT" -> "深圳通"
                else -> "公共交通"
            },
            line = "",
            transitType = "公共交通"
        )
    }

    private fun TransitData.StationEntry.toStationRef(): StationRef {
        return StationRef(
            station = station,
            line = line,
            transitType = TransitData.transitTypeLabel(type),
            mappingTransitType = type,
            lineColor = lineColor,
            lineId = lineId,
            stationId = stationId,
            cityCode = cityCode,
            deviceLocation = deviceLocation,
            deviceCode = code,
            spRule = spRule
        )
    }

    /** 按终端号模糊匹配余额（1E 与 18 终端号长度不一致时的兜底）；匹配不到返回 null（该记录无余额数据） */
    private fun findBalanceByTerminal(terminal: String, balanceMap: Map<String, Long?>): Long? {
        var best: Long? = null
        var bestTs = ""
        for ((key, value) in balanceMap) {
            if (value == null) continue
            val keyTerminal = key.substringBefore("|")
            if (keyTerminal == terminal || keyTerminal.endsWith(terminal) || terminal.endsWith(keyTerminal)) {
                val ts = key.substringAfter("|")
                if (ts > bestTs) {
                    bestTs = ts
                    best = value
                }
            }
        }
        return best
    }

    private fun contentRecordNo(hex: String): Int? {
        val data = ApduUtil.hexToBytes(hex)
        return if (data.size >= 2) ApduUtil.hexToLong(data.copyOfRange(0, 2)).toInt() else null
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
     * 从卡原始记录解析折扣统计，两种来源共用一套字段偏移（[3-4] 年月 / [6] 地铁次 / [7] 总次 / [10-12) 地铁金额 / [12-14) 总金额）：
     *  - 广州(5810)/佛山(5880) TU 卡：SFI 0x19 rec1
     *  - 岭南通 YCT 卡：LNT 钱包 SFI 0x08 rec1
     * 无对应记录或数据过短返回 null。
     */
    fun parseTuDiscountStats(records: List<RawRecord>): TuDiscountStats? {
        val rec = records.firstOrNull { it.sfi == 0x19 && it.recNo == 1 }
            ?: records.firstOrNull { it.sfi == 0x08 && it.recNo == 1 && it.protocol == "LNT" }
            ?: return null
        val data = ApduUtil.hexToBytes(rec.hex)
        if (data.size < 14) return null
        val year = 2000 + bcdNibble(data[3])
        val month = bcdNibble(data[4])
        return TuDiscountStats(
            statsMonth = if (month in 1..12) year * 100 + month else null,
            metroCount = data[6].toInt() and 0xFF,
            totalCount = data[7].toInt() and 0xFF,
            metroFen = ApduUtil.hexToLong(data.copyOfRange(10, 12)),
            totalFen = ApduUtil.hexToLong(data.copyOfRange(12, 14))
        )
    }
}

/** 折扣统计（广州/佛山 TU 卡 SFI 0x19 rec1 或岭南通 YCT LNT 钱包 SFI 0x08 rec1）——卡内本月乘车汇总，乘车时由卡自行维护 */
data class TuDiscountStats(
    val statsMonth: Int?,   // YYYYMM（[3-4] BCD 年+月），null = 月份非法
    val metroCount: Int,    // [6] 地铁次数（二进制）
    val totalCount: Int,    // [7] 总乘车次数（二进制）
    val metroFen: Long,     // [10-11] 地铁累计金额（分）
    val totalFen: Long      // [12-13] 总累计金额（分）
)
