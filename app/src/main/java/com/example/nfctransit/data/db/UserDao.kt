package com.example.nfctransit.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface UserDao {

    // ── cards ──

    @Query("SELECT * FROM cards ORDER BY rowid")
    suspend fun getAllCards(): List<CardEntity>

    @Query("SELECT * FROM cards WHERE card_id = :cardId LIMIT 1")
    suspend fun getCard(cardId: String): CardEntity?

    @Query("SELECT * FROM cards WHERE card_number = :cardNumber AND card_number != '' LIMIT 1")
    suspend fun findByCardNumber(cardNumber: String): CardEntity?

    /** 旧数据兜底：按尾号匹配（读不到卡号时的旧身份） */
    @Query("SELECT * FROM cards WHERE last_four = :lastFour LIMIT 1")
    suspend fun findByLastFour(lastFour: String): CardEntity?

    @Upsert
    suspend fun upsertCard(card: CardEntity)

    @Query("UPDATE cards SET latest_balance_fen = :balanceFen, last_read_at = :lastReadAt WHERE card_id = :cardId")
    suspend fun updateCardBalance(cardId: String, balanceFen: Long?, lastReadAt: Long)

    @Query("DELETE FROM cards WHERE card_id = :cardId")
    suspend fun deleteCard(cardId: String)

    @Query("DELETE FROM cards")
    suspend fun clearCards()

    @Query("SELECT COUNT(*) FROM cards")
    suspend fun countCards(): Int

    // ── raw_records（卡内当前槽位状态）──

    @Query("SELECT * FROM raw_records WHERE card_id = :cardId ORDER BY sfi, rec_no")
    suspend fun getRawRecords(cardId: String): List<RawRecordEntity>

    @Query("SELECT * FROM raw_records")
    suspend fun getAllRawRecords(): List<RawRecordEntity>

    @Query("SELECT * FROM raw_records WHERE card_id = :cardId AND protocol = :protocol AND sfi = :sfi AND rec_no = :recNo LIMIT 1")
    suspend fun getRawSlot(cardId: String, protocol: String, sfi: String, recNo: Int): RawRecordEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawRecord(record: RawRecordEntity): Long

    @Query("UPDATE raw_records SET last_seen_at = :lastSeenAt WHERE card_id = :cardId AND protocol = :protocol AND sfi = :sfi AND rec_no = :recNo")
    suspend fun touchRawSlot(cardId: String, protocol: String, sfi: String, recNo: Int, lastSeenAt: Long)

    @Query("UPDATE raw_records SET hex = :hex, content_hash = :contentHash, last_seen_at = :lastSeenAt WHERE card_id = :cardId AND protocol = :protocol AND sfi = :sfi AND rec_no = :recNo")
    suspend fun overwriteRawSlot(cardId: String, protocol: String, sfi: String, recNo: Int, hex: String, contentHash: String, lastSeenAt: Long)

    // ── transactions_archive（渲染唯一来源，按内容去重）──

    @Query("SELECT * FROM transactions_archive WHERE card_id = :cardId ORDER BY sfi, row_id")
    suspend fun getArchive(cardId: String): List<ArchivedTransactionEntity>

    @Query("SELECT * FROM transactions_archive")
    suspend fun getAllArchive(): List<ArchivedTransactionEntity>

    @Query("SELECT * FROM transactions_archive WHERE card_id = :cardId AND content_hash = :contentHash AND protocol = :protocol AND sfi = :sfi LIMIT 1")
    suspend fun getArchiveSlot(cardId: String, contentHash: String, protocol: String, sfi: String): ArchivedTransactionEntity?

    /** 完全一样（同内容同协议同扇区）→ IGNORE 跳过；内容/协议/扇区任一不同 → 新行插入 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArchiveRow(row: ArchivedTransactionEntity): Long

    @Query("UPDATE transactions_archive SET last_seen_at = :lastSeenAt WHERE card_id = :cardId AND content_hash = :contentHash AND protocol = :protocol AND sfi = :sfi")
    suspend fun touchArchive(cardId: String, contentHash: String, protocol: String, sfi: String, lastSeenAt: Long)

    @Query("DELETE FROM raw_records WHERE card_id = :cardId")
    suspend fun clearRawRecords(cardId: String)

    @Query("DELETE FROM transactions_archive WHERE card_id = :cardId")
    suspend fun clearArchive(cardId: String)

    // ── card_app（卡上应用 SELECT/BALANCE 记录，追加历史）──

    @Insert
    suspend fun insertCardApp(row: CardAppEntity): Long

    @Query("SELECT * FROM card_app WHERE card_id = :cardId ORDER BY read_at DESC, row_id DESC")
    suspend fun getCardApps(cardId: String): List<CardAppEntity>

    @Query("DELETE FROM card_app WHERE card_id = :cardId")
    suspend fun clearCardApps(cardId: String)
}
