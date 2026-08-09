package com.example.nfctransit.data

import android.content.Context

/**
 * 基于 CSV 的交通卡数据加载器。
 *
 * 数据目录（app/src/main/assets/data），按协议分文件夹、再按城市码分文件夹：
 *   citylist.csv              城市码 -> 城市信息（显示用）
 *   TU/<城市码>/              交通联合卡（TU）站点数据（优先支持，文件名如 metro-tu.csv）
 *   CU/<城市码>/              数字城市一卡通（暂不支持）
 *   YCT/<城市码>/             岭南通/羊城通（暂不支持）
 *
 * 每个站点 CSV 保持 tripreader-data 原始列：City,Code,Type,Line,Station，可直接编辑。
 * 站点匹配因城市编码方式而异，支持两种策略：
 *   1) 线路码 + 站点码拼接（去前导 0 后匹配）—— 广州/北京/重庆/成都系等
 *   2) 终端号精确匹配 —— 杭州/嘉兴/绍兴/南京单车等以终端号为键的城市
 */
object TransitData {

    private const val ROOT = "data"

    /** CSV 一行站点条目 */
    data class StationEntry(
        val code: String,   // CSV 的 Code 列
        val type: String,   // CSV 的 Type 列（地铁/公交/BRT/train/bike）
        val line: String,   // CSV 的 Line 列（如 "1号线"）
        val station: String // CSV 的 Station 列（站名）
    )

    private data class CityData(
        // 原始 Code 索引（终端号 / 完整编码直接命中）
        val byCode: Map<String, StationEntry>,
        // 线路码+站点码 去前导0 索引：key = "<线路码去0>|<站点码去0>"
        val byLineStation: Map<String, StationEntry>
    )

    private var appContext: Context? = null
    private val cityInfos = mutableMapOf<String, Triple<String, String, String>>() // code -> (province, cityEn, cityZh)
    private val cityCache = mutableMapOf<String, CityData>() // "协议/城市码" -> 站点表

    fun init(context: Context) {
        appContext = context.applicationContext
        loadCityInfos()
    }

    /** 城市码 -> 显示名（如 "广州 (Guangzhou)"），未知时返回 "城市码:xxxx" */
    fun cityName(cityCode: String): String {
        val (_, cityEn, cityZh) = cityInfos[cityCode] ?: return "城市码:$cityCode"
        return "$cityZh ($cityEn)"
    }

    /** 城市码 -> 中文城市名（如 "广州"），未知时返回原城市码 */
    fun cityZh(cityCode: String): String {
        return cityInfos[cityCode]?.third ?: cityCode
    }

    /**
     * 查询 TU 站点。
     * @param cityCode   卡内城市码（4 位，来自 SFI 0x1E）
     * @param lineCode   线路码（4 位）
     * @param stationCode 站点码（4 位）
     * @param terminal   终端号（12 位，用于终端号匹配的城市）
     */
    fun resolveTuStation(
        cityCode: String,
        lineCode: String,
        stationCode: String,
        terminal: String
    ): StationEntry? {
        val data = cityData("TU", cityCode) ?: return null

        // 1) 终端号精确匹配（杭州等以终端号为键的城市）
        if (terminal.isNotEmpty()) {
            data.byCode[terminal]?.let { return it }
        }

        // 2) 线路码+站点码 去前导0 匹配
        if (lineCode.isNotEmpty()) {
            val key = "${stripLeadingZeros(lineCode)}|${stripLeadingZeros(stationCode)}"
            data.byLineStation[key]?.let { return it }
            // 兜底：完整 8 位编码直接命中
            data.byCode["$lineCode$stationCode"]?.let { return it }
        }
        return null
    }

    /** CSV Type 列 -> 界面交通类型标签 */
    fun transitTypeLabel(type: String?): String {
        return when (type) {
            "地铁" -> "地铁 (Metro)"
            "BRT", "公交", "bus" -> "公交 (Bus)"
            "train" -> "轨道交通 (Rail)"
            "bike" -> "公共自行车 (Bike)"
            else -> "其他 (Other)"
        }
    }

    /** 读取某协议某城市的站点表（带缓存） */
    private fun cityData(protocol: String, cityCode: String): CityData? {
        if (cityCode.isEmpty()) return null
        val cacheKey = "$protocol/$cityCode"
        cityCache[cacheKey]?.let { return it }

        val ctx = appContext ?: return null
        val dir = "$ROOT/$protocol/$cityCode"
        val files = try {
            ctx.assets.list(dir)?.toList().orEmpty().filter { it.endsWith(".csv") }.sorted()
        } catch (e: Exception) {
            return null
        }
        if (files.isEmpty()) return null

        val byCode = linkedMapOf<String, StationEntry>()
        // Line 名 -> 线路码（来自"线路头"行：Station 为空的 Code）
        val lineHeaders = linkedMapOf<String, String>()
        val all = mutableListOf<StationEntry>()

        for (name in files) {
            for (row in readCsv("$dir/$name")) {
                if (row.size < 5) continue
                val code = row[1].trim()
                if (code.isEmpty() || code == "Code") continue
                val entry = StationEntry(code, row[2].trim(), row[3].trim(), row[4].trim())
                byCode[code] = entry
                all += entry
                if (entry.station.isEmpty() && entry.line.isNotEmpty()) {
                    lineHeaders[entry.line] = code
                }
            }
        }

        // 线路码+站点码 去前导0 索引
        val byLineStation = linkedMapOf<String, StationEntry>()
        for (e in all) {
            if (e.station.isEmpty()) continue
            val headerCode = lineHeaders[e.line] ?: continue
            if (!e.code.startsWith(headerCode)) continue
            val stationPart = e.code.removePrefix(headerCode)
            val key = "${stripLeadingZeros(headerCode)}|${stripLeadingZeros(stationPart)}"
            byLineStation[key] = e
        }

        val data = CityData(byCode, byLineStation)
        cityCache[cacheKey] = data
        return data
    }

    private fun loadCityInfos() {
        val ctx = appContext ?: return
        for (row in readCsv("$ROOT/citylist.csv")) {
            if (row.size < 4) continue
            val code = row[0].trim()
            if (code.isEmpty() || code == "code") continue
            cityInfos[code] = Triple(row[1].trim(), row[2].trim(), row[3].trim())
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
