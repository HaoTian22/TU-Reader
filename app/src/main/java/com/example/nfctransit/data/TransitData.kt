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
        val lineColor: String? = null,  // 线路颜色（"#RRGGBB"，空白时 UI 保持灰色）
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
    // 大类 fallback 用：device_code 按长度降序，供最长前缀匹配（如 5180xxxx 未命中 → 51804=地铁 / 518040=10号线）
    private val deviceCodesByLen = mutableListOf<String>()
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
        terminal: String,
        rawCode: String? = null
    ): StationEntry? {
        ensureLoaded()
        // 深圳 TU：卡片 1E 城市码 5840 无独立站点数据（city_id 200 空），重定向到 5180（深圳数据所在）
        val effectiveCity = if (cityCode == "5840") "5180" else cityCode
        if (terminal.isNotEmpty()) {
            byDeviceCode[effectiveCity + terminal]?.let { return it.toEntry() }
            byDeviceCode[terminal]?.let { return it.toEntry() }
        }
        if (lineCode.isNotEmpty()) {
            byDeviceCode[effectiveCity + lineCode + stationCode]?.let { return it.toEntry() }
            byMatchKey["$effectiveCity|${stripLeadingZeros(lineCode)}|${stripLeadingZeros(stationCode)}"]
                ?.let { return it.toEntry() }
        }
        // 深圳 TU 轨道交通：按 1E 终端号匹配（cu.csv 格式同 CU）。卡片终端号 "000262016106"
        // 去前导 0 后第 1-2 位为线路码（62=4号线）、3-5 位为站点码（016=深圳北站），
        // 对应 device_code "5180"+线路+站点（如 518062016）
        if (effectiveCity == "5180" && terminal.isNotEmpty()) {
            val s = stripLeadingZeros(terminal)
            if (s.length >= 6) {
                val line = s.substring(1, 3)
                val station = s.substring(3, 6)
                byDeviceCode["5180$line$station"]?.let { return it.toEntry() }
                byMatchKey["5180|$line|${stripLeadingZeros(station)}"]?.let { return it.toEntry() }
            }
        }
        // 统一最长匹配 fallback：取 raw 记录代码切片（[10..17)，如 "16180101602009"）加城市前缀作为候选，
        // 找同城 device_code 的最长重叠（整码或线路码部分作为候选子串，取最大）。不硬拆 line+station，
        // 自然覆盖 4 位 vs 3 位线路码（1618+0101 → 6180101 → bus 618）与站点填充（01001B00 → 01001B → 坝头）。
        var best: StationResolution? = null
        var bestLen = 0   // 从 0 起，只接受真实重叠（ov>0），避免无重叠时误取第一个设备
        val rawCand = if (!rawCode.isNullOrEmpty()) effectiveCity + rawCode else null
        if (rawCand != null) {
            for ((dev, r) in byDeviceCode) {
                if (r.cityCode != effectiveCity || !dev.startsWith(effectiveCity)) continue
                val devCode = dev.removePrefix(effectiveCity)
                val ov = when {
                    rawCand.contains(dev) -> dev.length            // 整码是候选子串（metro 坝头 602001001B）
                    !devCode.isEmpty() && rawCand.contains(devCode) -> devCode.length  // 线路码是子串（bus 618）
                    else -> 0
                }
                if (ov > bestLen) { bestLen = ov; best = r }
            }
        }
        // 深圳提取码前缀（未知站 → 4号线/13号线 大类）
        if (effectiveCity == "5180" && terminal.isNotEmpty()) {
            val s = stripLeadingZeros(terminal)
            if (s.length >= 6) {
                val codeRegion = s.substring(1, 3) + s.substring(3, 6)  // "62099"
                for ((dev, r) in byDeviceCode) {
                    if (r.cityCode != effectiveCity || !dev.startsWith(effectiveCity)) continue
                    val devCode = dev.removePrefix(effectiveCity)
                    if (devCode.isNotEmpty() && codeRegion.startsWith(devCode) && devCode.length > bestLen) {
                        bestLen = devCode.length; best = r
                    }
                }
            }
        }
        // 终端整体前缀（大类：51804=地铁）
        if (terminal.isNotEmpty()) {
            longestPrefixMatch(effectiveCity + terminal)?.let { r ->
                if (r.deviceCode.length > bestLen) { bestLen = r.deviceCode.length; best = r }
            }
        }
        best?.let { return it.toEntry() }
        return null
    }

    /** 大类 fallback：找 candidate 的最长前缀 device_code（按长度降序，首个命中即最长前缀） */
    private fun longestPrefixMatch(candidate: String): StationResolution? {
        for (dev in deviceCodesByLen) {
            if (dev.length < 5) continue
            if (candidate.startsWith(dev)) return byDeviceCode[dev]
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
     * 非 TU 卡种（YCT/SZT/CU/苏州/天津）的站点解析。
     *
     * @param standard 卡种标识（"CU"/"YCT"/"SZT"/"SUXIN"/"SZTK"/"TFT"，仅用于兜底文案）
     * @param cityCode 交易城市码（4 位十进制，来自记录 [10..12) 压缩 BCD，如 5180）
     * @param code     记录 [10..16) 的 hex 串（12 位，含城市码前缀 [10..12)）
     * @param terminal 记录 [10..16) 的 BCD 串（12 位终端号）
     *
     * 各卡种 device_code 形态不同（DB 由 CSV 生成，key = City/Prefix + Code 拼接）：
     *   - CU（深圳/上海等）：device_code = 城市码 + 线路/站点码，如 518040011
     *   - YCT（广州羊城通）：device_code = 0100 + 终端号，如 010000163423
     * 且 DB 的 standard 标签来自 CSV 文件名，与卡 AID 类型可能不一致
     * （深圳的 cu.csv → standard='CU'，但卡 AID 是 PAY.SZT）。
     * 因此这里不按 standard 过滤，而是生成「城市码+位置码」「城市码+终端号」等候选 key
     * 逐个试命中；未命中时对 in-memory 索引做一次有界的 后缀/前缀 扫描兜底。
     */
    fun resolveByStandard(
        standard: String,
        cityCode: String,
        code: String,
        terminal: String
    ): StationEntry? {
        ensureLoaded()
        val city = cityCode.ifBlank { "" }
        // [10..12) 是城市码（hex 前 4 位），[12..16) 是位置码；去掉前缀
        val pos = if (code.length > 4) code.substring(4) else code

        val candidates = linkedSetOf<String>()
        if (city.isNotEmpty()) {
            candidates.add(city + pos)
            candidates.add(city + stripLeadingZeros(pos))
            candidates.add(city + pos.trimEnd('0'))
            if (terminal.isNotEmpty()) {
                candidates.add(city + terminal)
                candidates.add(city + stripLeadingZeros(terminal))
            }
        }
        candidates.add(pos)
        candidates.add(stripLeadingZeros(pos))
        if (terminal.isNotEmpty()) {
            candidates.add(terminal)
            candidates.add(stripLeadingZeros(terminal))
        }
        for (c in candidates) {
            byDeviceCode[c]?.let { return it.toEntry() }
        }

        // 兜底：有界后缀/前缀扫描（先短后长，命中第一个即返回）
        var best: Pair<StationResolution, Int>? = null
        for ((dev, r) in byDeviceCode) {
            val dl = dev.length
            if (dl < 5) continue
            val matchLen = when {
                dl <= code.length && dev.endsWith(pos) && code.endsWith(dev) -> dl
                code.length <= dl && pos.endsWith(dev) && dev.length <= pos.length -> code.length
                terminal.isNotEmpty() && dl <= terminal.length && terminal.endsWith(dev) -> dl
                else -> 0
            }
            if (matchLen > 0 && (best == null || matchLen > best.second)) {
                best = r to matchLen
            }
        }
        return best?.first?.toEntry()
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
                "有轨电车" -> "Tram"
                "城际" -> "Intercity"
                "单轨" -> "Monorail"
                "轻轨" -> "Light Rail"
                "快轨" -> "Express Rail"
                "铁路" -> "Railway"
                "磁浮" -> "Maglev"
                "train" -> "Rail"
                "bike" -> "Bike"
                else -> "Other"
            }
            else -> when (type) {
                "地铁" -> "地铁 (Metro)"
                "BRT", "公交", "bus" -> "公交 (Bus)"
                "有轨电车" -> "有轨电车 (Tram)"
                "城际" -> "城际 (Intercity)"
                "单轨" -> "单轨 (Monorail)"
                "轻轨" -> "轻轨 (Light Rail)"
                "快轨" -> "快轨 (Express Rail)"
                "铁路" -> "铁路 (Railway)"
                "磁浮" -> "磁浮 (Maglev)"
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

    /** DB 解析结果 -> 对外 StationEntry，线路/站点名按显示语言回退，并携带 ID。
     *  空站名/空线路名的大类 fallback 设备 → 站名回退到线路名或交通类型（空串也要兜底，不能用 ?: 只对 null 生效） */
    private fun StationResolution.toEntry(): StationEntry {
        val useEn = useEnglish()
        val line = if (useEn) lineNameEn ?: lineName else lineName ?: lineNameEn
        val station = sequenceOf(
            if (useEn) stationNameEn ?: stationName else stationName,
            line,
            transitType
        ).firstOrNull { !it.isNullOrEmpty() } ?: ""
        return StationEntry(deviceCode, transitType, line ?: "", station, lineColor, lineId, stationId)
    }

    /** 在线更新站名映射表后清空内存索引并从新库重新载入（调用方需先完成 DB 文件替换） */
    fun reload() {
        synchronized(loadLock) {
            cityInfos.clear()
            byDeviceCode.clear()
            byMatchKey.clear()
            byStationId.clear()
            byLineStationId.clear()
            byCombinedZh.clear()
            byCombinedEn.clear()
            byStationNameZh.clear()
            byStationNameEn.clear()
            deviceCodesByLen.clear()
            loaded = false
            ensureLoaded()
        }
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
                        r.stationId?.let { byStationId[it] = r }
                        r.lineId?.let { lid -> r.stationId?.let { byLineStationId[lid to it] = r } }
                        byCombinedZh["${r.lineName ?: ""} ${r.stationName ?: ""}".trim()] = r
                        byCombinedEn[
                            "${r.lineNameEn ?: r.lineName ?: ""} ${r.stationNameEn ?: r.stationName ?: ""}".trim()
                        ] = r
                        // 同名站多线路时优先保留带线路的一例（旧数据无线路时也能反查）
                        if (!r.stationName.isNullOrEmpty()) {
                            byStationNameZh.putIfAbsent(r.stationName, r)
                        }
                        if (!r.stationNameEn.isNullOrEmpty()) {
                            byStationNameEn.putIfAbsent(r.stationNameEn, r)
                        }
                    }
                    deviceCodesByLen.clear()
                    deviceCodesByLen.addAll(byDeviceCode.keys.sortedByDescending { it.length })
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
