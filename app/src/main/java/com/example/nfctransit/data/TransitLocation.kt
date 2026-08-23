package com.example.nfctransit.data

import com.google.gson.annotations.SerializedName

/** 来源决定该城市是否可作为地图真实站点。 */
enum class LocationSource {
    STATION_GEO,
    PARENT_DIRECTORY,
    DECLARED_CITY_FALLBACK
}

data class ActualLocation(
    val cityCode: String?,
    val cityName: String,
    val source: LocationSource
) {
    val routeEligible: Boolean get() = source == LocationSource.STATION_GEO
}

data class CityOption(
    val code: String,
    val name: String,
    val nameEn: String?
) {
    val displayName: String = nameEn?.takeIf { it.isNotBlank() }?.let { "$name ($it)" } ?: name
}

data class CityBoundaryAsset(
    val version: String = "0",
    val cities: List<CityBoundary> = emptyList()
)

data class CityBoundary(
    @SerializedName("cityCode") val cityCode: String = "",
    @SerializedName("cityName") val cityName: String = "",
    @SerializedName("cityNameEn") val cityNameEn: String? = null,
    val polygons: List<List<List<Double>>> = emptyList()
)
