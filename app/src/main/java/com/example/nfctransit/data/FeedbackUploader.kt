package com.example.nfctransit.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.nfctransit.BuildConfig
import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.nfctransit.R
import com.example.nfctransit.util.L10n

/** 上传结果：提示文案 + 是否成功（决定提示条样式） */
data class UploadResult(val message: String, val ok: Boolean)

object FeedbackUploader {
    private val gson = Gson()

    private data class Payload(
        val prefix: String,
        val code: String,
        val type: String,
        val standard: String,
        val line: String,
        val station: String,
        val locationCityCode: String? = null,
        val locationCityName: String? = null,
        val locationSource: FeedbackLocationSource,
        val rawRecord: String? = null,
        /** 提交时间：手机本地时间 + 时区偏移（ISO-8601，如 2026-10-25T10:42:00+08:00） */
        val submittedAt: String
    )

    fun upload(
        context: Context,
        row: TransitOverrideRow,
        type: String,
        standard: String,
        locationCityCode: String? = null,
        locationCityName: String? = null,
        locationSource: FeedbackLocationSource,
        rawRecord: String? = null
    ): UploadResult {
        val endpoint = BuildConfig.FEEDBACK_UPLOAD_URL.trim()
        if (endpoint.isEmpty()) return UploadResult(L10n.str(R.string.upload_not_configured), false)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val body = gson.toJson(
                Payload(
                    row.prefix,
                    row.code,
                    type,
                    standard,
                    row.line,
                    row.station,
                    locationCityCode,
                    locationCityName,
                    locationSource,
                    rawRecord,
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
                )
            )
            val startedAt = SystemClock.elapsedRealtime()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            Log.d("OverrideSave", "upload HTTP $code: ${SystemClock.elapsedRealtime() - startedAt} ms, ${body.length} chars")
            when (code) {
                in 200..299 -> UploadResult(L10n.str(R.string.upload_done), true)
                else -> {
                    val detail = runCatching {
                        connection.errorStream?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                    Log.w("OverrideSave", "upload failed HTTP $code: $detail")
                    UploadResult(L10n.str(R.string.upload_failed_http, code), false)
                }
            }
        } catch (e: Exception) {
            Log.w("OverrideSave", "upload failed", e)
            UploadResult(L10n.str(R.string.upload_failed, e.message ?: L10n.str(R.string.network_error)), false)
        } finally {
            connection.disconnect()
        }
    }
}
