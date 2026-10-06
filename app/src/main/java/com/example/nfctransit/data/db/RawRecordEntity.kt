package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.nfctransit.CardProfiles
import com.example.nfctransit.data.RawRecord
import com.example.nfctransit.data.toSfiInt

/**
 * 卡内当前 SFI 槽位状态（用户数据库）。
 * (card_id, selected_aid, sfi, rec_no) 唯一——AID 区分各个应用下的文件；
 * rec_no 只是卡内循环记录槽位，会被新交易覆盖：
 *  槽不存在 → 插入；hex 相同 → 只更新 last_seen_at；hex 不同 → 覆盖。
 * 不用于渲染；渲染唯一来源是 transactions_archive。
 */
@Entity(
    tableName = "raw_records",
    foreignKeys = [
        ForeignKey(
            entity = CardEntity::class,
            parentColumns = ["card_id"],
            childColumns = ["card_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["card_id", "selected_aid", "sfi", "rec_no"], unique = true),
        Index(value = ["card_id"])
    ]
)
data class RawRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "sfi") val sfi: String,  // hex 字符串（"0x19"），与 README/日志格式一致
    @ColumnInfo(name = "rec_no") val recNo: Int,
    @ColumnInfo(name = "selected_aid") val selectedAid: String,
    @ColumnInfo(name = "hex") val hex: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,  // SHA-256(hex)
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long
) {
    /** 解码协议由应用 AID 解析；数据库只保存应用身份。 */
    fun toRawRecord(): RawRecord {
        val zone = sfi.toSfiInt()
        val appProtocol = CardProfiles.protocolForAid(selectedAid)
        // 苏州/天津交易沿用空解码标签，避免把已有归档重新记成不同协议的交易。
        val protocol = appProtocol.takeIf { zone == 0x15 || it in setOf("LNT", "TU", "CU", "SZT") }.orEmpty()
        return RawRecord(zone, recNo, protocol, hex, selectedAid)
    }
}
