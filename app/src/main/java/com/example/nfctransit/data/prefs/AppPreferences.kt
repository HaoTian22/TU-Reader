package com.example.nfctransit.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
