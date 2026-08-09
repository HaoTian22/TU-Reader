package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 线路表。line_code 为 CSV 线路头行的 Code（如广州 0001），城市内唯一。 */
@Entity(
    tableName = "line",
    foreignKeys = [
        ForeignKey(
            entity = CityEntity::class,
            parentColumns = ["city_id"],
            childColumns = ["city_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["city_id"]),
        Index(value = ["city_id", "line_code"], unique = true)
    ]
)
data class LineEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "line_id") val lineId: Long = 0,
    @ColumnInfo(name = "city_id") val cityId: Long,
    @ColumnInfo(name = "line_code") val lineCode: String,
    @ColumnInfo(name = "line_name") val lineName: String,
    @ColumnInfo(name = "line_name_en") val lineNameEn: String? = null,
    @ColumnInfo(name = "line_color") val lineColor: String? = null
)
