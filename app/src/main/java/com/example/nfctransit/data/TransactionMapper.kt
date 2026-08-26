package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.example.nfctransit.data.db.CardEntity
import com.example.nfctransit.model.CanonicalTransaction
import com.example.nfctransit.model.UiCard
import com.example.nfctransit.model.UiTransaction
import com.example.nfctransit.model.TransitDirection
import kotlin.math.abs

/**
 * 持久化实体/内部模型 → UI 模型的映射。
 * 余额内部统一为 Long 分；UiCard.balanceYuan 为计算属性。
 */
object TransactionMapper {

    fun CardEntity.toUiCard(): UiCard = UiCard(
        id = cardId,
        name = name,
        cardType = name,  // 保持旧版 UI 语义：历史调用将 cardType 当作展示名
        protocolType = cardType,
        lastFour = lastFour,
        cardNumber = cardNumber,
        secondCardNumber = secondCardNumber,
        balanceFen = latestBalanceFen,
        gradientStartColor = gradientStartColor,
        gradientEndColor = gradientEndColor,
        lastReadAt = lastReadAt
    )

    /** CanonicalTransaction → UiTransaction；站点/线路名按当前界面语言经 ID 重新解析，ID 缺失回退存档名 */
    fun CanonicalTransaction.toUiTransaction(index: Int, cardType: String = ""): UiTransaction {
        val amountYuan = (amountFen ?: 0L) / 100.0
        val isRecharge = typeHex == "02" || (amountFen ?: 0L) < 0
        val isTicketProcessing = isTuTicketProcessing()
        val amountAbs = abs(amountYuan)

        val resolved = resolveDisplayNames()

        val (icon, iconBgColor, transitType, lineName) = when {
            isRecharge -> Quad("💳", 0xFFE8F5E9, "充值", "—")
            resolved.transitType.contains("地铁") || resolved.transitType.contains("Metro") ||
                resolved.transitType.contains("轨道交通") ->
                Quad("🚇", 0xFFE3F2FD, "地铁", resolved.lineName.ifEmpty { "—" })
            resolved.transitType.contains("公交") || resolved.transitType.contains("Bus") ->
                Quad("🚌", 0xFFFFF3E0, "公交", resolved.lineName.ifEmpty { "—" })
            resolved.transitType.contains("有轨电车") || resolved.transitType.contains("Tram") ->
                Quad("🚊", 0xFFE0F7FA, "有轨电车", resolved.lineName.ifEmpty { "—" })
            resolved.transitType.contains("城际") || resolved.transitType.contains("Intercity") ->
                Quad("🚄", 0xFFE8EAF6, "城际", resolved.lineName.ifEmpty { "—" })
            resolved.transitType.contains("消费") || resolved.transitType.contains("便利店") ->
                Quad("🛒", 0xFFFCE4EC, if (resolved.transitType.contains("便利店")) "便利店" else "消费", "—")
            else -> {
                if (resolved.lineName.isNotEmpty() && resolved.lineName[0].isDigit())
                    Quad("🚇", 0xFFE3F2FD, "地铁", resolved.lineName)
                else
                    Quad("🚌", 0xFFFFF3E0, "公交", resolved.lineName.ifEmpty { "—" })
            }
        }

        val formattedDate = formatBcdDate(date)
        val formattedTime = formatBcdTime(time)
        val balanceAfterYuan = balanceAfterFen?.div(100.0)   // 无余额数据为 null（区别于真实的 ¥0.00）
        val amountText = when {
            isRecharge -> "+¥${String.format("%.2f", amountAbs)}"
            isTicketProcessing && amountYuan == 0.0 -> "票务处理"
            amountYuan == 0.0 && transitType != "消费" && transitType != "便利店" -> when (direction) {
                TransitDirection.ENTRY -> "进站"
                TransitDirection.EXIT -> "出站"
                null -> "乘车"
            }
            else -> "-¥${String.format("%.2f", amountAbs)}"
        }

        val declaredCityCode = rawCityCode ?: cityCode
        val actualLocation = if (isRecharge) {
            ActualLocation(null, "", LocationSource.DECLARED_CITY_FALLBACK)
        } else {
            TransitData.actualLocation(resolved.stationId, deviceCode, declaredCityCode)
        }

        return UiTransaction(
            id = index,
            seq = sequence ?: 0,
            sfi = sfi,
            hex = hex,
            cardType = cardType,
            protocol = protocol,
            amountYuan = amountYuan,
            amountText = amountText,
            typeHex = typeHex,
            transitType = transitType,
            terminal = terminal,
            // 站名找不到（空/未知）时，站名位置兜底到线路名，再到交通类型（充值→"充值"）
            stationName = resolved.stationName.takeIf { it.isNotBlank() && it != "未知" }
                ?: resolved.lineName.takeIf { it.isNotBlank() && it != "—" }
                ?: transitType,
            direction = direction,
            cityName = actualLocation.cityName,
            cityCode = declaredCityCode,
            lineName = resolved.lineName,
            lineColor = resolved.lineColor,
            lineId = resolved.lineId,
            stationId = resolved.stationId,
            date = formattedDate,
            time = formattedTime,
            displayDateTime = "$formattedDate $formattedTime",
            balanceAfterYuan = balanceAfterYuan,
            balanceAfterText = balanceAfterFen?.let { "余额 ¥${String.format("%.2f", it / 100.0)}" },
            icon = icon,
            iconBgColor = iconBgColor,
            protocols = if (protocols.isNotEmpty()) protocols.sorted()
                else if (protocol.isBlank()) emptyList() else listOf(protocol),
            journeyHex = journeyHex,
            rawVariants = rawVariants,
            deviceCode = deviceCode,
            spRule = spRule,
            actualCityCode = actualLocation.cityCode,
            locationSource = actualLocation.source
        )
    }

    private fun CanonicalTransaction.isTuTicketProcessing(): Boolean {
        if (protocol != "TU" && !protocols.contains("TU")) return false
        val raw = if (sfi == 0x1E) hex else journeyHex ?: return false
        val data = ApduUtil.hexToBytes(raw)
        return data.size >= 10 &&
            (data[0].toInt() and 0xFF) == 0x08 &&
            (data[9].toInt() and 0xFF) == 0x01
    }

    /** 按 ID（或旧数据反查）优先解析匹配记录；命中后站名、线路、城市和类型均以记录为准。 */
    private fun CanonicalTransaction.resolveDisplayNames(): ResolvedNames {
        if (typeHex == "02" || (amountFen ?: 0L) < 0) {  // 充值无站点
            return ResolvedNames(stationName, lineName, lineColor, transitType, cityCode, lineId, stationId)
        }
        val base = stationName.trim()

        var entry = if (lineId != null && stationId != null) {
            TransitData.entryOf(lineId, stationId)
        } else if (stationId != null) {
            TransitData.entryOf(null, stationId)
        } else null
        if (entry == null && base.contains(" ")) entry = TransitData.resolveByCombined(base)
        if (entry == null) entry = TransitData.resolveByCombined("${lineName} $base".trim())
        if (entry == null) entry = TransitData.resolveByStationName(base)
        if (entry == null) {
            return ResolvedNames(stationName, lineName, lineColor, transitType, cityCode, lineId, stationId)
        }
        val line = entry.line
        val station = entry.station
        return ResolvedNames(
            stationName = station,
            lineName = line.ifEmpty { lineName },
            lineColor = entry.lineColor ?: lineColor,
            transitType = TransitData.transitTypeLabel(entry.type),
            cityCode = entry.cityCode ?: cityCode,
            lineId = entry.lineId ?: lineId,
            stationId = entry.stationId ?: stationId
        )
    }

    private fun formatBcdDate(bcd: String): String {
        if (bcd.length != 8) return bcd
        return "${bcd.substring(0, 4)}-${bcd.substring(4, 6)}-${bcd.substring(6, 8)}"
    }

    private fun formatBcdTime(bcd: String): String {
        if (bcd.length < 6) return bcd
        return "${bcd.substring(0, 2)}:${bcd.substring(2, 4)}:${bcd.substring(4, 6)}"
    }

    private data class ResolvedNames(
        val stationName: String,
        val lineName: String,
        val lineColor: String?,
        val transitType: String,
        val cityCode: String?,
        val lineId: Long?,
        val stationId: Long?
    )

    private data class Quad(
        val icon: String,
        val bgColor: Long,
        val transitType: String,
        val lineName: String
    )
}
