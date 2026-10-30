package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 卡上应用（用户数据库）——每张卡、每个应用一行；原始记录与交易归档都挂在应用上（app_id）。
 * 同一张卡可能命中多个应用（双协议 YCT 的 LNT 信息应用 PAY.APPY、钱包 PAY.TICL + TU 钱包
 * A000000632010105，各存一行）；PSE（2PAY.SYS.DDF01）枚举结果也存一行（无余额）。
 * app_card_number 为该应用自己的应用序列号（卡号），balance_fen 为该应用钱包余额。
 * select_resp 为空表示该应用从未实际 SELECT：只由导入数据（TripReader）或旧版本数据推断得到。
 * 按 (card_id, selected_aid) 合并，read_at 标记最新读取时间。
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
        Index(value = ["card_id", "selected_aid"], unique = true),
        Index(value = ["app_card_number"])
    ]
)
data class CardAppEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "app_id") val appId: Long = 0,
    @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "selected_aid") val selectedAid: String,
    @ColumnInfo(name = "app_card_number") val appCardNumber: String? = null,
    @ColumnInfo(name = "select_resp") val selectResp: String? = null,
    @ColumnInfo(name = "balance_fen") val balanceFen: Long? = null,
    @ColumnInfo(name = "balance_resp") val balanceResp: String? = null,
    @ColumnInfo(name = "read_at") val readAt: Long
)
