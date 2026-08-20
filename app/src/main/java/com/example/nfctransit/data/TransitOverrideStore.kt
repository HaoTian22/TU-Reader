package com.example.nfctransit.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.charset.StandardCharsets

object TransitOverrideStore {
    private const val CSV_NAME = "overrides.csv"
    private const val META_NAME = "overrides.meta.json"
    private val gson = Gson()

    fun csvFile(context: Context): File = File(context.filesDir, CSV_NAME)

    private fun metaFile(context: Context): File = File(context.filesDir, META_NAME)

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
        return OverrideSnapshot(rows, standards)
    }

    @Synchronized
    fun upsert(context: Context, feedback: FeedbackOverride) {
        val snapshot = read(context)
        snapshot.rows[feedback.row.deviceCode] = feedback.row
        snapshot.standards[feedback.row.deviceCode] = feedback.standard
        writeCsv(context, snapshot.rows.values)
        writeMeta(context, snapshot.standards)
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
