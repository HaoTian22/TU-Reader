package com.example.nfctransit.data

import android.content.Context
import android.util.Log
import com.example.nfctransit.data.db.AppDatabase
import com.example.nfctransit.data.db.StationResolution
import java.util.Locale
import kotlinx.coroutines.runBlocking

/**
 * 交通卡数据门面。
 *
 * 底层由 Room SQLite（预置 assets/data/transit.db，tools/build_db.py 生成）提供；
 * 首次访问时把全部站点解析结果载入内存索引（约 2.7 万行），读卡链路为纯内存查询。
 *
 * 站点/线路在应用内以数据库 ID（stationId / lineId）传递，名称按界面语言（system/zh/en）即时解析，
 * 避免持久化/页面间直接传名字导致渲染错乱，也便于切换语言时直接转换。
 */
object TransitData {

    private const val ROOT = "data"

    private const val PREFS = "transit_prefs"
    private const val KEY_LANG = "display_lang"
    private const val LANG_SYSTEM = "system"
    private const val LANG_ZH = "zh"
    private const val LANG_EN = "en"

    /** 一条站点解析结果（名称已按界面语言回退；ID 用于跨页面传递与切换语言时重新解析） */
    data class StationEntry(
        val code: String,       // device_code（城市码 + Code 拼接）
        val type: String,       // CSV 的 Type 列（地铁/公交/BRT/train/bike）
        val line: String,       // 线路名（如 "1号线" / "Line 1"）
        val station: String,    // 站名
        val lineId: Long? = null,
        val stationId: Long? = null
    )

    private data class CityInfo(val zh: String, val en: String?)

    @Volatile
    private var appContext: Context? = null

    private val cityInfos = mutableMapOf<String, CityInfo>()             // 城市码 -> 中英文名
    private val byDeviceCode = mutableMapOf<String, StationResolution>() // device_code -> 解析结果
    private val byMatchKey = mutableMapOf<String, StationResolution>()   // 去前导0 match_key -> 解析结果
    private val byStationId = mutableMapOf<Long, StationResolution>()    // station_id -> 解析结果
    private val byLineStationId = mutableMapOf<Pair<Long, Long>, StationResolution>() // (line_id, station_id) -> 解析结果
    // "线路 站点" 组合串 -> 解析结果（中/英各一），用于修复旧版本按空格误拆线路/站名的持久化数据
    private val byCombinedZh = mutableMapOf<String, StationResolution>()
    private val byCombinedEn = mutableMapOf<String, StationResolution>()
    // 站名 -> 解析结果（中/英各一；同名站多线路时优先带线路者），用于给旧数据补回 ID
    private val byStationNameZh = mutableMapOf<String, StationResolution>()
    private val byStationNameEn = mutableMapOf<String, StationResolution>()

    // 交通联合卡 IIN -> 卡名（来自 assets/data/TU/cardname-tu.csv）
    private val iinNames = mutableMapOf<String, String>()

    @Volatile
    private var loaded = false
    private val loadLock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        loadCardNames()
    }

    /** 卡号（PAN，来自应用序列号）-> 卡名。IIN = PAN 前 8 位，按最长前缀匹配。 */
    fun cardName(iin: String): String? {
        var best: String? = null
        var bestLen = -1
        for ((key, name) in iinNames) {
            if (iin.startsWith(key) && key.length > bestLen) {
                bestLen = key.length
                best = name
            }
        }
        return best
    }

    /** 城市码 -> 显示名（如 "广州 (Guangzhou)"），英文缺失时仅中文，未知时返回 "城市码:xxxx" */
    fun cityName(cityCode: String): String {
        ensureLoaded()
        val info = cityInfos[cityCode] ?: return "城市码:$cityCode"
        return if (info.en.isNullOrEmpty()) info.zh else "${info.zh} (${info.en})"
    }

    /** 城市码 -> 中文城市名（如 "广州"），未知时返回原城市码 */
    fun cityZh(cityCode: String): String {
        ensureLoaded()
        return cityInfos[cityCode]?.zh ?: cityCode
    }

    /**
     * 解析 TU 站点。
     * @param cityCode    卡内城市码（4 位，来自 SFI 0x1E）
     * @param lineCode    线路码（4 位 BCD）
     * @param stationCode 站点码（4 位 BCD）
     * @param terminal    终端号（12 位，用于终端号匹配的城市）
     *
     * 解析策略（顺序）：
     *   1. 终端号城市（杭州 TU / 广州 YCT 等，整行 Code 即终端号）：device_code = 城市码 + 终端号
     *   2. 线路头行城市拼接：device_code = 城市码 + 线路码 + 站点码（保留卡片 BCD 前导 0，如广州 0001 0001）
     *   3. 去前导 0 规范化键：{city}|{strip0(线路)}|{strip0(站点)}，兼容变长编码（北京/深圳/重庆等）
     */
    fun resolveTuStation(
        cityCode: String,
        lineCode: String,
        stationCode: String,
        terminal: String
    ): StationEntry? {
        ensureLoaded()
        if (terminal.isNotEmpty()) {
            byDeviceCode[cityCode + terminal]?.let { return it.toEntry() }
            byDeviceCode[terminal]?.let { return it.toEntry() }
        }
        if (lineCode.isNotEmpty()) {
            byDeviceCode[cityCode + lineCode + stationCode]?.let { return it.toEntry() }
            byMatchKey["$cityCode|${stripLeadingZeros(lineCode)}|${stripLeadingZeros(stationCode)}"]
                ?.let { return it.toEntry() }
        }
        return null
    }

    /**
     * 按数据库 ID 解析线路/站名（跟随界面语言）。
     * lineId 为 null（终端号城市无线路）时退化为按 stationId 解析站名。
     */
    fun entryOf(lineId: Long?, stationId: Long): StationEntry? {
        ensureLoaded()
        val r = lineId?.let { byLineStationId[it to stationId] } ?: byStationId[stationId] ?: return null
        return r.toEntry()
    }

    /**
     * 旧版本持久化数据修复：按 "线路 站点" 组合串（中或英）反查解析结果，
     * 用于恢复丢失的 lineId/stationId，并修正按空格误拆导致线路/站名错位的数据。
     */
    fun resolveByCombined(combined: String): StationEntry? {
        ensureLoaded()
        val clean = combined.replace(Regex(" [↑↓]$"), "").trim()
        if (clean.isEmpty()) return null
        val r = byCombinedZh[clean] ?: byCombinedEn[clean] ?: return null
        return r.toEntry()
    }

    /** 旧版本持久化数据修复：按站名（中或英）反查解析结果，用于补回丢失的 stationId */
    fun resolveByStationName(name: String): StationEntry? {
        ensureLoaded()
        val clean = name.replace(Regex(" [↑↓]$"), "").trim()
        if (clean.isEmpty()) return null
        val r = byStationNameZh[clean] ?: byStationNameEn[clean] ?: return null
        return r.toEntry()
    }

    /** CSV Type 列 -> 界面交通类型标签（跟随显示语言） */
    fun transitTypeLabel(type: String?): String {
        return when {
            useEnglish() -> when (type) {
                "地铁" -> "Metro"
                "BRT", "公交", "bus" -> "Bus"
                "train" -> "Rail"
                "bike" -> "Bike"
                else -> "Other"
            }
            else -> when (type) {
                "地铁" -> "地铁 (Metro)"
                "BRT", "公交", "bus" -> "公交 (Bus)"
                "train" -> "轨道交通 (Rail)"
                "bike" -> "公共自行车 (Bike)"
                else -> "其他 (Other)"
            }
        }
    }

    /** 站点/线路显示语言："system" 跟随系统，否则强制 zh / en */
    fun getDisplayLanguage(): String {
        val ctx = appContext ?: return LANG_SYSTEM
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, LANG_SYSTEM) ?: LANG_SYSTEM
    }

    /** 设置站点/线路显示语言并立即生效（下次读取即可用） */
    fun setDisplayLanguage(lang: String) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).apply()
    }

    private fun useEnglish(): Boolean {
        return when (getDisplayLanguage()) {
            LANG_EN -> true
            LANG_ZH -> false
            else -> Locale.getDefault().language == "en"
        }
    }

    /** DB 解析结果 -> 对外 StationEntry，线路/站点名按显示语言回退，并携带 ID */
    private fun StationResolution.toEntry(): StationEntry {
        val useEn = useEnglish()
        val line = if (useEn) lineNameEn ?: lineName else lineName ?: lineNameEn
        val station = if (useEn) stationNameEn ?: stationName else stationName
        return StationEntry(deviceCode, transitType, line ?: "", station, lineId, stationId)
    }

    /** 首次访问时一次性载入 DB 数据（双检锁；失败则保持空索引，读卡链路降级为"未知"） */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return@ensureLoaded
            try {
                val ctx = appContext ?: return@ensureLoaded
                runBlocking {
                    val dao = AppDatabase.get(ctx).transitDao()
                    for (c in dao.getAllCities()) {
                        cityInfos[c.cityCode] = CityInfo(c.cityName, c.cityNameEn)
                    }
                    for (r in dao.getAllResolutions()) {
                        byDeviceCode[r.deviceCode] = r
                        r.matchKey?.let { byMatchKey[it] = r }
                        byStationId[r.stationId] = r
                        r.lineId?.let { byLineStationId[it to r.stationId] = r }
                        byCombinedZh["${r.lineName ?: ""} ${r.stationName}".trim()] = r
                        byCombinedEn[
                            "${r.lineNameEn ?: r.lineName ?: ""} ${r.stationNameEn ?: r.stationName}".trim()
                        ] = r
                        // 同名站多线路时优先保留带线路的一例（旧数据无线路时也能反查）
                        if (!r.stationName.isNullOrEmpty()) {
                            byStationNameZh.putIfAbsent(r.stationName, r)
                        }
                        if (!r.stationNameEn.isNullOrEmpty()) {
                            byStationNameEn.putIfAbsent(r.stationNameEn, r)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TransitData", "DB load failed", e)
            } finally {
                loaded = true
            }
        }
    }

    /** 加载 IIN -> 卡名映射（cardname-tu.csv），Name 列非空的才入库 */
    private fun loadCardNames() {
        val ctx = appContext ?: return
        for (row in readCsv("$ROOT/TU/cardname-tu.csv")) {
            if (row.size < 3) continue
            val iin = row[0].trim()
            val name = row[2].trim()
            if (iin.isEmpty() || iin == "IIN" || name.isEmpty()) continue
            iinNames[iin] = name
        }
    }

    private fun readCsv(path: String): List<List<String>> {
        val ctx = appContext ?: return emptyList()
        return try {
            ctx.assets.open(path).bufferedReader().use { reader ->
                reader.lineSequence().mapNotNull { line ->
                    if (line.isBlank()) null else splitCsv(line)
                }.toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 简单 CSV 行切分：逗号分隔，处理带引号的字段与转义引号 */
    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuote && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"'); i++
                    } else {
                        inQuote = !inQuote
                    }
                }
                c == ',' && !inQuote -> {
                    out.add(sb.toString()); sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** 去掉前导 0（"0001" -> "1"），用于线路/站点编码的归一化匹配 */
    private fun stripLeadingZeros(s: String): String {
        val trimmed = s.trimStart('0')
        return if (trimmed.isEmpty()) "0" else trimmed
    }
}
