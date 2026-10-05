package com.example.nfctransit.data

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.example.nfctransit.data.db.ReaderDeviceEntity
import java.io.File
import java.nio.charset.StandardCharsets
import com.example.nfctransit.R
import com.example.nfctransit.util.L10n

object TransitOverrideStore {
    private const val CSV_NAME = "overrides.csv"
    private const val META_NAME = "overrides.meta.json"
    private const val LOCATION_NAME = "overrides.locations.json"
    private const val ORIGINAL_NAME = "overrides.originals.json"
    private val gson = GsonBuilder().serializeNulls().create()

    fun csvFile(context: Context): File = File(context.filesDir, CSV_NAME)

    private fun metaFile(directory: File): File = File(directory, META_NAME)

    private fun locationFile(directory: File): File = File(directory, LOCATION_NAME)

    private fun originalFile(directory: File): File = File(directory, ORIGINAL_NAME)

    fun read(context: Context): OverrideSnapshot = read(context.filesDir)

    @Synchronized
    internal fun read(directory: File): OverrideSnapshot {
        val rows = LinkedHashMap<String, TransitOverrideRow>()
        val file = File(directory, CSV_NAME)
        if (file.isFile) {
            file.useLines { lines ->
                val all = lines.toList()
                if (all.isEmpty() || all.first() != OVERRIDE_HEADER) {
                    throw IllegalStateException(L10n.str(R.string.override_csv_bad_header))
                }
                all.drop(1).forEachIndexed { index, line ->
                    if (line.isBlank()) return@forEachIndexed
                    parseCsvLine(line)?.let { row ->
                        rows[row.mappingKey] = row
                    } ?: throw IllegalStateException(L10n.str(R.string.override_csv_bad_row, index + 2))
                }
            }
        }
        val standards = mutableMapOf<String, String>()
        val meta = metaFile(directory)
        if (meta.isFile) {
            val type = object : TypeToken<Map<String, String>>() {}.type
            runCatching {
                standards.putAll(gson.fromJson<Map<String, String>>(meta.readText(), type).orEmpty())
            }
        }
        val locations = mutableMapOf<String, String>()
        val location = locationFile(directory)
        if (location.isFile) {
            val type = object : TypeToken<Map<String, String>>() {}.type
            runCatching {
                locations.putAll(gson.fromJson<Map<String, String>>(location.readText(), type).orEmpty())
            }
        }
        val originals = mutableMapOf<String, ReaderDeviceEntity?>()
        val originalsFile = originalFile(directory)
        if (originalsFile.isFile) {
            val type = object : TypeToken<Map<String, ReaderDeviceEntity?>>() {}.type
            runCatching {
                originals.putAll(gson.fromJson<Map<String, ReaderDeviceEntity?>>(originalsFile.readText(), type).orEmpty())
            }
        }
        // 旧 sidecar 按编号存储；从对应 CSV 行补上类型，保留原映射及显式 null。
        migrateKeys(standards, rows.values)
        migrateKeys(locations, rows.values)
        migrateKeys(originals, rows.values)
        return OverrideSnapshot(rows, standards, locations, originals)
    }

    private fun <T> migrateKeys(values: MutableMap<String, T>, rows: Collection<TransitOverrideRow>) {
        for (row in rows) {
            if (values.containsKey(row.deviceCode)) {
                if (!values.containsKey(row.mappingKey)) {
                    values[row.mappingKey] = values.getValue(row.deviceCode)
                }
                values.remove(row.deviceCode)
            }
        }
    }

    fun upsert(
        context: Context,
        feedback: FeedbackOverride,
        original: ReaderDeviceEntity? = null
    ) = upsert(context.filesDir, feedback, original)

    @Synchronized
    internal fun upsert(directory: File, feedback: FeedbackOverride, original: ReaderDeviceEntity? = null) {
        val snapshot = read(directory)
        val key = feedback.row.mappingKey
        if (!snapshot.rows.containsKey(key)) {
            snapshot.originals[key] = original
        }
        snapshot.rows[key] = feedback.row
        snapshot.standards[key] = feedback.standard
        feedback.locationCityCode?.takeIf { it.isNotBlank() }?.let {
            snapshot.locations[key] = it
        } ?: snapshot.locations.remove(key)
        writeSnapshot(directory, snapshot)
    }

    fun remove(context: Context, key: String): OverrideRemoval? = remove(context.filesDir, key)

    @Synchronized
    internal fun remove(directory: File, key: String): OverrideRemoval? {
        val snapshot = read(directory)
        val row = snapshot.rows.remove(key) ?: return null
        val hasOriginal = snapshot.originals.containsKey(key)
        val removal = OverrideRemoval(row, snapshot.originals.remove(key), hasOriginal)
        snapshot.standards.remove(key)
        snapshot.locations.remove(key)
        writeSnapshot(directory, snapshot)
        return removal
    }

    fun list(context: Context): List<TransitOverrideRow> {
        val snapshot = read(context)
        return snapshot.rows.values.map { row ->
            row.copy(locationCityCode = snapshot.locations[row.mappingKey])
        }
    }

    private fun writeSnapshot(directory: File, snapshot: OverrideSnapshot) {
        writeCsv(directory, snapshot.rows.values)
        writeMeta(directory, snapshot.standards)
        writeLocations(directory, snapshot.locations)
        writeOriginals(directory, snapshot.originals)
    }

    private fun writeCsv(directory: File, rows: Collection<TransitOverrideRow>) {
        val destination = File(directory, CSV_NAME)
        val temp = File(directory, "$CSV_NAME.tmp")
        temp.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.appendLine(OVERRIDE_HEADER)
            rows.forEach { row ->
                writer.appendLine(listOf(row.prefix, row.code, row.type, row.line, row.station)
                    .joinToString(",") { csvEscape(it) })
            }
        }
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }

    private fun writeMeta(directory: File, standards: Map<String, String>) {
        val destination = metaFile(directory)
        val temp = File(directory, "$META_NAME.tmp")
        temp.writeText(gson.toJson(standards), StandardCharsets.UTF_8)
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }

    private fun writeLocations(directory: File, locations: Map<String, String>) {
        val destination = locationFile(directory)
        val temp = File(directory, "$LOCATION_NAME.tmp")
        temp.writeText(gson.toJson(locations), StandardCharsets.UTF_8)
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }

    private fun writeOriginals(directory: File, originals: Map<String, ReaderDeviceEntity?>) {
        val destination = originalFile(directory)
        val temp = File(directory, "$ORIGINAL_NAME.tmp")
        temp.writeText(gson.toJson(originals), StandardCharsets.UTF_8)
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }
    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' }) "\"$escaped\"" else escaped
    }

    private fun parseCsvLine(line: String): TransitOverrideRow? {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        if (quoted) return null
        fields += current.toString()
        if (fields.size != 5) return null
        return TransitOverrideRow(
            prefix = fields[0].trim(),
            code = fields[1].trim(),
            type = fields[2].trim(),
            line = fields[3].trim(),
            station = fields[4].trim()
        )
    }
}
