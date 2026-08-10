package com.example.nfctransit

import android.nfc.tech.IsoDep
import android.util.Log
import com.example.nfctransit.data.RawRecord
import com.example.nfctransit.data.TransitData
import java.util.Calendar

/**
 * 交通卡读取核心逻辑：
 * 1. SELECT 依次尝试已知 AID，首个成功即判定卡型（顺序见 CardProfiles.known）
 * 2. 读信息文件（YCT 用 SFI 0x15 P2=0x40 专用布局；其余用通用 SFI 0x15）
 * 3. 查余额（TU 用 SFI 0x1E 映射表；其余用 BALANCE CHECK）
 * 4. 读交易明细 SFI 0x18，逐条解析 23 字节记录并映射站名/线路名
 * 参考：wiki.nfc.im 智能卡手册 交通卡章节 APDU/SFI 定义
 *     + tripreader-technical.md（Trip Reader 1.7.17 逆向：APDU 序列与字段偏移）
 */
class TransitCardReader(private val isoDep: IsoDep) {

    private val TAG = "TransitCardReader"

    /** YCT 卡读取结果：信息 + 余额 + 年份锚点 + 卡内折扣统计 */
    private data class YctCardResult(
        val info: CardInfo?,
        val balance: Long,
        val issueYear: Int,
        val payMonth: Int?            // 统计月份 YYYYMM（LNT 交易年份锚点）
    )

    data class ReadResult(
        val matchedProfile: CardProfile?,
        val cardInfo: CardInfo?,
        val stationInfo: StationInfo?,
        val transactions: List<TransactionRecord>,
        val rawLog: List<String>,
        val secondCardInfo: CardInfo? = null,  // 双钱包卡（如 LNT+TU）的第二个钱包信息
        val secondStationInfo: StationInfo? = null,  // 第二个钱包的余额
        val rawRecords: List<RawRecord> = emptyList()  // 各交易区原始记录（持久化/重读去重用）
    )

    /** SFI 0x1E 终端映射表中的一条站点信息（线路/站名分开，供界面直接使用；ID 用于跨页面传递） */
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

    /** SFI 0x1E 读取结果：最新站点信息 + 终端映射 + 余额映射 + 原始记录 + 1E 旅程交易 */
    private data class TuMapResult(
        val stationInfo: StationInfo?,
        val stationMap: Map<String, StationRef>,
        val balanceMap: Map<String, Long>,
        val rawRecords: List<RawRecord>,
        val journeyTxns: List<TransactionRecord>  // 1E 记录解析出的旅程交易（无金额，站点/方向/余额）
    )

    /** 双协议卡 TU 钱包读取结果：信息 + 站点信息 + 1E/18 合并交易 + 原始记录 */
    private data class TuWalletResult(
        val info: CardInfo?,
        val stationInfo: StationInfo?,
        val transactions: List<TransactionRecord>,
        val rawRecords: List<RawRecord>
    )

    fun read(): ReadResult {
        val log = mutableListOf<String>()
        isoDep.connect()
        isoDep.timeout = 3000

        try {
            for (profile in CardProfiles.known) {
                for (aid in profile.aidCandidates) {
                    val selectCmd = ApduUtil.buildSelectByName(aid)
                    val resp = isoDep.transceive(selectCmd)
                    log.add("SELECT AID $aid -> ${ApduUtil.bytesToHex(resp)}")

                    if (ApduUtil.isSuccess(resp)) {
                        // TU 卡：先读 SFI 0x1E 建立 终端→站点+方向 映射表 和 余额映射表
                        val terminalInfoMap = mutableMapOf<String, StationRef>()
                        val balanceMap = mutableMapOf<String, Long>()  // "terminal|timestamp" → balanceFen
                        var stationInfo: StationInfo? = null
                        var info: CardInfo?
                        var secondInfo: CardInfo? = null  // 双协议卡第二个钱包（如 LNT+TU 的 TU 协议）
                        var secondStationInfo: StationInfo? = null
                        var tradeSfi = profile.tradeSfi

                        if (profile.cardType == "TU" && profile.stationSfi != null) {
                            info = readCardInfo(profile, log)
                            // 遍历手册列出的全部 SFI，读出原始数据用于分析文件结构
                            probeAllFiles(profile, log)
                            val tuMap = buildTuTerminalStationMap(profile.stationSfi, log)
                            stationInfo = tuMap.stationInfo
                            terminalInfoMap.putAll(tuMap.stationMap)
                            balanceMap.putAll(tuMap.balanceMap)
                            val rawRecs = tuMap.rawRecords.toMutableList()
                            val fareTxns = readTransactions(
                                profile, log, terminalInfoMap, balanceMap,
                                stationInfo?.cityCode, tradeSfi, "TU",
                                Calendar.getInstance().get(Calendar.YEAR), 0L,
                                emptyMap(), null, null, rawRecs
                            )
                            val merged = mergeJourneyAndFare(tuMap.journeyTxns, fareTxns)
                            return ReadResult(profile, info, stationInfo, merged, log, rawRecords = rawRecs)
                        } else if (profile.cardType == "YCT") {
                            // 双协议卡：同一钱包（余额相同），LNT + TU 两套信息与交易记录
                            // 读取顺序关键：必须先读 LNT（PAY.APPY → PAY.TICL）再读 TU 钱包。
                            // 实测先 SELECT TU AID 后，再重选 PAY.APPY 返回 6A82（无法切回），导致 LNT 读不出。
                            // LNT 交易无年份，用统计月份（SFI 0x08 rec1）做锚点 + 记录间月份连续性推断。
                            val yct = readYctCard(profile, log)
                            info = yct.info
                            stationInfo = StationInfo(balanceFen = yct.balance)
                            val rawRecs = mutableListOf<RawRecord>()
                            val lntTrades = readTransactions(
                                profile, log, emptyMap(), emptyMap(), null,
                                profile.tradeSfi, "LNT", yct.issueYear, yct.balance,
                                emptyMap(), null, yct.payMonth, rawRecs
                            )
                            // 第二协议：TU 钱包（SELECT TU AID 必须在 LNT 之后）
                            val tu = readTuWallet(log)
                            secondInfo = tu.info
                            secondStationInfo = tu.stationInfo
                            rawRecs.addAll(tu.rawRecords)
                            // 统计月份缺失时，用 TU 交易锚点兜底 LNT 年份（mmdd 对齐，否则出现最多的年份）
                            var finalLnt = lntTrades
                            if (yct.payMonth == null && tu.transactions.isNotEmpty()) {
                                val anchor = tu.transactions.mapNotNull { t ->
                                    if (t.date.length >= 8) t.date.substring(4, 8) to t.date.take(4) else null
                                }.toMap()
                                val fallback = tu.transactions.mapNotNull { t ->
                                    if (t.date.length >= 8) t.date.take(4) else null
                                }.groupBy { it }.maxByOrNull { it.value.size }?.key
                                if (anchor.isNotEmpty() || fallback != null) {
                                    finalLnt = lntTrades.map { t ->
                                        val mmdd = t.date.takeLast(4)
                                        val y = anchor[mmdd] ?: fallback
                                        if (y != null && y != t.date.take(4)) t.copy(date = y + mmdd) else t
                                    }
                                }
                            }
                            val allTrades = finalLnt + tu.transactions
                            // 双协议卡的 seq 是各协议文件内的序号（LNT 与 TU 各自从高到低），
                            // 按 seq 合并会把 LNT 全排前、TU 全排后（协议分组）。改为按交易时间倒序合并。
                            val trades = allTrades.sortedWith(
                                compareByDescending<TransactionRecord> { it.date + it.time }
                                    .thenByDescending { it.seq }
                            )
                            return ReadResult(profile, info, stationInfo, trades, log, secondInfo, secondStationInfo, rawRecs)
                        } else {
                            // 非 TU 卡（CU/SZT/苏州/天津）：通用 SFI 0x15 + BALANCE CHECK
                            info = readCardInfo(profile, log)
                            val balance = readBalance(profile, log)
                            stationInfo = StationInfo(balanceFen = balance)
                        }
                        val trades = readTransactions(profile, log, terminalInfoMap, balanceMap, stationInfo?.cityCode, tradeSfi)
                        return ReadResult(profile, info, stationInfo, trades, log)
                    }
                }
            }
        } catch (e: Exception) {
            log.add("异常: ${e.message}")
            Log.e(TAG, "read error", e)
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }

        return ReadResult(null, null, null, emptyList(), log)
    }

    /**
     * TU 卡：遍历 SFI 0x1E 所有记录，建立「终端号 → (站名, 交通类型)」映射表，
     * 同时返回最新一条记录（按时间戳）作为 StationInfo、余额映射表、
     * 原始记录（持久化/重读去重）与 1E 旅程交易（进站/出站事件，无金额，用于与 18 合并去重）。
     *
     * SFI 0x1E 记录结构（48 字节）：
     *   offset 0:     记录类型 03=入站(↓) 04=出站(↑)
     *   offset 3-8:   终端号 BCD (6B) → 如 413101330638
     *   offset 10-11: 线路编码 BCD (2B) → 如 0001
     *   offset 12-13: 站点编码 BCD (2B) → 如 0003
     *   offset 21-24: 余额 Hex Long (4B) → 如 00000F4A = 3914分 = ¥39.14
     *   offset 25-31: 时间戳 BCD (7B YYYYMMDDHHMMSS)
     *   offset 32-33: 城市码 BCD (2B) → 如 5810
     */
    private fun buildTuTerminalStationMap(
        sfi: Int,
        log: MutableList<String>
    ): TuMapResult {
        val map = mutableMapOf<String, StationRef>()
        val balanceMap = mutableMapOf<String, Long>()
        val rawRecords = mutableListOf<RawRecord>()
        val journeyTxns = mutableListOf<TransactionRecord>()
        var latestStationInfo: StationInfo? = null
        var latestTimestamp = ""

        for (recordNo in 1..30) {
            try {
                val cmd = ApduUtil.buildReadRecord(sfi, recordNo, 0x00)
                val resp = isoDep.transceive(cmd)
                log.add("READ RECORD SFI=${sfi.toString(16).uppercase()} rec=$recordNo (TU map) -> ${ApduUtil.bytesToHex(resp)}")
                if (!ApduUtil.isSuccess(resp)) break
                val data = ApduUtil.dataOnly(resp)
                if (data.size < 22) break
                rawRecords.add(RawRecord(sfi, recordNo, ApduUtil.bytesToHex(data)))

                // 循环记录文件的空槽：整条全 0，跳过（不生成旅程交易/映射条目）
                if (data.all { it.toInt() == 0 }) continue

                // 终端号: offset 3-8 (6 bytes BCD)
                val terminal = ApduUtil.bcdToString(data.copyOfRange(3, 9))
                // 线路: offset 10-11
                val lineCode = ApduUtil.bcdToString(data.copyOfRange(10, 12))
                // 站点: offset 12-13
                val stationCode = ApduUtil.bcdToString(data.copyOfRange(12, 14))
                // 城市码: offset 32-33 (4 位 BCD)，决定使用哪个城市的 CSV
                val cityCode = if (data.size >= 34) ApduUtil.bcdToString(data.copyOfRange(32, 34)) else ""

                // 城市码 + 线路/站点码/终端号 → 从该城市 CSV 查询站名与交通类型
                val entry = TransitData.resolveTuStation(cityCode, lineCode, stationCode, terminal)
                val lineName = entry?.line ?: ""
                val stationName = entry?.station ?: "未知"
                val transitType = TransitData.transitTypeLabel(entry?.type)
                val lineColor = entry?.lineColor
                val lineId = entry?.lineId
                val stationId = entry?.stationId

                // 方向: offset 0 → 03=入站(↓), 04=出站(↑)
                val direction = when {
                    data.isNotEmpty() && data[0] == 0x03.toByte() -> "↓"
                    data.isNotEmpty() && data[0] == 0x04.toByte() -> "↑"
                    else -> ""
                }

                // 余额: offset 21-24 (4 bytes, big-endian hex, 单位: 分)
                val balanceFen = if (data.size >= 25) {
                    ApduUtil.hexToLong(data.copyOfRange(21, 25))
                } else 0L

                // 金额（票价）: offset 19-20 (2 bytes, big-endian hex, 单位: 分)；进站为 0，出站=本次票价
                val amountFen = if (data.size >= 21) {
                    ApduUtil.hexToLong(data.copyOfRange(19, 21))
                } else 0L

                // 时间戳: offset 25-31 (7 bytes BCD: YYYYMMDDHHMMSS) → 用于比较取最新记录
                val timestamp = if (data.size >= 32) {
                    ApduUtil.bcdToString(data.copyOfRange(25, 32))
                } else ""

                map[terminal] = StationRef(stationName, lineName, transitType, direction, lineColor, lineId, stationId)

                // 余额映射表: key = "terminal|timestamp"，供交易记录按终端+时间匹配余额
                if (terminal.isNotEmpty() && timestamp.isNotEmpty()) {
                    balanceMap["$terminal|$timestamp"] = balanceFen
                }

                // 1E 旅程交易：进站/出站事件（1E 记录自带票价[19..21)与余额[21..25)），用于与 18 合并去重
                if (timestamp.length >= 14) {
                    journeyTxns.add(
                        TransactionRecord(
                            seq = 0,
                            amountYuan = amountFen / 100.0,
                            typeHex = ApduUtil.bytesToHex(byteArrayOf(data[0])),
                            transitType = transitType,
                            terminal = terminal,
                            stationName = StationRef(stationName, lineName, transitType, direction, lineColor, lineId, stationId).stationWithDir,
                            lineName = lineName,
                            lineColor = lineColor,
                            lineId = lineId,
                            stationId = stationId,
                            cityName = if (cityCode.isNotEmpty()) TransitData.cityZh(cityCode) else "",
                            date = timestamp.substring(0, 8),
                            time = timestamp.substring(8, 14),
                            balanceAfterFen = balanceFen,
                            sourceSfi = sfi,
                            sourceRecNo = recordNo,
                            rawKey = "$sfi:$recordNo:${ApduUtil.bytesToHex(data)}"
                        )
                    )
                }

                // 选择时间戳最新的记录作为当前卡片信息
                if (timestamp > latestTimestamp) {
                    latestTimestamp = timestamp
                    val cityName = if (cityCode.isNotEmpty()) TransitData.cityName(cityCode) else "未知"
                    latestStationInfo = StationInfo(
                        cityCode = if (cityCode.isNotEmpty()) cityCode else "未知",
                        cityName = cityName,
                        lineCode = lineCode,
                        stationCode = stationCode,
                        stationName = stationName,
                        rawHex = ApduUtil.bytesToHex(data),
                        balanceFen = balanceFen,
                        direction = direction
                    )
                }
            } catch (e: Exception) {
                log.add("读取 TU 映射异常 rec=$recordNo: ${e.message}")
                break
            }
        }
        log.add("TU 终端映射表: ${map.size} 条, 1E 旅程 ${journeyTxns.size} 条, 最新余额: ${latestStationInfo?.balanceFen ?: 0}分")
        return TuMapResult(latestStationInfo, map, balanceMap, rawRecords, journeyTxns)
    }

    /** 遍历手册列出的全部 SFI，先试 READ BINARY，失败再试 READ RECORD，读出原始数据用于分析文件结构 */
    private fun probeAllFiles(profile: CardProfile, log: MutableList<String>) {
        if (profile.cardType != "TU") return
        // 手册文件映射（电子钱包 AID=010105 下）：
        // 01-04 支付应用专用文件、0B 消费交易明细、0C 圈存交易明细、
        // 05-08/19 发卡机构自定义、15 公共应用基本/余额、16 持卡人信息、
        // 17 管理信息、18 交易明细、1A 公交过程信息变长记录、1E 公交过程信息循环记录
        val sfis = listOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x0B, 0x0C,
            0x15, 0x16, 0x17, 0x18, 0x19,
            0x1A, 0x1E
        )
        for (sfi in sfis) {
            try {
                // 1) READ BINARY
                val resp = isoDep.transceive(ApduUtil.buildReadBinary(sfi, 0, 0x00))
                log.add("PROBE SFI=${sfi.toString(16).uppercase()} BINARY -> ${ApduUtil.bytesToHex(resp)}")
                if (ApduUtil.isSuccess(resp)) continue
                // 2) READ RECORD 循环（变长/循环记录文件不支持 READ BINARY）
                for (recordNo in 1..30) {
                    val rresp = isoDep.transceive(ApduUtil.buildReadRecord(sfi, recordNo, 0x00))
                    log.add("PROBE SFI=${sfi.toString(16).uppercase()} REC rec=$recordNo -> ${ApduUtil.bytesToHex(rresp)}")
                    if (!ApduUtil.isSuccess(rresp)) break
                }
            } catch (e: Exception) {
                log.add("PROBE SFI=${sfi.toString(16).uppercase()} 异常: ${e.message}")
            }
        }
        log.add("PROBE 全部 SFI 探测完成")
    }

    private fun readCardInfo(profile: CardProfile, log: MutableList<String>): CardInfo? {
        return when (profile.cardType) {
            "YCT" -> readYctInfo(profile, log)
            else -> readCuInfo(profile, log)
        }
    }

    /**
     * YCT（岭南通/羊城通）LNT 钱包读取（tripreader-technical.md §3.2 + 真卡文件结构）：
     *  基本应用 PAY.APPY(DDF1) 下：READ BINARY SFI=0x15 P2=0x40 Le=0x46 读信息文件；
     *   同上下文读 SFI=0x08 rec1（00 B2 01 44 16）当月统计月份 → 作为 LNT 交易年份锚点
     *  钱包应用 PAY.TICL(ADF3) 下：BALANCE CHECK 查余额
     * 随后的交易明细（SFI=0x18）也必须在 PAY.TICL 上下文读取，因此这里选中 TICL 后不切回。
     */
    private fun readYctCard(
        profile: CardProfile,
        log: MutableList<String>
    ): YctCardResult {
        // 0) 若识别为 YCT2(PAY.TICL)，先重选基本应用 PAY.APPY，信息文件在其上下文中
        val appyAid = "5041592E41505059"
        val selectAppy = isoDep.transceive(ApduUtil.buildSelectByName(appyAid))
        log.add("SELECT AID $appyAid (YCT basic app) -> ${ApduUtil.bytesToHex(selectAppy)}")
        // ① 信息文件（PAY.APPY 上下文）：SFI=0x15, P2=0x40, Le=0x46
        val info = readYctInfo(profile, log)
        val issueYear = info?.validFrom?.take(4)?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
        // ①.5 统计月份（PAY.APPY 上下文）：SFI=0x08 rec1（00 B2 01 44 16，22B）[3]年BCD+2000、[4]月BCD。
        //    LNT 交易记录无年份，用它做年份锚点（Trip Reader relYear 逻辑同源）
        var payMonth: Int? = null
        try {
            val resp = isoDep.transceive(ApduUtil.buildReadRecord(0x08, 1, 0x16))
            log.add("READ RECORD SFI=08 rec=1 (stats month) -> ${ApduUtil.bytesToHex(resp)}")
            val d = ApduUtil.dataOnly(resp)
            if (d.size >= 5) {
                val year = 2000 + bcdNibble(d[3])
                val month = bcdNibble(d[4])
                if (month in 1..12) payMonth = year * 100 + month
                log.add("统计月份: ${if (payMonth != null) payMonth else "无法解析"}")
            }
        } catch (e: Exception) {
            log.add("读取统计月份异常: ${e.message}")
        }
        // ② SELECT 钱包应用 PAY.TICL（AID=5041592E5449434C）
        val ticlAid = "5041592E5449434C"
        val selectTicl = isoDep.transceive(ApduUtil.buildSelectByName(ticlAid))
        log.add("SELECT AID $ticlAid (YCT wallet) -> ${ApduUtil.bytesToHex(selectTicl)}")
        if (!ApduUtil.isSuccess(selectTicl)) {
            log.add("YCT 钱包应用 PAY.TICL 选择失败")
        }
        // ③ BALANCE CHECK 查余额（必须在 TICL 上下文）
        val balance = readBalance(profile, log)
        return YctCardResult(info, balance, issueYear, payMonth)
    }

    /**
     * 双协议卡的 TU 协议钱包（A000000632010105）：信息文件 + 余额 + 交易记录。
     * 与纯 TU 卡一致，先读 SFI 0x1E 建立 终端→站点+方向 映射表 与 余额映射表，
     * 并把 1E 记录解析成旅程交易，与 SFI 0x18 主交易按时间戳合并去重。
     * 返回信息、站点信息、合并后交易、原始记录。
     */
    private fun readTuWallet(log: MutableList<String>): TuWalletResult {
        val tuProfile = CardProfiles.known.firstOrNull { it.cardType == "TU" }
            ?: return TuWalletResult(null, null, emptyList(), emptyList())
        val selectTu = isoDep.transceive(ApduUtil.buildSelectByName(tuProfile.aidCandidates.first()))
        log.add("SELECT AID ${tuProfile.aidCandidates.first()} (dual TU) -> ${ApduUtil.bytesToHex(selectTu)}")
        if (!ApduUtil.isSuccess(selectTu)) {
            log.add("双协议卡 TU 协议选择失败")
            return TuWalletResult(null, null, emptyList(), emptyList())
        }
        val info = readCuInfo(tuProfile, log)
        val balance = readBalance(tuProfile, log)
        // 与纯 TU 卡一致：先建 SFI 0x1E 终端映射表，交联交易按终端号解析站点
        val tuMap = buildTuTerminalStationMap(tuProfile.stationSfi!!, log)
        val station = tuMap.stationInfo ?: StationInfo(balanceFen = balance)
        val cardCityCode = station.cityCode?.takeIf { it != "未知" }
        val rawRecs = tuMap.rawRecords.toMutableList()
        val fareTxns = readTransactions(
            tuProfile, log, tuMap.stationMap, tuMap.balanceMap, cardCityCode,
            tuProfile.tradeSfi, "TU", Calendar.getInstance().get(Calendar.YEAR), 0L,
            emptyMap(), null, null, rawRecs
        )
        val merged = mergeJourneyAndFare(tuMap.journeyTxns, fareTxns)
        return TuWalletResult(info, station, merged, rawRecs)
    }

    /**
     * YCT（岭南通/羊城通）信息文件读取。
     * 目标：SFI 0x15 公共应用基本信息（PAY.APPY 上下文），文档命令 `00 B0 2D 40`
     * 在本卡上返回 6986（命令不允许），因此逐个尝试多种 READ BINARY 变体：
     *   标准短 EF 形式 `0x80|SFI`（TU 卡同款，最可能命中）+ 文档形式。
     * 命中后用 `[8..18)` hex 作卡号、`[27..31)` BCD 作有效期。
     */
    private fun readYctInfo(profile: CardProfile, log: MutableList<String>): CardInfo? {
        return try {
            val variants = listOf(
                "00B0950046",  // 0x80|0x15=SFI 0x15, offset 0, Le=0x46
                "00B0954046",  // SFI 0x15, offset 0x40, Le=0x46
                "00B0950000",  // SFI 0x15, offset 0, Le=0（卡自行返回）
                "00B02D4046",  // 文档形式（返回 6986，保留日志参考）
                "00B0A54046",  // 0x80|0x25=SFI 0x25
                "00B0B54046",  // 0x80|0x35=SFI 0x35
                "00B0854046",  // 0x80|0x05=SFI 0x05
                "00B02D4000"   // 文档形式 + Le=0
            )
            var best: CardInfo? = null
            for (cmdHex in variants) {
                val resp = isoDep.transceive(ApduUtil.hexToBytes(cmdHex))
                log.add("READ BINARY (YCT info) $cmdHex -> ${ApduUtil.bytesToHex(resp)}")
                if (!ApduUtil.isSuccess(resp)) continue
                best = parseYctInfo(profile.name, resp, cmdHex, log)
                if (best != null) break
            }
            if (best == null) {
                // 若 READ BINARY 全部失败，可能 SFI 0x15 是循环/变长记录文件，改用 READ RECORD
                for (recordNo in 1..30) {
                    val resp = isoDep.transceive(ApduUtil.buildReadRecord(0x15, recordNo, 0x00))
                    log.add("READ RECORD (YCT info) SFI=15 rec=$recordNo -> ${ApduUtil.bytesToHex(resp)}")
                    if (!ApduUtil.isSuccess(resp)) break
                    val parsed = parseYctInfo(profile.name, resp, "READ RECORD SFI=15 rec=$recordNo", log)
                    if (parsed != null) { best = parsed; break }
                }
            }
            best
        } catch (e: Exception) {
            log.add("读取 YCT 信息文件异常: ${e.message}")
            null
        }
    }

    /** 从一次成功的 YCT 信息文件响应解析卡号/有效期，返回 null 表示数据不足 */
    private fun parseYctInfo(
        cardName: String,
        resp: ByteArray,
        label: String,
        log: MutableList<String>
    ): CardInfo? {
        val data = ApduUtil.dataOnly(resp)
        val hex = ApduUtil.bytesToHex(data)
        var validFrom = "未知"
        var validTo = "未知"
        if (data.size >= 32) {
            // 发卡/到期日期在 [23..27) / [27..31)（实测信息文件：...FF 20240621 20341231 20...，前导 FF 占 [22]）
            validFrom = ApduUtil.bcdToString(data.copyOfRange(23, 27))  // 发卡日期 YYYYMMDD
            validTo = ApduUtil.bcdToString(data.copyOfRange(27, 31))    // 有效期 YYYYMMDD
        }
        // LNT 卡号: [11..16) BCD（如 9534635882）；[8..18) 是带前缀的完整 hex，不直接用
        val cardNumber = if (data.size >= 16) {
            ApduUtil.bcdToString(data.copyOfRange(11, 16))
        } else ""
        val info = CardInfo(cardName, validFrom, validTo, cardNumber, hex)
        log.add("READ YCT info 命中 $label, 卡号=$cardNumber, 发卡=$validFrom, 到期=$validTo, 共${data.size}字节")
        return info
    }

    /** 通用（CU/SZT/TFT/苏州/TU）信息文件：READ BINARY SFI=0x15，卡号 [10..20) BCD */
    private fun readCuInfo(profile: CardProfile, log: MutableList<String>): CardInfo? {
        return try {
            val cmd = ApduUtil.buildReadBinary(profile.infoSfi, 0, 0x00)
            val resp = isoDep.transceive(cmd)
            log.add("READ BINARY SFI=${profile.infoSfi} -> ${ApduUtil.bytesToHex(resp)}")
            if (!ApduUtil.isSuccess(resp)) return null

            val data = ApduUtil.dataOnly(resp)
            val hex = ApduUtil.bytesToHex(data)
            var validFrom = "未知"
            var validTo = "未知"
            if (data.size >= 28) {
                validFrom = ApduUtil.bcdToString(data.copyOfRange(20, 24))
                validTo = ApduUtil.bcdToString(data.copyOfRange(24, 28))
            }
            // 应用序列号（卡号）: offset 10-19 (10B BCD)，IIN 取前 8 位匹配卡名
            val cardNumber = if (data.size >= 20) {
                ApduUtil.bcdToString(data.copyOfRange(10, 20)).trimStart('0')
            } else ""
            CardInfo(profile.name, validFrom, validTo, cardNumber, hex)
        } catch (e: Exception) {
            log.add("读取信息文件异常: ${e.message}")
            null
        }
    }

    /**
     * 非 TU 卡种的 BALANCE CHECK（PBOC 电子钱包）查余额：
     *   `80 5C 00 02 04`，响应体内联余额。
     * 实际响应（如岭南通 `00 00 0F 00`）：首字节 0x00 为状态字节，[1..] 为大端 HEX 余额（分）
     *   → 0x0F00 = 3840 分 = ¥38.40。失败/无余额时返回 0（读卡链路降级，不中断交易读取）。
     */
    private fun readBalance(profile: CardProfile, log: MutableList<String>): Long {
        return try {
            val cmd = ApduUtil.hexToBytes("805C000204")
            val resp = isoDep.transceive(cmd)
            log.add("BALANCE CHECK -> ${ApduUtil.bytesToHex(resp)}")
            if (!ApduUtil.isSuccess(resp)) return 0L
            val data = ApduUtil.dataOnly(resp)
            if (data.size < 2) return 0L
            // 跳过状态字节 [0]（0x00），[1..] 为大端 hex 余额（分）
            ApduUtil.hexToLong(data.copyOfRange(1, data.size))
        } catch (e: Exception) {
            log.add("BALANCE CHECK 异常: ${e.message}")
            0L
        }
    }

    private fun readTransactions(
        profile: CardProfile,
        log: MutableList<String>,
        terminalInfoMap: Map<String, StationRef> = emptyMap(),
        balanceMap: Map<String, Long> = emptyMap(),
        cardCityCode: String? = null,
        tradeSfi: Int = profile.tradeSfi,
        protocol: String = "TU",
        issueYear: Int = Calendar.getInstance().get(Calendar.YEAR),
        defaultBalanceFen: Long = 0L,
        lntYearAnchor: Map<String, String> = emptyMap(),   // LNT mmdd → 年份（TU 记录对齐）
        lntYearFallback: String? = null,                    // 兜底年份（TU 记录出现最多的年份）
        lntPayMonth: Int? = null,                           // 统计月份 YYYYMM（SFI 0x08 rec1），LNT 年份锚点
        rawCollector: MutableList<RawRecord>? = null        // 原始记录收集器（持久化/重读去重用）
    ): List<TransactionRecord> {
        val results = mutableListOf<TransactionRecord>()
        // LNT 记录从文件读到的是倒序（新→旧），但记录号越旧 seq 越大。年份连续性推断：
        // 从统计月份倒推，月份回退（跨年）则年份减 1。文件遍历方向（rec 从小到大）恰为时间从新到旧。
        var relYear: Int? = lntPayMonth?.div(100)
        var lastMonth: Int? = lntPayMonth?.mod(100)
        // 城市中文名：整个卡片属于同一城市，取 SFI 0x1E 城市码查询一次（仅 TU 有该字段）
        val cardCityName = if (!cardCityCode.isNullOrEmpty() && cardCityCode != "未知") {
            TransitData.cityZh(cardCityCode)
        } else ""
        for (recordNo in 1..30) {
            try {
                val cmd = ApduUtil.buildReadRecord(tradeSfi, recordNo, profile.tradeRecordLen)
                val resp = isoDep.transceive(cmd)
                log.add("READ RECORD SFI=${tradeSfi.toString(16).uppercase()} rec=$recordNo -> ${ApduUtil.bytesToHex(resp)}")

                if (!ApduUtil.isSuccess(resp)) break
                val data = ApduUtil.dataOnly(resp)
                if (data.size < 0x17) break
                rawCollector?.add(RawRecord(tradeSfi, recordNo, ApduUtil.bytesToHex(data)))

                // 循环记录文件的空槽：整条全 0（seq=0/时间全 0/金额 0），跳过不生成交易
                if (data.all { it.toInt() == 0 }) continue

                val seq = ApduUtil.hexToLong(data.copyOfRange(0, 2)).toInt()
                val amountHex = data.copyOfRange(6, 9)
                val amountFen = ApduUtil.hexToLong(amountHex)
                val typeHex = ApduUtil.bytesToHex(byteArrayOf(data[9]))
                val terminal = ApduUtil.bcdToString(data.copyOfRange(10, 16))
                val posHex = ApduUtil.bytesToHex(data.copyOfRange(10, 16))  // [10..16) 6 字节 hex

                // 日期：TU/CU 等 [16..20) 是 YYYYMMDD、[20..23) 是 HHMMSS；
                // LNT 无年份字段，[18..23) = MMDDHHMMSS。年份用 relYear 连续性推断：
                //  从统计月份（或回退锚点）倒推，月份回退（跨年）则年份减 1。
                val (date, time) = if (protocol == "LNT") {
                    val mmdd = ApduUtil.bcdToString(data.copyOfRange(18, 20))
                    val hhmmss = ApduUtil.bcdToString(data.copyOfRange(20, 23))
                    val thisMonth = mmdd.take(2).toIntOrNull()
                    var year: String? = null
                    if (relYear != null && thisMonth != null && lastMonth != null) {
                        // 本记录月份比上一记录大（回退遍历到更早的跨年点）→ 年份减一
                        if (thisMonth > lastMonth) relYear = relYear!! - 1
                        year = relYear.toString()
                        lastMonth = thisMonth
                    }
                    val resolvedYear = year ?: lntYearAnchor[mmdd] ?: lntYearFallback ?: issueYear.toString()
                    resolvedYear + mmdd to hhmmss
                } else {
                    ApduUtil.bcdToString(data.copyOfRange(16, 20)) to
                        ApduUtil.bcdToString(data.copyOfRange(20, 23))
                }
                val timestamp = date + time  // YYYYMMDDHHMMSS, 匹配 SFI 0x1E 时间戳

                // 城市码：压缩 BCD，U([11]) + U([10])*100，补零到 4 位（如 0100/5180）
                val cityCode = String.format(
                    "%04d", bcdNibble(data[11]) + bcdNibble(data[10]) * 100
                )

                // 充值/结算记录（终端号全 0 或特定时间戳）：无站点、标记为充值
                val posIsRecharge = posHex == "20151031095400" || posHex == "00000000000000"
                val isRecharge = posIsRecharge || typeHex == "02"
                val effectiveTypeHex = if (posIsRecharge) "02" else typeHex

                // 优先用 TU 终端映射表，回退到按卡种/城市/位置码解析
                val ref = if (isRecharge) {
                    StationRef("", "", "充值", "")
                } else {
                    resolveStation(cityCode, posHex, terminal, profile, terminalInfoMap)
                }
                val stationWithDir = ref.stationWithDir

                // 从 SFI 0x1E 余额映射表按 终端+时间 精确匹配交易后余额；
                // LNT 记录 [16..18) 是票价非余额，用当前钱包余额回填（双协议卡余额一致）
                val balanceAfterFen = if (protocol == "LNT") {
                    defaultBalanceFen
                } else {
                    balanceMap["$terminal|$timestamp"]
                        ?: findBalanceByTerminal(terminal, balanceMap)
                }

                results.add(
                    TransactionRecord(
                        seq = seq,
                        amountYuan = amountFen / 100.0,
                        typeHex = effectiveTypeHex,
                        transitType = ref.transitType,
                        terminal = terminal,
                        stationName = stationWithDir,
                        lineName = ref.line,
                        lineColor = ref.lineColor,
                        lineId = ref.lineId,
                        stationId = ref.stationId,
                        cityName = when {
                            cardCityName.isNotEmpty() -> cardCityName
                            isRecharge -> ""
                            else -> TransitData.cityZh(cityCode)
                        },
                        date = date,
                        time = time,
                        balanceAfterFen = balanceAfterFen,
                        sourceSfi = tradeSfi,
                        sourceRecNo = recordNo,
                        rawKey = "$tradeSfi:$recordNo:${ApduUtil.bytesToHex(data)}"
                    )
                )
            } catch (e: Exception) {
                log.add("读取交易记录异常 rec=$recordNo: ${e.message}")
                break
            }
        }
        return results
    }

    /**
     * 1E（旅程，48B，含站点/方向/余额）与 18（主交易，23B，含金额/类型）按时间戳去重合并：
     *  - 同一时间戳两条都有 → 合并：取 18 的金额/类型/序号 + 1E 的站点/线路/方向/余额
     *  - 只有 18 → 原样（站点已由终端映射解析）
     *  - 只有 1E → 保留为旅程交易（进站/出站事件，金额 0），满足"只有 1E 也要展示"
     */
    private fun mergeJourneyAndFare(
        journey: List<TransactionRecord>,
        fare: List<TransactionRecord>
    ): List<TransactionRecord> {
        if (journey.isEmpty()) return fare
        val fareByTs = fare.groupBy { it.date + it.time }
        val out = mutableListOf<TransactionRecord>()
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

    /** BCD 半字节 → 十进制数（0x12 → 12） */
    private fun bcdNibble(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return (v shr 4) * 10 + (v and 0x0F)
    }

    /**
     * 按终端号模糊匹配余额：有时 SFI 0x18 与 SFI 0x1E 的终端号长度不一致，
     * 取 SFI 0x1E 中同一终端的最新一条余额作为兜底。
     */
    private fun findBalanceByTerminal(
        terminal: String,
        balanceMap: Map<String, Long>
    ): Long {
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

    /**
     * 解析站点名称和交通类型。
     * - TU 卡：优先从 SFI 0x1E 建立的终端→站点映射表查找（映射表由 TransitData 从 DB 生成）。
     * - 其他卡种（YCT/CU/SZT/苏州/天津）：用 城市码 + 位置码/终端号 查 DB（TransitData.resolveByStandard）。
     */
    private fun resolveStation(
        cityCode: String,
        posHex: String,
        terminal: String,
        profile: CardProfile,
        tuMap: Map<String, StationRef>
    ): StationRef {
        // TU 卡：从映射表查找 (station, line, transitType, direction)
        if (profile.cardType == "TU") {
            tuMap[terminal]?.let { return it }
            // 尝试终端号模糊匹配
            for ((key, value) in tuMap) {
                if (terminal.endsWith(key) || key.endsWith(terminal)) {
                    return value
                }
            }
            // 回退到 DB 按城市+位置码解析
            val fallback = TransitData.resolveByStandard("TU", cityCode, posHex, terminal)
            if (fallback != null) return fallback.toStationRef()
            return StationRef("轨道交通", "", "轨道交通 (Metro)", "")
        }

        // 非 TU 卡种：按 城市码 + 位置码/终端号 查 DB
        val entry = TransitData.resolveByStandard(profile.cardType, cityCode, posHex, terminal)
        if (entry != null) return entry.toStationRef()
        // 站点未解析到时用友好兜底（城市已单独显示，站名不再带终端号等内部信息）
        return StationRef(
            when (profile.cardType) {
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

    /** TransitData.StationEntry -> 内部 StationRef（带方向占位，线路颜色/ID 一并传递） */
    private fun TransitData.StationEntry.toStationRef(): StationRef {
        return StationRef(station, line, TransitData.transitTypeLabel(type), "", lineColor, lineId, stationId)
    }
}
