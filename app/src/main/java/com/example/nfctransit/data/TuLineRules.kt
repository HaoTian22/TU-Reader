package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * `line_rules.json`：TU 交易的声明式规则。一条交易按三步解析：
 *
 * 1. **求解**：按原始记录（1E 旅程，及按时间戳配对的 TU 0x18）求查找计划 [Plan]：查哪些目标、交易实际城市、
 *    交通族。命中的规则全部叠加，每项取第一条给出它的规则；都没给出时只查记录自身城市码。
 * 2. **设备匹配**：TransitData 在计划各目标内按最长重叠评分取优（评分算法在代码里，不在规则里）。
 * 3. **线路号**：库未给出线路/站点的公交 1E 记录，按计划目标依次套 `line` 规则（仅显示）。
 *
 * 具体设备、站点及单独命名的线路由 transit.db 提供；规则只描述库装不下的城市级规律。文件与 transit.db 并列、
 * 独立发布（[LineRulesStore]）；App 只执行固定步骤，不执行代码。
 *
 * ## 文件格式（format = 2）
 * ```
 * { "format": 2, "version": "yyyyMMddHHmmss", "rules": [Rule, ...] }
 * Rule   = { "id": "guangzhou-foshan",          // 唯一；详情页标记 "(SP Rule: id, …)"；在线版样本不符时回退内置同 id 规则
 *            "note": "来源说明",
 *            "when": [Cond, ...],                // 全部成立
 *            "any": [[Cond, ...], ...],          // 可选：至少一组全部成立
 *            "then": Then,
 *            "samples": [Sample, ...] }          // 实卡样本，加载时校验
 * Then   = { "targets": [Target, ...],           // 求解：查找目标，按序
 *            "city": "1620",                     // 求解：交易实际城市（标准码，显示/统计，不再经库别名）；不影响查找
 *            "family": "bus" | "rail",           // 求解：覆盖 subtype 推断的交通族
 *            "line": Line }                      // 线路号：库未给出线路/站点的公交记录显示的线路号
 * Target = { "prefix": "5880" | "city": "4610",  // prefix = 设备前缀桶（device_code 前 4 位 = 卡上写的码）；
 *                                                // city = 按 city_id 查所有含该城市 TU 设备的前缀桶，只取该城市设备
 *            "family": "bus" | "rail",           // 可选：覆盖本目标的交通族
 *            "mode": "overlap" | "stationPrefix" | "key",
 *                                                // 缺省 overlap（最长重叠评分）；stationPrefix = 设备码去前缀后须为
 *                                                //   line+station 段（8 位）的前缀，取最长；key = 设备码去前缀后须
 *                                                //   等于 `key` 切片拼成的查找键（线路、站点不相邻，如贵阳地铁）
 *            "key": [[22, 24], [26, 28]],        // 仅 key 模式：1E 记录 hex 串的字符下标 [a, b) 列表，按序拼接；
 *                                                //   任一切片缺失 / 含未知半字节则本目标不查
 *            "types": ["城际"],                  // 仅 stationPrefix / key：限定 transit_type（精确）
 *            "fallbackBelow": 8 }                // 可选：前面目标最佳重叠 < n 才查，命中即取代
 * Cond   = { "rec": "1E" | "18", "bytes": [a, b] | "nibbles": [a, b],
 *            以下之一: "eq": "0101" | "in": ["0", "1"] | "regex": "^[01]" | "range": [lo, hi], "radix": 10,
 *            "not": false }
 *          | { "target": "6020" | ["1270", …] }  // 仅 line 规则：当前套用的目标码（前缀或城市码）
 * Line   = "固定线路名称" | { "rec": "1E", "nibbles": [a, b], "radix": 10 | 16,
 *            "ops": [["mod", 1000], ["div", n], ["add", n], ["sub", n], ["mul", n]], "min": 1, "max": n,
 *            "prefix": "专", "append": "路" }   // 数值检查通过后拼接可选的前后缀
 * Sample = { "1E": "<hex>", "18": "<hex>",      // 至少其一；实卡记录，未知半字节写 '?'（如只知线路字段的样本）
 *            "expect": { "city": "1620", "targets": ["8040"], "line": "747",
 *                        "device": "8040…" } }   // 均可选；device 只由 tools/line_rules.py 对库校验
 * ```
 * - 切片：`bytes` 为记录字节下标 [a, b)，取大写 hex（BCD 城市码 5810 即 "5810"）；`nibbles` 为记录 hex 串的
 *   字符下标 [a, b)。常用位置：1E 城市码 bytes [32, 34]，收单机构 [34, 42]，线路站点字段 [10, 16]（nibbles 20–32）。
 *   `18` 指与 1E 同一时间戳配对的 TU 钱包 0x18 记录；只有 0x18（1E 已轮换掉）时 `1E` 缺失。
 * - 记录缺失、切片越界或含未知半字节视为「不匹配」，因此加 `not` 的条件此时成立。
 * - 求解：按文件顺序，命中且 then 含 targets / city / family 的规则全部叠加，各项取第一条给出者；
 *   只有 0x18 时没有记录城市，须有命中规则给出 `targets`。多目标按序评分，同等时取靠前的目标；
 *   命中设备的城市一律取其 city_id。
 * - 线路号：按计划目标依次，取文件中第一条成立且数值有效的 line 规则；数值不可解析 / 越出 min–max 视为不成立。
 * - 兼容：`format` 不等于 [FORMAT] 的文件整体忽略；规则含未知键 / 未知运算只丢弃该条。
 * - 样本按全部规则整体求值并比对；不符的规则被丢弃（坏规则上线也不会显示错名）。
 */
internal object TuLineRules {

    const val FORMAT = 2

    class RuleSet(
        val version: String,
        internal val rules: List<Rule>,
        /** 解析失败 / 样本不符被丢弃的规则：id → 文件中的位置 */
        internal val droppedIds: Map<String, Int>,
        /** 被丢弃规则的说明，供日志与测试检查 */
        val dropped: List<String>
    ) {
        /** 本文件丢弃的规则按 id 用 [bundled] 中的同 id 规则补回（原位置）。 */
        fun withFallback(bundled: RuleSet?): RuleSet {
            if (bundled == null || bundled === this || droppedIds.isEmpty()) return this
            val present = rules.map { it.id }.toSet()
            val restored = bundled.rules
                .filter { it.id in droppedIds && it.id !in present }
                .map { it.at(droppedIds.getValue(it.id)) }
            if (restored.isEmpty()) return this
            return RuleSet(version, (rules + restored).sortedBy { it.position }, emptyMap(), dropped)
        }

        companion object {
            val EMPTY = RuleSet("", emptyList(), emptyMap(), emptyList())
        }
    }

    @Volatile
    private var installed: RuleSet = RuleSet.EMPTY

    fun install(set: RuleSet) {
        installed = set
    }

    /** 当前生效规则的 version（yyyyMMddHHmmss）；未安装为空串。 */
    fun installedVersion(): String = installed.version

    /**
     * 一个查找目标，交给 TransitData 的评分器。
     * [byCity] = false：[code] 是设备前缀桶（device_code 前 4 位，即卡上写的码），查桶内全部设备；
     * [byCity] = true：[code] 是城市（city_id 对应的城市码），查所有含该城市 TU 设备的前缀桶，只取该城市的设备
     * （前缀不可靠：如济宁设备也在 3000 前缀，佛山设备也在 5810/2900 前缀）。
     */
    class Target(
        val code: String,
        val byCity: Boolean = false,
        val family: TransitData.TuTransitFamily? = null,
        val stationPrefix: Boolean = false,
        val types: Set<String>? = null,
        val fallbackBelow: Int? = null,
        /** key 模式：1E 记录 hex 切片 [a, b)，按序拼成查找键；null = 非 key 模式 */
        val keySlices: List<IntRange>? = null,
        /** key 模式求解时由记录算出的查找键；切片不可得时为 null（本目标不查） */
        val key: String? = null
    ) {
        val keyMode: Boolean get() = keySlices != null

        /** key 模式：按本笔记录算出查找键（[solve] 时调用）；其他模式原样返回。 */
        internal fun bind(records: Records): Target {
            val slices = keySlices ?: return this
            val journey = records.journey
            val value = journey?.let { j ->
                slices.map { j.sliceKnown(it.first, it.last + 1) ?: return@let null }.joinToString("")
            }
            return Target(code, byCity, family, stationPrefix, types, fallbackBelow, slices, value)
        }
    }

    /**
     * 一条交易的查找计划。
     * @param recordCity 1E 记录城市码（byte 32–33）；只有 0x18 时为 null
     * @param city       规则声明的实际城市（标准码）；null = 沿用记录城市并经库别名解析
     * @param family     规则覆盖的交通族；null = 按 subtype 推断
     * @param ruleIds    参与求解的规则 id（文件顺序）；空 = 默认计划
     */
    class Plan(
        val recordCity: String?,
        val city: String?,
        val family: TransitData.TuTransitFamily?,
        val targets: List<Target>,
        val ruleIds: List<String>
    ) {
        /** 详情页 Match 行标记；默认计划为 null。 */
        val label: String? get() = label(ruleIds)
    }

    /** 线路号命中：[line] 线路号，[ruleId] 给出它的规则。 */
    class Match(val line: String, val ruleId: String)

    /** 详情页 Match 行标记：求解规则与线路号规则的 id，去重；都没有为 null。 */
    fun label(plan: Plan?, match: Match?): String? =
        label(plan?.ruleIds.orEmpty() + listOfNotNull(match?.ruleId))

    private fun label(ids: List<String>): String? =
        ids.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ", "(SP Rule: ", ")")

    // ---------------------------------------------------------------- 求值

    /**
     * 按原始记录求查找计划。[journey] = TU 1E，[fare] = 同一笔交易的 TU 0x18（可缺）。
     * 有 1E 时总有计划（无规则命中即默认计划）；只有 0x18 且无命中规则给出 targets 时返回 null。
     */
    fun solve(journey: ByteArray?, fare: ByteArray?): Plan? = solveWith(installed.rules, Records.of(journey, fare))

    /** 1E 记录（及配对的 0x18）求解后推算线路号；无规则 / 不命中返回 null。 */
    fun match(record: ByteArray, fare: ByteArray? = null): Match? {
        val records = Records.of(record, fare)
        val plan = solveWith(installed.rules, records) ?: return null
        return lineWith(installed.rules, plan, records)
    }

    /** 按已求得的计划，在各目标依次套线路号规则。 */
    fun match(plan: Plan, record: ByteArray, fare: ByteArray? = null): Match? =
        lineWith(installed.rules, plan, Records.of(record, fare))

    private fun solveWith(rules: List<Rule>, records: Records): Plan? {
        var targets: List<Target>? = null
        var city: String? = null
        var family: TransitData.TuTransitFamily? = null
        val fired = mutableListOf<String>()
        for (rule in rules) {
            if (!rule.solves || !rule.matches(records, null)) continue
            fired += rule.id
            targets = targets ?: rule.targets
            city = city ?: rule.city
            family = family ?: rule.family
        }
        val recordCity = records.recordCity
        val searched = targets?.map { it.bind(records) } ?: recordCity?.let { listOf(Target(it)) } ?: return null
        return Plan(recordCity, city, family, searched, fired)
    }

    private fun lineWith(rules: List<Rule>, plan: Plan, records: Records): Match? {
        val journey = records.journey ?: return null
        for (code in plan.targets.map { it.code }.distinct()) {
            for (rule in rules) {
                val line = rule.line ?: continue
                if (!rule.matches(records, code)) continue
                line(journey)?.let { return Match(it, rule.id) }
            }
        }
        return null
    }

    /** 1E / 0x18 原始记录的大写 hex；样本中未知半字节为 '?'。1E 不足 34 字节视为缺失。 */
    internal class Records(val journey: String?, val fare: String?) {
        val recordCity: String? = journey?.sliceKnown(64, 68)

        fun get(rec: String): String? = if (rec == REC_FARE) fare else journey

        companion object {
            fun of(journey: ByteArray?, fare: ByteArray?) = Records(
                journey?.takeIf { it.size >= 34 }?.let(ApduUtil::bytesToHex),
                fare?.let(ApduUtil::bytesToHex)
            )
        }
    }

    internal class Rule(
        val id: String,
        val position: Int,
        private val conditions: List<Condition>,
        private val anyOf: List<List<Condition>>,
        /** `target` 条件：仅 line 规则；null = 任一目标 */
        private val targetCodes: Set<String>?,
        val targets: List<Target>?,
        val city: String?,
        val family: TransitData.TuTransitFamily?,
        val line: ((String) -> String?)?,
        val samples: List<Sample>
    ) {
        /** 是否参与求解（给出 targets / city / family） */
        val solves: Boolean get() = targets != null || city != null || family != null

        fun matches(records: Records, target: String?): Boolean =
            (targetCodes == null || (target != null && target in targetCodes)) &&
                conditions.all { it.test(records) } &&
                (anyOf.isEmpty() || anyOf.any { group -> group.all { it.test(records) } })

        fun at(newPosition: Int) =
            Rule(id, newPosition, conditions, anyOf, targetCodes, targets, city, family, line, samples)
    }

    internal class Sample(
        val records: Records,
        val city: String?,
        val targets: List<String>?,
        val line: String?
    )

    internal class Condition(
        private val select: (Records) -> String?,
        private val negate: Boolean,
        private val check: (String) -> Boolean
    ) {
        fun test(records: Records): Boolean = (select(records)?.let(check) ?: false) != negate
    }

    private class LineSpec(
        private val start: Int,
        private val end: Int,
        private val radix: Int,
        private val ops: List<Pair<String, Long>>,
        private val min: Long?,
        private val max: Long?,
        private val prefix: String,
        private val append: String
    ) {
        fun compute(journey: String): String? {
            var v = journey.sliceKnown(start, end)?.toLongOrNull(radix) ?: return null
            for ((op, n) in ops) {
                v = when (op) {
                    "mod" -> v % n
                    "div" -> v / n
                    "add" -> v + n
                    "sub" -> v - n
                    else -> v * n  // "mul"（解析时已限定运算名）
                }
            }
            if (min != null && v < min) return null
            if (max != null && v > max) return null
            return "$prefix$v$append"
        }
    }

    /** hex 串字符 [start, end)；越界或含未知半字节为 null。 */
    private fun String.sliceKnown(start: Int, end: Int): String? =
        if (end <= length) substring(start, end).takeIf { '?' !in it } else null

    // ---------------------------------------------------------------- 解析

    /** 解析规则文件。文件损坏 / format 不符抛 [IllegalArgumentException]；单条规则问题只记入 dropped。 */
    fun parse(json: String): RuleSet {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("line_rules: invalid JSON (${e.message})", e)
        }
        val format = root.get("format")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw IllegalArgumentException("line_rules: missing format")
        require(format == FORMAT) { "line_rules: unsupported format $format (app supports $FORMAT)" }
        val version = root.str("version").orEmpty()
        val rulesJson = root.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalArgumentException("line_rules: missing rules")

        val dropped = mutableListOf<String>()
        val droppedIds = mutableMapOf<String, Int>()
        val seen = mutableSetOf<String>()
        var rules = rulesJson.mapIndexedNotNull { i, el ->
            val id = el.takeIf { it.isJsonObject }?.asJsonObject?.str("id")
            try {
                require(!id.isNullOrBlank()) { "missing id" }
                require(seen.add(id)) { "duplicate id" }
                parseRule(el.asJsonObject, id, i)
            } catch (e: Exception) {
                dropped += "#$i ${id.orEmpty()}: ${e.message}"
                if (!id.isNullOrBlank() && id !in droppedIds) droppedIds[id] = i
                null
            }
        }
        // 样本按全部规则整体求值；不符的规则丢弃后重验，直到稳定
        while (true) {
            val bad = rules.firstOrNull { rule -> rule.samples.any { !sampleHolds(rules, rule, it) } } ?: break
            dropped += "#${bad.position} ${bad.id}: sample mismatch"
            droppedIds[bad.id] = bad.position
            rules = rules - bad
        }
        return RuleSet(version, rules, droppedIds, dropped)
    }

    private fun sampleHolds(rules: List<Rule>, rule: Rule, sample: Sample): Boolean {
        val plan = solveWith(rules, sample.records) ?: return false
        if (rule.solves && rule.id !in plan.ruleIds) return false
        if (sample.city != null && sample.city != (plan.city ?: plan.recordCity)) return false
        if (sample.targets != null && sample.targets != plan.targets.map { it.code }) return false
        if (rule.line != null) {
            val match = lineWith(rules, plan, sample.records)
            if (sample.line != null && sample.line != match?.line) return false
        }
        return true
    }

    private const val REC_JOURNEY = "1E"
    private const val REC_FARE = "18"
    private val RULE_KEYS = setOf("id", "note", "when", "any", "then", "samples")
    private val THEN_KEYS = setOf("targets", "city", "family", "line")
    private val TARGET_KEYS = setOf("prefix", "city", "family", "mode", "types", "fallbackBelow", "key")
    private val COND_KEYS = setOf("rec", "bytes", "nibbles", "eq", "in", "regex", "range", "radix", "not")
    private val LINE_KEYS = setOf("rec", "nibbles", "radix", "ops", "min", "max", "prefix", "append")
    private val SAMPLE_KEYS = setOf(REC_JOURNEY, REC_FARE, "expect")
    private val EXPECT_KEYS = setOf("city", "targets", "line", "device")
    private val CITY_CODE = Regex("[0-9]{4}")
    private val SAMPLE_HEX = Regex("(?:[0-9A-F?]{2})+")
    private val OPS = setOf("mod", "div", "add", "sub", "mul")

    private fun parseRule(o: JsonObject, id: String, position: Int): Rule {
        checkKeys(o, RULE_KEYS, "rule")
        var targetCodes: Set<String>? = null
        val conditions = o.get("when")?.arr("when")?.mapNotNull { el ->
            val c = el.obj("condition")
            if (!c.has("target")) return@mapNotNull parseCondition(c)
            checkKeys(c, setOf("target"), "target condition")
            require(targetCodes == null) { "only one target condition" }
            val t = c.get("target")
            targetCodes = (if (t.isJsonArray) t.asJsonArray.map { it.asString } else listOf(t.asString)).toSet()
                .also { codes -> require(codes.isNotEmpty() && codes.all { it.matches(CITY_CODE) }) { "bad target condition" } }
            null
        }.orEmpty()
        val anyOf = o.get("any")?.arr("any")?.map { group ->
            group.arr("any group").map { parseCondition(it.obj("condition")) }.also {
                require(it.isNotEmpty()) { "empty any group" }
            }
        }.orEmpty()
        require(conditions.isNotEmpty() || anyOf.isNotEmpty() || targetCodes != null) { "rule needs conditions" }

        val then = o.get("then")?.obj("then") ?: error("missing then")
        checkKeys(then, THEN_KEYS, "then")
        require(then.size() > 0) { "then sets nothing" }
        val city = then.str("city")?.also { require(it.matches(CITY_CODE)) { "bad city $it" } }
        require(city != null || !then.has("city")) { "city must be a string" }
        val targets = then.get("targets")?.arr("targets")?.map { parseTarget(it.obj("target")) }
        require(targets == null || targets.isNotEmpty()) { "targets must not be empty" }
        require(targets?.firstOrNull()?.fallbackBelow == null) { "first target cannot be a fallback" }
        val family = parseFamily(then)
        val line = then.get("line")?.let(::parseLine)
        require(targetCodes == null || (line != null && targets == null && city == null && family == null)) {
            "target condition is only for line rules"
        }
        val samples = o.get("samples")?.arr("samples")?.map { parseSample(it.obj("sample")) }.orEmpty()
        return Rule(id, position, conditions, anyOf, targetCodes, targets, city, family, line, samples)
    }

    private fun parseTarget(o: JsonObject): Target {
        checkKeys(o, TARGET_KEYS, "target")
        require(o.has("prefix") != o.has("city")) { "target needs exactly one of prefix / city" }
        val byCity = o.has("city")
        val code = o.str(if (byCity) "city" else "prefix")?.takeIf { it.matches(CITY_CODE) } ?: error("bad target code")
        val mode = o.str("mode") ?: "overlap"
        require(mode in setOf("overlap", "stationPrefix", "key")) { "unknown mode $mode" }
        val stationPrefix = mode == "stationPrefix"
        val keySlices = if (mode == "key") {
            o.get("key")?.arr("key")?.map { el ->
                val a = el.arr("key slice")
                require(a.size() == 2) { "key slice needs [start, end]" }
                val start = a[0].asInt
                val end = a[1].asInt
                require(start in 0 until end) { "bad key slice [$start, $end]" }
                start until end
            }?.takeIf { it.isNotEmpty() } ?: error("mode key needs key slices")
        } else {
            require(!o.has("key")) { "key needs mode key" }
            null
        }
        val types = o.get("types")?.arr("types")?.map { it.asString }?.toSet()
        require(types == null || (mode != "overlap" && types.isNotEmpty())) { "types needs mode stationPrefix / key" }
        val fallbackBelow = o.get("fallbackBelow")?.asInt?.also { require(it > 0) { "bad fallbackBelow" } }
        return Target(code, byCity, parseFamily(o), stationPrefix, types, fallbackBelow, keySlices)
    }

    private fun parseFamily(o: JsonObject): TransitData.TuTransitFamily? = when (val f = o.str("family")) {
        null -> {
            require(!o.has("family")) { "family must be a string" }
            null
        }
        "bus" -> TransitData.TuTransitFamily.BUS
        "rail" -> TransitData.TuTransitFamily.RAIL
        else -> error("unknown family $f")
    }

    private fun parseSample(o: JsonObject): Sample {
        checkKeys(o, SAMPLE_KEYS, "sample")
        val journey = o.str(REC_JOURNEY)?.let(::sampleHex)
        val fare = o.str(REC_FARE)?.let(::sampleHex)
        require(journey != null || fare != null) { "sample needs 1E or 18" }
        val expect = o.get("expect")?.obj("expect")
        expect?.let { checkKeys(it, EXPECT_KEYS, "expect") }
        val city = expect?.str("city")?.also { require(it.matches(CITY_CODE)) { "bad expected city" } }
        val targets = expect?.get("targets")?.arr("targets")?.map { it.asString }
        return Sample(Records(journey?.takeIf { it.length >= 68 }, fare), city, targets, expect?.str("line"))
    }

    private fun sampleHex(hex: String): String {
        require(hex.matches(SAMPLE_HEX)) { "sample hex must be uppercase (unknown nibbles as '?')" }
        return hex
    }

    private fun parseRange(o: JsonObject, key: String): IntRange {
        val a = o.get(key)?.arr(key) ?: error("missing $key")
        require(a.size() == 2) { "$key needs [start, end]" }
        val start = a[0].asInt
        val end = a[1].asInt
        require(start in 0 until end) { "bad $key [$start, $end]" }
        return start until end
    }

    private fun parseCondition(o: JsonObject): Condition {
        checkKeys(o, COND_KEYS, "condition")
        val radix = parseRadix(o)
        val tests = listOfNotNull(
            o.str("eq")?.let { eq -> { s: String -> s == eq } },
            o.get("in")?.arr("in")?.map { it.asString }?.toSet()?.let { set -> { s: String -> s in set } },
            o.str("regex")?.let { Regex(it) }?.let { re -> { s: String -> re.containsMatchIn(s) } },
            o.get("range")?.arr("range")?.let { a ->
                require(a.size() == 2) { "range needs [lo, hi]" }
                val lo = a[0].asLong
                val hi = a[1].asLong
                { s: String -> s.toLongOrNull(radix)?.let { it in lo..hi } ?: false }
            }
        )
        require(tests.size == 1) { "condition needs exactly one of eq / in / regex / range" }
        return Condition(parseSelector(o), o.get("not")?.asBoolean ?: false, tests.single())
    }

    /** `rec` + `bytes` / `nibbles` → 记录 hex 切片（字节下标折算为 hex 字符下标）。 */
    private fun parseSelector(o: JsonObject): (Records) -> String? {
        val rec = o.str("rec") ?: error("condition needs rec")
        require(rec == REC_JOURNEY || rec == REC_FARE) { "unknown rec $rec" }
        require(o.has("bytes") != o.has("nibbles")) { "condition needs exactly one of bytes / nibbles" }
        val range = if (o.has("bytes")) parseRange(o, "bytes").let { it.first * 2 until (it.last + 1) * 2 }
            else parseRange(o, "nibbles")
        return { records -> records.get(rec)?.sliceKnown(range.first, range.last + 1) }
    }

    private fun parseLine(element: JsonElement): (String) -> String? {
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            val label = element.asString
            require(label.isNotBlank()) { "fixed line name must not be blank" }
            return { label }
        }
        val o = element.obj("line")
        checkKeys(o, LINE_KEYS, "line")
        require(o.str("rec") == REC_JOURNEY) { "line reads rec 1E" }
        val ops = o.get("ops")?.arr("ops")?.map { op ->
            val a = op.arr("op")
            require(a.size() == 2) { "op needs [name, n]" }
            val name = a[0].asString
            require(name in OPS) { "unknown op $name" }
            val n = a[1].asLong
            require(!(name == "mod" || name == "div") || n != 0L) { "$name by zero" }
            name to n
        }.orEmpty()
        val prefix = o.str("prefix")
        require(prefix != null || !o.has("prefix")) { "prefix must be a string" }
        val append = o.str("append")
        require(append != null || !o.has("append")) { "append must be a string" }
        val range = parseRange(o, "nibbles")
        return LineSpec(
            range.first, range.last + 1, parseRadix(o), ops, o.get("min")?.asLong, o.get("max")?.asLong,
            prefix.orEmpty(), append.orEmpty()
        )::compute
    }

    private fun parseRadix(o: JsonObject): Int {
        val radix = o.get("radix")?.asInt ?: 10
        require(radix == 10 || radix == 16) { "radix must be 10 or 16" }
        return radix
    }

    private fun checkKeys(o: JsonObject, allowed: Set<String>, what: String) {
        val unknown = o.keySet() - allowed
        require(unknown.isEmpty()) { "unknown $what key ${unknown.joinToString()}" }
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonElement.arr(what: String): JsonArray =
        takeIf { it.isJsonArray }?.asJsonArray ?: error("$what must be an array")

    private fun JsonElement.obj(what: String): JsonObject =
        takeIf { it.isJsonObject }?.asJsonObject ?: error("$what must be an object")
}
