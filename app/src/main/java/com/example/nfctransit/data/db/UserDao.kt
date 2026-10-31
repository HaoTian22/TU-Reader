package com.example.nfctransit.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert

@Dao
interface UserDao {

    // ── cards ──

    @Query("SELECT * FROM cards ORDER BY rowid")
    suspend fun getAllCards(): List<CardEntity>

    @Query("SELECT * FROM cards WHERE card_id = :cardId LIMIT 1")
    suspend fun getCard(cardId: String): CardEntity?

    @Upsert
    suspend fun upsertCard(card: CardEntity)

    @Query("UPDATE cards SET name = :name WHERE card_id = :cardId")
    suspend fun updateCardName(cardId: String, name: String)

    @Query("UPDATE cards SET gradient_start_color = :startColor, gradient_end_color = :endColor WHERE card_id = :cardId")
    suspend fun updateCardColors(cardId: String, startColor: Long, endColor: Long)

    @Query("DELETE FROM cards WHERE card_id = :cardId")
    suspend fun deleteCard(cardId: String)

    @Query("DELETE FROM cards")
    suspend fun clearCards()

    // ── card_app（每张卡、每个应用一行；原始记录与归档的归属）──

    @Insert
    suspend fun insertCardAppRow(row: CardAppEntity): Long

    @Update
    suspend fun updateCardAppRow(row: CardAppEntity)

    @Query("SELECT * FROM card_app WHERE card_id = :cardId AND selected_aid = :selectedAid LIMIT 1")
    suspend fun getCardApp(cardId: String, selectedAid: String): CardAppEntity?

    /**
     * 所有写入入口共用，返回 app_id：旧快照不覆盖新快照；新快照缺少的卡号/余额沿用已知值，
     * 推断出的应用（无 SELECT 响应）不会覆盖实际读取过的响应。
     */
    @Transaction
    suspend fun mergeCardApp(row: CardAppEntity): Long {
        val existing = getCardApp(row.cardId, row.selectedAid)
            ?: return insertCardAppRow(row.copy(appId = 0))
        val newer = row.readAt >= existing.readAt
        val (latest, older) = if (newer) row to existing else existing to row
        val merged = existing.copy(
            appCardNumber = latest.appCardNumber ?: older.appCardNumber,
            selectResp = latest.selectResp ?: older.selectResp,
            balanceFen = latest.balanceFen ?: older.balanceFen,
            balanceResp = if (latest.balanceFen != null) latest.balanceResp else older.balanceResp,
            readAt = maxOf(existing.readAt, row.readAt)
        )
        if (merged != existing) updateCardAppRow(merged)
        return existing.appId
    }

    @Query("UPDATE card_app SET app_card_number = :number WHERE app_id = :appId")
    suspend fun updateAppCardNumber(appId: Long, number: String)

    @Query("SELECT * FROM card_app WHERE card_id = :cardId ORDER BY app_id")
    suspend fun getCardApps(cardId: String): List<CardAppEntity>

    @Query("SELECT * FROM card_app ORDER BY app_id")
    suspend fun getAllCardApps(): List<CardAppEntity>

    // ── raw_records（槽位历史版本；同槽同内容一行）──

    /** 各槽位当前内容：同一 (app_id, sfi, rec_no) 取 last_seen_at 最新（相同取 row_id 最大）的版本。 */
    @Query(
        "SELECT r.* FROM raw_records AS r INNER JOIN card_app AS a ON r.app_id = a.app_id " +
            "WHERE a.card_id = :cardId AND NOT EXISTS (SELECT 1 FROM raw_records AS n " +
            "WHERE n.app_id = r.app_id AND n.sfi = r.sfi AND n.rec_no = r.rec_no " +
            "AND (n.last_seen_at > r.last_seen_at OR (n.last_seen_at = r.last_seen_at AND n.row_id > r.row_id))) " +
            "ORDER BY r.sfi, r.rec_no"
    )
    suspend fun getRawRecords(cardId: String): List<RawRecordEntity>

    /** 卡上全部原始记录版本（含已被卡内覆盖的历史内容）。 */
    @Query(
        "SELECT r.* FROM raw_records AS r INNER JOIN card_app AS a ON r.app_id = a.app_id " +
            "WHERE a.card_id = :cardId ORDER BY r.sfi, r.rec_no, r.last_seen_at"
    )
    suspend fun getRawHistory(cardId: String): List<RawRecordEntity>

    @Query("SELECT * FROM raw_records")
    suspend fun getAllRawRecords(): List<RawRecordEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM raw_records WHERE app_id = :appId AND sfi = :sfi AND rec_no = :recNo)")
    suspend fun hasRawSlot(appId: Long, sfi: String, recNo: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawRecord(record: RawRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawRecords(records: List<RawRecordEntity>): List<Long>

    @Query("UPDATE raw_records SET last_seen_at = :lastSeenAt WHERE row_id IN (:rowIds)")
    suspend fun touchRawRecords(rowIds: List<Long>, lastSeenAt: Long)

    /** 合并来的版本已存在：只扩展它的首次/最近读取时间。 */
    @Query(
        "UPDATE raw_records SET first_seen_at = MIN(first_seen_at, :firstSeenAt), last_seen_at = MAX(last_seen_at, :lastSeenAt) " +
            "WHERE app_id = :appId AND sfi = :sfi AND rec_no = :recNo AND content_hash = :contentHash"
    )
    suspend fun mergeRawSeen(appId: Long, sfi: String, recNo: Int, contentHash: String, firstSeenAt: Long, lastSeenAt: Long)

    // ── transactions_archive（渲染唯一来源，按内容去重）──

    @Query(
        "SELECT t.* FROM transactions_archive AS t INNER JOIN card_app AS a ON t.app_id = a.app_id " +
            "WHERE a.card_id = :cardId ORDER BY t.sfi, t.row_id"
    )
    suspend fun getArchive(cardId: String): List<ArchivedTransactionEntity>

    @Query("SELECT * FROM transactions_archive")
    suspend fun getAllArchive(): List<ArchivedTransactionEntity>

    /** 该卡归档最大 row_id，UI 构建缓存的失效标记（新交易落库即增长；无记录返回 null） */
    @Query(
        "SELECT MAX(t.row_id) FROM transactions_archive AS t INNER JOIN card_app AS a ON t.app_id = a.app_id " +
            "WHERE a.card_id = :cardId"
    )
    suspend fun maxArchiveRowId(cardId: String): Long?

    /** 完全一样（同应用同扇区同内容）→ IGNORE 跳过并返回 -1；任一不同 → 新行插入 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArchiveRow(row: ArchivedTransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArchiveRows(rows: List<ArchivedTransactionEntity>): List<Long>

    @Query("UPDATE transactions_archive SET last_seen_at = :lastSeenAt WHERE app_id = :appId AND sfi = :sfi AND content_hash IN (:hashes)")
    suspend fun touchArchives(appId: Long, sfi: String, hashes: List<String>, lastSeenAt: Long)
}
