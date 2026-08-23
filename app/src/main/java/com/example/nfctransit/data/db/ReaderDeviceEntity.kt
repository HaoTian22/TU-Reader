package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 读卡器（终端）设备表。device_code 为 CSV 中 City/Prefix 与 Code 两列直接拼接的唯一键，
 * 例如 广州地铁 00010001 站 → "581000010001"；深圳 CU 60026 → "518060026"。
 * CU 标准的线路可能缺失（数据源限制），line_id 允许为空。
 *
 * match_key 为线路头行城市专用的「去前导0」规范化键（{city}|{线路码去0}|{站点码去0}），
 * 用于兼容变长编码（如北京 010001、重庆 4/8 位）下卡片读取的 4 位 BCD 线路/站点码匹配；
 * 终端号城市（整行 Code 即终端号）该列为 NULL。
 */
@Entity(
    tableName = "reader_device",
    foreignKeys = [
        ForeignKey(
            entity = CityEntity::class,
            parentColumns = ["city_id"],
            childColumns = ["city_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["device_code"], unique = true),
        Index(value = ["match_key"]),
        Index(value = ["city_id"]),
        Index(value = ["line_id"]),
        Index(value = ["station_id"])
    ]
)
data class ReaderDeviceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "device_id") val deviceId: Long = 0,
    @ColumnInfo(name = "standard") val standard: String, // "CU" / "TU" / "YCT"
    @ColumnInfo(name = "device_code") val deviceCode: String,
    @ColumnInfo(name = "city_id") val cityId: Long,
    @ColumnInfo(name = "line_id") val lineId: Long? = null,
    @ColumnInfo(name = "station_id") val stationId: Long? = null,
    @ColumnInfo(name = "transit_type") val transitType: String, // CSV Type 列：地铁/公交/BRT/城际/…
    @ColumnInfo(name = "device_location") val deviceLocation: String? = null, // 实际地点城市码；仅 station_id 为空时使用
    @ColumnInfo(name = "match_key") val matchKey: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null
)
