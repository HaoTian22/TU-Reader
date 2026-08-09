package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 站点表。同一城市内站名唯一。经度/纬度留空，后续可补。 */
@Entity(
    tableName = "station",
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
        Index(value = ["city_id", "station_name"], unique = true)
    ]
)
data class StationEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "station_id") val stationId: Long = 0,
    @ColumnInfo(name = "city_id") val cityId: Long,
    @ColumnInfo(name = "station_name") val stationName: String,
    @ColumnInfo(name = "station_name_en") val stationNameEn: String? = null,
    @ColumnInfo(name = "longitude") val longitude: Double? = null,
    @ColumnInfo(name = "latitude") val latitude: Double? = null
)
