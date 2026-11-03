package com.example.nfctransit.data

import android.content.Context
import android.util.Log
import com.example.nfctransit.BuildConfig
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * 服务端下发的反馈策略（`GET /v1/feedback-policy`，与上传端点同目录）。
 * - [publish]：公开上传总开关，false 时所有交易都只能本地保存 / 去 GitHub 反馈
 * - [imported]：是否允许导入卡片的记录公开上传
 * - [terminalMismatch]：是否允许终端号与钱包类型不符的记录公开上传
 * - [outdated]：是否允许本机站名库 / 线路规则落后于已发布版本时公开上传（见 [PublishedVersions]）
 * 拉取失败时沿用上次缓存，从未拉到时用 [DEFAULT]（与服务端默认一致）。
 */
data class FeedbackPolicy(
    val publish: Boolean,
    val imported: Boolean,
    val terminalMismatch: Boolean,
    val outdated: Boolean = false
) {
    companion object {
        val DEFAULT = FeedbackPolicy(publish = true, imported = false, terminalMismatch = false, outdated = false)

        private const val PREFS = "feedback_policy"
        private const val KEY_JSON = "json"

        @Volatile
        private var cached: FeedbackPolicy? = null

        /** 当前策略（内存 → SharedPreferences → 默认值） */
        fun current(context: Context): FeedbackPolicy {
            cached?.let { return it }
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null)
            return (stored?.let { runCatching { parse(it) }.getOrNull() } ?: DEFAULT).also { cached = it }
        }

        /** 拉取服务端策略并缓存；阻塞网络调用，须在 IO 线程执行。失败时保持原缓存。 */
        fun refresh(context: Context) {
            val url = policyUrl() ?: return
            val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) {
                    Log.w("FeedbackPolicy", "policy HTTP ${connection.responseCode}")
                    return
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val policy = parse(body)
                cached = policy
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_JSON, body).apply()
            } catch (e: Exception) {
                Log.w("FeedbackPolicy", "policy fetch failed", e)
            } finally {
                connection.disconnect()
            }
        }

        /** 缺失或非布尔的键取默认值，便于服务端日后增删键 */
        internal fun parse(json: String): FeedbackPolicy {
            val o = JsonParser.parseString(json).asJsonObject
            fun flag(key: String, default: Boolean): Boolean {
                val v = o.get(key)
                return if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isBoolean) v.asBoolean else default
            }
            return FeedbackPolicy(
                publish = flag("publish", DEFAULT.publish),
                imported = flag("imported", DEFAULT.imported),
                terminalMismatch = flag("terminalMismatch", DEFAULT.terminalMismatch),
                outdated = flag("outdated", DEFAULT.outdated)
            )
        }

        /** 由上传端点推导：…/v1/overrides → …/v1/feedback-policy */
        internal fun policyUrl(endpoint: String = BuildConfig.FEEDBACK_UPLOAD_URL): String? {
            val trimmed = endpoint.trim().trimEnd('/')
            if (trimmed.isEmpty() || !trimmed.contains('/')) return null
            return trimmed.substringBeforeLast('/') + "/feedback-policy"
        }
    }
}
