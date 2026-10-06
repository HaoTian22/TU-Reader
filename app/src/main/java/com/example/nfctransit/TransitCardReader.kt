package com.example.nfctransit

import android.nfc.tech.IsoDep
import com.example.nfctransit.data.RawRecord
import java.math.BigInteger
import java.util.Calendar

/**
 * 交通卡读取核心逻辑（重构：只收原始 hex + 身份/余额，交易解析移入 RecordDecoder）：
 * 1. SELECT 依次尝试已知 AID，首个成功即判定卡型（顺序见 CardProfiles.known）
 * 2. 读信息文件（YCT 用 SFI 0x15 P2=0x40 专用布局；其余用通用 SFI 0x15）→ 身份（卡号）
 * 3. 查余额（TU 用 SFI 0x1E 最新记录；其余用 BALANCE CHECK）
 * 4. 收集各交易区原始 hex（SFI 0x18 + 附加区 + TU 0x1E），带 protocol 标签，
 *    由 RecordDecoder 统一解码为 CanonicalTransaction（读卡展示与启动渲染共用同一条解析路径）
 * 参考：wiki.nfc.im 智能卡手册 交通卡章节 APDU/SFI 定义
 */
internal fun parseSztCardNumber(data: ByteArray): String {
    val cardNumberBytes = data.copyOfRange(16, 20).reversedArray()
    return ApduUtil.hexToLong(cardNumberBytes).toString()
}

internal fun parseLegacyCuCardNumber(data: ByteArray): String {
    if (data.size < 20) return ""
    return ApduUtil.bcdToString(data.copyOfRange(10, 20)).trimStart('0')
}

internal fun parseCuCardNumber(data: ByteArray): String {
    return BigInteger(1, data.copyOfRange(12, 20)).toString()
}

class TransitCardReader internal constructor(
    private val channel: CardChannel,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    constructor(isoDep: IsoDep) : this(IsoDepCardChannel(isoDep))

    private var connectionError: Exception? = null
    private var readError: String? = null
    private var optionalReading = false
    private var activeAid = ""
    private val applicationReads = linkedMapOf<String, AppRead>()
    private val attemptedAids = mutableSetOf<String>()
    private val selectedAids = mutableSetOf<String>()

    private fun rememberApplication(aid: String, response: ByteArray) {
        activeAid = aid
        selectedAids.add(aid)
        val previous = applicationReads[aid]
        applicationReads[aid] = AppRead(
            aid, ApduUtil.bytesToHex(ApduUtil.dataOnly(response)),
            previous?.balanceFen, previous?.balanceResp
        )
    }

    private fun rememberBalance(aid: String, balance: Long?, response: String?) {
        val application = applicationReads[aid] ?: return
        applicationReads[aid] = application.copy(balanceFen = balance, balanceResp = response)
    }

    private fun rawRecord(sfi: Int, recNo: Int, protocol: String, hex: String) =
        RawRecord(sfi, recNo, protocol, hex, selectedAid = activeAid)

    /** 通信一旦中断，本次会话不再发送命令；业务上的非 9000 响应仍由各文件处理。 */
    private fun transceive(command: ByteArray): ByteArray {
        connectionError?.let { throw it }
        return try {
            channel.transceive(command)
        } catch (error: Exception) {
            connectionError = error
            if (!optionalReading) readError = error.message ?: error.javaClass.simpleName
            throw error
        }
    }

    /** 卡读取结果：信息 + 余额 + 年份锚点 + TICL SELECT/BALANCE 响应 */
    private data class YctCardResult(
        val info: CardInfo?,
        val balance: Long?,             // LNT 钱包余额（分），BALANCE CHECK 失败为 null
        val payMonth: Int?,            // 统计月份 YYYYMM（LNT 交易年份锚点）
        val balanceResp: String? = null,   // LNT 钱包 BALANCE CHECK 响应 hex
        val statsRecord: RawRecord? = null,
        val walletSelected: Boolean = false
    )

    /** 双协议卡 TU 钱包读取结果：信息 + 余额 + TU SELECT/BALANCE 响应 */
    private data class TuWalletResult(
        val info: CardInfo?,
        val balance: Long?,             // TU 钱包余额（分），BALANCE CHECK 失败为 null
        val selectedAid: String = "",
        val balanceResp: String? = null
    )

    /** BALANCE CHECK 结果：余额（分）+ 原始响应 hex（失败时 respHex=null） */
    private data class BalanceResult(val fen: Long, val respHex: String?)

    /** 卡上一个应用的 SELECT + BALANCE 快照（落 card_app 表） */
    data class AppRead(
        val selectedAid: String,
        val selectResp: String,        // SELECT 成功响应（FCI）dataOnly hex
        val balanceFen: Long?,         // 该应用余额（分），无/失败为 null
        val balanceResp: String?       // BALANCE CHECK 响应 dataOnly hex，未发/失败为 null
    )

    data class ReadResult(
        val matchedProfile: CardProfile?,
        val cardInfo: CardInfo?,
        val balanceFen: Long? = null,             // 主钱包余额（分），读卡失败/无余额为 null
        val secondCardInfo: CardInfo? = null,     // 双协议卡第二个钱包（如 LNT+TU / SZT+TU 的 TU 钱包）
        val secondBalanceFen: Long? = null,       // 第二个钱包余额（分）
        val statsMonth: Int? = null,              // LNT 统计月份 YYYYMM（SFI 0x08 rec1，年份锚点）
        val rawRecords: List<RawRecord> = emptyList(),  // 各交易区原始记录（带 protocol 标签）
        val rawLog: List<String> = emptyList(),
        val appReads: List<AppRead> = emptyList(),     // 卡上各应用 SELECT/BALANCE（含 PSE）
        val readError: String? = null                  // 核心通信中断，已读部分仍保留
    )

    fun read(): ReadResult {
        val log = mutableListOf<String>()
        applicationReads.clear()
        attemptedAids.clear()
        selectedAids.clear()
        val startedAt = nowMs()

        try {
            channel.connect()
            channel.timeout = 3000
            // 顺序尝试 CardProfiles.known（首命中即读；YCT/SZT 双协议卡在各自分支内部同时读 TU 钱包）。
            // 注意：PSE 不能先于识别——实测 SELECT PSE 后卡片会锁定到目录列出的应用，
            // 导致未列出的 PAY.APPY/PAY.TICL SELECT 返回 6A82（LNT 钱包读不到、双协议变纯 TU），
            // 因此全部应用枚举（含 PSE）放在核心数据和额外文件读取之后。
            for (profile in CardProfiles.known) {
                val result = readProfile(profile, log)
                if (result != null) {
                    optionalReading = true
                    if (connectionError == null) {
                        try {
                            channel.timeout = 500
                            discoverApplications(log)
                        } catch (error: Exception) {
                            log.add("应用枚举异常: ${error.message}")
                        }
                    }
                    log.add("读取总耗时: ${nowMs() - startedAt} ms")
                    return result.copy(appReads = applicationReads.values.toList(), rawLog = log.toList(), readError = readError)
                }
            }
        } catch (e: Exception) {
            log.add("异常: ${e.message}")
            readError = e.message ?: e.javaClass.simpleName
        } finally {
            try { channel.close() } catch (_: Exception) {}
        }

        return ReadResult(null, null, rawLog = log, appReads = applicationReads.values.toList(), readError = readError)
    }

    /**
     * 读取单个卡型：依次 SELECT 其候选 AID，命中后按卡型读信息/交易/余额，
     * 并把该应用的 SELECT + BALANCE 记入 appReads。无候选命中返回 null。
     */
    private fun readProfile(
        profile: CardProfile,
        log: MutableList<String>
    ): ReadResult? {
        for (aid in profile.aidCandidates) {
            attemptedAids.add(aid)
            val selectResp = transceive(ApduUtil.buildSelectByName(aid))
            log.add("SELECT AID $aid -> ${ApduUtil.bytesToHex(selectResp)}")
            if (!ApduUtil.isSuccess(selectResp)) continue
            rememberApplication(aid, selectResp)

            val coreStartedAt = nowMs()
            val protocol = when (profile.cardType) {
                "YCT" -> "LNT"
                "TU", "CU", "SZT" -> profile.cardType
                else -> ""
            }
            val rawRecs = mutableListOf<RawRecord>()
            val yct = if (profile.cardType == "YCT") readYctCard(profile, log) else null
            val info = yct?.info ?: if (yct == null) readCuInfo(profile, log) else null
            val bc = if (yct == null) readBalance(profile, log) else null
            var balance = yct?.balance ?: bc?.takeIf { it.respHex != null }?.fen
            addInfoRecord(rawRecs, info, protocol.ifEmpty { profile.cardType },
                if (yct != null) "5041592E41505059" else aid)
            yct?.statsRecord?.let(rawRecs::add)
            if (profile.cardType == "TU") {
                val recordBalance = collectTuWallet(profile, log, rawRecs)
                if (balance == null) balance = recordBalance
            } else if (yct == null || yct.walletSelected) {
                collectFareZone(profile, log, profile.tradeSfi, protocol, rawRecs)
                for (sfi in profile.extraTradeSfis) {
                    if (connectionError != null) break
                    collectFareZone(profile, log, sfi, protocol, rawRecs)
                }
            }
            // 已有解析器依赖的统计文件属于必读数据，不受额外探测预算限制。
            val statsSfis = when (profile.cardType) {
                "CU" -> setOf(0x17)
                "TU", "SZT" -> setOf(0x19)
                else -> emptySet()
            }
            for (sfi in statsSfis) collectAuxiliaryFile(sfi, protocol, log, rawRecs)
            val primaryAid = if (yct != null) "5041592E5449434C" else aid
            if (yct == null || yct.walletSelected) {
                rememberBalance(primaryAid, balance, yct?.balanceResp ?: bc?.respHex)
            }
            val tu = if (profile.cardType in setOf("YCT", "CU", "SZT") && connectionError == null) {
                readTuWallet(log, rawRecs)
            } else TuWalletResult(null, null)
            addInfoRecord(rawRecs, tu.info, "TU", tu.selectedAid)
            if (tu.selectedAid.isNotEmpty()) {
                rememberBalance(tu.selectedAid, tu.balance, tu.balanceResp)
            }
            log.add("核心读取耗时: ${nowMs() - coreStartedAt} ms")

            // 先完成全部钱包的核心读取，再限时保留未知文件；断卡不会丢弃核心结果。
            optionalReading = true
            if (connectionError == null && (yct == null || yct.walletSelected)) {
                val optionalStartedAt = nowMs()
                try {
                    channel.timeout = 500
                    val primarySelected = profile.cardType !in setOf("YCT", "CU", "SZT") ||
                        selectApplication(primaryAid, log) != null
                    if (primarySelected && connectionError == null) {
                        probeAllFiles(log, rawRecs, protocol,
                            profile.transactionSfis + profile.infoSfi + statsSfis +
                                (if (yct != null) setOf(0x08) else emptySet()),
                            optionalStartedAt)
                    }
                } catch (error: Exception) {
                    log.add("额外文件探测异常: ${error.message}")
                }
                log.add("额外探测耗时: ${nowMs() - optionalStartedAt} ms")
            }
            return ReadResult(profile, info, balance, tu.info, tu.balance, yct?.payMonth,
                rawRecs, log, applicationReads.values.toList(), readError)
        }
        return null
    }

    /**
     * 收集 SFI 0x1E（TU 终端映射/旅程区）记录并返回最新余额。
     * 只解析余额/时间戳（offset 21-25 / 25-32）用于取最新，其余解析交给 RecordDecoder。
     * @return 最新一条带时间戳记录的嵌入余额（分）；无任何带余额的 1E 记录时为 null
     */
    private fun collectTuWallet(
        profile: CardProfile,
        log: MutableList<String>,
        collector: MutableList<RawRecord>
    ): Long? {
        var latestBalance: Long? = null
        var latestTs = ""
        for (recordNo in 1..30) {
            if (connectionError != null) break
            try {
                val cmd = ApduUtil.buildReadRecord(profile.stationSfi!!, recordNo, 0x00)
                val resp = transceive(cmd)
                log.add("READ RECORD SFI=${profile.stationSfi.toString(16).uppercase()} rec=$recordNo (TU map) -> ${ApduUtil.bytesToHex(resp)}")
                if (!ApduUtil.isSuccess(resp)) break
                val data = ApduUtil.dataOnly(resp)
                if (data.size < 22) break
                collector.add(rawRecord(profile.stationSfi, recordNo, "TU", ApduUtil.bytesToHex(data)))
                if (data.all { it.toInt() == 0 }) continue  // 空槽
                val balance = if (data.size >= 25) ApduUtil.hexToLong(data.copyOfRange(21, 25)) else null
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

    /** 双协议卡的 TU 钱包：信息 + 余额 + 1E/18 记录（protocol="TU"）+ TU SELECT/BALANCE */
    private fun readTuWallet(log: MutableList<String>, collector: MutableList<RawRecord>): TuWalletResult {
        val tuProfile = CardProfiles.known.firstOrNull { it.cardType == "TU" }
            ?: return TuWalletResult(null, null)
        var selectedAid = ""
        var selectTu: ByteArray? = null
        for (aid in tuProfile.aidCandidates) {
            if (connectionError != null) break
            val response = selectApplication(aid, log) ?: continue
            selectedAid = aid
            selectTu = response
            break
        }
        if (selectTu == null) {
            log.add("双协议卡 TU 协议选择失败")
            return TuWalletResult(null, null)
        }
        val info = readCuInfo(tuProfile, log)
        val bc = readBalance(tuProfile, log)
        val lntBalance = collectTuWallet(tuProfile, log, collector)
        collectAuxiliaryFile(0x19, "TU", log, collector)
        val balance = if (bc.respHex != null) bc.fen else lntBalance
        return TuWalletResult(
            info, balance,
            selectedAid,
            bc.respHex
        )
    }

    private fun selectApplication(aid: String, log: MutableList<String>): ByteArray? = try {
        attemptedAids.add(aid)
        val response = transceive(ApduUtil.buildSelectByName(aid))
        log.add("SELECT AID $aid -> ${ApduUtil.bytesToHex(response)}")
        response.takeIf(ApduUtil::isSuccess)?.also { rememberApplication(aid, it) }
    } catch (error: Exception) {
        log.add("选择应用异常: ${error.message}")
        null
    }

    private fun collectAuxiliaryFile(
        sfi: Int, protocol: String, log: MutableList<String>, collector: MutableList<RawRecord>,
        deadlineMs: Long = Long.MAX_VALUE
    ) {
        if (connectionError != null || nowMs() >= deadlineMs) return
        try {
            val binary = transceive(ApduUtil.buildReadBinary(sfi, 0, 0x00))
            log.add("PROBE SFI=${sfi.toString(16).uppercase()} BINARY -> ${ApduUtil.bytesToHex(binary)}")
            if (ApduUtil.isSuccess(binary)) {
                val data = ApduUtil.dataOnly(binary)
                if (data.any { it.toInt() != 0 }) {
                    collector.add(rawRecord(sfi, 0, protocol, ApduUtil.bytesToHex(data)))
                }
                return
            }
            for (recordNo in 1..30) {
                if (connectionError != null || nowMs() >= deadlineMs) break
                val response = transceive(ApduUtil.buildReadRecord(sfi, recordNo, 0x00))
                log.add("PROBE SFI=${sfi.toString(16).uppercase()} REC rec=$recordNo -> ${ApduUtil.bytesToHex(response)}")
                if (!ApduUtil.isSuccess(response)) break
                val data = ApduUtil.dataOnly(response)
                if (data.any { it.toInt() != 0 }) {
                    collector.add(rawRecord(sfi, recordNo, protocol, ApduUtil.bytesToHex(data)))
                }
            }
        } catch (error: Exception) {
            log.add("PROBE SFI=${sfi.toString(16).uppercase()} 异常: ${error.message}")
        }
    }

    /** 核心读取后枚举应用，先探测已知 AID，再读取 PPSE/PSE，保留目录列出的未知应用。 */
    private fun discoverApplications(log: MutableList<String>) {
        for (aid in CardProfiles.known.flatMap { it.aidCandidates }.distinct()) {
            if (connectionError != null) return
            if (aid !in attemptedAids) selectApplication(aid, log)
        }
        val listedAids = linkedSetOf<String>()
        for (directoryAid in listOf(CardProfiles.PSE_AID, "315041592E5359532E4444463031")) {
            if (connectionError != null) break
            val response = selectApplication(directoryAid, log) ?: continue
            val directory = parseApplicationDirectory(ApduUtil.dataOnly(response))
            fun rememberEntries(entries: DirectoryEntries) {
                for (aid in entries.aids) {
                    listedAids.add(aid)
                    applicationReads.putIfAbsent(aid, AppRead(aid, "", null, null))
                }
            }
            rememberEntries(directory)
            // PSE 可能在 FCI 中只给出目录 SFI，应用列表在该文件的记录中。
            val sfi = directory.sfi ?: continue
            for (recordNo in 1..30) {
                if (connectionError != null) break
                try {
                    val record = transceive(ApduUtil.buildReadRecord(sfi, recordNo, 0))
                    log.add("READ DIRECTORY SFI=${sfi.toString(16).uppercase()} rec=$recordNo -> ${ApduUtil.bytesToHex(record)}")
                    if (!ApduUtil.isSuccess(record)) break
                    val data = ApduUtil.dataOnly(record)
                    if (data.isEmpty()) break
                    rememberEntries(parseApplicationDirectory(data))
                } catch (error: Exception) {
                    log.add("应用目录读取异常: ${error.message}")
                    break
                }
            }
        }
        for (aid in listedAids) {
            if (connectionError != null) break
            if (aid !in selectedAids) selectApplication(aid, log)
        }
        log.add("应用枚举完成: ${applicationReads.size} 个应用/目录")
    }

    private data class DirectoryEntries(val aids: List<String>, val sfi: Int?)

    /** 递归解析 6F/A5/BF0C/61/70 等嵌套 BER-TLV，原实现会整块跳过构造标签。 */
    private fun parseApplicationDirectory(data: ByteArray): DirectoryEntries {
        val aids = linkedSetOf<String>()
        var directorySfi: Int? = null
        fun visit(start: Int, end: Int, depth: Int) {
            if (depth > 16) return
            var offset = start
            while (offset < end) {
                val firstTag = data[offset++].toInt() and 0xFF
                if (firstTag == 0 || firstTag == 0xFF) continue
                var tag = firstTag
                if (firstTag and 0x1F == 0x1F) {
                    var count = 0
                    do {
                        if (offset >= end || ++count > 3) return
                        val next = data[offset++].toInt() and 0xFF
                        tag = (tag shl 8) or next
                    } while (next and 0x80 != 0)
                }
                if (offset >= end) return
                var length = data[offset++].toInt() and 0xFF
                if (length and 0x80 != 0) {
                    val bytes = length and 0x7F
                    if (bytes !in 1..3 || offset + bytes > end) return
                    length = 0
                    repeat(bytes) { length = (length shl 8) or (data[offset++].toInt() and 0xFF) }
                }
                if (length > end - offset) return
                val valueEnd = offset + length
                when {
                    tag == 0x4F && length in 5..16 -> aids.add(ApduUtil.bytesToHex(data.copyOfRange(offset, valueEnd)))
                    tag == 0x88 && length == 1 -> {
                        val sfi = data[offset].toInt() and 0xFF
                        if (sfi in 1..30) directorySfi = sfi
                    }
                    firstTag and 0x20 != 0 -> visit(offset, valueEnd, depth + 1)
                }
                offset = valueEnd
            }
        }
        visit(0, data.size, 0)
        return DirectoryEntries(aids.toList(), directorySfi)
    }

    /**
     * 遍历全部 SFI（0x01..0x1F），先试 READ BINARY，失败再试 READ RECORD。
     * 读到的有内容文件全部收进 raw_records（信息/统计/折扣等扇区都保留，供后续按需解析）；
     * 交易解析由 RecordDecoder 按 cardType 的交易 SFI 白名单过滤，不会误解析这些扇区。
     *
     * @param skipSfis 跳过的 SFI（交易区）：这些区由 collectTuWallet/collectFareZone 专门读取，
     *                 probe 再读一遍会让同一记录进 raw_records 两次，decodeCard 收到重复输入。
     */
    private fun probeAllFiles(
        log: MutableList<String>,
        collector: MutableList<RawRecord>,
        protocol: String,
        skipSfis: Set<Int> = emptySet(),
        startedAt: Long = nowMs()
    ) {
        for (sfi in 0x01..0x1F) {
            if (connectionError != null) break
            if (nowMs() - startedAt >= 600) {
                log.add("额外文件探测已达到 600 ms 预算，保留已读文件")
                break
            }
            if (sfi in skipSfis) continue
            collectAuxiliaryFile(sfi, protocol, log, collector, startedAt + 600)
        }
        log.add("PROBE 额外文件探测结束")
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
            if (connectionError != null) break
            try {
                val cmd = ApduUtil.buildReadRecord(sfi, recordNo, profile.tradeRecordLen)
                val resp = transceive(cmd)
                log.add("READ RECORD SFI=${sfi.toString(16).uppercase()} rec=$recordNo -> ${ApduUtil.bytesToHex(resp)}")
                if (!ApduUtil.isSuccess(resp)) break
                val data = ApduUtil.dataOnly(resp)
                if (data.size < 0x17) break
                collector.add(rawRecord(sfi, recordNo, protocol, ApduUtil.bytesToHex(data)))
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
        val selectAppy = selectApplication(appyAid, log)
        // ① 信息文件（PAY.APPY 上下文）：SFI=0x15, P2=0x40, Le=0x46
        val info = if (selectAppy != null) {
            readYctInfo(profile, log)
        } else {
            log.add("PAY.APPY 选择失败，跳过 YCT 信息文件读取")
            null
        }
        // ①.5 统计月份（PAY.APPY 上下文）：SFI=0x08 rec1（00 B2 01 44 16，22B）[3]年BCD+2000、[4]月BCD。
        //    LNT 交易记录无年份，用它做年份锚点（Trip Reader relYear 逻辑同源）
        var payMonth: Int? = null
        var statsRecord: RawRecord? = null
        try {
            val resp = transceive(ApduUtil.buildReadRecord(0x08, 1, 0x16))
            log.add("READ RECORD SFI=08 rec=1 (stats month) -> ${ApduUtil.bytesToHex(resp)}")
            val d = ApduUtil.dataOnly(resp)
            if (ApduUtil.isSuccess(resp) && d.size >= 5) {
                statsRecord = rawRecord(0x08, 1, "LNT", ApduUtil.bytesToHex(d))
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
        val selectTicl = selectApplication(ticlAid, log)
        if (selectTicl == null) {
            log.add("YCT 钱包应用 PAY.TICL 选择失败")
            return YctCardResult(info, null, payMonth, statsRecord = statsRecord)
        }
        // ③ BALANCE CHECK 查余额（必须在 TICL 上下文）；失败（respHex=null）→ 余额未知为 null
        val balance = readBalance(profile, log)
        val balanceFen: Long? = if (balance.respHex != null) balance.fen else null
        return YctCardResult(
            info, balanceFen, payMonth,
            balance.respHex, statsRecord, walletSelected = true
        )
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
                val resp = transceive(ApduUtil.hexToBytes(cmdHex))
                log.add("READ BINARY (YCT info) $cmdHex -> ${ApduUtil.bytesToHex(resp)}")
                if (!ApduUtil.isSuccess(resp)) continue
                best = parseYctInfo(profile.name, resp, cmdHex, log)
                if (best != null) break
            }
            if (best == null) {
                // 若 READ BINARY 全部失败，可能 SFI 0x15 是循环/变长记录文件，改用 READ RECORD
                for (recordNo in 1..30) {
                    val resp = transceive(ApduUtil.buildReadRecord(0x15, recordNo, 0x00))
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
        if (data.size < 16) return null
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
        val issuerCode = if (data.size >= 52) {
            ApduUtil.bytesToHex(data.copyOfRange(48, 52))
                .takeIf { it.any { ch -> ch != '0' } }
        } else null
        val info = CardInfo(cardName, validFrom, validTo, cardNumber, hex, issuerCode)
        log.add("READ YCT info 命中 $label, 卡号=$cardNumber, 发卡机构码=${issuerCode ?: "未知"}, 发卡=$validFrom, 到期=$validTo, 共${data.size}字节")
        return info
    }

    /** 通用（CU/SZT/TFT/苏州/TU）信息文件：READ BINARY SFI=0x15，卡号 [10..20) BCD */
    private fun readCuInfo(profile: CardProfile, log: MutableList<String>): CardInfo? {
        return try {
            val cmd = ApduUtil.buildReadBinary(profile.infoSfi, 0, 0x00)
            val resp = transceive(cmd)
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
            // 应用序列号（卡号）：SZT 使用 4 字节倒序规则，CU 使用 8 字节 HEX，其他卡种使用 10 字节 BCD
            val cardNumber = if (data.size >= 20) {
                when (profile.cardType) {
                    "SZT" -> parseSztCardNumber(data)
                    "CU" -> parseCuCardNumber(data)
                    else -> ApduUtil.bcdToString(data.copyOfRange(10, 20)).trimStart('0')
                }
            } else ""
            CardInfo(profile.name, validFrom, validTo, cardNumber, hex)
        } catch (e: Exception) {
            log.add("读取信息文件异常: ${e.message}")
            null
        }
    }

    private fun addInfoRecord(
        collector: MutableList<RawRecord>,
        info: CardInfo?,
        protocol: String,
        selectedAid: String
    ) {
        if (info == null || info.rawHex.isBlank()) return
        collector.removeAll { it.sfi == 0x15 && it.recNo == 0 && it.selectedAid == selectedAid }
        collector.add(RawRecord(0x15, 0, protocol, info.rawHex, selectedAid))
    }

    /**
     * BALANCE CHECK（PBOC 电子钱包）查余额：
     *   `80 5C 00 02 04`，响应体内联余额。
     * 实际响应（如岭南通 `00 00 0F 00`）：首字节 0x00 为状态字节，[1..] 为大端 HEX 余额（分）
     *   → 0x0F00 = 3840 分 = ¥38.40。失败/无余额时返回 BalanceResult(0, null)（读卡链路降级，不中断交易读取）。
     */
    private fun readBalance(profile: CardProfile, log: MutableList<String>): BalanceResult {
        return try {
            val cmd = ApduUtil.hexToBytes("805C000204")
            val resp = transceive(cmd)
            log.add("BALANCE CHECK -> ${ApduUtil.bytesToHex(resp)}")
            if (!ApduUtil.isSuccess(resp)) return BalanceResult(0L, null)
            val data = ApduUtil.dataOnly(resp)
            if (data.size < 2) return BalanceResult(0L, ApduUtil.bytesToHex(data))
            // 跳过状态字节 [0]（0x00），[1..] 为大端 hex 余额（分）
            val fen = ApduUtil.hexToLong(data.copyOfRange(1, data.size))
            BalanceResult(fen, ApduUtil.bytesToHex(data))
        } catch (e: Exception) {
            log.add("BALANCE CHECK 异常: ${e.message}")
            BalanceResult(0L, null)
        }
    }

    /** BCD 半字节 → 十进制数（0x12 → 12） */
    private fun bcdNibble(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return (v shr 4) * 10 + (v and 0x0F)
    }
}
