package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 交易区原始内容归档（用户数据库）——渲染唯一数据源。
 * 只存交易区（0x18/0x10/0x09/0x06/0x1A/0x1E/EC 日志）内容，不含 rec_no；每行属于读出它的应用（app_id），
 * 解码协议由应用 AID 推出，不另存协议标签。
 * 去重键 = (app_id, sfi, content_hash)：同一内容在不同应用/扇区都出现时各存一行，渲染时再按内容合并为一条展示。
 *
 * resolved_date 在读卡时解析并随行落库：LNT 记录本身无年份字段（年份靠统计月份锚点 + 记录连续性推断），
 * 纯 hex 无法复得。其余字段（含余额）一律从 hex 重新解码；EC 日志按所属应用该文件的 DOL（raw_records rec_no=0）解码。
 */
@Entity(
    tableName = "transactions_archive",
    foreignKeys = [
        ForeignKey(
            entity = CardAppEntity::class,
            parentColumns = ["app_id"],
            childColumns = ["app_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["app_id", "sfi", "content_hash"], unique = true)
    ]
)
data class ArchivedTransactionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "app_id") val appId: Long,
    @ColumnInfo(name = "sfi") val sfi: String,  // hex 字符串（"0x19"），与 README/日志格式一致
    @ColumnInfo(name = "hex") val hex: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,  // SHA-256(hex)，去重键
    @ColumnInfo(name = "resolved_date") val resolvedDate: String,  // "yyyyMMdd"，含推断年份
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long
)
