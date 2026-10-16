package com.example.nfctransit.data

import android.content.Context
import com.example.nfctransit.data.db.AppDatabase
import com.example.nfctransit.data.db.ReaderDeviceEntity
import java.text.Normalizer

/** 本地映射与映射库原始条目的重复程度 */
enum class OverrideDuplicateKind {
    /** 城市、线路、站名与库中完全一致：映射已无作用 */
    SAME,
    /** 同城市，站名/线路仅写法不同（空白、全半角、「站」「线」「地铁」等后缀前缀）：大概率重复，需人工确认 */
    SIMILAR
}

data class OverrideDuplicate(
    val row: TransitOverrideRow,
    val kind: OverrideDuplicateKind,
    /** 库中原始条目的线路 / 站名（中文），用于界面提示 */
    val dbLine: String,
    val dbStation: String
)

/**
 * 检查本地映射是否已被映射库覆盖（在线更新或内置库升级后，库里可能已收录相同条目）。
 *
 * 比较对象是 `overrides.originals.json` 中该 (device_code, transit_type) 的覆盖前条目——
 * 映射一经导入，库中对应行就被改写成映射值，只有覆盖前快照才代表库本身的数据。
 * 因此每次整库替换后、重新导入映射前，须先调 [captureOriginals] 用新库刷新快照。
 */
object TransitOverrideDuplicates {

    /** 以当前（尚未导入映射的）库内容刷新所有映射的覆盖前快照；库中无该设备则记为 null（映射为新增） */
    suspend fun captureOriginals(context: Context) {
        val appContext = context.applicationContext
        val snapshot = TransitOverrideStore.read(appContext)
        if (snapshot.rows.isEmpty()) return
        val dao = AppDatabase.get(appContext).transitDao()
        val originals = snapshot.rows.mapValues { (_, row) -> dao.getDeviceByCode(row.deviceCode, row.type) }
        TransitOverrideStore.replaceOriginals(appContext, originals)
    }

    suspend fun find(context: Context): List<OverrideDuplicate> {
        val appContext = context.applicationContext
        val snapshot = TransitOverrideStore.read(appContext)
        if (snapshot.rows.isEmpty()) return emptyList()
        val dao = AppDatabase.get(appContext).transitDao()
        return snapshot.rows.mapNotNull { (key, row) ->
            val original: ReaderDeviceEntity = snapshot.originals[key] ?: return@mapNotNull null
            val city = dao.getCityById(original.cityId) ?: return@mapNotNull null
            val dbStation = original.stationId?.let { dao.getStationById(it) }?.stationName.orEmpty()
            val dbLine = original.lineId?.let { dao.getLineById(it) }?.lineName.orEmpty()
            val located = row.copy(locationCityCode = snapshot.locations[key])
            classify(located, city.cityCode, dbLine, dbStation)
                ?.let { OverrideDuplicate(located, it, dbLine, dbStation) }
        }
    }

    internal fun classify(
        row: TransitOverrideRow,
        dbCityCode: String,
        dbLine: String,
        dbStation: String
    ): OverrideDuplicateKind? {
        // 映射指定了别的城市即为纠正位置，不算重复；库里没有站名的条目也不算
        if (!row.locationCityCode.isNullOrBlank() && row.locationCityCode != dbCityCode) return null
        if (dbStation.isBlank() && row.station.isNotBlank()) return null
        if (row.station.trim() == dbStation.trim() && row.line.trim() == dbLine.trim()) {
            return OverrideDuplicateKind.SAME
        }
        val stationMatches = normalizeStation(row.station) == normalizeStation(dbStation)
        val lineA = normalizeLine(row.line)
        val lineB = normalizeLine(dbLine)
        // 线路一侧为空视为「未纠正线路」；两侧都有且不同说明映射在纠正线路，保留
        val lineMatches = lineA.isEmpty() || lineB.isEmpty() || lineA == lineB
        return if (stationMatches && lineMatches) OverrideDuplicateKind.SIMILAR else null
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .filter { it.isLetterOrDigit() }

    internal fun normalizeStation(value: String): String =
        normalize(value).removeSuffix("站").removeSuffix("station")

    internal fun normalizeLine(value: String): String =
        normalize(value)
            .removePrefix("轨道交通").removePrefix("地铁").removePrefix("metro").removePrefix("line")
            .removeSuffix("线").removeSuffix("路").removeSuffix("line")
}
