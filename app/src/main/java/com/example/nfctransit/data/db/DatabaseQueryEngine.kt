package com.example.nfctransit.data.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import java.io.File

data class DatabaseQueryResult(
    val columns: List<String>,
    val rows: List<List<String>>,
    val truncated: Boolean
)

object DatabaseQueryEngine {

    private const val MAX_RESULT_ROWS = 1000
    private const val MAX_RESULT_COLUMNS = 64
    private const val MAX_RESULT_CELLS = 5000
    private const val MAX_CELL_CHARS = 4096
    private val forbiddenKeywords = setOf(
        "INSERT", "UPDATE", "DELETE", "ALTER", "DROP", "CREATE",
        "ATTACH", "DETACH", "PRAGMA", "VACUUM", "REINDEX", "ANALYZE", "BEGIN",
        "COMMIT", "ROLLBACK"
    )

    fun execute(
        context: Context,
        spec: DatabaseQuerySpec,
        sql: String,
        cancellationSignal: CancellationSignal? = null
    ): DatabaseQueryResult {
        return when (spec) {
            DatabaseQuerySpec.TRANSIT -> AppDatabase.withDatabaseLock {
                executeUnlocked(context, spec, sql, cancellationSignal)
            }
            DatabaseQuerySpec.USER -> executeUnlocked(context, spec, sql, cancellationSignal)
        }
    }

    private fun executeUnlocked(
        context: Context,
        spec: DatabaseQuerySpec,
        sql: String,
        cancellationSignal: CancellationSignal?
    ): DatabaseQueryResult {
        val statement = validate(sql)
        val appContext = context.applicationContext
        val databaseFile = ensureDatabaseFile(appContext, spec)
        if (!databaseFile.isFile) throw IllegalStateException("数据库文件不存在")

        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            database.rawQuery(statement, null, cancellationSignal).use { cursor ->
                val columnCount = cursor.columnCount
                require(columnCount <= MAX_RESULT_COLUMNS) {
                    "查询结果列数不能超过 $MAX_RESULT_COLUMNS 列"
                }
                val columns = cursor.columnNames.toList()
                val rows = ArrayList<List<String>>()
                var truncated = false
                while (cursor.moveToNext()) {
                    if (rows.size >= MAX_RESULT_ROWS ||
                        (rows.size + 1) * columnCount > MAX_RESULT_CELLS
                    ) {
                        truncated = true
                        break
                    }
                    rows += List(columnCount) { index -> readCell(cursor, index) }
                }
                return DatabaseQueryResult(columns, rows, truncated)
            }
        } finally {
            database.close()
        }
    }

    private fun ensureDatabaseFile(context: Context, spec: DatabaseQuerySpec): File {
        when (spec) {
            DatabaseQuerySpec.USER -> UserDatabase.get(context).openHelper.readableDatabase
            DatabaseQuerySpec.TRANSIT -> AppDatabase.get(context).openHelper.readableDatabase
        }
        return context.getDatabasePath(spec.fileName)
    }

    private fun validate(sql: String): String {
        val statement = sql.trim().removeSuffix(";").trim()
        require(statement.isNotEmpty()) { "SQL 不能为空" }
        require(statement.firstToken() == "SELECT" || statement.firstToken() == "WITH") {
            "只允许执行 SELECT 查询"
        }
        require(!hasSemicolonOutsideQuotes(statement)) { "只允许执行一条 SQL" }
        require(!hasForbiddenKeyword(statement)) { "只允许执行只读查询" }
        return statement
    }

    private fun String.firstToken(): String {
        var end = 0
        while (end < length && (this[end].isLetter() || this[end] == '_')) end++
        return substring(0, end).uppercase()
    }

    private fun hasSemicolonOutsideQuotes(sql: String): Boolean {
        var quote: Char? = null
        var index = 0
        while (index < sql.length) {
            val character = sql[index]
            if (quote != null) {
                if (character == quote) {
                    if (index + 1 < sql.length && sql[index + 1] == quote) {
                        index++
                    } else {
                        quote = null
                    }
                }
            } else if (character == '\'' || character == '"') {
                quote = character
            } else if (character == ';') {
                return true
            }
            index++
        }
        return false
    }

    private fun hasForbiddenKeyword(sql: String): Boolean {
        var quote: Char? = null
        var index = 0
        while (index < sql.length) {
            val character = sql[index]
            if (quote != null) {
                if (character == quote) {
                    if (index + 1 < sql.length && sql[index + 1] == quote) {
                        index++
                    } else {
                        quote = null
                    }
                }
                index++
                continue
            }
            if (character == '\'' || character == '"') {
                quote = character
                index++
                continue
            }
            if (character.isLetter() || character == '_') {
                val start = index
                index++
                while (index < sql.length && (sql[index].isLetterOrDigit() || sql[index] == '_')) {
                    index++
                }
                if (sql.substring(start, index).uppercase() in forbiddenKeywords) return true
            } else {
                index++
            }
        }
        return false
    }

    private fun readCell(cursor: Cursor, index: Int): String {
        return when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> "NULL"
            Cursor.FIELD_TYPE_BLOB -> "[BLOB]"
            else -> cursor.getString(index)?.let { value ->
                if (value.length > MAX_CELL_CHARS) {
                    value.take(MAX_CELL_CHARS) + "…"
                } else {
                    value
                }
            }.orEmpty()
        }
    }
}
