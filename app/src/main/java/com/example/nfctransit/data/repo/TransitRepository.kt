package com.example.nfctransit.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.nfctransit.ApduUtil
import com.example.nfctransit.data.RawRecord
import com.example.nfctransit.data.RecordDecoder
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.toSfiHex
import com.example.nfctransit.data.db.ArchivedTransactionEntity
import com.example.nfctransit.data.db.CardAppEntity
import com.example.nfctransit.data.db.CardEntity
import com.example.nfctransit.data.db.RawRecordEntity
import com.example.nfctransit.data.db.UserDatabase
import com.example.nfctransit.data.prefs.AppPreferences
import com.example.nfctransit.data.prefs.CurrentTripRouteDisplayMode
import com.example.nfctransit.model.CanonicalTransaction
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 导入数据库的结果统计（新增卡片/原始记录/交易行数） */
data class ImportSummary(val cards: Int, val raw: Int, val archive: Int)

private data class CardAppImportKey(
    val cardId: String,
    val readAt: Long,
    val selectedAid: String,
    val selectResp: String,
    val balanceFen: Long?,
    val balanceResp: String?
)

private fun CardAppEntity.cardAppImportKey(cardId: String) = CardAppImportKey(
    cardId = cardId,
    readAt = readAt,
    selectedAid = selectedAid,
    selectResp = selectResp,
    balanceFen = balanceFen,
    balanceResp = balanceResp
)

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

    suspend fun updateCardName(cardId: String, name: String) = dao.updateCardName(cardId, name)

    suspend fun updateCardColors(cardId: String, startColor: Long, endColor: Long) =
        dao.updateCardColors(cardId, startColor, endColor)

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

    suspend fun maxArchiveRowId(cardId: String): Long? = dao.maxArchiveRowId(cardId)

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
        val importedApps = srcDao.getAllCardApps()
        src.close()

        val now = System.currentTimeMillis()
        val existingCards = dao.getAllCards().toMutableList()
        val idMap = mutableMapOf<String, String>()
        val newCardIds = mutableListOf<String>()

        for (card in importedCards) {
            val existing = matchCardByNumbers(
                existingCards,
                card.cardNumber,
                card.secondCardNumber.orEmpty(),
                card.lastFour
            )
            if (existing != null) {
                val merged = mergeCardNumbers(existing, card.cardNumber, card.secondCardNumber.orEmpty())
                if (merged != existing) {
                    dao.upsertCard(merged)
                    existingCards[existingCards.indexOf(existing)] = merged
                }
                idMap[card.cardId] = existing.cardId
            } else {
                val newId = UUID.randomUUID().toString()
                val imported = card.copy(cardId = newId)
                dao.upsertCard(imported)
                existingCards.add(imported)
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

        val existingAppKeys = dao.getAllCardApps()
            .mapTo(mutableSetOf()) { it.cardAppImportKey(it.cardId) }
        for (app in importedApps) {
            val targetId = idMap[app.cardId] ?: continue
            if (existingAppKeys.add(app.cardAppImportKey(targetId))) {
                dao.insertCardApp(app.copy(cardId = targetId, rowId = 0))
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

    /**
     * 导入 TripReader（card_table / tran_table）数据库：
     * 卡片按 cdNo / cdNo2（交通联合 19 位 / 岭南通 10 位应用序列号）匹配现有卡（card_number /
     * second_card_number），已存在则复用其 card_id 合并交易；新卡按记录内容推断卡型并分配新 UUID。
     * 交易记录去重沿用 (card_id, content_hash, protocol, sfi)：
     *  - TU 钱包记录（0x1E 旅程 + 全日期 0x18）走 RecordDecoder.decodeCard 复原日期/余额/站点；
     *  - LNT 记录（岭南通，年份字段 0000）直接用源库 txDate 复原年份，resolved_date 落库。
     */
    suspend fun importTripReaderDatabase(importFile: File): ImportSummary {
        val source = TripReaderDatabase.read(importFile)
        val existingCards = dao.getAllCards().toMutableList()
        val now = System.currentTimeMillis()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val newCardIds = mutableListOf<String>()
        val usedColors = existingCards.map { it.gradientStartColor }.toMutableSet()
        var newArchive = 0

        for (src in source.cards) {
            val tuRecords = mutableListOf<RecordDecoder.ZoneRecord>()
            val sztRecords = mutableListOf<RecordDecoder.ZoneRecord>()
            val lntRows = mutableListOf<Pair<String, Long>>()  // (0x18 hex, 源库 txDate 毫秒)
            var recNo = 0
            for (tx in src.transactions) {
                if (tx.cuHex.isNotEmpty()) {
                    if (src.isSZT) {
                        sztRecords.add(RecordDecoder.ZoneRecord(0x18, recNo++, "SZT", tx.cuHex))
                    } else {
                        val sourceIsLnt = tx.isLNT
                            ?: src.isLNT?.takeIf { !src.isTU }
                            ?: src.isYCT.takeIf { !src.isTU }
                        when (sourceIsLnt) {
                            true -> lntRows.add(tx.cuHex to tx.dateMs)
                            false -> tuRecords.add(RecordDecoder.ZoneRecord(0x18, recNo++, "TU", tx.cuHex))
                            null -> {
                                val data = ApduUtil.hexToBytes(tx.cuHex)
                                val fullYear = data.size >= 20 &&
                                    ApduUtil.bcdToString(data.copyOfRange(16, 20)).startsWith("20")
                                if (fullYear) {
                                    tuRecords.add(RecordDecoder.ZoneRecord(0x18, recNo++, "TU", tx.cuHex))
                                } else {
                                    lntRows.add(tx.cuHex to tx.dateMs)
                                }
                            }
                        }
                    }
                }
                if (tx.tuHex.isNotEmpty()) {
                    tuRecords.add(RecordDecoder.ZoneRecord(0x1E, recNo++, "TU", tx.tuHex))
                }
            }

            val hasLnt = src.isLNT == true || src.isYCT || lntRows.isNotEmpty()
            val importedCardType = if (src.isSZT) "SZT" else if (hasLnt) "YCT" else "TU"
            val existing = matchTripReaderCard(existingCards, src.cdNo, src.cdNo2)
            val cardId = if (existing != null) {
                val merged = mergeCardNumbers(existing, src.cdNo, src.cdNo2).let {
                    if (importedCardType == "SZT") it.copy(cardType = "SZT") else it
                }
                if (merged != existing) {
                    dao.upsertCard(merged)
                    existingCards[existingCards.indexOf(existing)] = merged
                }
                existing.cardId
            } else run {
                val id = UUID.randomUUID().toString()
                val gradient = importCardPalette.firstOrNull { it.first !in usedColors }
                    ?: importCardPalette[newCardIds.size % importCardPalette.size]
                usedColors.add(gradient.first)
                val imported = buildTripReaderCard(id, src, importedCardType, gradient)
                dao.upsertCard(imported)
                existingCards.add(imported)
                newCardIds.add(id)
                id
            }

            // SZT+TU 或单 TU：沿用读卡解码路径，归档按协议保存 SZT/TU
            val transactionRecords = if (src.isSZT) sztRecords + tuRecords else tuRecords
            val transactionCardType = if (src.isSZT) "SZT" else "TU"
            if (transactionRecords.isNotEmpty()) {
                val decoded = RecordDecoder.decodeCard(transactionCardType, transactionRecords, null, currentYear)
                for (t in decoded.archive) {
                    val sfiHex = t.sfi.toSfiHex()
                    if (dao.getArchiveSlot(cardId, t.identity, t.protocol, sfiHex) == null) {
                        dao.insertArchiveRow(
                            ArchivedTransactionEntity(
                                cardId = cardId, sfi = sfiHex, protocol = t.protocol, hex = t.hex,
                                contentHash = t.identity, resolvedDate = t.date,
                                balanceAfterFen = t.balanceAfterFen,
                                firstSeenAt = now, lastSeenAt = now
                            )
                        )
                        newArchive++
                    }
                }
            }
            // LNT：年份来自源库绝对时间，渲染时由归档的 resolved_date 覆盖 hex 内 0000 年份
            for ((hex, dateMs) in lntRows) {
                val hash = RecordDecoder.contentHash(hex)
                if (dao.getArchiveSlot(cardId, hash, "LNT", "0x18") == null) {
                    dao.insertArchiveRow(
                        ArchivedTransactionEntity(
                            cardId = cardId, sfi = "0x18", protocol = "LNT", hex = hex,
                            contentHash = hash, resolvedDate = yyyyMMdd(dateMs),
                            balanceAfterFen = null, firstSeenAt = now, lastSeenAt = now
                        )
                    )
                    newArchive++
                }
            }
        }

        if (newCardIds.isNotEmpty()) {
            val order = AppPreferences.getCardOrder(context)
            AppPreferences.setCardOrder(context, order + newCardIds)
        }
        return ImportSummary(newCardIds.size, 0, newArchive)
    }

    /** 判断所选文件是否为 TripReader 库（含 card_table 且非本应用 user_data 库） */
    fun isTripReaderDatabase(file: File): Boolean = TripReaderDatabase.isTripReaderDatabase(file)

    private fun matchTripReaderCard(
        existing: List<CardEntity>,
        cdNo: String,
        cdNo2: String
    ): CardEntity? = matchCardByNumbers(existing, cdNo, cdNo2)

    private fun matchCardByNumbers(
        existing: List<CardEntity>,
        first: String,
        second: String,
        fallbackLastFour: String = ""
    ): CardEntity? {
        val numbers = listOf(first, second).filter { it.isNotEmpty() }.toSet()
        if (numbers.isNotEmpty()) {
            existing.firstOrNull { card ->
                numbers.any { it == card.cardNumber || it == card.secondCardNumber }
            }?.let { return it }
        }
        val lastFours = buildSet {
            addAll(numbers.map { it.takeLast(4) }.filter { it.isNotEmpty() })
            if (fallbackLastFour.isNotEmpty()) add(fallbackLastFour)
        }
        return if (lastFours.isNotEmpty()) {
            existing.firstOrNull { it.lastFour in lastFours }
        } else {
            null
        }
    }

    private fun mergeCardNumbers(existing: CardEntity, first: String, second: String): CardEntity {
        val numbers = listOf(first, second).filter { it.isNotEmpty() }
        val primary = existing.cardNumber.ifEmpty { numbers.firstOrNull().orEmpty() }
        val secondary = existing.secondCardNumber?.takeIf { it.isNotEmpty() }
            ?: numbers.firstOrNull { it != primary }
        return if (primary != existing.cardNumber || secondary != existing.secondCardNumber) {
            existing.copy(cardNumber = primary, secondCardNumber = secondary)
        } else {
            existing
        }
    }

    private fun buildTripReaderCard(
        cardId: String,
        src: TripReaderCard,
        cardType: String,
        gradient: Pair<Long, Long>
    ): CardEntity {
        val now = System.currentTimeMillis()
        return CardEntity(
            cardId = cardId,
            cardNumber = src.cdNo,
            secondCardNumber = src.cdNo2.ifEmpty { null },
            name = TransitData.cardName(src.cdNo) ?: src.cdTitle,
            cardType = cardType,
            lastFour = src.cdNo.takeLast(4).ifEmpty { src.cdNo2.takeLast(4) },
            gradientStartColor = gradient.first,
            gradientEndColor = gradient.second,
            latestBalanceFen = if (src.cdBalance >= 0) src.cdBalance else null,
            createdAt = now,
            lastReadAt = now
        )
    }

    private fun yyyyMMdd(epochMs: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(epochMs))

    private val importCardPalette: List<Pair<Long, Long>> = listOf(
        0xFF1A73E8 to 0xFF0D47A1,
        0xFF2E7D32 to 0xFF1B5E20,
        0xFFE65100 to 0xFFBF360C,
        0xFF6A1B9A to 0xFF4A148C,
        0xFFC62828 to 0xFFB71C1C,
        0xFF00838F to 0xFF006064,
        0xFFF9A825 to 0xFFF57F17,
        0xFF5D4037 to 0xFF3E2723,
        0xFF455A64 to 0xFF263238,
        0xFFAD1457 to 0xFF880E4F
    )

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
    suspend fun getCurrentTripRouteDisplayMode(): CurrentTripRouteDisplayMode =
        AppPreferences.getCurrentTripRouteDisplayMode(context)
    suspend fun setCurrentTripRouteDisplayMode(mode: CurrentTripRouteDisplayMode) =
        AppPreferences.setCurrentTripRouteDisplayMode(context, mode)
    suspend fun getSchemaVersion(): Int = AppPreferences.getSchemaVersion(context)
    suspend fun markMigrated() = AppPreferences.markMigrated(context)
}
