package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户卡片表（用户数据库 user_data.db）。
 * card_id 为 App 生成的稳定 UUID，卡片身份由 card_number（应用序列号）匹配。
 * name 为展示名（如 "羊城通"），card_type 为协议类型（"TU"/"YCT"/"CU"/…，供解码器使用）。
 */
@Entity(
    tableName = "cards",
    indices = [Index(value = ["card_number"]), Index(value = ["second_card_number"])]
)
data class CardEntity(
    @PrimaryKey
    @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "card_number") val cardNumber: String,
    @ColumnInfo(name = "second_card_number") val secondCardNumber: String? = null,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "card_type") val cardType: String,
    @ColumnInfo(name = "last_four") val lastFour: String,
    @ColumnInfo(name = "gradient_start_color") val gradientStartColor: Long,
    @ColumnInfo(name = "gradient_end_color") val gradientEndColor: Long,
    @ColumnInfo(name = "latest_balance_fen") val latestBalanceFen: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long
)
