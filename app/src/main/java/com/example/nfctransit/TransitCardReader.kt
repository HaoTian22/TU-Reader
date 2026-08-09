package com.example.nfctransit

import android.nfc.tech.IsoDep
import android.util.Log
import com.example.nfctransit.data.TransitData

/**
 * 交通卡读取核心逻辑：
 * 1. SELECT PSE (2PAY.SYS.DDF01)
 * 2. 依次尝试已知 AID，SELECT 成功后读取信息文件与交易明细文件
 * 参考：wiki.nfc.im 智能卡手册 - 交通卡章节 APDU / SFI 定义
 */
class TransitCardReader(private val isoDep: IsoDep) {

    private val TAG = "TransitCardReader"

    data class ReadResult(
        val matchedProfile: CardProfile?,
        val cardInfo: CardInfo?,
        val stationInfo: StationInfo?,
        val transactions: List<TransactionRecord>,
        val rawLog: List<String>
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
                        // 暂不支持的卡种：仅识别 AID，不做进一步解析
                        if (!profile.supported) {
                            log.add("该卡种暂不支持（当前仅支持交通联合卡）: ${profile.name}")
                            return ReadResult(profile, null, null, emptyList(), log)
                        }

                        val info = readCardInfo(profile, log)
                        // 遍历手册列出的全部 SFI，读出原始数据用于分析文件结构
                        probeAllFiles(profile, log)

                        // TU 卡：先读 SFI 0x1E 建立 终端→站点+方向 映射表 和 余额映射表
                        val terminalInfoMap = mutableMapOf<String, Triple<String, String, String>>()
                        val balanceMap = mutableMapOf<String, Long>()  // "terminal|timestamp" → balanceFen
                        var stationInfo: StationInfo? = null

                        if (profile.cardType == "TU" && profile.stationSfi != null) {
                            val (si, infoMap, balMap) = buildTuTerminalStationMap(profile.stationSfi, log)
                            stationInfo = si
                            terminalInfoMap.putAll(infoMap)
                            balanceMap.putAll(balMap)
                        }

                        val trades = readTransactions(profile, log, terminalInfoMap, balanceMap, stationInfo?.cityCode)
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
    ): Triple<StationInfo?, Map<String, Triple<String, String, String>>, Map<String, Long>> {
        val map = mutableMapOf<String, Triple<String, String, String>>()
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
                val stationName = entry?.let { "${it.line} ${it.station}".trim() } ?: "未知"
                val transitType = TransitData.transitTypeLabel(entry?.type)

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

                map[terminal] = Triple(stationName, transitType, direction)

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

    private fun readTransactions(
        profile: CardProfile,
        log: MutableList<String>,
        terminalInfoMap: Map<String, Triple<String, String, String>> = emptyMap(),
        balanceMap: Map<String, Long> = emptyMap(),
        cardCityCode: String? = null
    ): List<TransactionRecord> {
        val results = mutableListOf<TransactionRecord>()
        // 城市中文名：整个卡片属于同一城市，取 SFI 0x1E 城市码查询一次
        val cityName = if (!cardCityCode.isNullOrEmpty() && cardCityCode != "未知") {
            TransitData.cityZh(cardCityCode)
        } else ""
        for (recordNo in 1..30) {
            try {
                val cmd = ApduUtil.buildReadRecord(profile.tradeSfi, recordNo, profile.tradeRecordLen)
                val resp = isoDep.transceive(cmd)
                log.add("READ RECORD SFI=${profile.tradeSfi.toString(16).uppercase()} rec=$recordNo -> ${ApduUtil.bytesToHex(resp)}")

                if (!ApduUtil.isSuccess(resp)) break
                val data = ApduUtil.dataOnly(resp)
                if (data.size < 0x17) break

                val seq = ApduUtil.hexToLong(data.copyOfRange(0, 2)).toInt()
                val amountHex = data.copyOfRange(5, 9)
                val amountFen = ApduUtil.hexToLong(amountHex)
                val typeHex = ApduUtil.bytesToHex(byteArrayOf(data[9]))
                val terminal = ApduUtil.bcdToString(data.copyOfRange(10, 16))
                val date = ApduUtil.bcdToString(data.copyOfRange(16, 20))
                val time = ApduUtil.bcdToString(data.copyOfRange(20, 23))
                val timestamp = date + time  // YYYYMMDDHHMMSS, 匹配 SFI 0x1E 时间戳

                // 优先用 TU 终端映射表，回退到通用映射
                val (station, transitType) = resolveStation(terminal, profile, terminalInfoMap)

                // 从 SFI 0x1E 余额映射表按 终端+时间 精确匹配交易后余额
                val balanceAfterFen = balanceMap["$terminal|$timestamp"]
                    ?: findBalanceByTerminal(terminal, balanceMap)

                results.add(
                    TransactionRecord(
                        seq = seq,
                        amountYuan = amountFen / 100.0,
                        typeHex = typeHex,
                        transitType = transitType,
                        terminal = terminal,
                        stationName = station,
                        cityName = cityName,
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
     * 当前仅支持 TU 卡：优先从 SFI 0x1E 建立的终端→站点映射表查找（映射表由 TransitData 从 CSV 生成）。
     */
    private fun resolveStation(
        terminal: String,
        profile: CardProfile,
        tuMap: Map<String, Triple<String, String, String>>
    ): Pair<String, String> {
        // TU 卡：从映射表查找 (stationName, transitType, direction)
        if (profile.cardType == "TU") {
            tuMap[terminal]?.let { (station, transitType, direction) ->
                val stationWithDir = if (direction.isNotEmpty()) "$station $direction" else station
                return Pair(stationWithDir, transitType)
            }
            // 尝试终端号模糊匹配
            for ((key, value) in tuMap) {
                if (terminal.endsWith(key) || key.endsWith(terminal)) {
                    val stationWithDir = if (value.third.isNotEmpty()) "${value.first} ${value.third}" else value.first
                    return Pair(stationWithDir, value.second)
                }
            }
            return Pair("轨道交通 (TU终端:$terminal)", "轨道交通 (Metro)")
        }

        // 其他卡种暂不支持
        return Pair("暂不支持", "未知")
    }
}
