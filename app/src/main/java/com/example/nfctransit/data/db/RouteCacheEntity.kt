package com.example.nfctransit.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 腾讯公交路线规划的派生缓存；不属于用户原始交易数据，可随时清除。 */
@Entity(
    tableName = "route_cache",
    indices = [Index(value = ["expires_at"])]
)
data class RouteCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "from_station_id") val fromStationId: Long,
    @ColumnInfo(name = "to_station_id") val toStationId: Long,
    @ColumnInfo(name = "departure_time") val departureTime: Long,
    @ColumnInfo(name = "policy") val policy: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "response_json") val responseJson: String? = null,
    @ColumnInfo(name = "is_estimate") val isEstimate: Boolean = false,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long
) {
    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_NO_ROUTE = "NO_ROUTE"
    }
}
