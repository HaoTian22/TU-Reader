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

    /**
     * 下载最新 transit.db 到应用缓存目录，返回临时文件（调用方负责删除）。
     * 仅保证非空与 HTTP 200；是否与当前 Room schema 兼容由 AppDatabase.replaceWithDownloaded 校验。
     */
    fun download(context: Context, url: String = DOWNLOAD_URL): File {
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
        } finally {
            connection.disconnect()
        }
        return tmp
    }
}
