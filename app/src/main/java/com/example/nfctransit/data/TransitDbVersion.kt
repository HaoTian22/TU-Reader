package com.example.nfctransit.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * transit.db 版本工具：读取内置 sidecar、比较新旧、格式化时间戳。
 * 版本号用于决定「清理缓存」时是否重置库为内置版本，以及 UiCache 缓存是否失效。
 */
object TransitDbVersion {

    const val ASSET_VERSION_PATH = "data/transit.db.version"
    const val VERSION_PATTERN = "yyyyMMddHHmmss"

    /** 版本号是 UTC+8（北京时间），由 tools/update_transit_db.py 按该时区写入 */
    private val versionZone = TimeZone.getTimeZone("GMT+08:00")

    /** 读取 APK 内置版本 sidecar；缺失/空白/异常返回 null（旧 APK 无 sidecar）。 */
    fun readAssetVersion(context: Context): String? {
        return try {
            context.assets.open(ASSET_VERSION_PATH).use { input ->
                input.readBytes().toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 转 epoch 毫秒；null/长度不符/不可解析 → Long.MIN_VALUE（视为最旧）。 */
    fun toEpochMillis(version: String?): Long {
        if (version == null || version.length != VERSION_PATTERN.length) return Long.MIN_VALUE
        return try {
            SimpleDateFormat(VERSION_PATTERN, Locale.US).apply { timeZone = versionZone }
                .parse(version)?.time ?: Long.MIN_VALUE
        } catch (e: Exception) {
            Long.MIN_VALUE
        }
    }

    /** candidate 是否比 reference 新。 */
    fun isNewer(candidate: String?, reference: String?): Boolean =
        toEpochMillis(candidate) > toEpochMillis(reference)

    /** 版本号（UTC+8）→ 手机当前本地时区 "yyyy-MM-dd HH:mm"，用于设置页显示；无效/未知版本返回 null。 */
    fun toDisplayText(version: String?): String? {
        val millis = toEpochMillis(version)
        if (millis == Long.MIN_VALUE) return null
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
    }

    /** epoch 毫秒 → VERSION_PATTERN（UTC+8），用于 OTA 回退路径的版本发现。 */
    fun formatTimestamp(epochMillis: Long): String =
        SimpleDateFormat(VERSION_PATTERN, Locale.US).apply { timeZone = versionZone }.format(Date(epochMillis))
}
