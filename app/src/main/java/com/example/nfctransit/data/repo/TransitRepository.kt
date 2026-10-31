package com.example.nfctransit.data.repo

import com.example.nfctransit.model.CardPalette
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.nfctransit.ApduUtil
import com.example.nfctransit.CardProfiles
import com.example.nfctransit.parseCuCardNumber
import com.example.nfctransit.data.RawRecord
import com.example.nfctransit.data.RecordDecoder
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.toSfiHex
import com.example.nfctransit.data.toSfiInt
import com.example.nfctransit.data.db.ArchivedTransactionEntity
import com.example.nfctransit.data.db.CardAppEntity
import com.example.nfctransit.data.db.CardEntity
import com.example.nfctransit.data.db.RawRecordEntity
import com.example.nfctransit.data.db.UserDatabase
import com.example.nfctransit.data.prefs.AppPreferences
import com.example.nfctransit.data.prefs.CardSortMode
import com.example.nfctransit.data.prefs.CurrentTripRouteDisplayMode
import com.example.nfctransit.model.CanonicalTransaction
import com.example.nfctransit.model.DiscountRegistry
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.example.nfctransit.R
import com.example.nfctransit.util.L10n

/** 导入数据库的结果统计（新增卡片/原始记录/交易行数） */
data class ImportSummary(
    val cards: Int,
    val raw: Int,
    val archive: Int,
    /** 本地已有的重复卡在导入前合并的数量（不计为新增卡）。 */
    val merged: Int = 0
)

/** 归档去重键：同一应用、同一扇区、同一内容。 */
private data class ArchiveKey(
    val selectedAid: String,
    val sfi: String,
    val contentHash: String
)

/** 原始记录版本键：同一应用、同一槽位、同一内容。 */
private data class RawVersionKey(
    val appId: Long,
    val sfi: String,
    val recNo: Int,
    val contentHash: String
)

/** 卡号由协议字段产生，导入时忽略意外的首尾空白；空值不参与身份比较。 */
internal fun cardIdentityNumbers(vararg values: String?): Set<String> =
    values.map { it.orEmpty().trim() }.filter { it.isNotEmpty() }.toSet()

internal fun sharesCardIdentity(first: Set<String>, second: Set<String>): Boolean =
    first.any(second::contains)

internal fun cuCardNumberFromRaw(records: List<RawRecord>): String? {
    val info = records.firstOrNull {
        it.sfi == 0x15 && it.recNo == 0 && it.selectedAid.equals(CardProfiles.CU_AID, true)
    } ?: return null
    val data = runCatching { ApduUtil.hexToBytes(info.hex) }.getOrNull() ?: return null
    return data.takeIf { it.size >= 20 }?.let(::parseCuCardNumber)
}

/** 表结构没有逐条应用标记时的旧推断：cuHex 是通用 0x18 字段；完整年份不能区分 CU 与 TU，应先使用来源卡型。 */
internal fun tripReaderFareProtocol(card: TripReaderCard, transaction: TripReaderTransaction): String {
    if (card.isSZT) return "SZT"
    if (card.isCU) return "CU"
    val sourceIsLnt = transaction.isLNT
        ?: card.isLNT?.takeIf { !card.isTU }
        ?: card.isYCT.takeIf { !card.isTU }
    return when (sourceIsLnt) {
        true -> "LNT"
        false -> "TU"
        null -> {
            val data = ApduUtil.hexToBytes(transaction.cuHex)
            val fullYear = data.size >= 20 &&
                ApduUtil.bcdToString(data.copyOfRange(16, 20)).startsWith("20")
            if (fullYear) "TU" else "LNT"
        }
    }
}

/** TripReader「CU 侧」城市钱包对应的应用；苏州（SUXIN/SZTK 两个 AID）等无法确定应用时为 null。 */
internal fun tripReaderWalletAid(card: TripReaderCard, transaction: TripReaderTransaction? = null): String? = when {
    card.isSZT -> CardProfiles.walletAid("SZT")
    card.isCU -> CardProfiles.CU_AID
    card.isTFT -> CardProfiles.TFT_AID
    transaction?.isYCT == true || card.isYCT || card.isLNT == true -> CardProfiles.YCT_TICL_AID
    else -> null
}

/** 主交易 cuHex 所属应用：优先用 TripReader 逐条应用标记（txTU18 / txCU），无标记列时回退旧推断。 */
internal fun tripReaderFareAid(card: TripReaderCard, transaction: TripReaderTransaction): String? = when {
    transaction.isTU18 == true -> CardProfiles.TU_EP_AID
    transaction.isCU == true -> tripReaderWalletAid(card, transaction)
    transaction.isTU18 == false && transaction.isCU == false -> null
    card.isSUZ -> null
    else -> when (tripReaderFareProtocol(card, transaction)) {
        "TU" -> CardProfiles.TU_EP_AID
        "LNT" -> CardProfiles.YCT_TICL_AID
        else -> tripReaderWalletAid(card, transaction)
    }
}

/** 本应用卡型：城市钱包优先（双协议卡的主应用），只有 TU 时为 TU。 */
internal fun tripReaderCardType(card: TripReaderCard): String = when {
    card.isSZT -> "SZT"
    card.isCU -> "CU"
    card.isTFT -> "TFT"
    card.isYCT || card.isLNT == true -> "YCT"
    else -> "TU"
}

/**
 * TripReader 卡号的归属应用：有 TU 时 cdNo 归 TU 应用，cdNo2 归城市钱包的卡号应用（岭南通为 PAY.APPY）；
 * 无 TU 时 cdNo 归城市钱包。无法确定应用的卡号不保存。
 */
internal fun tripReaderCardNumbers(card: TripReaderCard): List<Pair<String, String>> {
    val walletInfoAid = tripReaderCardType(card).takeIf { it != "TU" && tripReaderWalletAid(card) != null }
        ?.let(CardProfiles::infoAid)
    val tuNumber = card.cdNo.trim().takeIf { card.isTU && it.isNotEmpty() }
    val walletNumber = (if (card.isTU) card.cdNo2 else card.cdNo).trim().takeIf { it.isNotEmpty() }
    return listOfNotNull(
        tuNumber?.let { CardProfiles.TU_EP_AID to it },
        walletNumber?.let { number -> walletInfoAid?.let { it to number } }
    )
}

/**
 * TripReader 余额的归属应用：分侧余额（cdTUBalance / cdCUBalance）直接对应应用；
 * 总余额 cdBalance 补给没有分侧余额的每个钱包（双钱包卡两侧共用同一余额，实卡读取两侧一致）。
 */
internal fun tripReaderBalances(card: TripReaderCard): List<Pair<String, Long>> {
    fun known(value: Long) = value.takeIf { it != TripReaderDatabase.UNKNOWN_BALANCE && it >= 0 }
    val walletAid = tripReaderWalletAid(card)
    val result = linkedMapOf<String, Long>()
    if (card.isTU) known(card.cdTUBalance)?.let { result[CardProfiles.TU_EP_AID] = it }
    walletAid?.let { aid -> known(card.cdCUBalance)?.let { result[aid] = it } }
    for (aid in listOfNotNull(CardProfiles.TU_EP_AID.takeIf { card.isTU }, walletAid)) {
        known(card.cdBalance)?.let { result.putIfAbsent(aid, it) }
    }
    return result.toList()
}

/**
 * TripReader 保存的信息文件（SFI 0x15）→ 本应用读卡时的信息槽位 (AID, hex)：
 * cdTURawInfo 与 TU 应用 0x15 逐字节一致；cdCURawInfo 已核对与岭南通 PAY.APPY 0x15（70 字节布局）一致，
 * 其它城市钱包尚无样本核对，不导入。
 */
internal fun tripReaderInfoRecords(card: TripReaderCard): List<Pair<String, String>> = listOfNotNull(
    card.cdTURawInfo.trim().takeIf { card.isTU && it.isNotEmpty() }?.let { CardProfiles.TU_EP_AID to it },
    card.cdCURawInfo.trim().takeIf { tripReaderCardType(card) == "YCT" && it.isNotEmpty() }
        ?.let { CardProfiles.YCT_APPY_AID to it }
)

/**
 * 用户数据仓库：协调 Room 用户库（cards/card_app/raw_records/transactions_archive）、
 * DataStore（轻量设置）与日志文件（SessionLogStore）。
 * 原始记录与归档都挂在卡上应用（card_app.app_id）下；渲染唯一来源是 transactions_archive。
 */
class TransitRepository(private val context: Context) {

    private val database = UserDatabase.get(context)
    private val dao = database.userDao()

    // ── cards ──

    suspend fun loadCards(): List<CardEntity> = dao.getAllCards()

    /** 旧版本按旧规则解析的住建部卡号：按 CU 应用信息文件重新解析并写回该应用。 */
    suspend fun migrateCuCardNumbers() = database.withTransaction {
        for (card in dao.getAllCards().filter { it.cardType == "CU" }) {
            val cardNumber = cuCardNumberFromRaw(loadRawRecords(card.cardId))
                ?.takeIf { it.isNotEmpty() } ?: continue
            val app = dao.getCardApps(card.cardId)
                .firstOrNull { it.selectedAid.equals(CardProfiles.CU_AID, true) } ?: continue
            if (app.appCardNumber != cardNumber) dao.updateAppCardNumber(app.appId, cardNumber)
        }
    }

    suspend fun getCard(cardId: String): CardEntity? = dao.getCard(cardId)

    suspend fun upsertCard(card: CardEntity) = dao.upsertCard(card)

    suspend fun updateCardName(cardId: String, name: String) = dao.updateCardName(cardId, name)

    suspend fun updateCardColors(cardId: String, startColor: Long, endColor: Long) =
        dao.updateCardColors(cardId, startColor, endColor)

    // ── card_app ──

    /** 卡上该应用的 app_id；没有时新建一行推断出的应用（无 SELECT 响应、read_at = 0）。 */
    private suspend fun ensureApp(cardId: String, selectedAid: String): Long =
        dao.getCardApp(cardId, selectedAid)?.appId
            ?: dao.insertCardAppRow(CardAppEntity(cardId = cardId, selectedAid = selectedAid, readAt = 0))

    suspend fun syncCardApps(cardId: String, apps: List<CardAppEntity>) {
        apps.filter { it.selectedAid.isNotBlank() }.forEach { dao.mergeCardApp(it.copy(cardId = cardId)) }
    }

    suspend fun loadCardApps(cardId: String): List<CardAppEntity> = dao.getCardApps(cardId)

    suspend fun loadAllCardApps(): List<CardAppEntity> = dao.getAllCardApps()

    private suspend fun appNumbersByCard(): MutableMap<String, MutableSet<String>> =
        dao.getAllCardApps().groupBy({ it.cardId }, { it.appCardNumber })
            .mapValuesTo(mutableMapOf()) { (_, numbers) -> cardIdentityNumbers(*numbers.toTypedArray()).toMutableSet() }

    // ── raw_records（槽位历史版本；不用于渲染）──

    /** 本次读到的槽位：已有同内容版本只刷新 last_seen_at；内容变了则新增版本，旧内容留作历史。 */
    suspend fun syncRawRecords(cardId: String, records: List<RawRecord>) {
        val now = System.currentTimeMillis()
        val appIds = mutableMapOf<String, Long>()
        val versions = dao.getRawHistory(cardId).associateBy { RawVersionKey(it.appId, it.sfi, it.recNo, it.contentHash) }
        val seen = mutableListOf<Long>()
        val added = mutableListOf<RawRecordEntity>()
        for (rec in records.associateBy { Triple(it.selectedAid, it.sfi.toSfiHex(), it.recNo) }.values) {
            val appId = appIds.getOrPut(rec.selectedAid) { ensureApp(cardId, rec.selectedAid) }
            val key = RawVersionKey(appId, rec.sfi.toSfiHex(), rec.recNo, RecordDecoder.contentHash(rec.hex))
            val existing = versions[key]
            if (existing != null) {
                seen += existing.rowId
            } else {
                added += RawRecordEntity(
                    appId = appId, sfi = key.sfi, recNo = key.recNo, hex = rec.hex, contentHash = key.contentHash,
                    firstSeenAt = now, lastSeenAt = now
                )
            }
        }
        // SQLite 绑定数量有上限，按小批次更新。
        for (batch in seen.chunked(400)) dao.touchRawRecords(batch, now)
        dao.insertRawRecords(added)
    }

    /** 原始记录（带所属应用 AID 与由 AID 推出的解码协议）。 */
    suspend fun loadRawRecords(cardId: String): List<RawRecord> {
        val aids = dao.getCardApps(cardId).associate { it.appId to it.selectedAid }
        return dao.getRawRecords(cardId).map { it.toRawRecord(aids[it.appId].orEmpty()) }
    }

    // ── transactions_archive（渲染唯一来源，按内容去重）──

    /**
     * 解码出的交易 → 所属应用 AID：取读出同一内容（扇区 + SHA-256(hex)）的原始记录的应用，
     * 多个候选时优先解码协议一致者；没有读卡来源时按协议推断。
     */
    private fun aidResolver(
        records: List<RecordDecoder.ZoneRecord>,
        cardType: String
    ): (CanonicalTransaction) -> String {
        val aidsByContent = records.filter { it.selectedAid.isNotEmpty() }
            .groupBy({ it.sfi to RecordDecoder.contentHash(it.hex) }, { it.selectedAid })
        return { t ->
            val candidates = aidsByContent[t.sfi to t.identity].orEmpty()
            candidates.firstOrNull { CardProfiles.decodeProtocol(it, t.sfi) == t.protocol }
                ?: candidates.firstOrNull()
                ?: CardProfiles.defaultAidForProtocol(t.protocol, cardType)
        }
    }

    private suspend fun archiveKeys(cardId: String): Set<ArchiveKey> {
        val aids = dao.getCardApps(cardId).associate { it.appId to it.selectedAid }
        return dao.getArchive(cardId).mapTo(mutableSetOf()) {
            ArchiveKey(aids[it.appId].orEmpty(), it.sfi, it.contentHash)
        }
    }

    /** 写入归档，返回新增行数；已存在的行只刷新 last_seen_at。 */
    suspend fun archiveTransactions(
        cardId: String,
        cardType: String,
        records: List<RecordDecoder.ZoneRecord>,
        transactions: List<CanonicalTransaction>
    ): Int {
        if (transactions.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val aidOf = aidResolver(records, cardType)
        val appIds = mutableMapOf<String, Long>()
        val rows = transactions.map { aidOf(it) to it }
            .distinctBy { (aid, t) -> ArchiveKey(aid, t.sfi.toSfiHex(), t.identity) }
            .map { (aid, t) ->
                ArchivedTransactionEntity(
                    appId = appIds.getOrPut(aid) { ensureApp(cardId, aid) },
                    sfi = t.sfi.toSfiHex(),
                    hex = t.hex,
                    contentHash = t.identity,
                    resolvedDate = t.date,
                    firstSeenAt = now,
                    lastSeenAt = now
                )
            }
        val inserted = dao.insertArchiveRows(rows)
        val repeated = rows.filterIndexed { index, _ -> inserted[index] == -1L }
        for ((zone, entries) in repeated.groupBy { it.appId to it.sfi }) {
            // SQLite 绑定数量有上限，长历史导入也按小批次更新。
            for (batch in entries.chunked(400)) {
                dao.touchArchives(zone.first, zone.second, batch.map { it.contentHash }, now)
            }
        }
        return inserted.count { it != -1L }
    }

    suspend fun backfillArchiveFromRaw(
        cardId: String,
        cardType: String,
        rawRecords: List<RawRecord>,
        currentYear: Int
    ): Boolean {
        val records = RecordDecoder.transactionRecords(cardType, rawRecords)
        if (records.isEmpty()) return false

        val existingKeys = archiveKeys(cardId)
        if (records.none {
                ArchiveKey(it.selectedAid, it.sfi.toSfiHex(), RecordDecoder.contentHash(it.hex)) !in existingKeys
            }) {
            return false
        }

        // LNT 交易无年份字段：用月累乘统计记录的年月做解码年份锚点（岭南通 0x08 / TU 0x19 rec1）
        val statsMonth = RecordDecoder.parseMonthAccumulation(
            rawRecords, DiscountRegistry.walletMonthStats
        )?.month
        val issueDate = RecordDecoder.lntIssueDate(rawRecords)
        val decoded = RecordDecoder.decodeCard(cardType, records, statsMonth, currentYear, issueDate)
        val aidOf = aidResolver(records, cardType)
        val missing = decoded.archive.filter {
            ArchiveKey(aidOf(it), it.sfi.toSfiHex(), it.identity) !in existingKeys
        }
        if (missing.isEmpty()) return false

        return database.withTransaction {
            val currentKeys = archiveKeys(cardId)
            val pending = missing.filter {
                ArchiveKey(aidOf(it), it.sfi.toSfiHex(), it.identity) !in currentKeys
            }
            if (pending.isEmpty()) {
                false
            } else {
                archiveTransactions(cardId, cardType, records, orderForArchive(cardId, records, pending, issueDate))
                true
            }
        }
    }

    suspend fun persistNfcRead(
        card: CardEntity,
        rawRecords: List<RawRecord>,
        transactions: List<CanonicalTransaction>,
        apps: List<CardAppEntity>
    ): Boolean = database.withTransaction {
        dao.upsertCard(card)
        syncCardApps(card.cardId, apps)
        syncRawRecords(card.cardId, rawRecords)
        val records = RecordDecoder.transactionRecords(card.cardType, rawRecords)
        archiveTransactions(
            card.cardId, card.cardType, records,
            orderForArchive(card.cardId, records, transactions, RecordDecoder.lntIssueDate(rawRecords))
        ) > 0
    }

    /**
     * 原始记录 → 归档的落库顺序与 LNT 年份校正：
     * 按原始槽位 recNo 倒序（越大越旧），使 row_id 递增 = 写卡先后；
     * 未归档的 LNT 交易必晚于归档中 row_id 最大的 LNT 行，以其日期为下界校正推断年份。
     */
    private suspend fun orderForArchive(
        cardId: String,
        records: List<RecordDecoder.ZoneRecord>,
        transactions: List<CanonicalTransaction>,
        lntIssueDate: String?
    ): List<CanonicalTransaction> {
        val recNoByContent = records.associate { (it.sfi to RecordDecoder.contentHash(it.hex)) to it.recNo }
        val aids = dao.getCardApps(cardId).associate { it.appId to it.selectedAid }
        val archive = dao.getArchive(cardId)
        val existingContent = archive.mapTo(mutableSetOf()) { it.sfi.toSfiInt() to it.contentHash }
        val (existing, fresh) = transactions.sortedByDescending { recNoByContent[it.sfi to it.identity] ?: 0 }
            .partition { (it.sfi to it.identity) in existingContent }
        val latestLnt = archive.filter {
            CardProfiles.decodeProtocol(aids[it.appId].orEmpty(), it.sfi.toSfiInt()) == "LNT"
        }.maxByOrNull { it.rowId }?.resolvedDate
        return existing + RecordDecoder.alignLntYears(fresh, latestLnt, lntIssueDate = lntIssueDate)
    }

    /** 归档行 + 所属应用 AID；EC 日志附带该应用该文件的 DOL（原始记录 rec_no=0）。 */
    suspend fun loadArchive(cardId: String, rawRecords: List<RawRecord>): List<RecordDecoder.ArchivedRecord> {
        val aids = dao.getCardApps(cardId).associate { it.appId to it.selectedAid }
        val formats = RecordDecoder.ecLogFormats(rawRecords)
        return dao.getArchive(cardId).map { row ->
            val aid = aids[row.appId].orEmpty()
            val sfi = row.sfi.toSfiInt()
            RecordDecoder.ArchivedRecord(sfi, aid, row.hex, row.resolvedDate, formats[aid.uppercase() to sfi])
        }
    }

    suspend fun maxArchiveRowId(cardId: String): Long? = dao.maxArchiveRowId(cardId)

    // ── 删除 / 清空 ──

    suspend fun deleteCard(cardId: String) {
        dao.deleteCard(cardId)  // FK CASCADE：cards → card_app → raw_records + transactions_archive
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
        db.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { cursor -> cursor.moveToFirst() }
        val src = context.getDatabasePath(UserDatabase.DB_NAME)
        val out = context.contentResolver.openOutputStream(dest)
            ?: throw IOException(L10n.str(R.string.err_write_target))
        out.use { FileInputStream(src).use { input -> input.copyTo(out) } }
    }

    /**
     * 导入另一份用户库（旧版本经 Room 迁移到当前结构）：卡片按任一应用卡号（未知卡按芯片 UID）匹配现有卡，
     * 已存在则复用其 card_id 并合并记录（不覆盖卡片元数据）；新卡分配新 UUID 并追加到卡序。
     * 应用按 (card_id, selected_aid) 合并；原始记录按 (应用, sfi, rec_no, content_hash)、交易按 (应用, sfi, content_hash) 去重，
     * 只插入现有库中没有的行。原始记录保留源库读取时间，各槽位当前内容仍取两库中最近读到的版本。
     */
    suspend fun importDatabase(importFile: File): ImportSummary {
        val src = Room.databaseBuilder(
            context.applicationContext, UserDatabase::class.java, importFile.absolutePath
        ).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).addMigrations(*UserDatabase.MIGRATIONS).build()
        val srcDao = src.userDao()
        val importedCards = srcDao.getAllCards()
        val importedApps = srcDao.getAllCardApps()
        val importedRaws = srcDao.getAllRawRecords()
        val importedArchives = srcDao.getAllArchive()
        src.close()

        val now = System.currentTimeMillis()
        // 早期版本可能已把同一物理卡写成多张 cards 行。先按完整卡号合并本地旧数据，
        // 再让本次导入的记录落到唯一的 card_id，避免“再次导入也还是两张卡”。
        val mergedCards = coalesceDuplicateCards()
        val existingCards = dao.getAllCards().toMutableList()
        val numbersByCard = appNumbersByCard()
        val importedAppsByCard = importedApps.groupBy { it.cardId }
        val idMap = mutableMapOf<String, String>()
        val newCardIds = mutableListOf<String>()

        for (card in importedCards) {
            val numbers = cardIdentityNumbers(
                *importedAppsByCard[card.cardId].orEmpty().map { it.appCardNumber }.toTypedArray()
            )
            val existing = matchCard(existingCards, numbersByCard, numbers, card.chipUid)
            val targetId = if (existing != null) {
                existing.cardId
            } else {
                val newId = UUID.randomUUID().toString()
                val imported = card.copy(cardId = newId)
                dao.upsertCard(imported)
                existingCards.add(imported)
                newCardIds.add(newId)
                newId
            }
            idMap[card.cardId] = targetId
            numbersByCard.getOrPut(targetId) { mutableSetOf() }.addAll(numbers)
        }
        if (newCardIds.isNotEmpty()) {
            val order = AppPreferences.getCardOrder(context)
            AppPreferences.setCardOrder(context, order + newCardIds)
        }

        val appIdMap = mutableMapOf<Long, Long>()
        for (app in importedApps) {
            val targetId = idMap[app.cardId] ?: continue
            appIdMap[app.appId] = dao.mergeCardApp(app.copy(appId = 0, cardId = targetId))
        }

        var newRaw = 0
        for (raw in importedRaws) {
            val targetApp = appIdMap[raw.appId] ?: continue
            if (dao.insertRawRecord(raw.copy(rowId = 0, appId = targetApp)) != -1L) {
                newRaw++
            } else {
                dao.mergeRawSeen(targetApp, raw.sfi, raw.recNo, raw.contentHash, raw.firstSeenAt, raw.lastSeenAt)
            }
        }

        var newArchive = 0
        for (arc in importedArchives) {
            val targetApp = appIdMap[arc.appId] ?: continue
            val inserted = dao.insertArchiveRow(
                arc.copy(rowId = 0, appId = targetApp, firstSeenAt = now, lastSeenAt = now)
            )
            if (inserted != -1L) newArchive++
        }

        return ImportSummary(newCardIds.size, newRaw, newArchive, mergedCards)
    }

    /**
     * 导入 TripReader（card_table / tran_table）数据库：
     * 卡片按 cdNo / cdNo2 匹配现有卡的应用卡号，已存在则复用其 card_id 合并交易；新卡按来源卡型标志建卡。
     * 每条记录按 TripReader 逐条应用标记归入应用（txTU18 → TU 钱包，txCU → 城市钱包，txTUResult → TU 0x1E），
     * 卡号/余额按 TU / 城市钱包两侧归入对应应用（见 [tripReaderCardNumbers] / [tripReaderBalances]）。
     *  - TU 与 CU/SZT/天府通记录走 RecordDecoder.decodeCard 复原日期；
     *  - LNT 记录（岭南通，年份字段 0000）直接用源库 txDate 复原年份，resolved_date 落库。
     */
    suspend fun importTripReaderDatabase(importFile: File): ImportSummary {
        val source = TripReaderDatabase.read(importFile)
        val existingCards = dao.getAllCards().toMutableList()
        val numbersByCard = appNumbersByCard()
        val now = System.currentTimeMillis()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val newCardIds = mutableListOf<String>()
        val usedColors = existingCards.map { it.gradientStartColor }.toMutableSet()
        var newArchive = 0

        for (src in source.cards) {
            val tuRecords = mutableListOf<RecordDecoder.ZoneRecord>()
            val walletRecords = mutableListOf<RecordDecoder.ZoneRecord>()
            val lntRows = mutableListOf<Pair<String, Long>>()  // (0x18 hex, 源库 txDate 毫秒)
            var recNo = 0
            for (tx in src.transactions) {
                val fareAid = tx.cuHex.takeIf { it.isNotEmpty() }?.let { tripReaderFareAid(src, tx) }
                if (fareAid != null) {
                    val sfi = tx.sfi ?: 0x18
                    val record = RecordDecoder.ZoneRecord(
                        sfi, recNo++, CardProfiles.decodeProtocol(fareAid, sfi), tx.cuHex, selectedAid = fareAid
                    )
                    when (fareAid) {
                        CardProfiles.YCT_TICL_AID -> lntRows.add(tx.cuHex to tx.dateMs)
                        CardProfiles.TU_EP_AID -> tuRecords.add(record)
                        else -> walletRecords.add(record)
                    }
                }
                if (tx.tuHex.isNotEmpty()) {
                    tuRecords.add(
                        RecordDecoder.ZoneRecord(0x1E, recNo++, "TU", tx.tuHex, selectedAid = CardProfiles.TU_EP_AID)
                    )
                }
            }

            val importedCardType = tripReaderCardType(src)
            val cardNumbers = tripReaderCardNumbers(src)
            val existing = matchCard(
                existingCards, numbersByCard,
                cardIdentityNumbers(*cardNumbers.map { it.second }.toTypedArray()), null
            )
            val cardId = if (existing != null) {
                if (importedCardType in setOf("SZT", "CU", "TFT") && existing.cardType != importedCardType) {
                    val retyped = existing.copy(cardType = importedCardType)
                    dao.upsertCard(retyped)
                    existingCards[existingCards.indexOf(existing)] = retyped
                }
                existing.cardId
            } else run {
                val id = UUID.randomUUID().toString()
                val gradient = importCardPalette.firstOrNull { it.first !in usedColors }
                    ?: importCardPalette[newCardIds.size % importCardPalette.size]
                usedColors.add(gradient.first)
                val imported = CardEntity(
                    cardId = id,
                    name = sequenceOf(src.cdNo, src.cdNo2)
                        .mapNotNull(TransitData::cardName)
                        .firstOrNull() ?: src.cdTitle,
                    cardType = importedCardType,
                    gradientStartColor = gradient.first,
                    gradientEndColor = gradient.second,
                    createdAt = now,
                    lastReadAt = now
                )
                dao.upsertCard(imported)
                existingCards.add(imported)
                newCardIds.add(id)
                id
            }

            // 卡号/余额只补充应用缺失的值：实际读卡得到的快照更新（read_at 更大），不被导入覆盖。
            for ((aid, number) in cardNumbers) {
                dao.mergeCardApp(CardAppEntity(cardId = cardId, selectedAid = aid, appCardNumber = number, readAt = 0))
            }
            for ((aid, balance) in tripReaderBalances(src)) {
                dao.mergeCardApp(CardAppEntity(cardId = cardId, selectedAid = aid, balanceFen = balance, readAt = 0))
            }
            numbersByCard.getOrPut(cardId) { mutableSetOf() }.addAll(cardNumbers.map { it.second })
            // 信息文件只填补空槽位：实际读卡得到的当前槽位内容不被导入覆盖。
            val seenAt = src.cdLastUpdateMs ?: now
            for ((aid, hex) in tripReaderInfoRecords(src)) {
                val appId = ensureApp(cardId, aid)
                if (!dao.hasRawSlot(appId, "0x15", 0)) {
                    dao.insertRawRecord(
                        RawRecordEntity(
                            appId = appId, sfi = "0x15", recNo = 0, hex = hex,
                            contentHash = RecordDecoder.contentHash(hex), firstSeenAt = seenAt, lastSeenAt = seenAt
                        )
                    )
                }
            }

            // TU 与城市钱包分开解码；归档按各记录自己的应用落库。
            val decodedArchive = buildList {
                if (tuRecords.isNotEmpty()) {
                    addAll(RecordDecoder.decodeCard("TU", tuRecords, null, currentYear).archive)
                }
                if (walletRecords.isNotEmpty()) {
                    addAll(RecordDecoder.decodeCard(importedCardType, walletRecords, null, currentYear).archive)
                }
            }
            newArchive += archiveTransactions(cardId, importedCardType, tuRecords + walletRecords, decodedArchive)
            // LNT：年份来自源库绝对时间，渲染时由归档的 resolved_date 覆盖 hex 内 0000 年份
            if (lntRows.isNotEmpty()) {
                val lntApp = ensureApp(cardId, CardProfiles.YCT_TICL_AID)
                for ((hex, dateMs) in lntRows) {
                    val inserted = dao.insertArchiveRow(
                        ArchivedTransactionEntity(
                            appId = lntApp, sfi = "0x18", hex = hex,
                            contentHash = RecordDecoder.contentHash(hex), resolvedDate = yyyyMMdd(dateMs),
                            firstSeenAt = now, lastSeenAt = now
                        )
                    )
                    if (inserted != -1L) newArchive++
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

    /**
     * 合并早期版本留下的重复卡。只接受应用卡号有交集的卡，绝不以尾号作为
     * 删除依据；尾号相同的不同实体卡会继续独立保留。
     */
    suspend fun coalesceDuplicateCards(): Int {
        val replacementIds = mutableMapOf<String, String>()
        val mergedCount = database.withTransaction {
            val numbersByCard = appNumbersByCard()
            val canonicalCards = mutableListOf<CardEntity>()
            var count = 0
            for (candidate in dao.getAllCards()) {
                val candidateNumbers = numbersByCard[candidate.cardId].orEmpty()
                val canonicalIndex = canonicalCards.indexOfFirst { canonical ->
                    sharesCardIdentity(numbersByCard[canonical.cardId].orEmpty(), candidateNumbers)
                }
                if (canonicalIndex < 0) {
                    canonicalCards.add(candidate)
                    continue
                }

                val canonical = canonicalCards[canonicalIndex]
                // 旧版本可能只有 raw_records；合并后槽位当前内容可能换成另一张卡的版本，先把两张卡的历史交易补入归档。
                for (card in listOf(canonical, candidate)) {
                    backfillArchiveFromRaw(
                        card.cardId,
                        card.cardType,
                        loadRawRecords(card.cardId),
                        Calendar.getInstance().get(Calendar.YEAR)
                    )
                }
                val merged = mergeDuplicateCardMetadata(canonical, candidate)
                dao.upsertCard(merged)
                mergeDuplicateCardRows(merged.cardId, candidate.cardId)
                dao.deleteCard(candidate.cardId)
                numbersByCard.getOrPut(merged.cardId) { mutableSetOf() }.addAll(candidateNumbers)
                canonicalCards[canonicalIndex] = merged
                replacementIds[candidate.cardId] = merged.cardId
                count++
            }
            count
        }
        if (replacementIds.isEmpty()) return mergedCount

        val order = AppPreferences.getCardOrder(context)
        AppPreferences.setCardOrder(
            context,
            order.map { cardId -> replacementIds[cardId] ?: cardId }.distinct()
        )
        AppPreferences.getSelectedCardId(context)?.let { selectedId ->
            replacementIds[selectedId]?.let { AppPreferences.setSelectedCardId(context, it) }
        }
        return mergedCount
    }

    /** 应用合并到目标卡，原始记录并入全部历史版本、归档补入缺失行，再由调用方删除冗余卡。 */
    private suspend fun mergeDuplicateCardRows(targetCardId: String, duplicateCardId: String) {
        val appIds = mutableMapOf<Long, Long>()
        for (app in dao.getCardApps(duplicateCardId)) {
            appIds[app.appId] = dao.mergeCardApp(app.copy(appId = 0, cardId = targetCardId))
        }
        for (raw in dao.getRawHistory(duplicateCardId)) {
            val targetApp = appIds[raw.appId] ?: continue
            if (dao.insertRawRecord(raw.copy(rowId = 0, appId = targetApp)) == -1L) {
                dao.mergeRawSeen(targetApp, raw.sfi, raw.recNo, raw.contentHash, raw.firstSeenAt, raw.lastSeenAt)
            }
        }
        for (archive in dao.getArchive(duplicateCardId)) {
            val targetApp = appIds[archive.appId] ?: continue
            dao.insertArchiveRow(archive.copy(rowId = 0, appId = targetApp))
        }
    }

    private fun mergeDuplicateCardMetadata(
        canonical: CardEntity,
        duplicate: CardEntity
    ): CardEntity = canonical.copy(
        cardType = if (canonical.cardType == "TU" && duplicate.cardType != "TU") {
            duplicate.cardType
        } else {
            canonical.cardType
        },
        chipUid = canonical.chipUid ?: duplicate.chipUid,
        createdAt = minOf(canonical.createdAt, duplicate.createdAt),
        lastReadAt = maxOf(canonical.lastReadAt, duplicate.lastReadAt)
    )

    /** 卡片身份：任一应用卡号相同；没有应用卡号的未知卡按芯片 UID。 */
    private fun matchCard(
        existing: List<CardEntity>,
        numbersByCard: Map<String, Set<String>>,
        numbers: Set<String>,
        chipUid: String?
    ): CardEntity? {
        if (numbers.isNotEmpty()) {
            return existing.firstOrNull { card ->
                sharesCardIdentity(numbers, numbersByCard[card.cardId].orEmpty())
            }
        }
        val uid = chipUid?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return existing.firstOrNull { it.chipUid == uid }
    }

    private fun yyyyMMdd(epochMs: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(epochMs))

    private val importCardPalette: List<Pair<Long, Long>> = CardPalette.colors

    // ── 日志文件 ──

    fun sessionLogs(cardId: String) = SessionLogStore.sessionLogs(context, cardId)

    fun combinedCardLog(cardId: String) = SessionLogStore.combinedLog(context, cardId)

    /** 调试模式开启时才写入会话文件（关闭时不写，避免隐私与存储膨胀） */
    suspend fun writeSessionLog(cardId: String, lines: List<String>) {
        if (AppPreferences.isDebugMode(context)) {
            SessionLogStore.write(context, cardId, System.currentTimeMillis(), lines)
        }
    }

    // ── DataStore 轻量设置 ──

    suspend fun getSelectedCardId(): String? = AppPreferences.getSelectedCardId(context)
    suspend fun setSelectedCardId(cardId: String?) = AppPreferences.setSelectedCardId(context, cardId)
    suspend fun getCardOrder(): List<String> = AppPreferences.getCardOrder(context)
    suspend fun setCardOrder(cardIds: List<String>) = AppPreferences.setCardOrder(context, cardIds)
    suspend fun getCardSortMode(): CardSortMode = AppPreferences.getCardSortMode(context)
    suspend fun getCardSortAscending(): Boolean? = AppPreferences.getCardSortAscending(context)
    suspend fun setCardSortMode(mode: CardSortMode, ascending: Boolean) =
        AppPreferences.setCardSortMode(context, mode, ascending)
    suspend fun isDebugMode(): Boolean = AppPreferences.isDebugMode(context)
    suspend fun setDebugMode(enabled: Boolean) = AppPreferences.setDebugMode(context, enabled)
    suspend fun isPlaybackHaptics(): Boolean = AppPreferences.isPlaybackHaptics(context)
    suspend fun setPlaybackHaptics(enabled: Boolean) = AppPreferences.setPlaybackHaptics(context, enabled)
    suspend fun getStatsCustomChart(): String? = AppPreferences.getStatsCustomChart(context)
    suspend fun setStatsCustomChart(encoded: String) = AppPreferences.setStatsCustomChart(context, encoded)
    suspend fun isPlaybackSound(): Boolean = AppPreferences.isPlaybackSound(context)
    suspend fun setPlaybackSound(enabled: Boolean) = AppPreferences.setPlaybackSound(context, enabled)
    suspend fun getCurrentTripRouteDisplayMode(): CurrentTripRouteDisplayMode =
        AppPreferences.getCurrentTripRouteDisplayMode(context)
    suspend fun setCurrentTripRouteDisplayMode(mode: CurrentTripRouteDisplayMode) =
        AppPreferences.setCurrentTripRouteDisplayMode(context, mode)
    suspend fun getSchemaVersion(): Int = AppPreferences.getSchemaVersion(context)
    suspend fun markMigrated() = AppPreferences.markMigrated(context)
}
