package com.example.nfctransit.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.nfctransit.data.RawRecord
import com.example.nfctransit.data.RecordDecoder
import com.example.nfctransit.data.toSfiHex
import com.example.nfctransit.data.db.ArchivedTransactionEntity
import com.example.nfctransit.data.db.CardAppEntity
import com.example.nfctransit.data.db.CardEntity
import com.example.nfctransit.data.db.RawRecordEntity
import com.example.nfctransit.data.db.UserDatabase
import com.example.nfctransit.data.prefs.AppPreferences
import com.example.nfctransit.model.CanonicalTransaction
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID

/** 导入数据库的结果统计（新增卡片/原始记录/交易行数） */
data class ImportSummary(val cards: Int, val raw: Int, val archive: Int)

/**
 * 用户数据仓库：协调 Room 用户库（cards/raw_records/transactions_archive）、
 * DataStore（轻量设置）与日志文件（SessionLogStore）。
 * 渲染唯一来源是 transactions_archive（按内容去重、append-only）。
 */
class TransitRepository(private val context: Context) {

    private val dao = UserDatabase.get(context).userDao()

    // ── cards ──

    suspend fun loadCards(): List<CardEntity> = dao.getAllCards()

    suspend fun findCardByCardNumber(cardNumber: String): CardEntity? =
        dao.findByCardNumber(cardNumber)

    suspend fun findCardByLastFour(lastFour: String): CardEntity? =
        dao.findByLastFour(lastFour)

    suspend fun getCard(cardId: String): CardEntity? = dao.getCard(cardId)

    suspend fun upsertCard(card: CardEntity) = dao.upsertCard(card)

    suspend fun updateCardBalance(cardId: String, balanceFen: Long?, lastReadAt: Long) =
        dao.updateCardBalance(cardId, balanceFen, lastReadAt)

    // ── raw_records（卡内当前槽位状态；不用于渲染）──

    suspend fun syncRawRecords(cardId: String, records: List<RawRecord>) {
        val now = System.currentTimeMillis()
        for (rec in records) {
            val hash = RecordDecoder.contentHash(rec.hex)
            val existing = dao.getRawSlot(cardId, rec.protocol, rec.sfi.toSfiHex(), rec.recNo)
            when {
                existing == null ->
                    dao.insertRawRecord(
                        RawRecordEntity(
                            cardId = cardId, sfi = rec.sfi.toSfiHex(), recNo = rec.recNo,
                            protocol = rec.protocol, hex = rec.hex, contentHash = hash,
                            firstSeenAt = now, lastSeenAt = now
                        )
                    )
                existing.hex == rec.hex ->
                    dao.touchRawSlot(cardId, rec.protocol, rec.sfi.toSfiHex(), rec.recNo, now)
                else ->
                    dao.overwriteRawSlot(cardId, rec.protocol, rec.sfi.toSfiHex(), rec.recNo, rec.hex, hash, now)
            }
        }
    }

    suspend fun loadRawRecords(cardId: String): List<RawRecordEntity> =
        dao.getRawRecords(cardId)

    // ── transactions_archive（渲染唯一来源，按内容去重）──

    suspend fun archiveTransactions(cardId: String, transactions: List<CanonicalTransaction>) {
        val now = System.currentTimeMillis()
        for (t in transactions) {
            val inserted = dao.insertArchiveRow(
                ArchivedTransactionEntity(
                    cardId = cardId,
                    sfi = t.sfi.toSfiHex(),
                    protocol = t.protocol,
                    hex = t.hex,
                    contentHash = t.identity,
                    resolvedDate = t.date,
                    balanceAfterFen = t.balanceAfterFen,
                    firstSeenAt = now,
                    lastSeenAt = now
                )
            )
            if (inserted == -1L) {
                // 完全一样（同内容同协议同扇区）→ 只更新 last_seen_at；不同协议/扇区的变体已作为新行插入
                dao.touchArchive(cardId, t.identity, t.protocol, t.sfi.toSfiHex(), now)
            }
        }
    }

    suspend fun loadArchive(cardId: String): List<ArchivedTransactionEntity> =
        dao.getArchive(cardId)

    // ── card_app（卡上应用 SELECT/BALANCE 记录，追加历史）──

    suspend fun syncCardApps(cardId: String, apps: List<CardAppEntity>) {
        for (app in apps) dao.insertCardApp(app)
    }

    suspend fun loadCardApps(cardId: String): List<CardAppEntity> =
        dao.getCardApps(cardId)

    // ── 删除 / 清空 ──

    suspend fun deleteCard(cardId: String) {
        dao.deleteCard(cardId)  // FK CASCADE 清除 raw_records + transactions_archive
        SessionLogStore.deleteCardLogs(context, cardId)
        if (AppPreferences.getSelectedCardId(context) == cardId) {
            AppPreferences.setSelectedCardId(context, null)
        }
    }

    suspend fun clearAll() {
        dao.clearCards()
        SessionLogStore.deleteAll(context)
        AppPreferences.clear(context)
    }

    // ── 数据库导入 / 导出 ──

    /** 导出用户库为单文件：先 checkpoint WAL 到主库，再整体拷贝（避免只拷主文件丢失 WAL 中未落盘数据） */
    suspend fun exportDatabase(dest: Uri) {
        val db = UserDatabase.get(context)
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        val src = context.getDatabasePath(UserDatabase.DB_NAME)
        val out = context.contentResolver.openOutputStream(dest)
            ?: throw IOException("无法写入目标文件")
        out.use { FileInputStream(src).use { input -> input.copyTo(out) } }
    }

    /**
     * 导入另一份用户库：卡片按 card_number（空号按 last_four）匹配现有卡，已存在则复用其 card_id
     * 并合并记录（不覆盖卡片元数据）；新卡分配新 UUID 并追加到卡序。
     * 交易按 (card_id, content_hash, protocol, sfi)、原始记录按 (card_id, protocol, sfi, rec_no) 去重，
     * 只插入现有库中没有的行。
     */
    suspend fun importDatabase(importFile: File): ImportSummary {
        val src = Room.databaseBuilder(
            context.applicationContext, UserDatabase::class.java, importFile.absolutePath
        ).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*UserDatabase.MIGRATIONS).build()
        val srcDao = src.userDao()
        val importedCards = srcDao.getAllCards()
        val importedRaws = srcDao.getAllRawRecords()
        val importedArchives = srcDao.getAllArchive()
        src.close()

        val now = System.currentTimeMillis()
        val idMap = mutableMapOf<String, String>()
        val newCardIds = mutableListOf<String>()

        for (card in importedCards) {
            val existing = when {
                card.cardNumber.isNotEmpty() -> dao.findByCardNumber(card.cardNumber)
                else -> dao.findByLastFour(card.lastFour)
            }
            if (existing != null) {
                idMap[card.cardId] = existing.cardId
            } else {
                val newId = UUID.randomUUID().toString()
                dao.upsertCard(card.copy(cardId = newId))
                idMap[card.cardId] = newId
                newCardIds.add(newId)
            }
        }
        if (newCardIds.isNotEmpty()) {
            val order = AppPreferences.getCardOrder(context)
            AppPreferences.setCardOrder(context, order + newCardIds)
        }

        var newRaw = 0
        for (raw in importedRaws) {
            val targetId = idMap[raw.cardId] ?: continue
            if (dao.getRawSlot(targetId, raw.protocol, raw.sfi, raw.recNo) == null) {
                dao.insertRawRecord(
                    raw.copy(cardId = targetId, rowId = 0, firstSeenAt = now, lastSeenAt = now)
                )
                newRaw++
            }
        }

        var newArchive = 0
        for (arc in importedArchives) {
            val targetId = idMap[arc.cardId] ?: continue
            if (dao.getArchiveSlot(targetId, arc.contentHash, arc.protocol, arc.sfi) == null) {
                dao.insertArchiveRow(
                    arc.copy(cardId = targetId, rowId = 0, firstSeenAt = now, lastSeenAt = now)
                )
                newArchive++
            }
        }

        return ImportSummary(newCardIds.size, newRaw, newArchive)
    }

    // ── 日志文件 ──

    fun sessionLogs(cardId: String) = SessionLogStore.sessionLogs(context, cardId)

    fun combinedCardLog(cardId: String) = SessionLogStore.combinedLog(context, cardId)

    /** 保留调试日志开启时才写入会话文件（关闭时不写，避免隐私与存储膨胀） */
    suspend fun writeSessionLog(cardId: String, lines: List<String>) {
        if (AppPreferences.isKeepDebugLogs(context)) {
            SessionLogStore.write(context, cardId, System.currentTimeMillis(), lines)
        }
    }

    // ── DataStore 轻量设置 ──

    suspend fun getSelectedCardId(): String? = AppPreferences.getSelectedCardId(context)
    suspend fun setSelectedCardId(cardId: String?) = AppPreferences.setSelectedCardId(context, cardId)
    suspend fun getCardOrder(): List<String> = AppPreferences.getCardOrder(context)
    suspend fun setCardOrder(cardIds: List<String>) = AppPreferences.setCardOrder(context, cardIds)
    suspend fun isKeepDebugLogs(): Boolean = AppPreferences.isKeepDebugLogs(context)
    suspend fun setKeepDebugLogs(keep: Boolean) = AppPreferences.setKeepDebugLogs(context, keep)
    suspend fun getSchemaVersion(): Int = AppPreferences.getSchemaVersion(context)
    suspend fun markMigrated() = AppPreferences.markMigrated(context)
}
