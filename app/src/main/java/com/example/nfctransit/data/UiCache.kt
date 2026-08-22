package com.example.nfctransit.data

import android.content.Context
import com.example.nfctransit.model.CanonicalTransaction
import com.example.nfctransit.model.UiTransaction
import com.google.gson.Gson
import java.io.File

/**
 * 每张卡「界面构建结果」的磁盘缓存（Gson JSON，存 cacheDir）。
 *
 * 启动时 restore() 优先读缓存，跳过 decodeArchive（hex 解析 + 站名解析 + 27k 行站名索引冷加载）
 * 与 toUiTransaction 映射，直接渲染上一次构建好的交易列表；无缓存、损坏或数据变化（archive
 * MAX(row_id) 变更 / transit.db 版本变更）才重建并回写。属于"缓存"而非用户数据：设置页「清理缓存」
 * 会清掉它，系统也允许在存储紧张时回收 cacheDir（缓存缺失时只是慢一点重新构建）。
 *
 * 缓存键 = 该卡 transactions_archive 的 MAX(row_id) + 当前 transit.db 版本：新交易落库或站名映射表
 * 更新都使缓存失效，保证界面不会残留旧站名。
 */
data class CardUiCache(
    val archiveRowId: Long,
    val canonicals: List<CanonicalTransaction>,
    val txns: List<UiTransaction>,
    val dbVersion: String? = null,
    val version: Int = FORMAT_VERSION
) {
    companion object {
        // 解码逻辑变更（站名/类型解析、读槽扩展等）或缓存结构变更（dbVersion 加入）时递增
        const val FORMAT_VERSION = 8
    }
}

object UiCache {
    private const val DIR = "ui_cache"
    private val gson = Gson()

    fun file(context: Context, cardId: String): File =
        File(File(context.cacheDir, DIR), "$cardId.json")

    /** 命中需同时满足：文件存在、格式版本一致、archive row_id 未变化、transit.db 版本一致；否则返回 null（当作无缓存） */
    fun load(context: Context, cardId: String, archiveRowId: Long, dbVersion: String?): CardUiCache? {
        val f = file(context, cardId)
        if (!f.exists()) return null
        return try {
            val c = gson.fromJson(f.readText(), CardUiCache::class.java)
            if (c.version != CardUiCache.FORMAT_VERSION || c.archiveRowId != archiveRowId || c.dbVersion != dbVersion) {
                null
            } else {
                c
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 写缓存；失败静默忽略（不影响功能，下次重建即可） */
    fun save(context: Context, cardId: String, cache: CardUiCache) {
        try {
            val f = file(context, cardId)
            f.parentFile?.mkdirs()
            f.writeText(gson.toJson(cache))
        } catch (e: Exception) {
            // 缓存写失败只损失启动加速，不阻塞主流程
        }
    }

    fun delete(context: Context, cardId: String) {
        try {
            file(context, cardId).delete()
        } catch (e: Exception) {
        }
    }

    fun clearAll(context: Context) {
        try {
            File(context.cacheDir, DIR).deleteRecursively()
        } catch (e: Exception) {
        }
    }
}
