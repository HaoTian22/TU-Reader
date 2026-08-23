package com.example.nfctransit.data

import android.content.Context
import androidx.room.withTransaction
import com.example.nfctransit.data.db.AppDatabase
import com.example.nfctransit.data.db.LineEntity
import com.example.nfctransit.data.db.ReaderDeviceEntity
import com.example.nfctransit.data.db.StationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TransitOverrideImporter {
    private val codeRegex = Regex("[0-9A-Za-z]+")
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    suspend fun import(context: Context): OverrideImportSummary {
        val appContext = context.applicationContext
        val snapshot = TransitOverrideStore.read(appContext)
        if (snapshot.rows.isEmpty()) return OverrideImportSummary()
        val dao = AppDatabase.get(appContext).transitDao()
        var added = 0
        var updated = 0
        var unchanged = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val now = timeFormat.format(Date())

        AppDatabase.get(appContext).withTransaction {
            snapshot.rows.values.forEachIndexed { index, row ->
                val error = validate(row)
                if (error != null) {
                    skipped++
                    errors += "第 ${index + 2} 行：$error"
                    return@forEachIndexed
                }
                val city = dao.getCity(row.prefix)
                if (city == null) {
                    skipped++
                    errors += "第 ${index + 2} 行：未知城市码 ${row.prefix}"
                    return@forEachIndexed
                }
                val lineId = row.line.takeIf { it.isNotBlank() }?.let { line ->
                    dao.getLineByName(city.cityId, line)?.lineId
                        ?: dao.insertLine(
                            LineEntity(
                                cityId = city.cityId,
                                lineCode = line,
                                lineName = line
                            )
                        )
                }
                val stationId = row.station.takeIf { it.isNotBlank() }?.let { station ->
                    dao.getStationByName(city.cityId, station)?.stationId
                        ?: dao.insertStation(
                            StationEntity(cityId = city.cityId, stationName = station)
                        )
                }
                val existing = dao.getDeviceByCode(row.deviceCode)
                val requestedLocation = snapshot.locations[row.deviceCode]
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (requestedLocation != null && dao.getCity(requestedLocation) == null) {
                    skipped++
                    errors += "第 ${index + 2} 行：未知实际城市码 $requestedLocation"
                    return@forEachIndexed
                }
                val deviceLocation = requestedLocation?.takeIf { stationId == null }
                val standard = snapshot.standards[row.deviceCode]
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: existing?.standard
                    ?: "OVERRIDE"
                if (existing == null) {
                    dao.insertDevice(
                        ReaderDeviceEntity(
                            standard = standard,
                            deviceCode = row.deviceCode,
                            cityId = city.cityId,
                            lineId = lineId,
                            stationId = stationId,
                            transitType = row.type,
                            deviceLocation = deviceLocation,
                            updatedAt = now
                        )
                    )
                    added++
                } else if (
                    existing.standard != standard ||
                    existing.cityId != city.cityId ||
                    existing.lineId != lineId ||
                    existing.stationId != stationId ||
                    existing.transitType != row.type ||
                    existing.deviceLocation != deviceLocation
                ) {
                    dao.updateDeviceMapping(
                        deviceCode = row.deviceCode,
                        standard = standard,
                        lineId = lineId,
                        stationId = stationId,
                        transitType = row.type,
                        deviceLocation = deviceLocation,
                        updatedAt = now
                    )
                    updated++
                } else {
                    unchanged++
                }
            }
        }
        return OverrideImportSummary(added, updated, unchanged, skipped, errors)
    }

    private fun validate(row: TransitOverrideRow): String? {
        if (row.prefix.length !in 1..16 || !row.prefix.matches(codeRegex)) return "Prefix 无效"
        if (row.code.length !in 1..64 || !row.code.matches(codeRegex)) return "Code 无效"
        if (row.type.isBlank() || row.type.length > 32) return "Type 无效"
        if (row.line.length > 128) return "线路过长"
        if (row.station.length > 128) return "站名过长"
        if (listOf(row.type, row.line, row.station).any { it.contains('\n') || it.contains('\r') }) {
            return "字段不能包含换行"
        }
        return null
    }
}
