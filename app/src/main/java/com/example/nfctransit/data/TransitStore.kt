package com.example.nfctransit.data

import android.content.Context
import com.example.nfctransit.model.*
import com.google.gson.Gson

/** 单张卡片的持久化数据 */
data class PersistedCardData(
    val card: UiCard,
    val transactions: List<UiTransaction>,
    val nfcLog: List<String>,
    val topStations: List<StationStat>,
    val topLines: List<LineStat>,
    val dailySpending: List<DailySpending>,
    val statsSummary: StatsSummary
)

/** 应用持久化状态：所有卡片 + 每张卡的数据 + 上次选中的卡片下标 */
data class PersistedState(
    val cards: List<UiCard>,
    val dataMap: Map<String, PersistedCardData>,
    val selectedIndex: Int
)

/**
 * 本地持久化存储：使用 SharedPreferences + JSON。
 * 每次读卡更新后保存，应用启动时恢复。
 */
object TransitStore {

    private const val PREFS_NAME = "transit_store"
    private const val KEY_STATE = "state"

    private val gson = Gson()

    fun save(context: Context, state: PersistedState) {
        val json = gson.toJson(state)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATE, json).apply()
    }

    fun load(context: Context): PersistedState? {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STATE, null) ?: return null
        return try {
            gson.fromJson(json, PersistedState::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
