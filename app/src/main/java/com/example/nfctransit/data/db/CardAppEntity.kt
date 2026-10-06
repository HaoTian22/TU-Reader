package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 卡上应用 SELECT/BALANCE 记录（用户数据库）——每张卡、每个应用一行。
 * 同一张卡可能命中多个应用（双协议 YCT 的 LNT 钱包 PAY.TICL + TU 钱包 A000000632010105，
 * 各存一行）；PSE（2PAY.SYS.DDF01）枚举结果也存一行（selected_aid=PSE_AID，无余额）。
 * 按 (card_id, selected_aid) 合并，read_at 标记最新读取时间，保留该次完整快照。
 */
@Entity(
    tableName = "card_app",
    foreignKeys = [
        ForeignKey(
            entity = CardEntity::class,
            parentColumns = ["card_id"],
            childColumns = ["card_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["card_id", "read_at"]),
        Index(value = ["card_id", "selected_aid"], unique = true)
    ]
)
data class CardAppEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "read_at") val readAt: Long,
    @ColumnInfo(name = "selected_aid") val selectedAid: String,
    @ColumnInfo(name = "select_resp") val selectResp: String,
    @ColumnInfo(name = "balance_fen") val balanceFen: Long? = null,
    @ColumnInfo(name = "balance_resp") val balanceResp: String? = null
)
