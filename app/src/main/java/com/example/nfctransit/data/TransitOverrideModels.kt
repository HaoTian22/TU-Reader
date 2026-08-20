package com.example.nfctransit.data

import java.util.LinkedHashMap

const val OVERRIDE_HEADER = "Prefix,Code,Type,Line,Station"

data class TransitOverrideRow(
    val prefix: String,
    val code: String,
    val type: String,
    val line: String,
    val station: String
) {
    val deviceCode: String get() = prefix + code
}

data class OverrideImportSummary(
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList()
) {
    fun message(): String = buildString {
        append("本地覆盖已导入：新增 ").append(added)
            .append(" 条，修改 ").append(updated)
            .append(" 条，未变化 ").append(unchanged)
        if (skipped > 0) append("，跳过 ").append(skipped).append(" 条")
        if (errors.isNotEmpty()) append("，失败 ").append(errors.size).append(" 条")
    }
}

data class FeedbackOverride(
    val row: TransitOverrideRow,
    val standard: String,
    val publish: Boolean
)

data class OverrideSnapshot(
    val rows: LinkedHashMap<String, TransitOverrideRow>,
    val standards: MutableMap<String, String>
)
