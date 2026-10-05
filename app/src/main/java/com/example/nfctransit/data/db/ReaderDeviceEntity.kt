package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 读卡器（终端）设备表。device_code 为 CSV 中 City/Prefix 与 Code 两列直接拼接，
 * 同一编号可用于不同交通类型，唯一键为 (device_code, transit_type)。
 * 例如 广州地铁 00010001 站 → "581000010001"；深圳 CU 60026 → "518060026"。
 * CU 标准的线路可能缺失（数据源限制），line_id 允许为空。
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
        Index(value = ["device_code", "transit_type"], unique = true),
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
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null
)
