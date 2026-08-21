package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** 站名解析结果：device_code 命中的城市/线路/站点（含英文，缺省回退中文由调用方处理）。
 *  站名字段可空——大类 fallback 设备（空站名的类别行，如 51804=地铁）station_id/station_name 为 NULL。
 *  longitude/latitude 为站点 WGS84 坐标（station 表），缺省 null（未采集）。 */
data class StationResolution(
    @ColumnInfo(name = "city_id") val cityId: Long,
    @ColumnInfo(name = "city_code") val cityCode: String,
    @ColumnInfo(name = "city_name") val cityName: String,
    @ColumnInfo(name = "city_name_en") val cityNameEn: String?,
    @ColumnInfo(name = "line_id") val lineId: Long?,
    @ColumnInfo(name = "line_name") val lineName: String?,
    @ColumnInfo(name = "line_name_en") val lineNameEn: String?,
    @ColumnInfo(name = "line_color") val lineColor: String?,
    @ColumnInfo(name = "station_id") val stationId: Long?,
    @ColumnInfo(name = "station_name") val stationName: String?,
    @ColumnInfo(name = "station_name_en") val stationNameEn: String?,
    @ColumnInfo(name = "standard") val standard: String,
    @ColumnInfo(name = "transit_type") val transitType: String,
    @ColumnInfo(name = "device_code") val deviceCode: String,
    @ColumnInfo(name = "match_key") val matchKey: String?,
    @ColumnInfo(name = "longitude") val longitude: Double? = null,
    @ColumnInfo(name = "latitude") val latitude: Double? = null
)

@Dao
interface TransitDao {

    @Query("SELECT * FROM city WHERE city_code = :cityCode LIMIT 1")
    suspend fun getCity(cityCode: String): CityEntity?

    @Query("SELECT * FROM city")
    suspend fun getAllCities(): List<CityEntity>

    /**
     * 全部站点解析结果（城市/线路/站点 + 英文 + match_key）。
     * LEFT JOIN station：空站名的大类 fallback 设备（如 51804=地铁）也载入，
     * 供最长前缀匹配兜底显示交通类型/线路。
     * 数据量为参考数据集（约 2.7 万行），首次读取时一次性载入内存缓存。
     */
    @Query(
        """
        SELECT c.city_id, c.city_code, c.city_name, c.city_name_en,
               l.line_id, l.line_name, l.line_name_en, l.line_color,
               s.station_id, s.station_name, s.station_name_en,
               r.standard, r.transit_type, r.device_code, r.match_key,
               s.longitude, s.latitude
        FROM reader_device r
        JOIN city c ON c.city_id = r.city_id
        LEFT JOIN line l ON l.line_id = r.line_id
        LEFT JOIN station s ON s.station_id = r.station_id
        """
    )
    suspend fun getAllResolutions(): List<StationResolution>

    /** 遍历全部 device_code（供调试） */
    @Query("SELECT device_code FROM reader_device")
    suspend fun getAllDeviceCodes(): List<String>

    @Query("SELECT COUNT(*) FROM reader_device")
    suspend fun countDevices(): Int

    @Query("SELECT * FROM line WHERE city_id = :cityId AND line_name = :lineName LIMIT 1")
    suspend fun getLineByName(cityId: Long, lineName: String): LineEntity?

    @Insert
    suspend fun insertLine(line: LineEntity): Long

    @Query("SELECT * FROM station WHERE city_id = :cityId AND station_name = :stationName LIMIT 1")
    suspend fun getStationByName(cityId: Long, stationName: String): StationEntity?

    @Insert
    suspend fun insertStation(station: StationEntity): Long

    @Query("SELECT * FROM reader_device WHERE device_code = :deviceCode LIMIT 1")
    suspend fun getDeviceByCode(deviceCode: String): ReaderDeviceEntity?

    @Insert
    suspend fun insertDevice(device: ReaderDeviceEntity): Long

    @Query(
        """
        UPDATE reader_device
        SET standard = :standard, line_id = :lineId, station_id = :stationId,
            transit_type = :transitType, updated_at = :updatedAt
        WHERE device_code = :deviceCode
        """
    )
    suspend fun updateDeviceMapping(
        deviceCode: String,
        standard: String,
        lineId: Long?,
        stationId: Long?,
        transitType: String,
        updatedAt: String
    )

    @Update
    suspend fun restoreDevice(device: ReaderDeviceEntity)

    @Query("DELETE FROM reader_device WHERE device_code = :deviceCode")
    suspend fun deleteDeviceByCode(deviceCode: String)

    @Query("SELECT * FROM city WHERE city_id = :cityId LIMIT 1")
    suspend fun getCityById(cityId: Long): CityEntity?

    @Query("SELECT * FROM line WHERE line_id = :lineId LIMIT 1")
    suspend fun getLineById(lineId: Long): LineEntity?

    @Query("SELECT * FROM station WHERE station_id = :stationId LIMIT 1")
    suspend fun getStationById(stationId: Long): StationEntity?

    @Query("SELECT COUNT(*) FROM reader_device WHERE device_code = :deviceCode")
    suspend fun countDevice(deviceCode: String): Int
}
