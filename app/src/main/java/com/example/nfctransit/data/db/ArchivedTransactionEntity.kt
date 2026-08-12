package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 交易区原始内容归档（用户数据库）——渲染唯一数据源。
 * 只存交易区（0x18/0x10/0x09/0x06/0x1A/0x1E）内容，不含 rec_no。
 * 去重键 = (content_hash, protocol, sfi)：同一内容在不同协议/扇区都出现时（如 TU 0x1E 与 LNT 0x18
 * 内容相同）各存一行；只有完全一样（同内容同协议同扇区）才跳过。渲染时再按内容合并为一条展示。
 *
 * resolved_date / balance_after_fen 在读卡时解析并随行落库：
 * LNT 记录本身无年份字段（年份靠统计月份锚点 + 记录连续性推断），纯 hex 无法复得；
 * LNT 余额是钱包级快照，也须读卡时固化。其余字段渲染时从 hex 重新解析。
 */
@Entity(
    tableName = "transactions_archive",
    foreignKeys = [
        ForeignKey(
            entity = CardEntity::class,
            parentColumns = ["card_id"],
            childColumns = ["card_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["card_id", "content_hash", "protocol", "sfi"], unique = true),
        Index(value = ["card_id"])
    ]
)
data class ArchivedTransactionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "sfi") val sfi: Int,
    @ColumnInfo(name = "protocol") val protocol: String,  // "LNT"/"TU"/"" — 双协议卡区分钱包
    @ColumnInfo(name = "hex") val hex: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,  // SHA-256(hex)，去重键
    @ColumnInfo(name = "resolved_date") val resolvedDate: String,  // "yyyyMMdd"，含推断年份
    @ColumnInfo(name = "balance_after_fen") val balanceAfterFen: Long? = null,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long
)
