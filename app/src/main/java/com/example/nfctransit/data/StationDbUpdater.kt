package com.example.nfctransit.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 站名映射表（transit.db）在线更新：从固定 HTTPS 地址下载到缓存目录，供后续校验替换 */
object StationDbUpdater {

    const val DOWNLOAD_URL = "https://assets2.haotian22.top/transit.db"
    const val DOWNLOAD_VERSION_URL = "https://assets2.haotian22.top/transit.db.version"

    /** 下载产物：临时库文件 + 服务端 Last-Modified（epoch 毫秒，无则 null）。 */
    data class DownloadedDb(val file: File, val lastModifiedMillis: Long?)

    /**
     * 下载最新 transit.db 到应用缓存目录（调用方负责删除临时文件）。
     * 仅保证非空与 HTTP 200；是否与当前 Room schema 兼容由 AppDatabase.replaceWithDownloaded 校验。
     */
    fun download(context: Context, url: String = DOWNLOAD_URL): DownloadedDb {
        val tmp = File(context.cacheDir, "transit_download_${System.currentTimeMillis()}.db")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            connection.connect()
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $code")
            }
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            if (tmp.length() == 0L) throw IOException("下载内容为空")
            val lastModified = connection.getHeaderFieldDate("Last-Modified", 0L)
            return DownloadedDb(tmp, lastModified.takeIf { it > 0 })
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 尽力下载版本 sidecar 内容；HTTP 非 200 / 异常 / 空白 → null（不抛，调用方做版本回退）。
     */
    fun downloadVersionString(context: Context, url: String = DOWNLOAD_VERSION_URL): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        return try {
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { input ->
                input.readBytes().toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
