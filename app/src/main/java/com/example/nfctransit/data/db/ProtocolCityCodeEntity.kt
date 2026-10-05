package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 非标准城市/网络代码的解释；设备的实际城市始终由 reader_device.city_id 表示。 */
@Entity(
    tableName = "protocol_city_code",
    primaryKeys = ["protocol", "code"],
    foreignKeys = [ForeignKey(
        entity = CityEntity::class, parentColumns = ["city_id"],
        childColumns = ["city_id"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["city_id"])]
)
data class ProtocolCityCodeEntity(
    val protocol: String,
    val code: String,
    @ColumnInfo(name = "city_id") val cityId: Long
)
