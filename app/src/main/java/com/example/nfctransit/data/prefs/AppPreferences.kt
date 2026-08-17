package com.example.nfctransit.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.nfctransit.data.TransitDbVersion
import com.example.nfctransit.data.db.AppDatabase
import kotlinx.coroutines.flow.first

/**
 * 轻量 UI/设置状态（Preferences DataStore "transit_preferences"）。
 * 卡片/交易等业务数据不在此处——它们在 Room 用户库（UserDatabase）。
 */
object AppPreferences {

    private val Context.dataStore by preferencesDataStore("transit_preferences")

    private val KEY_SELECTED_CARD_ID = stringPreferencesKey("selected_card_id")
    private val KEY_CARD_ORDER = stringPreferencesKey("card_order")          // 逗号连接的 cardId 列表
    private val KEY_KEEP_DEBUG_LOGS = stringPreferencesKey("keep_debug_logs") // "true"/"false"
    private val KEY_SCHEMA_VERSION = intPreferencesKey("schema_version")
    private val KEY_DB_VERSION = stringPreferencesKey("db_version")           // 当前 databases/transit.db 的版本
    private val KEY_APP_INSTALL_MARKER = stringPreferencesKey("app_install_marker")
    const val SCHEMA_VERSION = 1

    // ── 读取（一次性，供启动）──

    suspend fun getSelectedCardId(context: Context): String? =
        context.dataStore.data.first()[KEY_SELECTED_CARD_ID]

    suspend fun getCardOrder(context: Context): List<String> {
        val raw = context.dataStore.data.first()[KEY_CARD_ORDER] ?: return emptyList()
        return raw.split(",").filter { it.isNotEmpty() }
    }

    suspend fun isKeepDebugLogs(context: Context): Boolean =
        context.dataStore.data.first()[KEY_KEEP_DEBUG_LOGS] != "false"  // 默认 true

    suspend fun getSchemaVersion(context: Context): Int =
        context.dataStore.data.first()[KEY_SCHEMA_VERSION] ?: 0

    /** 当前 databases/transit.db 的版本；未追踪/未知 → "0"（视为最旧）。 */
    suspend fun getDbVersion(context: Context): String =
        context.dataStore.data.first()[KEY_DB_VERSION] ?: "0"

    suspend fun setDbVersion(context: Context, version: String) {
        context.dataStore.edit { it[KEY_DB_VERSION] = version }
    }

    /** 返回 true 表示本次启动检测到新安装/升级，需要清理一次 UI 缓存。 */
    suspend fun markAppInstall(context: Context, marker: String): Boolean {
        var changed = false
        context.dataStore.edit { prefs ->
            if (prefs[KEY_APP_INSTALL_MARKER] != marker) {
                prefs[KEY_APP_INSTALL_MARKER] = marker
                changed = true
            }
        }
        return changed
    }

    /**
     * 首次进入版本追踪时初始化 db_version。
     * 若 databases/transit.db 已存在（老版本升级而来，可能是网络更新的库），标为 "0"（未知）
     * —— 不能当成内置版本，否则会误判缓存有效键与清缓存重置逻辑。
     */
    suspend fun initDbVersion(context: Context) {
        if (getDbVersion(context) != "0") return
        val assetVersion = TransitDbVersion.readAssetVersion(context) ?: "0"
        setDbVersion(context, if (AppDatabase.hasDatabaseFile(context)) "0" else assetVersion)
    }

    // ── 写入（异步）──

    suspend fun setSelectedCardId(context: Context, cardId: String?) {
        context.dataStore.edit { prefs ->
            if (cardId == null) prefs.remove(KEY_SELECTED_CARD_ID)
            else prefs[KEY_SELECTED_CARD_ID] = cardId
        }
    }

    suspend fun setCardOrder(context: Context, cardIds: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CARD_ORDER] = cardIds.joinToString(",")
        }
    }

    suspend fun setKeepDebugLogs(context: Context, keep: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KEEP_DEBUG_LOGS] = keep.toString()
        }
    }

    suspend fun markMigrated(context: Context) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SCHEMA_VERSION] = SCHEMA_VERSION
        }
    }

    suspend fun clear(context: Context) {
        context.dataStore.edit { it.clear() }
    }
}
