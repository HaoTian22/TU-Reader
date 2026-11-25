package com.example.nfctransit.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * `line_rules.json`（[TuLineRules] 的声明式线路规则）的加载与在线更新，与 transit.db 并列但独立发布：
 * 内置 `assets/data/line_rules.json`，在线版下载到 filesDir；加载时取 version 较新且可解析的一份，
 * 其中被丢弃的规则按 id 用内置版补回（[TuLineRules.RuleSet.withFallback]）；format 不符的文件整体忽略。
 */
object LineRulesStore {

    const val ASSET_PATH = "data/line_rules.json"
    const val DOWNLOAD_URL = "https://assets2.haotian22.top/line_rules.json"
    private const val FILE_NAME = "line_rules.json"
    private const val TAG = "LineRulesStore"

    /** 载入并安装规则（内置与已下载取较新者）；均不可用时安装空规则。 */
    fun load(context: Context) {
        val bundled = readAsset(context)?.let { parseOrNull(it, "asset") }
        // 已下载的文件不可用（旧版 format 等）时删除，之后只用内置版，直到下载到受支持的新文件
        val downloaded = File(context.filesDir, FILE_NAME).takeIf { it.isFile }
            ?.let { file -> parseOrNull(file.readText(), "downloaded") ?: null.also { file.delete() } }
        // 在线版样本不符被丢弃的规则按 id 回退到内置同 id 规则，坏的在线规则不会让广佛/深圳等整体失效
        val chosen = (listOfNotNull(bundled, downloaded).maxByOrNull { it.version } ?: TuLineRules.RuleSet.EMPTY)
            .withFallback(bundled)
        chosen.dropped.forEach { Log.w(TAG, "rule skipped: $it") }
        TuLineRules.install(chosen)
    }

    /** 下载在线规则；解析通过（格式受支持）才落盘到 filesDir，随后重新 [load]。失败抛异常，原文件不动。 */
    fun update(context: Context, url: String = DOWNLOAD_URL) {
        val text = download(url)
        TuLineRules.parse(text)  // 损坏 / format 不支持 → 抛异常，不落盘
        val dest = File(context.filesDir, FILE_NAME)
        val staged = File(context.filesDir, "$FILE_NAME.downloaded")
        staged.writeText(text)
        if (!staged.renameTo(dest)) {
            staged.copyTo(dest, overwrite = true)
            staged.delete()
        }
        load(context)
    }

    /**
     * 已发布规则的 version；下载失败或本 App 不支持其 format（无法更新到它）时返回 null。
     */
    fun remoteVersion(url: String = DOWNLOAD_URL): String? = try {
        TuLineRules.parse(download(url)).version.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "remote line_rules version unavailable", e)
        null
    }

    private fun parseOrNull(text: String, source: String): TuLineRules.RuleSet? = try {
        TuLineRules.parse(text)
    } catch (e: Exception) {
        Log.w(TAG, "$source line_rules unusable", e)
        null
    }

    private fun readAsset(context: Context): String? = try {
        context.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: IOException) {
        null
    }

    private fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            connection.connect()
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }
}
