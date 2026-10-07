package com.example.nfctransit

/** Read-only BER-TLV and DOL decoding; malformed/truncated values are never sliced. */
object BerTlv {
    fun values(data: ByteArray): Map<Int, ByteArray> {
        val result = linkedMapOf<Int, ByteArray>()
        fun visit(start: Int, end: Int, depth: Int) {
            if (depth > 16) return
            var cursor = start
            while (cursor < end) {
                val first = data[cursor++].toInt() and 255
                if (first == 0 || first == 255) continue
                var tag = first
                if (first and 31 == 31) {
                    var count = 0
                    do {
                        if (cursor >= end || ++count > 3) return
                        val next = data[cursor++].toInt() and 255
                        tag = (tag shl 8) or next
                    } while (next and 128 != 0)
                }
                if (cursor >= end) return
                var length = data[cursor++].toInt() and 255
                if (length and 128 != 0) {
                    val count = length and 127
                    if (count !in 1..3 || cursor + count > end) return
                    length = 0
                    repeat(count) { length = (length shl 8) or (data[cursor++].toInt() and 255) }
                }
                if (length > end - cursor) return
                val valueEnd = cursor + length
                result.putIfAbsent(tag, data.copyOfRange(cursor, valueEnd))
                if (first and 32 != 0) visit(cursor, valueEnd, depth + 1)
                cursor = valueEnd
            }
        }
        visit(0, data.size, 0)
        return result
    }

    data class DolField(val tag: Int, val offset: Int, val length: Int)

    fun dol(data: ByteArray): List<DolField>? {
        val fields = mutableListOf<DolField>()
        var cursor = 0
        var offset = 0
        while (cursor < data.size) {
            val first = data[cursor++].toInt() and 255
            if (first == 0 || first == 255) return null
            var tag = first
            if (first and 31 == 31) {
                var count = 0
                do {
                    if (cursor >= data.size || ++count > 3) return null
                    val next = data[cursor++].toInt() and 255
                    tag = (tag shl 8) or next
                } while (next and 128 != 0)
            }
            if (cursor >= data.size) return null
            val length = data[cursor++].toInt() and 255
            fields.add(DolField(tag, offset, length))
            offset += length
        }
        return fields.takeIf { it.isNotEmpty() }
    }

    fun bcd(data: ByteArray): String? = ApduUtil.bytesToHex(data).takeIf { it.all(Char::isDigit) }
    fun pan(data: ByteArray): String? = ApduUtil.bytesToHex(data).trimEnd('F')
        .takeIf { it.length in 8..19 && it.all(Char::isDigit) }
}
