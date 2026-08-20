package com.example.nfctransit.data.route

import com.google.gson.Gson
import java.io.File

internal const val ROUTE_CACHE_FORMAT_VERSION = 7
internal const val STATION_CACHE_FORMAT_VERSION = 1

/** 路线规划响应缓存；文件名中的 SHA-256 已包含请求参数和格式版本。 */
internal data class RouteCacheEntry(
    val status: String,
    val responseJson: String? = null,
    val isEstimate: Boolean = false,
    val fetchedAt: Long,
    val expiresAt: Long,
    val formatVersion: Int = ROUTE_CACHE_FORMAT_VERSION
) {
    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_NO_ROUTE = "NO_ROUTE"
    }
}

/** 腾讯站点搜索得到的坐标缓存。 */
internal data class StationCoordinateCacheEntry(
    val lat: Double,
    val lng: Double,
    val matchedTitle: String,
    val expiresAt: Long,
    val formatVersion: Int = STATION_CACHE_FORMAT_VERSION
)

/**
 * 可随时重建的地图路线缓存，存放于 cache/route_cache，不进入用户数据库或数据导出。
 * 所有异常都按缓存未命中处理，不能阻塞路线展示和网络降级逻辑。
 */
internal class RouteCacheStore(cacheDir: File) {

    private val directory = File(cacheDir, DIRECTORY_NAME)
    private val gson = Gson()

    fun loadRoute(cacheKey: String, now: Long = System.currentTimeMillis()): RouteCacheEntry? =
        read(routeFile(cacheKey)) { json ->
            val entry = gson.fromJson(json, RouteCacheEntry::class.java)
            when {
                entry.formatVersion != ROUTE_CACHE_FORMAT_VERSION -> null
                entry.status !in VALID_ROUTE_STATUSES -> null
                entry.fetchedAt < 0L || entry.expiresAt < entry.fetchedAt -> null
                entry.status == RouteCacheEntry.STATUS_SUCCESS && entry.responseJson.isNullOrBlank() -> null
                entry.status == RouteCacheEntry.STATUS_NO_ROUTE && entry.expiresAt < now -> null
                else -> entry
            }
        }

    fun saveRoute(cacheKey: String, entry: RouteCacheEntry) {
        if (!entry.isValidForWrite()) return
        write(routeFile(cacheKey), gson.toJson(entry))
    }

    fun loadStation(cacheKey: String, now: Long = System.currentTimeMillis()): StationCoordinateCacheEntry? =
        read(stationFile(cacheKey)) { json ->
            val entry = gson.fromJson(json, StationCoordinateCacheEntry::class.java)
            when {
                entry.formatVersion != STATION_CACHE_FORMAT_VERSION -> null
                !entry.lat.isFinite() || entry.lat !in -90.0..90.0 -> null
                !entry.lng.isFinite() || entry.lng !in -180.0..180.0 -> null
                entry.matchedTitle.isBlank() || entry.expiresAt < now -> null
                else -> entry
            }
        }

    fun saveStation(cacheKey: String, entry: StationCoordinateCacheEntry) {
        if (entry.formatVersion != STATION_CACHE_FORMAT_VERSION ||
            !entry.lat.isFinite() || entry.lat !in -90.0..90.0 ||
            !entry.lng.isFinite() || entry.lng !in -180.0..180.0 ||
            entry.matchedTitle.isBlank() || entry.expiresAt < 0L
        ) return
        write(stationFile(cacheKey), gson.toJson(entry))
    }

    fun clearAll() {
        runCatching {
            synchronized(FILE_LOCK) {
                directory.deleteRecursively()
            }
        }
    }

    private fun RouteCacheEntry.isValidForWrite(): Boolean =
        formatVersion == ROUTE_CACHE_FORMAT_VERSION &&
            status in VALID_ROUTE_STATUSES &&
            fetchedAt >= 0L && expiresAt >= fetchedAt &&
            (status != RouteCacheEntry.STATUS_SUCCESS || !responseJson.isNullOrBlank())

    /** 解析失败或校验不通过时删除坏文件，避免每次进入地图都重复解析。 */
    private fun <T> read(file: File?, parse: (String) -> T?): T? {
        if (file == null) return null
        return runCatching {
            synchronized(FILE_LOCK) {
                if (!file.exists()) return@synchronized null
                val parsed = runCatching { parse(file.readText(Charsets.UTF_8)) }.getOrNull()
                if (parsed == null) file.delete()
                parsed
            }
        }.getOrNull()
    }

    /** 同目录临时文件写完后再替换正式文件，避免留下半截 JSON。 */
    private fun write(file: File?, json: String) {
        if (file == null) return
        runCatching {
            synchronized(FILE_LOCK) {
                if (!directory.exists() && !directory.mkdirs()) return@synchronized
                val temporary = File(directory, ".${file.name}.${System.nanoTime()}.tmp")
                try {
                    temporary.writeText(json, Charsets.UTF_8)
                    if (file.exists() && !file.delete()) return@synchronized
                    if (!temporary.renameTo(file)) {
                        // renameTo 极少数设备失败时仍保证功能可用；缓存写入失败不会影响主流程。
                        file.writeText(json, Charsets.UTF_8)
                    }
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    private fun routeFile(cacheKey: String): File? = cacheFile("route", cacheKey)
    private fun stationFile(cacheKey: String): File? = cacheFile("station", cacheKey)

    private fun cacheFile(prefix: String, cacheKey: String): File? =
        cacheKey.takeIf(KEY_PATTERN::matches)?.let { File(directory, "${prefix}_${it}.json") }

    companion object {
        private const val DIRECTORY_NAME = "route_cache"
        private val KEY_PATTERN = Regex("[0-9a-f]{64}")
        private val VALID_ROUTE_STATUSES = setOf(
            RouteCacheEntry.STATUS_SUCCESS,
            RouteCacheEntry.STATUS_NO_ROUTE
        )
        private val FILE_LOCK = Any()
    }
}
