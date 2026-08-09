package com.example.nfctransit

/**
 * APDU 命令构造与十六进制工具类
 * 参考 ISO/IEC 7816-4 及 NFC Wiki 智能卡手册
 */
object ApduUtil {

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4)
                    + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02X", b))
        return sb.toString()
    }

    /** 构造 SELECT 命令 (CLA=00, INS=A4, P1=04选DF名称, P2=00) */
    fun buildSelectByName(aidHex: String): ByteArray {
        val aid = hexToBytes(aidHex)
        val header = hexToBytes("00A40400")
        return header + aid.size.toByte() + aid + byteArrayOf(0x00)
    }

    /** 构造 READ RECORD 命令 (CLA=00, INS=B2, P1=记录号, P2=(SFI<<3)|0x04, Le) */
    fun buildReadRecord(sfi: Int, recordNo: Int, le: Int = 0x00): ByteArray {
        val p2 = (sfi shl 3) or 0x04
        return hexToBytes("00B2") +
                byteArrayOf(recordNo.toByte(), p2.toByte(), le.toByte())
    }

    /** 构造 READ BINARY 命令，用于读取二进制文件 (使用短文件标识寻址) */
    fun buildReadBinary(sfi: Int, offset: Int = 0, le: Int = 0x00): ByteArray {
        val p1 = 0x80 or (sfi and 0x1F)
        return hexToBytes("00B0") +
                byteArrayOf(p1.toByte(), offset.toByte(), le.toByte())
    }

    fun isSuccess(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2]
        val sw2 = response[response.size - 1]
        return sw1 == 0x90.toByte() && sw2 == 0x00.toByte()
    }

    /** 去掉末尾2字节状态码，返回纯数据 */
    fun dataOnly(response: ByteArray): ByteArray {
        if (response.size < 2) return ByteArray(0)
        return response.copyOfRange(0, response.size - 2)
    }

    /** BCD 转十进制字符串 */
    fun bcdToString(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val hi = (b.toInt() shr 4) and 0x0F
            val lo = b.toInt() and 0x0F
            sb.append(hi).append(lo)
        }
        return sb.toString()
    }

    /** 大端 HEX 字节数组转 Long */
    fun hexToLong(bytes: ByteArray): Long {
        var value = 0L
        for (b in bytes) {
            value = (value shl 8) or (b.toLong() and 0xFF)
        }
        return value
    }
}
