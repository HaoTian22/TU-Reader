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

    // 特殊匹配规则标记（详情页 Match 行展示）：命中经特殊规则解析时附加到 device_code 之后
    private const val SP_RULE_GUANGZHOU_FOSHAN = "(SP Rule: Guangzhou/Foshan)"
    private const val SP_RULE_SHENZHEN = "(SP Rule: Shenzhen)"
    private const val SP_RULE_MATCHKEY_FALLBACK = "(Fallback: match_key)"

    // 城市内匹配优先级（tier 越小越精确；仅用于广佛跨城两城比较，城内按代码顺序短路返回）
    private const val TIER_MATCHKEY = 0     // 去前导0 match_key 精确（raw 错位时的最后兜底，但仍是精确命中）
    private const val TIER_RAW = 1          // 前缀分桶 + 最长重叠（同长优先字节对齐）
    private const val TIER_TERM_EXACT = 2   // 终端号精确
    private const val TIER_TERM_PREFIX = 3  // 终端整体前缀大类

    /** 一条站点解析结果（名称已按界面语言回退；ID 用于跨页面传递与切换语言时重新解析） */
    data class StationEntry(
        val code: String,       // device_code（城市码 + Code 拼接）
        val type: String,       // CSV 的 Type 列（地铁/公交/BRT/train/bike）
        val line: String,       // 线路名（如 "1号线" / "Line 1"）
        val station: String,    // 站名
        val lineColor: String? = null,  // 线路颜色（"#RRGGBB"，空白时 UI 保持灰色）
        val lineId: Long? = null,
        val stationId: Long? = null,
        val cityCode: String? = null,    // 命中设备所在城市码（广佛跨城匹配时用于显示佛山）
        val spRule: String? = null       // 特殊匹配规则标记（广佛跨城/深圳），详情页 Match 行展示
    )

    private data class CityInfo(val zh: String, val en: String?)

    @Volatile
    private var appContext: Context? = null

    private val cityInfos = mutableMapOf<String, CityInfo>()             // 城市码 -> 中英文名
    private val byDeviceCode = mutableMapOf<String, StationResolution>() // device_code -> 解析结果
    private val byMatchKey = mutableMapOf<String, StationResolution>()   // 去前导0 match_key -> 解析结果
    private val byStationId = mutableMapOf<Long, StationResolution>()    // station_id -> 解析结果
    private val byLineStationId = mutableMapOf<Pair<Long, Long>, StationResolution>() // (line_id, station_id) -> 解析结果
    // 前缀/城市码（device_code[:4] == city_code 已验证成立）-> 该前缀下全部 device_code；
    // 最长重叠/终端前缀匹配只扫本桶，避免 DB 增大后每次 O(全部设备)
    private val deviceCodesByCity = mutableMapOf<String, MutableList<String>>()
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

    /** 预热站名索引：启动时后台调用，避免首次读卡/首屏渲染时在主线程一次性加载 2.7 万行 */
    fun warmup() {
        ensureLoaded()
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
     * @param cityCode    卡内城市码（4 位，来自 SFI 0x1E [32..34)，乘车城市）
     * @param lineCode    线路码（4 位 BCD）
     * @param stationCode 站点码（4 位 BCD）
     * @param terminal    终端号（12 位，用于终端号匹配的城市）
     *
     * 解析策略（前缀分桶 + 最长重叠；线路+站点权重大于终端号，终端号仅兜底）：
     *   0. 广佛跨城：5810 记录与变换为 5880 的候选连表匹配，两城各按下方 1-4 优先级取更精确者；
     *      5810 记录一律按双城联查解析并打 spRule 标记；5840 记录重定向到 5180 后解析（→ spRule 标记）
     *   1. 前缀分桶 + 最长重叠：只扫同前缀（=同城，device_code[:4]==city_code）reader_device，
     *      候选主体 = 深圳用终端号（其 [10..14) 非线路/站点，线路+站点编码在终端号里）、
     *      其余用 raw [10..17) hex 切片；取最长重叠，同长优先字节对齐（真实站码按字节存，跨字节伪重叠不误取）
     *   2. match_key（去前导0 规范化键 {city}|{strip0(线路)}|{strip0(站点)}）兜底：raw 切片错位时命中，
     *      打 (Fallback: match_key) 标记；深圳 [10..14) 非线路/站点，跳过
     *   3. 终端号精确匹配（杭州 TU / 广州 YCT 等终端号城市）
     *   4. 终端整体前缀大类（如 51804=地铁，最后兜底）
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
        val shenzhenRedirect = cityCode == "5840"
        val baseCity = if (shenzhenRedirect) "5180" else cityCode
        // 广佛跨城：5810 记录把城市前缀变换为 5880 后与原始 5810 数据连表匹配，两城候选同一优先级取更精确者，
        // 而不是旧的"先佛山后广州"按城逐个短路——那会让佛山弱命中（公交/线路码重叠）遮蔽广州精确命中。
        if (baseCity == "5810") {
            val gz = matchInCity("5810", lineCode, stationCode, terminal, rawCode)
            val fs = matchInCity("5880", lineCode, stationCode, terminal, rawCode)
            val best = pickBetter(gz, fs, preferCity = "5810") ?: return null
            // 5810 记录一律经广州/佛山双城联查解析（命中 5880 佛山数据即跨城乘车）→ 打联查规则标记
            return best.resolution.toEntry(SP_RULE_GUANGZHOU_FOSHAN)
        }
        val m = matchInCity(baseCity, lineCode, stationCode, terminal, rawCode) ?: return null
        // 5840 重定向命中 → 深圳特殊规则标记（终端号派生命中已在 matchInCity 内打标）
        return m.resolution.toEntry(if (shenzhenRedirect) SP_RULE_SHENZHEN else m.spRule)
    }

    /** 城市内匹配结果：resolution + 优先级（tier 越小越精确）+ 匹配长度（重叠/前缀，RAW/TERM_PREFIX 用） */
    private data class CityMatch(
        val resolution: StationResolution,
        val tier: Int,
        val len: Int = 0,
        val spRule: String? = null
    )

    /** 连表取优：优先级高（tier 小）者胜；同级比匹配长度；仍平手偏向原始城市 */
    private fun pickBetter(a: CityMatch?, b: CityMatch?, preferCity: String): CityMatch? {
        if (a == null) return b
        if (b == null) return a
        if (a.tier != b.tier) return if (a.tier < b.tier) a else b
        if (a.len != b.len) return if (a.len > b.len) a else b
        return if (a.resolution.cityCode == preferCity) a else b
    }

    /** 在指定城市内匹配 TU 站点（前缀分桶 + 最长重叠 → match_key 兜底 → 终端号兜底） */
    private fun matchInCity(
        effectiveCity: String,
        lineCode: String,
        stationCode: String,
        terminal: String,
        rawCode: String?
    ): CityMatch? {
        val isSZ = effectiveCity == "5180"
        // 候选主体：深圳 TU 用终端号（raw 切片 [10..17) 不含线路/站点，线路+站点编码在终端号里），
        // 其余用 raw hex 切片（保留 hex 站，避免 BCD 半字节展开破坏重叠）。
        val body = if (isSZ) terminal else rawCode
        if (!body.isNullOrEmpty()) {
            longestOverlap(effectiveCity, body)?.let { (r, len) ->
                return CityMatch(r, TIER_RAW, len, if (isSZ) SP_RULE_SHENZHEN else null)
            }
        }
        // match_key 兜底（放最后）：raw 切片错位（变长编码/位数不齐）时按去前导0 的 {city}|{line}|{station}
        // 规范化键精确命中，打 Fallback 标记。深圳 [10..14) 非线路/站点，跳过。
        if (!isSZ && lineCode.isNotEmpty() && stationCode.isNotEmpty()) {
            byMatchKey["$effectiveCity|${stripLeadingZeros(lineCode)}|${stripLeadingZeros(stationCode)}"]
                ?.let { return CityMatch(it, TIER_MATCHKEY, spRule = SP_RULE_MATCHKEY_FALLBACK) }
        }
        // 终端号精确匹配（终端号城市：杭州 TU / 广州 YCT 等；跨城卡终端号是发行前缀，仅此兜底）
        if (terminal.isNotEmpty()) {
            byDeviceCode[effectiveCity + terminal]?.let { return CityMatch(it, TIER_TERM_EXACT) }
            byDeviceCode[terminal]?.let { return CityMatch(it, TIER_TERM_EXACT) }
        }
        // 终端整体前缀大类（如 51804=地铁，最后兜底）
        if (terminal.isNotEmpty()) {
            longestPrefixInCity(effectiveCity, effectiveCity + terminal)?.let { (r, len) ->
                return CityMatch(r, TIER_TERM_PREFIX, len)
            }
        }
        return null
    }

    /**
     * 前缀分桶 + 最长重叠：只扫 prefix 前缀下的 reader_device，找候选串（prefix+body）里重叠最长的
     * device_code（整码或去前缀码作为候选子串，取最大）。不硬拆 line+station，自然覆盖 4 位 vs 3 位
     * 线路码（1618+0101 → 6180101 → bus 618）与站点 hex 填充（01001B00 → 01001B → 坝头），以及
     * 终端号形态设备（device_code = prefix + 完整终端号，杭州）。同长优先字节对齐：真实站码按字节存
     * （候选串偶数下标 = BCD 字节边界），跨字节伪重叠（如 00131335 里的 0131 与真实站码 1335）同长时不误取。
     * 返回命中设备与重叠长度；无命中返回 null。
     */
    private fun longestOverlap(prefix: String, body: String): Pair<StationResolution, Int>? {
        val candidate = prefix + body
        var best: StationResolution? = null
        var bestLen = 0   // 从 0 起，只接受真实重叠（ov>0），避免无重叠时误取第一个设备
        var bestAligned = false
        for (dev in deviceCodesByCity[prefix].orEmpty()) {
            if (!dev.startsWith(prefix)) continue
            val r = byDeviceCode[dev] ?: continue
            val devCode = dev.removePrefix(prefix)
            if (devCode.isEmpty()) continue
            val ov = when {
                candidate.contains(dev) -> dev.length            // 整码是候选子串（metro 坝头 602001001B）
                candidate.contains(devCode) -> devCode.length    // 去前缀码是子串（bus 618 / 杭州终端号）
                else -> 0
            }
            if (ov == 0) continue
            // 重叠必须真正压在记录数据（body）一侧：整段都落在城市前缀内的假重叠
            // （如 6510 前缀尾部 "10" 命中 651010）会被当成有效命中，必须过滤。
            // 字节对齐也按 body 坐标判断：body 起始偏移 0，真实站码在偶数偏移；
            // 城市码恒为 4 位（偶数），对 body 内真实匹配等价于旧的全串奇偶判断。
            val idx = candidate.indexOf(devCode)
            if (idx + ov <= prefix.length) continue
            val aligned = idx >= prefix.length && (idx - prefix.length) % 2 == 0
            if (ov > bestLen || (ov == bestLen && aligned && !bestAligned)) {
                bestLen = ov; bestAligned = aligned; best = r
            }
        }
        return best?.let { it to bestLen }
    }

    /** 大类 fallback：prefix 前缀下找 candidate 的最长前缀 device_code（同前缀分桶，只扫本桶） */
    private fun longestPrefixInCity(prefix: String, candidate: String): Pair<StationResolution, Int>? {
        var best: StationResolution? = null
        var bestLen = 0
        for (dev in deviceCodesByCity[prefix].orEmpty()) {
            if (dev.length <= prefix.length) continue
            val r = byDeviceCode[dev] ?: continue
            if (candidate.startsWith(dev) && dev.length > bestLen) {
                bestLen = dev.length; best = r
            }
        }
        return best?.let { it to bestLen }
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
     * 按 ID 解析完整站点解析结果（含中英文原始名），供全文搜索建立索引；
     * 与 entryOf 不同：不做显示语言回退，直接返回双语数据。无命中返回 null。
     */
    fun resolutionFor(lineId: Long?, stationId: Long?): StationResolution? {
        ensureLoaded()
        if (stationId == null) return null
        return lineId?.let { byLineStationId[it to stationId] } ?: byStationId[stationId]
    }

    /** 站点 WGS84 坐标 (lng, lat)；坐标缺失（未采集）或站点未知返回 null。
     *  站点坐标属于 station 表，与线路无关，按 stationId 直接查。 */
    fun coordsOf(stationId: Long?): Pair<Double, Double>? {
        if (stationId == null) return null
        ensureLoaded()
        val r = byStationId[stationId] ?: return null
        val lon = r.longitude ?: return null
        val lat = r.latitude ?: return null
        return lon to lat
    }

    /**
     * 非 TU 卡种（YCT/SZT/CU/苏州/天津）的站点解析（简化：前缀分桶 + 最长重叠）。
     *
     * 0x18 记录 [10..16)：前 4 位 = 城市/网络前缀（广州 YCT 0100、深圳 5180…），其余 = 位置/终端码。
     * 按前缀分桶到该前缀下全部 reader_device，对整段 code（= prefix + 剩余）做最长重叠（同长优先
     * 字节对齐），与 TU 0x1E 共用同一 longestOverlap。未知前缀（如珠海 5850 暂无设备数据）自然无命中。
     *
     * @param standard 卡种标识（"CU"/"YCT"/"SZT"/"SUXIN"/"SZTK"/"TFT"）；SZT 启用深圳 Terminal 特殊匹配
     * @param cityCode 交易城市码（与 code 前 4 位同源，保留签名兼容）
     * @param code     记录 [10..16) 的 hex 串（12 位，含前缀/终端字段）
     * @param terminal 记录 [10..16) 的 BCD 串（与 code 同字节，保留签名兼容）
     */
    fun resolveByStandard(
        standard: String,
        cityCode: String,
        code: String,
        terminal: String
    ): StationEntry? {
        ensureLoaded()
        if (standard == "SZT") {
            resolveShenzhenTerminal(code, terminal)?.let { return it }
        }

        val prefix = if (code.length >= 4) code.substring(0, 4) else code
        val body = if (code.length > 4) code.substring(4) else code
        if (body.isEmpty()) return null
        return longestOverlap(prefix, body)?.first?.toEntry()
    }

    /**
     * 深圳通 SZT 的 0x18 记录没有把 5180 放进 Terminal 字段；Terminal 中的编码为
     * 线路码 + 站点码，且中间夹有一个非编码字节。样例 000040020122 → 40022。
     */
    private fun resolveShenzhenTerminal(code: String, terminal: String): StationEntry? {
        val candidates = linkedSetOf<String>()
        if (code.length >= 11) {
            candidates += code.substring(4, 8) + code.substring(10, 11)
        }
        val stripped = terminal.trimStart('0')
        if (stripped.length >= 6) {
            candidates += stripped.substring(1, 3) + stripped.substring(3, 6)
        }
        for (stationCode in candidates) {
            byDeviceCode["5180$stationCode"]?.let {
                return it.toEntry(SP_RULE_SHENZHEN)
            }
            byMatchKey["5180|${stripLeadingZeros(stationCode.take(2))}|${stripLeadingZeros(stationCode.drop(2))}"]?.let {
                return it.toEntry(SP_RULE_SHENZHEN)
            }
        }
        return null
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
    private fun StationResolution.toEntry(spRule: String? = null): StationEntry {
        val useEn = useEnglish()
        val line = if (useEn) lineNameEn ?: lineName else lineName ?: lineNameEn
        val station = sequenceOf(
            if (useEn) stationNameEn ?: stationName else stationName,
            line,
            transitType
        ).firstOrNull { !it.isNullOrEmpty() } ?: ""
        return StationEntry(deviceCode, transitType, line ?: "", station, lineColor, lineId, stationId, cityCode, spRule)
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
            deviceCodesByCity.clear()
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
                        deviceCodesByCity.getOrPut(r.cityCode) { mutableListOf() }.add(r.deviceCode)
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
