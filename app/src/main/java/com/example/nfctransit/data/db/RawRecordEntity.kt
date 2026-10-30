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
 * 应用内当前 SFI 槽位状态（用户数据库）。
 * (app_id, sfi, rec_no) 唯一——记录属于读取它时选中的应用（card_app）；
 * rec_no 只是卡内循环记录槽位，会被新交易覆盖：
 *  槽不存在 → 插入；hex 相同 → 只更新 last_seen_at；hex 不同 → 覆盖。
 * EC 日志文件的 rec_no=0 保存 GET DATA 读到的日志格式（DOL），解析该文件的记录需要它。
 * 不用于渲染；渲染唯一来源是 transactions_archive。
 */
@Entity(
    tableName = "raw_records",
    foreignKeys = [
        ForeignKey(
            entity = CardAppEntity::class,
            parentColumns = ["app_id"],
            childColumns = ["app_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["app_id", "sfi", "rec_no"], unique = true)
    ]
)
data class RawRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "app_id") val appId: Long,
    @ColumnInfo(name = "sfi") val sfi: String,  // hex 字符串（"0x19"），与 README/日志格式一致
    @ColumnInfo(name = "rec_no") val recNo: Int,
    @ColumnInfo(name = "hex") val hex: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,  // SHA-256(hex)
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long
) {
    /** 解码协议由所属应用的 AID 推出；数据库只保存应用身份。 */
    fun toRawRecord(selectedAid: String): RawRecord {
        val zone = sfi.toSfiInt()
        return RawRecord(zone, recNo, CardProfiles.decodeProtocol(selectedAid, zone), hex, selectedAid)
    }
}
