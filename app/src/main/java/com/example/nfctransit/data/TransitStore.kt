package com.example.nfctransit.data

import android.content.Context
import com.example.nfctransit.model.*
import com.google.gson.Gson

/** 一条原始 SFI 记录（持久化用，重读时按原始身份去重） */
data class RawRecord(
    val sfi: Int,          // 来源区（0x18 / 0x1E / …）
    val recNo: Int,        // 记录号
    val hex: String        // 原始字节 hex
) {
    /** 原始身份：同 SFI 同记录号同内容视为同一条记录 */
    val identity: String get() = "$sfi:$recNo:$hex"
}

/** 单张卡片的持久化数据 */
data class PersistedCardData(
    val card: UiCard,
    val transactions: List<UiTransaction>,
    val nfcLog: List<String>,
    val topStations: List<StationStat>,
    val topLines: List<LineStat>,
    val dailySpending: List<DailySpending>,
    val statsSummary: StatsSummary,
    val rawRecords: List<RawRecord>? = null  // 各交易区原始记录；nullable 兼容旧版持久化（Gson 反序列化为 null）
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
