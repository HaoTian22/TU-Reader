package com.example.nfctransit.data

/** SFI → 十六进制字符串（"0x19"）——数据库 sfi 列以 hex 字符串存储，与 README/日志格式一致 */
fun Int.toSfiHex(): String = String.format("0x%02X", this)

/** "0x19" → Int SFI */
fun String.toSfiInt(): Int = removePrefix("0x").toInt(16)
