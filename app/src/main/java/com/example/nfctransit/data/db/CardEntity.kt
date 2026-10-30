package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户卡片表（用户数据库 user_data.db）：只存卡片本身的身份与展示设置。
 * card_id 为 App 生成的稳定 UUID；卡号、余额等属于卡上各应用，存 card_app。
 * name 为展示名（如 "羊城通"），card_type 为协议类型（"TU"/"YCT"/"CU"/…，供解码器使用）。
 * chip_uid 只在无任何已知应用的未知卡上保存芯片 UID（不是应用卡号）。
 */
@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey
    @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "card_type") val cardType: String,
    @ColumnInfo(name = "chip_uid") val chipUid: String? = null,
    @ColumnInfo(name = "gradient_start_color") val gradientStartColor: Long,
    @ColumnInfo(name = "gradient_end_color") val gradientEndColor: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long
)
