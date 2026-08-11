package com.example.nfctransit

import android.nfc.tech.IsoDep
import android.util.Log
import com.example.nfctransit.data.RawRecord
import java.util.Calendar

/**
 * 交通卡读取核心逻辑（重构：只收原始 hex + 身份/余额，交易解析移入 RecordDecoder）：
 * 1. SELECT 依次尝试已知 AID，首个成功即判定卡型（顺序见 CardProfiles.known）
 * 2. 读信息文件（YCT 用 SFI 0x15 P2=0x40 专用布局；其余用通用 SFI 0x15）→ 身份（卡号）
 * 3. 查余额（TU 用 SFI 0x1E 最新记录；其余用 BALANCE CHECK）
 * 4. 收集各交易区原始 hex（SFI 0x18 + 附加区 + TU 0x1E），带 protocol 标签，
 *    由 RecordDecoder 统一解码为 CanonicalTransaction（读卡展示与启动渲染共用同一条解析路径）
 * 参考：wiki.nfc.im 智能卡手册 交通卡章节 APDU/SFI 定义
 *     + tripreader-technical.md（Trip Reader 1.7.17 逆向：APDU 序列与字段偏移）
 */
class TransitCardReader(private val isoDep: IsoDep) {

    private val TAG = "TransitCardReader"

    /** YCT 卡读取结果：信息 + 余额 + 年份锚点（统计月份） */
    private data class YctCardResult(
        val info: CardInfo?,
        val balance: Long,
        val payMonth: Int?            // 统计月份 YYYYMM（LNT 交易年份锚点）
    )

    /** 双协议卡 TU 钱包读取结果：信息 + 余额 */
    private data class TuWalletResult(
        val info: CardInfo?,
        val balance: Long
    )

    data class ReadResult(
        val matchedProfile: CardProfile?,
        val cardInfo: CardInfo?,
        val balanceFen: Long = 0,                 // 主钱包余额（分）：TU 取 0x1E 最新，其余 BALANCE CHECK
        val secondCardInfo: CardInfo? = null,     // 双协议卡第二个钱包（如 LNT+TU 的 TU 钱包）
        val secondBalanceFen: Long = 0,           // 第二个钱包余额（分）
        val statsMonth: Int? = null,              // LNT 统计月份 YYYYMM（SFI 0x08 rec1，年份锚点）
        val rawRecords: List<RawRecord> = emptyList(),  // 各交易区原始记录（带 protocol 标签）
        val rawLog: List<String> = emptyList()
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
                        when (profile.cardType) {
                            // TU 卡：读信息文件 → 0x1E 建终端映射（取最新余额）→ 0x18 主交易
                            "TU" -> {
                                val info = readCuInfo(profile, log)
                                probeAllFiles(profile, log)
                                val rawRecs = mutableListOf<RawRecord>()
                                val balance = collectTuWallet(profile, log, rawRecs)
                                return ReadResult(profile, info, balance, rawRecords = rawRecs, rawLog = log)
                            }
                            // 双协议卡：LNT 钱包（信息/余额/统计月份 + 0x18 LNT）+ TU 钱包（1E + 0x18 TU）
                            "YCT" -> {
                                val yct = readYctCard(profile, log)
                                val rawRecs = mutableListOf<RawRecord>()
                                collectFareZone(profile, log, profile.tradeSfi, "LNT", rawRecs)
                                val tu = readTuWallet(log, rawRecs)
                                return ReadResult(
                                    profile, yct.info, yct.balance,
                                    tu.info, tu.balance, yct.payMonth, rawRecs, log
                                )
                            }
                            // 通用（CU/SZT/TFT/苏州）：信息 + BALANCE CHECK + 0x18 + 附加区
                            else -> {
                                val info = readCuInfo(profile, log)
                                val balance = readBalance(profile, log)
                                val rawRecs = mutableListOf<RawRecord>()
                                collectFareZone(profile, log, profile.tradeSfi, "", rawRecs)
                                val extraSfis = when (profile.cardType) {
                                    "CU" -> listOf(0x10, 0x06, 0x1A)
                                    "TFT" -> listOf(0x10, 0x09)
                                    else -> emptyList()
                                }
                                for (sfi in extraSfis) {
                                    collectFareZone(profile, log, sfi, "", rawRecs)
                                }
                                return ReadResult(profile, info, balance, rawRecords = rawRecs, rawLog = log)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.add("异常: ${e.message}")
            Log.e(TAG, "read error", e)
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }

        return ReadResult(null, null, rawLog = log)
    }

    /**
     * 收集 SFI 0x1E（TU 终端映射/旅程区）记录并返回最新余额。
     * 只解析余额/时间戳（offset 21-25 / 25-32）用于取最新，其余解析交给 RecordDecoder。
     */
    private fun collectTuWallet(
        profile: CardProfile,
        log: MutableList<String>,
        collector: MutableList<RawRecord>
    ): Long {
        var latestBalance = 0L
        var latestTs = ""
        for (recordNo in 1..30) {
            try {
                val cmd = ApduUtil.buildReadRecord(profile.stationSfi!!, recordNo, 0x00)
                val resp = isoDep.transceive(cmd)
                log.add("READ RECORD SFI=${profile.stationSfi.toString(16).uppercase()} rec=$recordNo (TU map) -> ${ApduUtil.bytesToHex(resp)}")
                if (!ApduUtil.isSuccess(resp)) break
                val data = ApduUtil.dataOnly(resp)
                if (data.size < 22) break
                collector.add(RawRecord(profile.stationSfi, recordNo, "TU", ApduUtil.bytesToHex(data)))
                if (data.all { it.toInt() == 0 }) continue  // 空槽
                val balance = if (data.size >= 25) ApduUtil.hexToLong(data.copyOfRange(21, 25)) else 0L
                val ts = if (data.size >= 32) ApduUtil.bcdToString(data.copyOfRange(25, 32)) else ""
                if (ts > latestTs) {
                    latestTs = ts
                    latestBalance = balance
                }
            } catch (e: Exception) {
                log.add("读取 TU 映射异常 rec=$recordNo: ${e.message}")
                break
            }
        }
        collectFareZone(profile, log, profile.tradeSfi, "TU", collector)
        return latestBalance
    }

    /** 双协议卡的 TU 钱包：信息 + 余额 + 1E/18 记录（protocol="TU"） */
    private fun readTuWallet(log: MutableList<String>, collector: MutableList<RawRecord>): TuWalletResult {
        val tuProfile = CardProfiles.known.firstOrNull { it.cardType == "TU" }
            ?: return TuWalletResult(null, 0L)
        val selectTu = isoDep.transceive(ApduUtil.buildSelectByName(tuProfile.aidCandidates.first()))
        log.add("SELECT AID ${tuProfile.aidCandidates.first()} (dual TU) -> ${ApduUtil.bytesToHex(selectTu)}")
        if (!ApduUtil.isSuccess(selectTu)) {
            log.add("双协议卡 TU 协议选择失败")
            return TuWalletResult(null, 0L)
        }
        val info = readCuInfo(tuProfile, log)
        val balance = collectTuWallet(tuProfile, log, collector)
        return TuWalletResult(info, balance)
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

    /**
     * 收集一个交易区（0x18 等）的全部原始记录（23B），带 protocol 标签。
     * 不解析；空槽/短记录由 RecordDecoder 过滤。读失败或数据过短即视为文件结束。
     */
    private fun collectFareZone(
        profile: CardProfile,
        log: MutableList<String>,
        sfi: Int,
        protocol: String,
        collector: MutableList<RawRecord>
    ) {
        for (recordNo in 1..30) {
            try {
                val cmd = ApduUtil.buildReadRecord(sfi, recordNo, profile.tradeRecordLen)
                val resp = isoDep.transceive(cmd)
                log.add("READ RECORD SFI=${sfi.toString(16).uppercase()} rec=$recordNo -> ${ApduUtil.bytesToHex(resp)}")
                if (!ApduUtil.isSuccess(resp)) break
                val data = ApduUtil.dataOnly(resp)
                if (data.size < 0x17) break
                collector.add(RawRecord(sfi, recordNo, protocol, ApduUtil.bytesToHex(data)))
            } catch (e: Exception) {
                log.add("读取交易记录异常 rec=$recordNo: ${e.message}")
                break
            }
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
        return YctCardResult(info, balance, payMonth)
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
     * BALANCE CHECK（PBOC 电子钱包）查余额：
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

    /** BCD 半字节 → 十进制数（0x12 → 12） */
    private fun bcdNibble(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return (v shr 4) * 10 + (v and 0x0F)
    }
}
