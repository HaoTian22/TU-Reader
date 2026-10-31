package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * `line_rules.json` 的解析与求值：transit.db 未给出线路名时，按文件描述的计算步骤从 TU 1E 记录推算
 * 公交线路号（仅显示：无 lineId / stationId / 配色，不写库）。
 *
 * 分工：具名线路、少量编码一律进 transit.db（tripreader-data CSV）；规则文件只放库装不下的
 * 「整段编码 → 线路号」公式（如东莞 999 条、宁波主线 504 条）。输出是纯数字线路号，与界面语言无关；
 * 需要拼接/命名的（如宁波支线 703-4）留在库里。
 * 文件与 transit.db 并列、独立发布（[LineRulesStore]）；App 只执行固定步骤，不执行代码。
 *
 * ## 文件格式（format = 1）
 * ```
 * { "format": 1, "version": "yyyyMMddHHmmss", "rules": [Rule, ...] }
 * Rule   = { "city": "6020" | ["1270", "1410", …], "type": "公交", "note": "来源说明",   // 多城共用同一规则可写数组
 *            "when": [Cond, ...],          // 全部成立才应用；可省略
 *            "value": Value,
 *            "samples": [Sample, ...] }    // 样本，加载时校验
 * Cond   = { "field": "line" | "city2", "slice": [a, b], 以下之一: "eq": "0101" | "in": ["0","1"]
 *            | "regex": "^[01]" | "range": [lo, hi], "radix": 10, "not": false }
 * Value  = { "slice": [a, b], "radix": 10 | 16,
 *            "ops": [["mod", 1000], ["div", n], ["add", n], ["sub", n], ["mul", n]], "min": 1, "max": n }
 * Sample = { "record": "<1E 完整 hex>" } 或 { "line": "<hex>", "city": "…", "city2": "…" }，加 "out": 期望线路号
 *          （line 样本的 city 缺省取规则的第一个城市）
 * ```
 * - `line` = 1E byte 10–15 hex（12 位）；
 *   `city2` = byte 36–37 BCD；城市码 = byte 32–33 BCD。Value 只取 `line`。
 * - 条件：字段缺失或 slice 越界视为「不匹配」，因此加 `not` 的条件此时成立。
 * - 同城规则按文件顺序取第一条命中。任一数值不可解析 / 越出 min–max 视为不命中，继续下一条。
 * - 兼容：`format` 高于 [FORMAT] 的文件整体忽略；规则含未知键 / 未知运算 / 非「公交」类型只跳过该条
 *   （新能力按「新增键」扩展，语义不兼容才升 format）。
 * - 样本按同城全部规则整体求值并比对；不符的规则被丢弃（坏规则上线也不会显示错名）。
 */
internal object TuLineRules {

    const val FORMAT = 1
    private const val BUS = "公交"

    class RuleSet(
        val version: String,
        internal val rules: List<Rule>,
        /** 被跳过/丢弃的规则说明（未知键、样本不符等），供日志与测试检查 */
        val dropped: List<String>
    ) {
        companion object {
            val EMPTY = RuleSet("", emptyList(), emptyList())
        }
    }

    @Volatile
    private var installed: RuleSet = RuleSet.EMPTY

    fun install(set: RuleSet) {
        installed = set
    }

    /** 规则命中结果：[line] 线路号，[tag] 详情页 Match 行标记（城市码 + 规则在文件中的序号）。 */
    class Match(val line: String, val tag: String)

    /** 记录所在城市的规则推算线路号；无规则 / 不命中返回 null。 */
    fun match(record: ByteArray): Match? {
        if (record.size < 34) return null
        val input = Input(
            city = ApduUtil.bcdToString(record.copyOfRange(32, 34)),
            line = ApduUtil.bytesToHex(record.copyOfRange(10, 16)),
            city2 = if (record.size >= 38) ApduUtil.bcdToString(record.copyOfRange(36, 38)) else null
        )
        val (rule, line) = find(installed.rules, input) ?: return null
        return Match(line, "(Line Rule: ${input.city} #${rule.index})")
    }

    // ---------------------------------------------------------------- 求值

    internal class Input(val city: String, val line: String, val city2: String?) {
        fun field(name: String): String? = if (name == "city2") city2 else line
    }

    internal class Rule(
        val cities: Set<String>,
        /** 规则在文件 rules 数组中的下标，用于 Match 标记定位 */
        val index: Int,
        val label: String,
        val conditions: List<Condition>,
        val value: ValueSpec,
        val samples: List<Pair<Input, String>>
    )

    internal class Condition(
        private val field: String,
        private val range: IntRange?,
        private val negate: Boolean,
        private val check: (String) -> Boolean
    ) {
        fun test(input: Input): Boolean {
            val text = input.field(field)?.let { if (range == null) it else it.sliceOrNull(range) }
            return (text != null && check(text)) != negate
        }
    }

    internal class ValueSpec(
        private val range: IntRange,
        private val radix: Int,
        private val ops: List<Pair<String, Long>>,
        private val min: Long?,
        private val max: Long?
    ) {
        fun compute(line: String): Long? {
            var v = line.sliceOrNull(range)?.toLongOrNull(radix) ?: return null
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
            return v
        }
    }

    /** 同城规则按顺序取第一条命中：返回 (命中规则, 线路号)。 */
    private fun find(rules: List<Rule>, input: Input): Pair<Rule, String>? {
        for (rule in rules) {
            if (input.city !in rule.cities || rule.conditions.any { !it.test(input) }) continue
            rule.value.compute(input.line)?.let { return rule to it.toString() }
        }
        return null
    }

    private fun String.sliceOrNull(range: IntRange): String? =
        if (range.last < length) substring(range.first, range.last + 1) else null

    // ---------------------------------------------------------------- 解析

    /** 解析规则文件。文件损坏 / format 不支持抛 [IllegalArgumentException]；单条规则问题只记入 dropped。 */
    fun parse(json: String): RuleSet {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("line_rules: invalid JSON (${e.message})", e)
        }
        val format = root.get("format")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw IllegalArgumentException("line_rules: missing format")
        require(format <= FORMAT) { "line_rules: unsupported format $format (app supports $FORMAT)" }
        val version = root.str("version").orEmpty()
        val rulesJson = root.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalArgumentException("line_rules: missing rules")

        val dropped = mutableListOf<String>()
        var rules = rulesJson.mapIndexedNotNull { i, el ->
            val note = el.takeIf { it.isJsonObject }?.asJsonObject?.str("note").orEmpty()
            val label = "#$i $note".trim()
            try {
                parseRule(el.asJsonObject, i, label)
            } catch (e: Exception) {
                dropped += "$label: ${e.message}"
                null
            }
        }
        // 样本校验：按同城全部规则整体求值；不符的规则丢弃后重验，直到稳定
        while (true) {
            val bad = rules.firstOrNull { rule ->
                rule.samples.any { (input, out) -> find(rules, input)?.second != out }
            } ?: break
            dropped += "${bad.label}: sample mismatch"
            rules = rules - bad
        }
        return RuleSet(version, rules, dropped)
    }

    private val RULE_KEYS = setOf("city", "type", "note", "when", "value", "samples")
    private val COND_KEYS = setOf("field", "slice", "eq", "in", "regex", "range", "radix", "not")
    private val VALUE_KEYS = setOf("slice", "radix", "ops", "min", "max")
    private val FIELDS = setOf("line", "city2")
    private val OPS = setOf("mod", "div", "add", "sub", "mul")

    private fun parseRule(o: JsonObject, index: Int, label: String): Rule {
        checkKeys(o, RULE_KEYS, "rule")
        val cityEl = o.get("city") ?: error("missing city")
        val cities = if (cityEl.isJsonArray) cityEl.asJsonArray.map { it.asString } else listOf(cityEl.asString)
        require(cities.isNotEmpty() && cities.all { it.isNotBlank() }) { "bad city" }
        val type = o.str("type") ?: BUS
        require(type == BUS) { "unsupported type $type" }
        val conditions = o.get("when")?.arr("when")?.map { parseCondition(it.asJsonObject) }.orEmpty()
        val value = parseValue(o.get("value")?.asJsonObject ?: error("missing value"))
        val samples = o.get("samples")?.arr("samples")?.map { parseSample(it.asJsonObject, cities) }.orEmpty()
        return Rule(cities.toSet(), index, label, conditions, value, samples)
    }

    private fun parseSlice(o: JsonObject): IntRange {
        val a = o.get("slice")?.arr("slice") ?: error("missing slice")
        require(a.size() == 2) { "slice needs [start, end]" }
        val start = a[0].asInt
        val end = a[1].asInt
        require(start in 0 until end) { "bad slice [$start, $end]" }
        return start until end
    }

    private fun parseCondition(o: JsonObject): Condition {
        checkKeys(o, COND_KEYS, "condition")
        val field = o.str("field") ?: "line"
        require(field in FIELDS) { "unknown field $field" }
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
        val range = if (o.has("slice")) parseSlice(o) else null
        return Condition(field, range, o.get("not")?.asBoolean ?: false, tests.single())
    }

    private fun parseValue(o: JsonObject): ValueSpec {
        checkKeys(o, VALUE_KEYS, "value")
        val ops = o.get("ops")?.arr("ops")?.map { op ->
            val a = op.arr("op")
            require(a.size() == 2) { "op needs [name, n]" }
            val name = a[0].asString
            require(name in OPS) { "unknown op $name" }
            val n = a[1].asLong
            require(!(name == "mod" || name == "div") || n != 0L) { "$name by zero" }
            name to n
        }.orEmpty()
        return ValueSpec(parseSlice(o), parseRadix(o), ops, o.get("min")?.asLong, o.get("max")?.asLong)
    }

    private fun parseSample(o: JsonObject, cities: List<String>): Pair<Input, String> {
        checkKeys(o, setOf("record", "line", "city", "city2", "out"), "sample")
        val input = o.str("record")?.let { hex ->
            val bytes = ApduUtil.hexToBytes(hex)
            require(bytes.size >= 38) { "sample record too short" }
            val recordCity = ApduUtil.bcdToString(bytes.copyOfRange(32, 34))
            require(recordCity in cities) { "sample record city $recordCity not in $cities" }
            Input(recordCity, ApduUtil.bytesToHex(bytes.copyOfRange(10, 16)), ApduUtil.bcdToString(bytes.copyOfRange(36, 38)))
        } ?: Input(
            o.str("city")?.also { require(it in cities) { "sample city $it not in $cities" } } ?: cities.first(),
            o.str("line") ?: error("sample needs record or line"), o.str("city2"))
        return input to (o.str("out") ?: error("sample needs out"))
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
}
