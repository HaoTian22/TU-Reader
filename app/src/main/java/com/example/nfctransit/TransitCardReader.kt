package com.example.nfctransit

import android.nfc.tech.IsoDep
import android.util.Log
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

    data class ReadResult(
        val matchedProfile: CardProfile?,
        val cardInfo: CardInfo?,
        val stationInfo: StationInfo?,
        val transactions: List<TransactionRecord>,
        val rawLog: List<String>,
        val secondCardInfo: CardInfo? = null,  // 双钱包卡（如 LNT+TU）的第二个钱包信息
        val secondStationInfo: StationInfo? = null  // 第二个钱包的余额
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
                            val (si, infoMap, balMap) = buildTuTerminalStationMap(profile.stationSfi, log)
                            stationInfo = si
                            terminalInfoMap.putAll(infoMap)
                            balanceMap.putAll(balMap)
                        } else if (profile.cardType == "YCT") {
                            // 双协议卡：同一钱包（余额相同），LNT + TU 两套信息与交易记录
                            //  LNT 协议（PAY.APPY / PAY.TICL）：SFI 0x15 信息文件 + SFI 0x18 交易（无年份，用发卡年）
                            //  TU 协议（A000000632010105）：SFI 0x15 信息文件 + SFI 0x18 交易（带年份）
                            // readYctCard 内部选中 PAY.TICL，随后读 LNT 交易；
                            // 之后再 SELECT TU AID 读 TU 信息与交易，合并。
                            val (yctInfo, yctBalance, issueYear) = readYctCard(profile, log)
                            info = yctInfo
                            stationInfo = StationInfo(balanceFen = yctBalance)
                            val lntTrades = readTransactions(profile, log, emptyMap(), emptyMap(), null, profile.tradeSfi, "LNT", issueYear, yctBalance)
                            // 第二协议：TU
                            val tu = readTuWallet(log)
                            secondInfo = tu.first
                            secondStationInfo = tu.second
                            val allTrades = lntTrades + tu.third
                            // 双协议卡的 seq 是各协议文件内的序号（LNT 与 TU 各自从高到低），
                            // 按 seq 合并会把 LNT 全排前、TU 全排后（协议分组）。改为按交易时间倒序合并。
                            val trades = allTrades.sortedWith(
                                compareByDescending<TransactionRecord> { it.date + it.time }
                                    .thenByDescending { it.seq }
                            )
                            return ReadResult(profile, info, stationInfo, trades, log, secondInfo, secondStationInfo)
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
     * TU 卡：遍历 SFI 0x1E 所有记录，建立「终端号 → (站名, 交通类型)」映射表
     * 同时返回最新一条记录（按时间戳）作为 StationInfo 用于显示余额和方向
     *
     * SFI 0x1E 记录结构（48 字节）：
     *   offset 3-8:   终端号 BCD (6B) → 如 413101330638
     *   offset 10-11: 线路编码 BCD (2B) → 如 0001
     *   offset 12-13: 站点编码 BCD (2B) → 如 0003
     *   offset 17-20: 余额 Hex Long (4B) → 如 00000F4A = 3914分 = ¥39.14
     *   offset 32-33: 城市码 BCD (2B) → 如 5810
     *
     * 方向判定：通过 SFI 0x1E Type 字段 → Type 03=入站, Type 04=出站
     *   offset 0 处可能是记录类型标识
     */
    private fun buildTuTerminalStationMap(
        sfi: Int,
        log: MutableList<String>
    ): Triple<StationInfo?, Map<String, StationRef>, Map<String, Long>> {
        val map = mutableMapOf<String, StationRef>()
        val balanceMap = mutableMapOf<String, Long>()
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

                // 时间戳: offset 25-31 (7 bytes BCD: YYYYMMDDHHMMSS) → 用于比较取最新记录
                val timestamp = if (data.size >= 32) {
                    ApduUtil.bcdToString(data.copyOfRange(25, 32))
                } else ""

                map[terminal] = StationRef(stationName, lineName, transitType, direction, lineColor, lineId, stationId)

                // 余额映射表: key = "terminal|timestamp"，供交易记录按终端+时间匹配余额
                if (terminal.isNotEmpty() && timestamp.isNotEmpty()) {
                    balanceMap["$terminal|$timestamp"] = balanceFen
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
        log.add("TU 终端映射表: ${map.size} 条, 最新余额: ${latestStationInfo?.balanceFen ?: 0}分")
        return Triple(latestStationInfo, map, balanceMap)
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
     *  基本应用 PAY.APPY(DDF1) 下：READ BINARY SFI=0x15 P2=0x40 Le=0x46 读信息文件
     *  钱包应用 PAY.TICL(ADF3) 下：BALANCE CHECK 查余额
     * 随后的交易明细（SFI=0x18）也必须在 PAY.TICL 上下文读取，因此这里选中 TICL 后不切回。
     * 返回 (信息, 余额分, 发卡年份)——发卡年用于给无年份的 LNT 交易记录补年份。
     */
    private fun readYctCard(profile: CardProfile, log: MutableList<String>): Triple<CardInfo?, Long, Int> {
        // 0) 若识别为 YCT2(PAY.TICL)，先重选基本应用 PAY.APPY，信息文件在其上下文中
        val appyAid = "5041592E41505059"
        val selectAppy = isoDep.transceive(ApduUtil.buildSelectByName(appyAid))
        log.add("SELECT AID $appyAid (YCT basic app) -> ${ApduUtil.bytesToHex(selectAppy)}")
        // ① 信息文件（PAY.APPY 上下文）：SFI=0x15, P2=0x40, Le=0x46
        val info = readYctInfo(profile, log)
        val issueYear = info?.validFrom?.take(4)?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
        // ② SELECT 钱包应用 PAY.TICL（AID=5041592E5449434C）
        val ticlAid = "5041592E5449434C"
        val selectTicl = isoDep.transceive(ApduUtil.buildSelectByName(ticlAid))
        log.add("SELECT AID $ticlAid (YCT wallet) -> ${ApduUtil.bytesToHex(selectTicl)}")
        if (!ApduUtil.isSuccess(selectTicl)) {
            log.add("YCT 钱包应用 PAY.TICL 选择失败")
        }
        // ③ BALANCE CHECK 查余额（必须在 TICL 上下文）
        val balance = readBalance(profile, log)
        return Triple(info, balance, issueYear)
    }

    /**
     * 双协议卡的 TU 协议钱包（A000000632010105）：信息文件 + 余额 + 交易记录。
     * 与纯 TU 卡共享通用信息文件（SFI 0x15，卡号 [10..20) BCD）与 SFI 0x18 交易。
     * 返回 (信息, 余额, 交易记录)。
     */
    private fun readTuWallet(log: MutableList<String>): Triple<CardInfo?, StationInfo?, List<TransactionRecord>> {
        val tuProfile = CardProfiles.known.firstOrNull { it.cardType == "TU" } ?: return Triple(null, null, emptyList())
        val selectTu = isoDep.transceive(ApduUtil.buildSelectByName(tuProfile.aidCandidates.first()))
        log.add("SELECT AID ${tuProfile.aidCandidates.first()} (dual TU) -> ${ApduUtil.bytesToHex(selectTu)}")
        if (!ApduUtil.isSuccess(selectTu)) {
            log.add("双协议卡 TU 协议选择失败")
            return Triple(null, null, emptyList())
        }
        val info = readCuInfo(tuProfile, log)
        val balance = readBalance(tuProfile, log)
        val trades = readTransactions(tuProfile, log, emptyMap(), emptyMap(), null, tuProfile.tradeSfi, "TU", Calendar.getInstance().get(Calendar.YEAR))
        return Triple(info, StationInfo(balanceFen = balance), trades)
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
        defaultBalanceFen: Long = 0L
    ): List<TransactionRecord> {
        val results = mutableListOf<TransactionRecord>()
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

                val seq = ApduUtil.hexToLong(data.copyOfRange(0, 2)).toInt()
                val amountHex = data.copyOfRange(6, 9)
                val amountFen = ApduUtil.hexToLong(amountHex)
                val typeHex = ApduUtil.bytesToHex(byteArrayOf(data[9]))
                val terminal = ApduUtil.bcdToString(data.copyOfRange(10, 16))
                val posHex = ApduUtil.bytesToHex(data.copyOfRange(10, 16))  // [10..16) 6 字节 hex

                // 日期：TU/CU 等 [16..20) 是 YYYYMMDD、[20..23) 是 HHMMSS；
                // LNT 无年份字段，[18..23) = MMDDHHMMSS，年份用发卡年（卡片生命周期内，发卡后所有交易都在当年或次年）
                val (date, time) = if (protocol == "LNT") {
                    val mmdd = ApduUtil.bcdToString(data.copyOfRange(18, 20))
                    val year = lntYear(issueYear, mmdd, ApduUtil.bcdToString(data.copyOfRange(20, 23)))
                    year + mmdd to ApduUtil.bcdToString(data.copyOfRange(20, 23))
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
                        balanceAfterFen = balanceAfterFen
                    )
                )
            } catch (e: Exception) {
                log.add("读取交易记录异常 rec=$recordNo: ${e.message}")
                break
            }
        }
        return results
    }

    /** BCD 半字节 → 十进制数（0x12 → 12） */
    private fun bcdNibble(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return (v shr 4) * 10 + (v and 0x0F)
    }

    /**
     * LNT 交易记录无年份字段（日期为 MMDD），无法逐条区分年份。
     * 实测该卡全部交易都发生在发卡年（2024 发卡、2024-08~10 使用），故直接用发卡年。
     * 相比"与今天比较推断今年/去年"：卡片可能已使用多年（如 2024 发卡、2026 读取），
     * 用今天的年份会错推成 2025/2026，而发卡年是唯一可靠的下界。
     */
    private fun lntYear(issueYear: Int, mmdd: String, hhmmss: String): String {
        return issueYear.toString()
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
            return StationRef("轨道交通 (TU终端:$terminal)", "", "轨道交通 (Metro)", "")
        }

        // 非 TU 卡种：按 城市码 + 位置码/终端号 查 DB
        val entry = TransitData.resolveByStandard(profile.cardType, cityCode, posHex, terminal)
        if (entry != null) return entry.toStationRef()
        return StationRef(
            when (profile.cardType) {
                "CU" -> "轨道交通 (CU终端:$terminal)"
                "YCT" -> "公共交通 (YCT终端:$terminal)"
                "SZT" -> "深圳通 (SZT终端:$terminal)"
                else -> "公共交通 (${profile.cardType}终端:$terminal)"
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
