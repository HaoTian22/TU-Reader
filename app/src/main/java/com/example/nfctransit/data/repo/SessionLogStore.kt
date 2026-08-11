package com.example.nfctransit.data.repo

import android.content.Context
import java.io.File

/**
 * APDU 会话调试日志文件存储：filesDir/transit_logs/<cardId>/<sessionTimestamp>.log。
 * 日志是调试工件（不是业务状态），不进数据库。保留策略：每卡最多 10 个会话文件或 ≤2MB。
 */
object SessionLogStore {

    private const val MAX_SESSIONS = 10
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024

    private fun root(context: Context): File =
        File(context.filesDir, "transit_logs")

    private fun cardDir(context: Context, cardId: String): File =
        File(root(context), cardId)

    /** 写入一个会话日志文件（调用方负责在 keepDebugLogs=false 时不调用） */
    fun write(context: Context, cardId: String, sessionTimestamp: Long, lines: List<String>) {
        val dir = cardDir(context, cardId)
        if (!dir.exists()) dir.mkdirs()
        try {
            val file = File(dir, "$sessionTimestamp.log")
            file.writeText(lines.joinToString("\n") + "\n")
            enforceRetention(context, cardId)
        } catch (_: Exception) {
            // 日志写入失败不影响主流程
        }
    }

    /** 该卡全部会话日志文件，按时间戳升序 */
    fun sessionLogs(context: Context, cardId: String): List<File> {
        val dir = cardDir(context, cardId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".log") }
            ?.sortedBy { it.name } ?: emptyList()
    }

    /** 合并该卡全部会话日志（导出用） */
    fun combinedLog(context: Context, cardId: String): String {
        return sessionLogs(context, cardId).joinToString("\n") { it.readText() }
    }

    /** 保留策略：每卡最多 10 个文件且总大小 ≤2MB，超限删除最早的文件 */
    fun enforceRetention(context: Context, cardId: String) {
        val logs = sessionLogs(context, cardId)
        if (logs.size > MAX_SESSIONS) {
            logs.take(logs.size - MAX_SESSIONS).forEach { it.delete() }
        }
        var total = 0L
        val newestFirst = logs.sortedByDescending { it.name }
        for (f in newestFirst) {
            total += f.length()
            if (total > MAX_TOTAL_BYTES) {
                f.delete()
            }
        }
    }

    fun deleteCardLogs(context: Context, cardId: String) {
        cardDir(context, cardId).deleteRecursively()
    }

    fun deleteAll(context: Context) {
        root(context).deleteRecursively()
    }
}
