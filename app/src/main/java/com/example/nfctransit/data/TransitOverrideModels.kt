package com.example.nfctransit.data

import com.example.nfctransit.data.db.ReaderDeviceEntity
import java.util.LinkedHashMap
import com.example.nfctransit.R
import com.example.nfctransit.util.L10n

const val OVERRIDE_HEADER = "Prefix,Code,Type,Line,Station"

/** JSON sidecar 的稳定键；编号只允许字母数字，分隔符不会与编号混淆。 */
fun deviceMappingKey(deviceCode: String, transitType: String): String = "$deviceCode|$transitType"

data class TransitOverrideRow(
    val prefix: String,
    val code: String,
    val type: String,
    val line: String,
    val station: String,
    val locationCityCode: String? = null
) {
    val deviceCode: String get() = prefix + code
    val mappingKey: String get() = deviceMappingKey(deviceCode, type)
}

data class OverrideImportSummary(
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList()
) {
    fun message(): String = buildString {
        append(L10n.str(R.string.override_import_summary, added, updated, unchanged))
        if (skipped > 0) append(L10n.str(R.string.override_import_skipped, skipped))
        if (errors.isNotEmpty()) append(L10n.str(R.string.override_import_failed, errors.size))
    }
}

data class FeedbackOverride(
    val row: TransitOverrideRow,
    val standard: String,
    val publish: Boolean,
    val locationCityCode: String? = null,
    val locationCityName: String? = null
)

data class OverrideSnapshot(
    val rows: LinkedHashMap<String, TransitOverrideRow>,
    val standards: MutableMap<String, String>,
    val locations: MutableMap<String, String> = mutableMapOf(),
    val originals: MutableMap<String, ReaderDeviceEntity?> = mutableMapOf()
)

data class OverrideRemoval(
    val row: TransitOverrideRow,
    val original: ReaderDeviceEntity?,
    val hasOriginal: Boolean
)
