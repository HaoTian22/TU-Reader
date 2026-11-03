package com.example.nfctransit.data

import android.content.Context
import android.os.SystemClock
import com.example.nfctransit.data.prefs.AppPreferences

/**
 * 已发布（R2）transit.db / line_rules.json 版本与本地版本的快照，供反馈表单同步判断能否公开上传：
 * 本地数据落后时禁止上传，避免按旧库反馈已修正的映射。
 * 进入详情页 / 映射列表页时后台 [refresh]；远端版本取不到（网络失败、规则 format 不受支持）时该项不阻止。
 */
object PublishedVersions {

    private const val REMOTE_TTL_MS = 5 * 60_000L

    @Volatile private var remoteDb: String? = null
    @Volatile private var remoteRules: String? = null
    @Volatile private var fetchedAt = 0L
    @Volatile private var localDb: String? = null

    /** 阻塞网络调用，须在 IO 线程执行。本地库版本每次重读；远端版本 5 分钟内复用。 */
    suspend fun refresh(context: Context) {
        localDb = AppPreferences.getDbVersion(context)
        val now = SystemClock.elapsedRealtime()
        if (fetchedAt != 0L && now - fetchedAt < REMOTE_TTL_MS) return
        StationDbUpdater.downloadVersionString(context)?.let { remoteDb = it }
        LineRulesStore.remoteVersion()?.let { remoteRules = it }
        fetchedAt = now
    }

    /** 本地库版本变化（在线更新 / 重置为内置库）时同步，避免更新后表单仍按旧版本禁止上传。 */
    fun noteLocalDb(version: String) {
        localDb = version
    }

    enum class Outdated { DB, RULES, BOTH }

    /** 本地落后于已发布版本时返回落后项；本地库版本未知（"0"）视为落后。 */
    fun outdated(): Outdated? {
        val db = remoteDb?.let { TransitDbVersion.isNewer(it, localDb) } == true
        val rules = remoteRules?.let { it > TuLineRules.installedVersion() } == true
        return when {
            db && rules -> Outdated.BOTH
            db -> Outdated.DB
            rules -> Outdated.RULES
            else -> null
        }
    }
}
