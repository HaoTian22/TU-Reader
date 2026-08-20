package com.example.nfctransit.data

import android.content.Context
import com.example.nfctransit.BuildConfig
import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL

object FeedbackUploader {
    private val gson = Gson()

    private data class Payload(
        val prefix: String,
        val code: String,
        val type: String,
        val standard: String,
        val line: String,
        val station: String
    )

    fun upload(
        context: Context,
        row: TransitOverrideRow,
        type: String,
        standard: String
    ): String {
        val endpoint = BuildConfig.FEEDBACK_UPLOAD_URL.trim()
        if (endpoint.isEmpty()) return "未配置公开上传地址"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val body = gson.toJson(Payload(row.prefix, row.code, type, standard, row.line, row.station))
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            when (val code = connection.responseCode) {
                in 200..299 -> "公开纠错已上传"
                else -> "公开上传失败（HTTP $code）"
            }
        } catch (e: Exception) {
            "公开上传失败：${e.message ?: "网络错误"}"
        } finally {
            connection.disconnect()
        }
    }
}
