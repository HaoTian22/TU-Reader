package com.example.nfctransit

import android.nfc.tech.NfcF
import com.example.nfctransit.data.RawRecord

/** FeliCa 通信抽象（与 [CardChannel] 同理，便于测试）。 */
internal interface FelicaChannel {
    var timeout: Int
    fun connect()
    fun transceive(command: ByteArray): ByteArray
    fun close()
}

internal class NfcFChannel(private val nfcF: NfcF) : FelicaChannel {
    override var timeout: Int
        get() = nfcF.timeout
        set(value) { nfcF.timeout = value }
    override fun connect() = nfcF.connect()
    override fun transceive(command: ByteArray): ByteArray = nfcF.transceive(command)
    override fun close() = nfcF.close()
}

/**
 * 八达通（Octopus，FeliCa 系统码 0x8008）读取：
 * 1. Polling 选中系统 8008，取该系统的 IDm（卡身份；印刷卡号不可读）
 * 2. Read Without Encryption 服务 0x0117 块 0：前 4 字节大端 = 余额 + 偏移，单位 0.1 HKD
 *    偏移 500（HK$50，2017-10-01 起全系统负值上限；之前为 350）
 * 交易记录服务需密钥，无法读取——八达通只有余额与 IDm。
 */
class OctopusReader internal constructor(private val channel: FelicaChannel) {
    constructor(nfcF: NfcF) : this(NfcFChannel(nfcF))

    fun read(): TransitCardReader.ReadResult {
        val log = mutableListOf<String>()
        var readError: String? = null
        try {
            channel.connect()
            channel.timeout = 1000
            val polling = byteArrayOf(0x06, 0x00, 0x80.toByte(), 0x08, 0x01, 0x00)
            val pollResp = channel.transceive(polling)
            log.add("FELICA POLLING 8008 -> ${ApduUtil.bytesToHex(pollResp)}")
            // [len][01][IDm 8][PMm 8][系统码 2]
            if (pollResp.size < 18 || pollResp[1] != 0x01.toByte()) {
                return TransitCardReader.ReadResult(null, null, rawLog = log)
            }
            val idm = pollResp.copyOfRange(2, 10)
            val idmHex = ApduUtil.bytesToHex(idm)

            val block = readBlock(idm, BALANCE_SERVICE, 0, log)
            val balanceFen = block?.let {
                (ApduUtil.hexToLong(it.copyOfRange(0, 4)) - BALANCE_OFFSET) *
                    CardProfiles.centsPerUnit(CardProfiles.OCTOPUS.cardType)
            }
            val blockHex = block?.let(ApduUtil::bytesToHex)
            val profile = CardProfiles.OCTOPUS
            // 互通行等 FeliCa 双钱包卡的深圳通钱包服务：格式未经实卡验证，只作原始数据保存
            val sztBlock = try { readBlock(idm, SZT_SERVICE, 0, log) } catch (e: Exception) {
                log.add("FELICA READ 0118 异常: ${e.message}"); null
            }
            val raw = listOfNotNull(
                blockHex?.let { RawRecord(BALANCE_SERVICE, 0, profile.cardType, it, SYSTEM_CODE) },
                sztBlock?.let { RawRecord(SZT_SERVICE, 0, profile.cardType, ApduUtil.bytesToHex(it), SYSTEM_CODE) }
            )
            val app = TransitCardReader.AppRead(SYSTEM_CODE, ApduUtil.bytesToHex(pollResp), balanceFen, blockHex)
            log.add("八达通: IDm=$idmHex, 余额=${balanceFen ?: "未知"}")
            return TransitCardReader.ReadResult(
                profile, CardInfo(profile.name, "未知", "未知", idmHex, ""), balanceFen,
                rawRecords = raw, rawLog = log, appReads = listOf(app)
            )
        } catch (e: Exception) {
            log.add("异常: ${e.message}")
            readError = e.message ?: e.javaClass.simpleName
        } finally {
            try { channel.close() } catch (_: Exception) {}
        }
        return TransitCardReader.ReadResult(null, null, rawLog = log, readError = readError)
    }

    /** Read Without Encryption：单服务单块；状态非 00/00 返回 null。 */
    private fun readBlock(idm: ByteArray, service: Int, blockNo: Int, log: MutableList<String>): ByteArray? {
        val body = byteArrayOf(0x06) + idm + byteArrayOf(
            0x01, (service and 0xFF).toByte(), (service shr 8).toByte(),  // 服务码小端
            0x01, 0x80.toByte(), blockNo.toByte()                         // 2 字节块列表元素
        )
        val response = channel.transceive(byteArrayOf((body.size + 1).toByte()) + body)
        log.add("FELICA READ service=${"%04X".format(service)} block=$blockNo -> ${ApduUtil.bytesToHex(response)}")
        // [len][07][IDm 8][status1][status2][块数][16 字节]
        if (response.size < 29 || response[1] != 0x07.toByte() || response[10] != 0.toByte() || response[11] != 0.toByte()) {
            return null
        }
        return response.copyOfRange(13, 29)
    }

    companion object {
        const val SYSTEM_CODE = "8008"
        private const val BALANCE_SERVICE = 0x0117
        private const val SZT_SERVICE = 0x0118

        /** 八达通 Polling 响应 [len][01][IDm 8]… 中的 IDm（card_app.select_resp 保存该响应）。 */
        fun idmFromPolling(pollingHex: String): String? =
            pollingHex.takeIf { it.length >= 20 }?.substring(4, 20)

        /**
         * 八达通联名卡（八达通 + TU / 八达通 + 深圳通 + TU 等）：同一张卡的 ISO-DEP 与 FeliCa 两次读取合并为一次。
         * ISO 侧有已知卡型时作主卡（交易/余额/卡号），八达通作附加应用；否则八达通作主卡，ISO 侧只保留原始数据。
         */
        fun merge(iso: TransitCardReader.ReadResult?, felica: TransitCardReader.ReadResult?): TransitCardReader.ReadResult? {
            val isoKnown = iso?.matchedProfile?.takeIf { it.cardType != CardProfiles.UNKNOWN.cardType } != null
            val primary = when {
                isoKnown -> iso
                felica?.matchedProfile != null -> felica
                else -> iso
            } ?: return felica
            val other = (if (primary === iso) felica else iso) ?: return primary
            return primary.copy(
                rawRecords = primary.rawRecords + other.rawRecords,
                appReads = primary.appReads + other.appReads.filter { app -> primary.appReads.none { it.selectedAid == app.selectedAid } },
                rawLog = primary.rawLog + "── 联名卡另一接口 ──" + other.rawLog
            )
        }
        const val BALANCE_OFFSET = 500L
    }
}
