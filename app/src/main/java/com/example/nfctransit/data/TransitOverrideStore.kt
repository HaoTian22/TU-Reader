package com.example.nfctransit.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.nfctransit.data.db.ReaderDeviceEntity
import java.io.File
import java.nio.charset.StandardCharsets

object TransitOverrideStore {
    private const val CSV_NAME = "overrides.csv"
    private const val META_NAME = "overrides.meta.json"
    private const val ORIGINAL_NAME = "overrides.originals.json"
    private val gson = Gson()

    fun csvFile(context: Context): File = File(context.filesDir, CSV_NAME)

    private fun metaFile(context: Context): File = File(context.filesDir, META_NAME)

    private fun originalFile(context: Context): File = File(context.filesDir, ORIGINAL_NAME)

    @Synchronized
    fun read(context: Context): OverrideSnapshot {
        val rows = LinkedHashMap<String, TransitOverrideRow>()
        val file = csvFile(context)
        if (file.isFile) {
            file.useLines { lines ->
                val all = lines.toList()
                if (all.isEmpty() || all.first() != OVERRIDE_HEADER) {
                    throw IllegalStateException("override CSV 表头无效")
                }
                all.drop(1).forEachIndexed { index, line ->
                    if (line.isBlank()) return@forEachIndexed
                    parseCsvLine(line)?.let { row ->
                        rows[row.deviceCode] = row
                    } ?: throw IllegalStateException("override CSV 第 ${index + 2} 行格式无效")
                }
            }
        }
        val standards = mutableMapOf<String, String>()
        val meta = metaFile(context)
        if (meta.isFile) {
            val type = object : TypeToken<Map<String, String>>() {}.type
            runCatching {
                standards.putAll(gson.fromJson<Map<String, String>>(meta.readText(), type).orEmpty())
            }
        }
        val originals = mutableMapOf<String, ReaderDeviceEntity?>()
        val originalsFile = originalFile(context)
        if (originalsFile.isFile) {
            val type = object : TypeToken<Map<String, ReaderDeviceEntity?>>() {}.type
            runCatching {
                originals.putAll(gson.fromJson<Map<String, ReaderDeviceEntity?>>(originalsFile.readText(), type).orEmpty())
            }
        }
        return OverrideSnapshot(rows, standards, originals)
    }

    @Synchronized
    fun upsert(
        context: Context,
        feedback: FeedbackOverride,
        original: ReaderDeviceEntity? = null
    ) {
        val snapshot = read(context)
        val deviceCode = feedback.row.deviceCode
        if (!snapshot.rows.containsKey(deviceCode)) {
            snapshot.originals[deviceCode] = original
        }
        snapshot.rows[deviceCode] = feedback.row
        snapshot.standards[deviceCode] = feedback.standard
        writeSnapshot(context, snapshot)
    }

    @Synchronized
    fun remove(context: Context, deviceCode: String): OverrideRemoval? {
        val snapshot = read(context)
        val row = snapshot.rows.remove(deviceCode) ?: return null
        val hasOriginal = snapshot.originals.containsKey(deviceCode)
        val removal = OverrideRemoval(row, snapshot.originals.remove(deviceCode), hasOriginal)
        snapshot.standards.remove(deviceCode)
        writeSnapshot(context, snapshot)
        return removal
    }

    fun list(context: Context): List<TransitOverrideRow> = read(context).rows.values.toList()

    private fun writeSnapshot(context: Context, snapshot: OverrideSnapshot) {
        writeCsv(context, snapshot.rows.values)
        writeMeta(context, snapshot.standards)
        writeOriginals(context, snapshot.originals)
    }

    private fun writeCsv(context: Context, rows: Collection<TransitOverrideRow>) {
        val destination = csvFile(context)
        val temp = File(context.filesDir, "$CSV_NAME.tmp")
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

    private fun writeMeta(context: Context, standards: Map<String, String>) {
        val destination = metaFile(context)
        val temp = File(context.filesDir, "$META_NAME.tmp")
        temp.writeText(gson.toJson(standards), StandardCharsets.UTF_8)
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }

    private fun writeOriginals(context: Context, originals: Map<String, ReaderDeviceEntity?>) {
        val destination = originalFile(context)
        val temp = File(context.filesDir, "$ORIGINAL_NAME.tmp")
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
