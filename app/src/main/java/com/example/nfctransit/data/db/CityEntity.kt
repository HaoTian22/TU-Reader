package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 市级城市表。city_code 依据 tripreader_tu_cities.csv 的标准 TU 代码，区县及省级条目不导入。 */
@Entity(
    tableName = "city",
    indices = [Index(value = ["city_code"], unique = true)]
)
data class CityEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "city_id") val cityId: Long = 0,
    @ColumnInfo(name = "city_code") val cityCode: String,
    @ColumnInfo(name = "city_name") val cityName: String,
    @ColumnInfo(name = "city_name_en") val cityNameEn: String? = null
)
