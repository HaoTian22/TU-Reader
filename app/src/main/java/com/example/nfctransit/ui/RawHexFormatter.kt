package com.example.nfctransit.ui

import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import com.example.nfctransit.ApduUtil
import com.example.nfctransit.data.RawRecord
import com.example.nfctransit.data.toSfiHex

object RawHexFormatter {
    const val DIM = 0xFF666688.toInt()
    const val RAW = 0xFFAAAAFF.toInt()
    const val TYPE = 0xFFFFC96B.toInt()
    const val RECORD = 0xFF7EE787.toInt()
    const val TERMINAL = 0xFF79C0FF.toInt()
    const val TIMESTAMP = 0xFFD2A8FF.toInt()
    const val SUBTYPE = 0xFFF79A9A.toInt()
    const val AMOUNT = 0xFFFF6B6B.toInt()
    const val LINE = 0xFFE6EE9C.toInt()
    const val BALANCE = 0xFF56D4A0.toInt()
    const val AREA = 0xFF4DD0E1.toInt()
    const val INSTITUTION = 0xFFFF9E5E.toInt()
    const val CARD_NUMBER = 0xFFB39DDB.toInt()
    const val ISSUE_DATE = 0xFF80CBC4.toInt()
    const val VALID_UNTIL = 0xFFFFB74D.toInt()
    const val ORIGINAL_FARE = 0xFFBA68C8.toInt()
    const val SELECT_AID = 0xFF00BFA5.toInt()
    const val SELECT_LABEL = 0xFFFFD54F.toInt()
    const val SELECT_TEMPLATE = 0xFFCE93D8.toInt()
    const val SELECT_FCI = 0xFF90CAF9.toInt()
    const val SELECT_RECORD = 0xFF81D4FA.toInt()
    const val SELECT_SCRIPT = 0xFFFF8A65.toInt()
    const val SELECT_SCRIPT_2 = 0xFFA5D6A7.toInt()
    const val SELECT_DISCRETIONARY = 0xFFB0BEC5.toInt()
    const val SELECT_DF = 0xFF80DEEA.toInt()
    const val SELECT_PRIORITY = 0xFFE57373.toInt()
    const val SELECT_SFI = 0xFF9575CD.toInt()
    const val SELECT_ISSUER = 0xFFFFAB91.toInt()
    const val SELECT_PREFERRED = 0xFFA1887F.toInt()
    const val SELECT_PDOL = 0xFF4DB6AC.toInt()
    const val SELECT_LOG = 0xFF64B5F6.toInt()
    const val SELECT_THIRD = 0xFFDCE775.toInt()
    const val SELECT_UNKNOWN = 0xFFBDBDBD.toInt()
    const val LEGEND_TEXT = 0xFFB8B8D0.toInt()

    private val selectTagNames = mapOf(
        "4F" to "Application Identifier",
        "50" to "Application Label",
        "61" to "Application Template",
        "6F" to "FCI Template",
        "70" to "Record Template",
        "71" to "Issuer Script Template",
        "72" to "Issuer Script Template 2",
        "73" to "Directory Discretionary Template",
        "84" to "Dedicated File Name",
        "87" to "Application Priority",
        "88" to "Short File Identifier",
        "9F11" to "Issuer Code Table Index",
        "9F12" to "Preferred Name",
        "9F38" to "Processing Options Data Object List",
        "9F4D" to "Log Entry",
        "9F6E" to "Third Party Data"
    )

    private fun selectColor(tag: String): Int = when (tag) {
        "4F" -> SELECT_AID
        "50" -> SELECT_LABEL
        "61" -> SELECT_TEMPLATE
        "6F" -> SELECT_FCI
        "70" -> SELECT_RECORD
        "71" -> SELECT_SCRIPT
        "72" -> SELECT_SCRIPT_2
        "73" -> SELECT_DISCRETIONARY
        "84" -> SELECT_DF
        "87" -> SELECT_PRIORITY
        "88" -> SELECT_SFI
        "9F11" -> SELECT_ISSUER
        "9F12" -> SELECT_PREFERRED
        "9F38" -> SELECT_PDOL
        "9F4D" -> SELECT_LOG
        "9F6E" -> SELECT_THIRD
        else -> SELECT_UNKNOWN
    }

    /** SELECT 响应中的 BER-TLV 字段，位置均以字节为单位。 */
    fun fieldsForSelectResponse(hex: String): List<FieldSpec> {
        val bytes = runCatching { ApduUtil.hexToBytes(hex) }.getOrNull() ?: return emptyList()
        if (bytes.isEmpty()) return emptyList()
        val fields = mutableListOf<FieldSpec>()
        parseSelectTlv(bytes, 0, bytes.size, fields)
        return fields
    }

    private fun parseSelectTlv(
        bytes: ByteArray,
        start: Int,
        limit: Int,
        fields: MutableList<FieldSpec>
    ) {
        var cursor = start
        while (cursor < limit) {
            val tagStart = cursor
            val firstTagByte = bytes[cursor].toInt() and 0xFF
            cursor++
            if ((firstTagByte and 0x1F) == 0x1F) {
                while (cursor < limit) {
                    val tagByte = bytes[cursor++].toInt() and 0xFF
                    if ((tagByte and 0x80) == 0) break
                }
            }
            if (cursor > limit) return
            val tag = bytes.copyOfRange(tagStart, cursor).joinToString("") {
                "%02X".format(it.toInt() and 0xFF)
            }
            if (cursor >= limit) return
            val lengthByte = bytes[cursor++].toInt() and 0xFF
            val length = when {
                lengthByte < 0x80 -> lengthByte
                lengthByte == 0x81 && cursor < limit -> bytes[cursor++].toInt() and 0xFF
                lengthByte == 0x82 && cursor + 1 < limit -> {
                    val value = ((bytes[cursor].toInt() and 0xFF) shl 8) or
                        (bytes[cursor + 1].toInt() and 0xFF)
                    cursor += 2
                    value
                }
                else -> return
            }
            val valueStart = cursor
            val valueEnd = valueStart + length
            if (valueEnd > limit) return
            val label = selectTagNames[tag] ?: "TLV $tag"
            fields += FieldSpec(label, tagStart, valueEnd, "TLV", selectColor(tag))
            if ((firstTagByte and 0x20) != 0 || tag in setOf("6F", "61", "70", "71", "72", "73", "A5")) {
                parseSelectTlv(bytes, valueStart, valueEnd, fields)
            }
            cursor = valueEnd
        }
    }

    data class FieldSpec(
        val label: String,
        val start: Int,
        val end: Int,
        val method: String,
        val color: Int
    )

    fun fieldsFor(sfi: Int, size: Int, protocol: String): List<FieldSpec> {
        if (sfi == 0x15 && protocol == "LNT" && size >= 32) {
            return listOf(
                FieldSpec("Card Number", 11, 16, "BCD", CARD_NUMBER),
                FieldSpec("Issue Date", 23, 27, "BCD", ISSUE_DATE),
                FieldSpec("Valid Until", 27, 31, "BCD", VALID_UNTIL),
                FieldSpec("Institution", 48, 52, "hex", INSTITUTION).takeIf { size >= 52 }
            ).filterNotNull()
        }
        if (sfi == 0x15 && size >= 28) {
            return listOf(
                FieldSpec("Card Number", 10, 20, "BCD", CARD_NUMBER),
                FieldSpec("Issue Date", 20, 24, "BCD", ISSUE_DATE),
                FieldSpec("Valid Until", 24, 28, "BCD", VALID_UNTIL)
            )
        }
        if (sfi == 0x1E && size >= 42) {
            return listOf(
                FieldSpec("Type", 0, 1, "hex", TYPE),
                FieldSpec("Terminal", 1, 9, "BCD", TERMINAL),
                FieldSpec("Subtype", 9, 10, "hex", SUBTYPE),
                FieldSpec("Line & Station", 10, 17, "", LINE),
                FieldSpec("Amount", 19, 21, "hex", AMOUNT),
                FieldSpec("Balance", 21, 25, "hex", BALANCE),
                FieldSpec("Timestamp", 25, 32, "BCD", TIMESTAMP),
                FieldSpec("Area", 32, 34, "BCD", AREA),
                FieldSpec("Institution", 34, 42, "hex", INSTITUTION)
            )
        }
        if (sfi == 0x18 && size >= 23 && protocol == "LNT") {
            return listOf(
                FieldSpec("Record No.", 0, 2, "dec", RECORD),
                FieldSpec("Amount", 6, 9, "hex", AMOUNT),
                FieldSpec("Type", 9, 10, "hex", TYPE),
                FieldSpec("Terminal", 10, 16, "BCD", TERMINAL),
                FieldSpec("Original Fare", 16, 18, "hex", ORIGINAL_FARE),
                FieldSpec("Timestamp", 18, 22, "BCD", TIMESTAMP),
                FieldSpec("Subtype", 22, 23, "hex", SUBTYPE)
            )
        }
        if (sfi == 0x18 && size >= 23) {
            return listOf(
                FieldSpec("Record No.", 0, 2, "dec", RECORD),
                FieldSpec("Amount", 6, 9, "hex", AMOUNT),
                FieldSpec("Type", 9, 10, "hex", TYPE),
                FieldSpec("Terminal", 10, 16, "BCD", TERMINAL),
                FieldSpec("Timestamp", 16, 23, "BCD", TIMESTAMP)
            )
        }
        return emptyList()
    }

    fun colorizeHex(hex: String, fields: List<FieldSpec>): SpannableString {
        val sp = SpannableString(hex)
        val byteCount = hex.count { !it.isWhitespace() } / 2
        if (byteCount == 0) return sp
        val byteStartChar = IntArray(byteCount + 1)
        var byteIdx = 0
        var charIdx = 0
        while (byteIdx < byteCount) {
            while (charIdx < hex.length && hex[charIdx].isWhitespace()) charIdx++
            byteStartChar[byteIdx] = charIdx
            charIdx += 2
            byteIdx++
        }
        byteStartChar[byteCount] = hex.length
        for (field in fields) {
            val start = field.start.coerceIn(0, byteCount)
            val end = field.end.coerceIn(start, byteCount)
            if (start < end) {
                sp.setSpan(
                    ForegroundColorSpan(field.color),
                    byteStartChar[start],
                    byteStartChar[end],
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return sp
    }

    fun header(record: RawRecord): String {
        val protocol = record.protocol.ifBlank { "GEN" }
        return "SFI ${record.sfi.toSfiHex()} · $protocol · Rec ${record.recNo}"
    }

    fun rangeText(start: Int, end: Int): String =
        if (end - start == 1) "$start" else "$start-${end - 1}"

    fun hexRange(hex: String, start: Int, end: Int): String {
        val compact = hex.filterNot { it.isWhitespace() }
        val from = (start * 2).coerceIn(0, compact.length)
        val to = (end * 2).coerceIn(from, compact.length)
        return compact.substring(from, to)
    }

    fun copyText(records: List<RawRecord>): String {
        val out = StringBuilder()
        for (record in records) {
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(header(record)).append('\n').append(record.hex)
            val fields = fieldsFor(record.sfi, runCatching { ApduUtil.hexToBytes(record.hex).size }.getOrDefault(0), record.protocol)
            for (field in fields) {
                val method = field.method.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
                out.append('\n').append("[${field.label} ${rangeText(field.start, field.end)}$method] ")
                    .append(hexRange(record.hex, field.start, field.end))
            }
        }
        return out.toString()
    }
}
