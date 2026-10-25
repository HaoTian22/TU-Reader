package com.example.nfctransit.data

import android.content.Context
import com.example.nfctransit.BuildConfig
import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.nfctransit.R
import com.example.nfctransit.util.L10n

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
    ): String {
        val endpoint = BuildConfig.FEEDBACK_UPLOAD_URL.trim()
        if (endpoint.isEmpty()) return L10n.str(R.string.upload_not_configured)
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
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            when (val code = connection.responseCode) {
                in 200..299 -> L10n.str(R.string.upload_done)
                else -> L10n.str(R.string.upload_failed_http, code)
            }
        } catch (e: Exception) {
            L10n.str(R.string.upload_failed, e.message ?: L10n.str(R.string.network_error))
        } finally {
            connection.disconnect()
        }
    }
}
