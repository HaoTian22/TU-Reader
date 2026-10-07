package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.example.nfctransit.data.db.CardEntity
import com.example.nfctransit.model.CanonicalTransaction
import com.example.nfctransit.model.UiCard
import com.example.nfctransit.model.UiTransaction
import com.example.nfctransit.model.TransitDirection
import kotlin.math.abs
import com.example.nfctransit.R
import com.example.nfctransit.util.L10n

/**
 * 持久化实体/内部模型 → UI 模型的映射。
 * 余额内部统一为 Long 分；UiCard.balanceYuan 为计算属性。
 */
object TransactionMapper {

    /** 交易类型图标底色：统一中性灰，类型差异只靠 Font Awesome 图形区分 */
    private const val ICON_BG = 0xFFECEEF1

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
        // 分类判定键：DB 命中用原始 Type 列；解码侧推断/兜底本身已是中文类别串。
        // 大分类之外但确有数据库类型 → 统一兜底图标 + 原始类型串；
        // 未命中数据库与任何推断逻辑的才回退公交。
        val categoryKey = resolved.dbType ?: resolved.transitType
        // 充值类展示（银行卡图标）：含映射命中的充值设备（可能同时承载退款）；
        // 但入账与否（"+" 符号、统计豁免）仍按原生 type "02" 判定——非 "02" 按 "-" 计消费
        val effRecharge = isRecharge ||
            resolved.dbType?.contains("充值") == true ||
            resolved.transitType == "充值"

        val (icon, transitType, lineName) = when {
            effRecharge -> Triple("", "充值", "—")
            categoryKey == "地铁" ->
                Triple("", "地铁", resolved.lineName.ifEmpty { "—" })
            categoryKey in BUS_LIKE_TYPES ->
                Triple("", "公交", resolved.lineName.ifEmpty { "—" })
            categoryKey == "有轨电车" ->
                Triple("", "有轨电车", resolved.lineName.ifEmpty { "—" })
            categoryKey == "城际" ->
                Triple("", "城际", resolved.lineName.ifEmpty { "—" })
            categoryKey == "便利店" || categoryKey == "消费" ->
                Triple("", categoryKey, "—")
            // 大分类之外的确证类型（轮渡/出租车/铁路……）：沿用数据库原始类型串 + 统一图标
            resolved.dbType != null ->
                Triple("", resolved.transitType, resolved.lineName.ifEmpty { "—" })
            else ->
                Triple("", "公交", resolved.lineName.ifEmpty { "—" })
        }

        val formattedDate = formatBcdDate(date)
        val formattedTime = formatBcdTime(time)
        val balanceAfterYuan = balanceAfterFen?.div(100.0)   // 无余额数据为 null（区别于真实的 ¥0.00）
        val amountText = when {
            // 原生充值（02）才按入账显示 "+"；映射类充值/退款保持 "-"，红色计入消费
            isRecharge -> "+¥${String.format("%.2f", amountAbs)}"
            isTicketProcessing && amountYuan == 0.0 -> L10n.str(R.string.amount_ticket_processing)
            amountYuan == 0.0 && transitType != "消费" && transitType != "便利店" -> when (direction) {
                TransitDirection.ENTRY -> L10n.str(R.string.amount_entry)
                TransitDirection.EXIT -> L10n.str(R.string.amount_exit)
                null -> L10n.str(R.string.amount_ride)
            }
            else -> "-¥${String.format("%.2f", amountAbs)}"
        }

        val declaredCityCode = rawCityCode ?: cityCode
        val actualLocation = if (effRecharge) {
            ActualLocation(null, "", LocationSource.DECLARED_CITY_FALLBACK)
        } else {
            TransitData.actualLocation(resolved.stationId, deviceCode, declaredCityCode, resolved.lineId, protocol)
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
            ticketProcessing = isTicketProcessing && amountYuan == 0.0,
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
            balanceAfterText = balanceAfterFen?.let { L10n.str(R.string.balance_after_format, String.format("%.2f", it / 100.0)) },
            icon = icon,
            iconBgColor = ICON_BG,
            protocols = if (protocols.isNotEmpty()) protocols.sorted()
                else if (protocol.isBlank()) emptyList() else listOf(protocol),
            journeyHex = journeyHex,
            rawVariants = rawVariants,
            deviceCode = deviceCode,
            spRule = spRule,
            actualCityCode = actualLocation.cityCode,
            locationSource = actualLocation.source,
            logFormat = logFormat
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
            transitType = entry.type,
            cityCode = entry.cityCode ?: cityCode,
            lineId = entry.lineId ?: lineId,
            stationId = entry.stationId ?: stationId,
            dbType = entry.type
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
        val stationId: Long?,
        /** 数据库命中的原始 Type 列（未命中/充值早退为 null），用于大分类外类型的统一展示 */
        val dbType: String? = null
    )

    /** 归并到"公交"大类的类型：含解码侧兜底串"公共交通"与 CSV 里的公交族 */
    private val BUS_LIKE_TYPES = setOf("公交", "公共交通", "BRT", "bus", "定制公交")
}
