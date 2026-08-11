package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.example.nfctransit.model.CanonicalTransaction
import com.example.nfctransit.data.db.ArchivedTransactionEntity
import java.security.MessageDigest

/**
 * 交易解码器（纯函数）：从原始 hex 记录解码交易。
 * 读卡时（decodeCard）与启动渲染（decodeArchive）共用同一解析路径，避免两套解析不一致。
 *
 * 解析逻辑迁移自 TransitCardReader：SFI 0x1E 建 终端→站点 映射表、0x18 主交易解析、
 * LNT 无年份字段的年份推断（统计月份锚点 + 记录连续性）、1E/18 按时间戳合并去重、
 * 充值/空槽过滤、城市码与站点名解析（TransitData）。
 */
object RecordDecoder {

    /** 读卡时带槽位的原始记录（recNo 顺序用于 LNT 年份连续性推断） */
    data class ZoneRecord(
        val sfi: Int,
        val recNo: Int,
        val protocol: String,  // "LNT"/"TU"/"" — 双协议卡区分钱包
        val hex: String
    )

    /** 1E 终端映射表条目（站点/线路/方向/ID） */
    private data class StationRef(
        val station: String,
        val line: String,
        val transitType: String,
        val direction: String,
        val lineColor: String? = null,
        val lineId: Long? = null,
        val stationId: Long? = null
    ) {
        val stationWithDir: String get() = if (direction.isNotEmpty()) "$station $direction" else station
    }

    /** TU 1E 建表结果 */
    private class TuMap(
        val stationMap: Map<String, StationRef>,
        val balanceMap: Map<String, Long>,
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
     * @param statsMonth    LNT 统计月份 YYYYMM（SFI 08 rec1），年份锚点
     * @param lntBalanceFen LNT 钱包当前余额（分），作为 LNT 记录交易后余额兜底
     */
    fun decodeCard(
        cardType: String,
        records: List<ZoneRecord>,
        statsMonth: Int?,
        currentYear: Int,
        lntBalanceFen: Long = 0L
    ): DecodeResult {
        return when (cardType) {
            "TU" -> {
                val tu = buildTuMap(records)
                val fare = parseFareRecords(cardType, records, "TU", tu, currentYear, null, 0L)
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
                    currentYear, statsMonth, lntBalanceFen
                )

                // TU 钱包：1E 映射表 + 18 主交易 + 旅程合并
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, tuRecords, "TU", tu, currentYear, null, 0L
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
            else -> {
                // 通用（CU/SZT/TFT/SUXIN/SZTK）：18 + 附加区，按内容去重后按时间倒序
                val fare = parseFareRecords(cardType, records, "", TuMap(emptyMap(), emptyMap(), emptyList()), currentYear, null, 0L)
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
        val records = rows.map { ZoneRecord(it.sfi, 0, it.protocol, it.hex) }
        val storedDate = rows.associate { it.contentHash to it.resolvedDate }      // 归档：contentHash → 日期
        val storedBalance = rows.associate { it.contentHash to (it.balanceAfterFen ?: 0L) }  // 归档：contentHash → 余额

        val base = when (cardType) {
            "TU" -> {
                val tu = buildTuMap(records)
                val fare = parseFareRecords(cardType, records, "TU", tu, 0, null, 0L, storedDate, storedBalance)
                mergeJourneyAndFare(tu.journey, fare)
            }
            "YCT" -> {
                val lntRecords = records.filter { it.protocol == "LNT" }
                val tuRecords = records.filter { it.protocol == "TU" }
                val lnt = if (lntRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, lntRecords, "LNT", TuMap(emptyMap(), emptyMap(), emptyList()), 0, null, 0L, storedDate, storedBalance
                )
                val tu = buildTuMap(tuRecords)
                val tuFare = if (tuRecords.isEmpty()) emptyList() else parseFareRecords(
                    cardType, tuRecords, "TU", tu, 0, null, 0L, storedDate, storedBalance
                )
                lnt + mergeJourneyAndFare(tu.journey, tuFare)
            }
            else -> parseFareRecords(
                cardType, records, "", TuMap(emptyMap(), emptyMap(), emptyList()), 0, null, 0L, storedDate, storedBalance
            ).distinctBy { "${it.date}|${it.time}|${it.terminal}|${it.amountFen}|${it.typeHex}" }
        }
        return base.sortedWith(compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence })
    }

    /**
     * 从 SFI 0x1E 循环记录建立 终端→站点 映射表 + 余额映射表 + 旅程交易（进站/出站事件）。
     * 空槽（整条全 0）跳过。返回按记录号排序处理后的结果。
     */
    private fun buildTuMap(records: List<ZoneRecord>): TuMap {
        val stationMap = mutableMapOf<String, StationRef>()
        val balanceMap = mutableMapOf<String, Long>()
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

            val entry = TransitData.resolveTuStation(cityCode, lineCode, stationCode, terminal)
            val direction = when {
                data[0] == 0x03.toByte() -> "↓"
                data[0] == 0x04.toByte() -> "↑"
                else -> ""
            }
            val ref = StationRef(
                station = entry?.station ?: "未知",
                line = entry?.line ?: "",
                transitType = TransitData.transitTypeLabel(entry?.type),
                direction = direction,
                lineColor = entry?.lineColor,
                lineId = entry?.lineId,
                stationId = entry?.stationId
            )
            val balanceFen = if (data.size >= 25) ApduUtil.hexToLong(data.copyOfRange(21, 25)) else 0L
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
                        stationName = ref.stationWithDir, lineName = ref.line, lineColor = ref.lineColor,
                        lineId = ref.lineId, stationId = ref.stationId,
                        transitType = ref.transitType,
                        cityCode = if (cityCode.isNotEmpty()) cityCode else null,
                        date = timestamp.substring(0, 8), time = timestamp.substring(8, 14),
                        balanceAfterFen = balanceFen
                    )
                )
            }
        }
        return TuMap(stationMap, balanceMap, journey, latestCity.ifEmpty { null })
    }

    /**
     * 解析 18（+附加区）主交易记录。
     * @param lntStatsMonth  LNT 统计月份锚点（年份推断起点）
     * @param lntBalanceFen  LNT 钱包余额（分），作为 LNT 记录交易后余额（钱包级快照，读卡时固化）
     * @param storedDateByHash 归档：contentHash → 已解析日期（yyyyMMdd），覆盖 hex 内日期（decodeArchive 用）
     * @param storedBalance    归档：contentHash → 已解析余额（decodeArchive 用）
     */
    private fun parseFareRecords(
        cardType: String,
        records: List<ZoneRecord>,
        protocol: String,
        tu: TuMap,
        currentYear: Int,
        lntStatsMonth: Int?,
        lntBalanceFen: Long,
        storedDateByHash: Map<String, String>? = null,
        storedBalance: Map<String, Long>? = null
    ): List<CanonicalTransaction> {
        val isLnt = protocol == "LNT"
        var relYear: Int? = if (isLnt) lntStatsMonth?.div(100) else null
        var lastMonth: Int? = if (isLnt) lntStatsMonth?.mod(100) else null
        val results = mutableListOf<CanonicalTransaction>()

        for (rec in records.sortedBy { it.recNo }) {
            if (rec.sfi == 0x1E) continue  // 旅程记录由 buildTuMap 处理
            val data = ApduUtil.hexToBytes(rec.hex)
            if (data.size < 0x17) continue
            if (data.all { it.toInt() == 0 }) continue  // 空槽

            val seq = ApduUtil.hexToLong(data.copyOfRange(0, 2)).toInt()
            val amountFen = ApduUtil.hexToLong(data.copyOfRange(6, 9))
            val typeHex = ApduUtil.bytesToHex(byteArrayOf(data[9]))
            val terminal = ApduUtil.bcdToString(data.copyOfRange(10, 16))
            val posHex = ApduUtil.bytesToHex(data.copyOfRange(10, 16))
            val time = ApduUtil.bcdToString(data.copyOfRange(20, 23))

            // 日期：归档优先用已解析日期；否则 LNT 用年份推断，其余直接用记录内日期
            val hash = contentHash(rec.hex)
            val date = storedDateByHash?.get(hash) ?: run {
                if (isLnt) {
                    val mmdd = ApduUtil.bcdToString(data.copyOfRange(18, 20))
                    val thisMonth = mmdd.take(2).toIntOrNull()
                    var year: String? = null
                    if (relYear != null && thisMonth != null && lastMonth != null) {
                        if (thisMonth > lastMonth) relYear = relYear!! - 1
                        year = relYear.toString()
                        lastMonth = thisMonth
                    }
                    val resolvedYear = year ?: currentYear.toString()
                    resolvedYear + mmdd
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

            val ref = if (isRecharge) {
                StationRef("", "", "充值", "")
            } else {
                resolveStation(cardType, cityCode18, posHex, terminal, tu)
            }

            val balanceAfterFen = storedBalance?.get(hash) ?: if (isLnt) {
                lntBalanceFen
            } else {
                tu.balanceMap["$terminal|$timestamp"]
                    ?: findBalanceByTerminal(terminal, tu.balanceMap)
            }

            results.add(
                buildTransaction(
                    sfi = rec.sfi, protocol = rec.protocol, hex = rec.hex,
                    sequence = seq, amountFen = amountFen, typeHex = effectiveTypeHex,
                    terminal = terminal,
                    stationName = ref.stationWithDir, lineName = ref.line, lineColor = ref.lineColor,
                    lineId = ref.lineId, stationId = ref.stationId,
                    transitType = ref.transitType,
                    cityCode = if (isRecharge) null else displayCityCode,
                    date = date, time = time,
                    balanceAfterFen = balanceAfterFen
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
        lineName: String,
        lineColor: String?,
        lineId: Long?,
        stationId: Long?,
        transitType: String,
        cityCode: String?,
        date: String,
        time: String,
        balanceAfterFen: Long
    ): CanonicalTransaction {
        return CanonicalTransaction(
            identity = contentHash(hex),
            sequence = sequence,
            amountFen = amountFen,
            balanceAfterFen = balanceAfterFen,
            typeHex = typeHex,
            terminal = terminal,
            cityCode = cityCode,
            lineId = lineId,
            stationId = stationId,
            stationName = stationName,
            lineName = lineName,
            lineColor = lineColor,
            transitType = transitType,
            date = date,
            time = time,
            sfi = sfi,
            protocol = protocol,
            hex = hex
        )
    }

    /**
     * 1E（旅程）与 18（主交易）按时间戳去重合并：
     *  - 同一时间戳两条都有 → 取 18 的金额/类型/序号 + 1E 的站点/线路/方向/余额
     *  - 只有 18 → 原样
     *  - 只有 1E → 保留为旅程交易（金额可能为 0）
     */
    private fun mergeJourneyAndFare(
        journey: List<CanonicalTransaction>,
        fare: List<CanonicalTransaction>
    ): List<CanonicalTransaction> {
        if (journey.isEmpty()) return fare
        val fareByTs = fare.groupBy { it.date + it.time }
        val out = mutableListOf<CanonicalTransaction>()
        val consumed = mutableSetOf<String>()
        for (f in fare) {
            val ts = f.date + f.time
            val j = fareByTs[ts]?.firstOrNull {
                it.stationName.isNotEmpty() && it.stationName != "未知"
            }
            if (j != null && consumed.add(ts)) {
                out.add(f.copy(
                    stationName = j.stationName,
                    lineName = j.lineName,
                    lineColor = j.lineColor,
                    lineId = j.lineId,
                    stationId = j.stationId,
                    balanceAfterFen = if (j.balanceAfterFen != 0L) j.balanceAfterFen else f.balanceAfterFen
                ))
            } else {
                out.add(f)
            }
        }
        for (j in journey) {
            val ts = j.date + j.time
            if (ts !in fareByTs && ts !in consumed) out.add(j)
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
            return StationRef("轨道交通", "", "轨道交通 (Metro)", "")
        }
        val entry = TransitData.resolveByStandard(cardType, cityCode, posHex, terminal)
        if (entry != null) return entry.toStationRef()
        return StationRef(
            when (cardType) {
                "CU" -> "轨道交通"
                "YCT" -> "公共交通"
                "SZT" -> "深圳通"
                else -> "公共交通"
            },
            "",
            "公共交通",
            ""
        )
    }

    private fun TransitData.StationEntry.toStationRef(): StationRef {
        return StationRef(station, line, TransitData.transitTypeLabel(type), "", lineColor, lineId, stationId)
    }

    /** 按终端号模糊匹配余额（1E 与 18 终端号长度不一致时的兜底） */
    private fun findBalanceByTerminal(terminal: String, balanceMap: Map<String, Long>): Long {
        var best = 0L
        var bestTs = ""
        for ((key, value) in balanceMap) {
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
     * 从卡原始记录解析折扣统计；无 SFI 0x19 rec1 或数据过短返回 null。
     * 字段偏移见 README「广州(5810)/佛山(5880) SFI 0x19 rec1 折扣统计」。
     */
    fun parseTuDiscountStats(records: List<RawRecord>): TuDiscountStats? {
        val rec = records.firstOrNull { it.sfi == 0x19 && it.recNo == 1 } ?: return null
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

/** 折扣统计（广州/佛山 TU 卡 SFI 0x19 rec1，48B）——卡内本月乘车汇总，乘车时由卡自行维护 */
data class TuDiscountStats(
    val statsMonth: Int?,   // YYYYMM（[3-4] BCD 年+月），null = 月份非法
    val metroCount: Int,    // [6] 地铁次数（二进制）
    val totalCount: Int,    // [7] 总乘车次数（二进制）
    val metroFen: Long,     // [10-11] 地铁累计金额（分）
    val totalFen: Long      // [12-13] 总累计金额（分）
)
