package com.example.nfctransit.data.db

/**
 * 从 AI 回复中抽出 SQL：去掉说明文字、注释和 Markdown 代码块标记。
 * 规则：
 * 1. 有 ``` 代码块时，优先取以 SELECT/WITH 开头的那块，否则取第一块；
 * 2. 从第一行以 SELECT/WITH 开头的语句起截取（跳过前面的说明和 -- 注释）；
 * 3. 遇到空行后接非 SQL 行（说明/备注）即停，再截到引号外的第一个分号。
 * 找不到 SELECT/WITH 时原样返回（trim 后），交给查询引擎报错提示。
 */
object SqlTextExtractor {

    private val FENCE = Regex("```[^\\n`]*\\n(.*?)(?:```|$)", RegexOption.DOT_MATCHES_ALL)
    private val SELECT_START = Regex("^SELECT\\b", RegexOption.IGNORE_CASE)
    // WITH 必须像 CTE（WITH name AS (…）），避免把 "With this query…" 之类的说明当成 SQL
    private val WITH_START = Regex(
        "^WITH\\s+(RECURSIVE\\s+)?[\"`\\[]?\\w+[\"`\\]]?\\s*(\\([^)]*\\)\\s*)?AS\\s*(NOT\\s+)?(MATERIALIZED\\s*)?\\(",
        RegexOption.IGNORE_CASE
    )
    // 空行之后若以这些开头，仍视为 SQL 续行
    private val CONTINUATION = Regex(
        "^(\\)|,|--|/\\*|SELECT|FROM|WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|CROSS|NATURAL|ON|USING|AND|OR|NOT|" +
            "GROUP|ORDER|HAVING|LIMIT|OFFSET|UNION|INTERSECT|EXCEPT|WINDOW|CASE|WHEN|THEN|ELSE|END|AS)\\b|^[),]",
        RegexOption.IGNORE_CASE
    )

    fun extract(raw: String): String {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) return ""
        val blocks = FENCE.findAll(text).map { it.groupValues[1] }.toList()
        val source = blocks.firstOrNull { findStart(it) != null } ?: blocks.firstOrNull() ?: text
        val start = findStart(source) ?: return unwrapInline(source.trim())
        return unwrapInline(cutStatement(source.substring(start)).trim())
    }

    /** 返回第一个以 SELECT/WITH 开头的行（去掉行首空白、反引号、引用符）在 [text] 中的偏移。 */
    private fun findStart(text: String): Int? {
        var offset = 0
        for (line in text.split('\n')) {
            val lead = line.indexOfFirst { !it.isWhitespace() && it != '`' && it != '>' }
            if (lead >= 0) {
                val rest = text.substring(offset + lead)
                if (SELECT_START.containsMatchIn(rest) || WITH_START.containsMatchIn(rest)) {
                    return offset + lead
                }
            }
            offset += line.length + 1
        }
        return null
    }

    private fun cutStatement(sql: String): String {
        // 先按空行截掉尾部说明，再在剩余部分找分号（说明里的分号不应把说明带进来）
        val statement = cutTrailingProse(sql)
        val semicolon = semicolonOutsideQuotes(statement)
        return if (semicolon >= 0) statement.substring(0, semicolon + 1) else statement
    }

    private fun cutTrailingProse(sql: String): String {
        val lines = sql.split('\n')
        val kept = ArrayList<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() } ?: break
                if (!CONTINUATION.containsMatchIn(next.trim())) break
            }
            kept += line
            index++
        }
        return kept.joinToString("\n")
    }

    private fun semicolonOutsideQuotes(sql: String): Int {
        var quote: Char? = null
        var index = 0
        while (index < sql.length) {
            val character = sql[index]
            if (quote != null) {
                if (character == quote) {
                    if (index + 1 < sql.length && sql[index + 1] == quote) index++ else quote = null
                }
            } else if (character == '\'' || character == '"') {
                quote = character
            } else if (character == ';') {
                return index
            }
            index++
        }
        return -1
    }

    /** 去掉整句外层的行内反引号：`SELECT …` */
    private fun unwrapInline(sql: String): String {
        var result = sql
        if (result.startsWith("`")) result = result.trimStart('`')
        if (result.endsWith("`")) result = result.trimEnd('`')
        return result.trim()
    }
}
