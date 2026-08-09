package com.example.nfctransit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.nfctransit.CardProfile
import com.example.nfctransit.TransactionRecord
import com.example.nfctransit.TransitCardReader
import com.example.nfctransit.data.PersistedCardData
import com.example.nfctransit.data.PersistedState
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.TransitStore
import com.example.nfctransit.model.*
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ── Per-card data storage ──

    private data class CardData(
        val card: UiCard,
        val transactions: List<UiTransaction>,
        val nfcLog: List<String>,
        val topStations: List<StationStat>,
        val topLines: List<LineStat>,
        val dailySpending: List<DailySpending>,
        val statsSummary: StatsSummary
    )

    private val cardStore = mutableMapOf<String, CardData>()

    // ── Observable state ──

    private val _hasData = MutableLiveData(false)
    val hasData: LiveData<Boolean> = _hasData

    private val _cards = MutableLiveData<List<UiCard>>(emptyList())
    val cards: LiveData<List<UiCard>> = _cards

    private val _selectedIndex = MutableLiveData(-1)
    val selectedIndex: LiveData<Int> = _selectedIndex

    private val _selectedCard = MutableLiveData<UiCard?>()
    val selectedCard: LiveData<UiCard?> = _selectedCard

    // 新卡片加入时发出其下标，供首页自动滑动跳转
    private val _cardAdded = MutableLiveData<Int?>()
    val cardAdded: LiveData<Int?> = _cardAdded

    private val _allTransactions = MutableLiveData<List<UiTransaction>>(emptyList())
    val allTransactions: LiveData<List<UiTransaction>> = _allTransactions

    private val _currentFilter = MutableLiveData("全部")
    val currentFilter: LiveData<String> = _currentFilter

    private val _filteredTransactions = MutableLiveData<List<UiTransaction>>(emptyList())
    val filteredTransactions: LiveData<List<UiTransaction>> = _filteredTransactions

    private val _nfcLog = MutableLiveData<List<String>>(emptyList())
    val nfcLog: LiveData<List<String>> = _nfcLog

    private val _topStations = MutableLiveData<List<StationStat>>(emptyList())
    val topStations: LiveData<List<StationStat>> = _topStations

    private val _topLines = MutableLiveData<List<LineStat>>(emptyList())
    val topLines: LiveData<List<LineStat>> = _topLines

    private val _dailySpending = MutableLiveData<List<DailySpending>>(emptyList())
    val dailySpending: LiveData<List<DailySpending>> = _dailySpending

    // 首页迷你图专用：固定"本周（周一~周日）"7 根柱子，不受统计页周期切换影响
    private val _homeWeeklySpending = MutableLiveData<List<DailySpending>>(emptyList())
    val homeWeeklySpending: LiveData<List<DailySpending>> = _homeWeeklySpending

    private val _statsSummary = MutableLiveData(StatsSummary(0.0, 0, 0.0))
    val statsSummary: LiveData<StatsSummary> = _statsSummary

    /** 当前选中卡片的主题色（渐变色起点），用于全站图标/按钮/进度条等主题色统一 */
    private val _mainAccent = MutableLiveData<Long>(0xFF0066FF)
    val mainAccent: LiveData<Long> = _mainAccent

    // 当前统计周期（本周/本月/本年/自定义）与周期偏移（0=当前，-1=上一期，+1=下一期）
    private var currentPeriod = "本周"
    private var currentOffset = 0
    private val _periodOffset = MutableLiveData(0)
    val periodOffset: LiveData<Int> = _periodOffset
    private val _periodRange = MutableLiveData("")
    val periodRange: LiveData<String> = _periodRange
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    init {
        restorePersistedState()
    }

    // ── NFC data loading (with merge + dedup) ──

    /**
     * 处理 NFC 读取结果。返回新卡片在列表中的下标，若为已存在卡片则返回 null。
     */
    fun onNfcDataLoaded(result: TransitCardReader.ReadResult): Int? {
        val profile = result.matchedProfile ?: return null
        val transactions = result.transactions
        if (transactions.isEmpty()) return null

        // Balance from latest SFI 0x1E record (offset 21-24, 单位分)
        val balanceFen = result.stationInfo?.balanceFen ?: 0L
        val balanceYuan = balanceFen / 100.0

        // 卡号（应用序列号）-> 用 cardname-tu.csv 按 IIN 匹配卡名；读不到时退回通用名
        val cardNumber = result.cardInfo?.cardNumber ?: ""
        // 卡号后四位用于各页面徽标；读不到卡号时退回交易终端号后四位
        val lastFour = if (cardNumber.isNotEmpty()) cardNumber.takeLast(4)
            else lastFourFromTransactions(transactions)
        val displayName = cardDisplayName(profile, cardNumber)
        val newCardId = cardId(profile, cardNumber, lastFour)

        // 查找已有卡片：优先按新 id（卡号）；旧版本按 "名字|尾号" 存，用尾号兼容查找
        var existing = cardStore[newCardId]
        var legacyKey: String? = null
        if (existing == null) {
            legacyKey = cardStore.entries.firstOrNull { it.value.card.lastFour == lastFour }?.key
            existing = legacyKey?.let { cardStore[it] }
        }

        // 新卡从 20 色板里挑一个还没被用过的颜色；已存在卡保留原颜色
        val (gradStart, gradEnd) = if (existing != null) {
            existing.card.gradientStartColor to existing.card.gradientEndColor
        } else {
            nextCardColor()
        }
        val card = UiCard(
            id = newCardId,
            name = displayName,
            cardType = displayName,
            lastFour = lastFour,
            cardNumber = cardNumber,
            balanceYuan = balanceYuan,
            gradientStartColor = gradStart,
            gradientEndColor = gradEnd
        )

        // SFI 0x18 records are newest-first
        val newUiTxns = transactions.mapIndexed { idx, txn -> mapToUiTransaction(txn, idx) }

        // 本次扫描的城市名，用于给旧记录回填（旧版本持久化数据没有 cityName 字段）
        val scannedCity = newUiTxns.firstOrNull()?.cityName ?: ""

        // 与已存交易合并去重（按 seq|日期|时间|终端 去重）
        val rawMerged = if (existing != null) {
            val existingKeys = existing.transactions.map { txnKey(it) }.toHashSet()
            val fresh = newUiTxns.filter { txnKey(it) !in existingKeys }
            (fresh + existing.transactions).distinctBy { txnKey(it) }
        } else {
            newUiTxns
        }
        // 城市名补全：同一张卡属于同一城市，用本次扫描结果回填；并修复旧数据的充值站点名
        val mergedTxns = rawMerged.mapIndexed { idx, t ->
            repairRechargeTxn(
                t.copy(id = idx, cityName = (t.cityName ?: "").ifEmpty { scannedCity })
            )
        }

        val (winStart, winEnd) = periodWindow(currentPeriod, currentOffset)
        val data = CardData(
            card = card,
            transactions = mergedTxns,
            nfcLog = result.rawLog,
            topStations = computeTopStations(mergedTxns),
            topLines = computeTopLines(mergedTxns),
            dailySpending = computeDailySpending(mergedTxns, currentPeriod, winStart, winEnd),
            statsSummary = computeStatsSummary(mergedTxns)
        )

        val isNew = !cardStore.containsKey(card.id) && legacyKey == null
        // 旧版本用 "名字|尾号" 作 key：迁移到新的卡号 key，删掉旧条目避免残留
        if (legacyKey != null && legacyKey != card.id) {
            cardStore.remove(legacyKey)
        }
        cardStore[card.id] = data

        val newIndex = (_cards.value ?: emptyList()).indexOfFirst { it.id == card.id }
        // 列表里可能还留着旧 id（"名字|尾号"）的条目：按尾号找到它并原地替换
        val legacyListIndex = if (newIndex < 0) {
            (_cards.value ?: emptyList()).indexOfFirst { it.lastFour == lastFour }
        } else -1
        val index = when {
            newIndex >= 0 -> {
                // 已存在：更新列表中的卡（余额/卡名可能变化），保持原地顺序
                val updated = (_cards.value ?: emptyList()).toMutableList()
                updated[newIndex] = card
                _cards.value = updated
                newIndex
            }
            legacyListIndex >= 0 -> {
                val updated = (_cards.value ?: emptyList()).toMutableList()
                updated[legacyListIndex] = card
                _cards.value = updated
                legacyListIndex
            }
            else -> {
                val updated = (_cards.value ?: emptyList()) + card
                _cards.value = updated
                updated.size - 1
            }
        }

        _hasData.value = true
        selectCardByIndex(index)
        if (isNew) _cardAdded.value = index

        persist()
        return if (isNew) index else null
    }

    fun selectCardByIndex(index: Int) {
        val list = _cards.value ?: return
        if (index !in list.indices) return
        _selectedIndex.value = index
        // 持久化选中索引，应用重开时能恢复到之前选中的那张卡
        persist()
        val data = cardStore[list[index].id] ?: return
        emitCardData(data)
    }

    fun clearCardAdded() {
        _cardAdded.value = null
    }

    fun setFilter(filter: String) {
        _currentFilter.value = filter
        val all = _allTransactions.value ?: return
        _filteredTransactions.value = filterTransactions(all, filter)
    }

    /** 切换统计周期（本周/本月/本年/自定义），重置到当前周期 */
    fun setStatsPeriod(period: String) {
        currentPeriod = period
        currentOffset = 0
        _periodOffset.value = 0
        emitStatsForSelected()
    }

    /** 上一期/下一期（delta=±1），仅本周/本月/本年有意义 */
    fun shiftPeriod(delta: Int) {
        if (currentPeriod == "自定义") return
        currentOffset += delta
        _periodOffset.value = currentOffset
        emitStatsForSelected()
    }

    /** 回到当前周期 */
    fun backToCurrentPeriod() {
        currentOffset = 0
        _periodOffset.value = 0
        emitStatsForSelected()
    }

    private fun emitStatsForSelected() {
        val index = _selectedIndex.value ?: return
        val list = _cards.value ?: return
        if (index in list.indices) {
            cardStore[list[index].id]?.let { emitStats(it) }
        }
    }

    /** 某周期的日期窗口 [起, 止]，yyyy-MM-dd；自定义返回空串 */
    private fun periodWindow(period: String, offset: Int): Pair<String, String> {
        val cal = Calendar.getInstance()
        return when (period) {
            "本周" -> {
                // 纯算术计算周一为起点的自然周，不受 firstDayOfWeek locale 影响（周日在欧美为每周最后一天）
                val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一=0 … 周日=6
                cal.add(Calendar.DAY_OF_YEAR, offset * 7 - dow)
                val start = dayFmt.format(cal.time)
                cal.add(Calendar.DAY_OF_YEAR, 6)
                val end = dayFmt.format(cal.time)
                start to end
            }
            "本月" -> {
                cal.add(Calendar.MONTH, offset)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val start = dayFmt.format(cal.time)
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                val end = dayFmt.format(cal.time)
                start to end
            }
            "本年" -> {
                cal.add(Calendar.YEAR, offset)
                cal.set(Calendar.MONTH, Calendar.JANUARY)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val start = dayFmt.format(cal.time)
                cal.set(Calendar.MONTH, Calendar.DECEMBER)
                cal.set(Calendar.DAY_OF_MONTH, 31)
                val end = dayFmt.format(cal.time)
                start to end
            }
            else -> "" to ""
        }
    }

    fun getTransactionById(id: Int): UiTransaction? {
        return _allTransactions.value?.find { it.id == id }
    }

    // ── 数据管理 ──

    /** 清除全部本地数据 */
    fun clearAllData() {
        cardStore.clear()
        _hasData.value = false
        _cards.value = emptyList()
        _selectedIndex.value = -1
        _selectedCard.value = null
        _cardAdded.value = null
        _allTransactions.value = emptyList()
        _filteredTransactions.value = emptyList()
        _nfcLog.value = emptyList()
        _topStations.value = emptyList()
        _topLines.value = emptyList()
        _dailySpending.value = emptyList()
        _homeWeeklySpending.value = emptyList()
        _statsSummary.value = StatsSummary(0.0, 0, 0.0)
        TransitStore.clear(getApplication())
    }

    /** 导出数据为 CSV */
    fun exportCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("序号,交易时间,类型,线路,站点,金额,交易后余额,终端编号")
        for (t in _allTransactions.value.orEmpty()) {
            sb.appendLine(
                "${t.seq},${t.displayDateTime},${t.transitType},${t.lineName}," +
                "\"${t.stationName}\",${t.amountText},${t.balanceAfterText},${t.terminal}"
            )
        }
        return sb.toString()
    }

    /** 导出数据为 JSON（完整持久化状态） */
    fun exportJson(): String {
        val state = buildPersistedState()
        return Gson().toJson(state)
    }

    // ── Private ──

    /** 从 NFC 日志里提取卡号（应用序列号）：READ BINARY SFI=21 行的 bytes 10-19 BCD */
    private fun cardNumberFromNfcLog(log: List<String>): String {
        val line = log.firstOrNull { it.contains("READ BINARY") && it.contains("SFI=21") } ?: return ""
        val hex = line.substringAfter("-> ").trim()
        if (!hex.endsWith("9000") || hex.length < 44) return ""
        val serialHex = hex.substring(20, 40) // data bytes 10-19
        val digits = buildString {
            for (i in 0 until serialHex.length step 2) {
                val b = serialHex.substring(i, i + 2).toInt(16)
                append((b shr 4) and 0xF)
                append(b and 0xF)
            }
        }
        return digits.trimStart('0')
    }

    private fun restorePersistedState() {
        val state = TransitStore.load(getApplication()) ?: return
        val upgraded = mutableMapOf<String, UiCard>() // newId -> card
        state.dataMap.forEach { (id, d) ->
            val cardNum = cardNumberFromNfcLog(d.nfcLog)
            val mappedName = if (cardNum.isNotEmpty()) {
                TransitData.cardName(cardNum)?.takeIf { it.isNotEmpty() }
            } else null
            val newName = mappedName ?: d.card.cardType
            val newId = if (cardNum.isNotEmpty()) cardNum else id
            // 旧版本 cardNumber 为 null/空、lastFour 取的是交易终端号：用卡号回填并修正后四位
            val upgradedCard = if (newId != id || newName != d.card.cardType ||
                d.card.cardNumber.isNullOrEmpty() || d.card.lastFour != cardNum.takeLast(4)
            ) {
                d.card.copy(
                    id = newId,
                    name = newName,
                    cardType = newName,
                    cardNumber = if (cardNum.isNotEmpty()) cardNum else (d.card.cardNumber ?: ""),
                    lastFour = if (cardNum.isNotEmpty()) cardNum.takeLast(4) else d.card.lastFour
                )
            } else d.card
            cardStore[newId] = CardData(
                card = upgradedCard,
                // 修复旧版本持久化数据：充值记录不显示站点名；按 "线路 站点" 组合串反查补回 lineId/stationId
                // （旧版本曾把英文 "Line 1 Huadiwan" 按空格误拆成 line='Line', station='1 Huadiwan'）
                transactions = d.transactions.map { resolveNamesById(it) }.map { repairRechargeTxn(it) },
                nfcLog = d.nfcLog,
                topStations = d.topStations,
                topLines = d.topLines,
                dailySpending = d.dailySpending,
                statsSummary = d.statsSummary
            )
            upgraded[newId] = upgradedCard
        }
        if (state.cards.isNotEmpty()) {
            // 保持持久化的卡顺序：旧 id 的条目替换为升级后的卡
            val ordered = state.cards.map { c ->
                val cardNum = cardNumberFromNfcLog(
                    state.dataMap[c.id]?.nfcLog ?: emptyList()
                )
                upgraded[if (cardNum.isNotEmpty()) cardNum else c.id] ?: c
            }
            _cards.value = ordered
            _hasData.value = true
            val idx = state.selectedIndex.coerceIn(0, ordered.size - 1)
            selectCardByIndex(idx)
            // 修复后把补回的 ID 持久化，避免每次启动都重复反查
            persist()
        }
    }

    private fun persist() {
        TransitStore.save(getApplication(), buildPersistedState())
    }

    private fun buildPersistedState(): PersistedState {
        return PersistedState(
            cards = _cards.value ?: emptyList(),
            dataMap = cardStore.mapValues { (_, d) ->
                PersistedCardData(
                    card = d.card,
                    transactions = d.transactions,
                    nfcLog = d.nfcLog,
                    topStations = d.topStations,
                    topLines = d.topLines,
                    dailySpending = d.dailySpending,
                    statsSummary = d.statsSummary
                )
            },
            selectedIndex = _selectedIndex.value ?: 0
        )
    }

    private fun emitCardData(data: CardData) {
        _selectedCard.value = data.card
        _mainAccent.value = data.card.gradientStartColor
        _allTransactions.value = data.transactions
        _filteredTransactions.value = filterTransactions(data.transactions, _currentFilter.value ?: "全部")
        _nfcLog.value = data.nfcLog
        emitStats(data)
        // 首页迷你图固定用"本周"视图（周一~周日），与统计页默认周期保持一致
        _homeWeeklySpending.value =
            computeDailySpending(data.transactions, "本周", periodWindow("本周", 0).first, periodWindow("本周", 0).second)
    }

    /**
     * 按当前统计周期与偏移从交易记录重算并发布统计。
     * 每次展示都重算，避免持久化的旧快照过期（例如充值混入消费）。
     */
    private fun emitStats(data: CardData) {
        val (start, end) = periodWindow(currentPeriod, currentOffset)
        val periodTxns = if (start.isEmpty()) {
            _periodRange.value = ""
            data.transactions
        } else {
            _periodRange.value = "$start ~ ${end.substring(5)}"
            data.transactions.filter { it.date in start..end }
        }
        _topStations.value = computeTopStations(periodTxns)
        _topLines.value = computeTopLines(periodTxns)
        _dailySpending.value = computeDailySpending(periodTxns, currentPeriod, start, end)
        _statsSummary.value = computeStatsSummary(periodTxns)
    }

    /** 在日期字符串上增加天数，返回 "yyyy-MM-dd" */
    private fun addDays(date: String, days: Int): String {
        return try {
            val cal = Calendar.getInstance().apply { time = dayFmt.parse(date)!! }
            cal.add(Calendar.DAY_OF_YEAR, days)
            dayFmt.format(cal.time)
        } catch (e: Exception) {
            date
        }
    }

    private fun filterTransactions(txns: List<UiTransaction>, filter: String): List<UiTransaction> {
        return when (filter) {
            "地铁" -> txns.filter { it.transitType == "地铁" }
            "公交" -> txns.filter { it.transitType == "公交" }
            "消费" -> txns.filter { it.transitType == "消费" }
            "充值" -> txns.filter { it.transitType == "充值" }
            else -> txns
        }
    }

    /** 交易唯一键，用于去重 */
    private fun txnKey(t: UiTransaction): String {
        return "${t.seq}|${t.date}|${t.time}|${t.terminal}"
    }

    /** 充值不是乘车记录，不显示站点名（旧数据可能残留 "轨道交通 (TU终端:…)" 兜底文本） */
    private fun repairRechargeTxn(t: UiTransaction): UiTransaction {
        if (t.typeHex == "02" || t.amountYuan < 0) return t.copy(stationName = "")
        return t
    }

    /**
     * 切换语言后按 ID 重新解析名称。
     * 线路/站点以数据库 ID 持久化；语言变化时据此重新解析，保持名称与界面语言一致。
     * 旧数据缺 ID：优先用 "线路 站点" 组合串反查，退化为站名反查。
     */
    fun relocalize(): List<UiTransaction> {
        return (_allTransactions.value ?: emptyList()).map { resolveNamesById(it) }
    }

    /**
     * 显示语言切换后，按 ID 重新解析所有卡片的站名/线路名，刷新界面并持久化。
     * 供设置页切换语言后调用。
     */
    fun reloadDisplayLanguage() {
        val selectedId = _cards.value?.getOrNull(_selectedIndex.value ?: -1)?.id
        val (winStart, winEnd) = periodWindow(currentPeriod, currentOffset)
        for ((id, d) in cardStore) {
            val txns = d.transactions.map { resolveNamesById(it) }.map { repairRechargeTxn(it) }
            cardStore[id] = d.copy(
                transactions = txns,
                topStations = computeTopStations(txns),
                topLines = computeTopLines(txns),
                dailySpending = computeDailySpending(txns, currentPeriod, winStart, winEnd),
                statsSummary = computeStatsSummary(txns)
            )
        }
        persist()
        selectedId?.let { id -> cardStore[id]?.let { emitCardData(it) } }
    }

    /** 从 ID（或旧数据反查）重新解析一条交易的中文/英文站名与线路名 */
    private fun resolveNamesById(t: UiTransaction): UiTransaction {
        if (t.typeHex == "02" || t.amountYuan < 0) return t  // 充值无站点

        // 记录方向箭头，解析后重新追加（旧数据修复时不丢失 入站/出站 标记）
        val direction = when {
            t.stationName.endsWith("↑") -> "↑"
            t.stationName.endsWith("↓") -> "↓"
            else -> ""
        }
        val base = t.stationName.removeSuffix("↑").removeSuffix("↓").trim()

        var entry = if (t.lineId != null && t.stationId != null) {
            TransitData.entryOf(t.lineId, t.stationId)
        } else if (t.stationId != null) {
            TransitData.entryOf(null, t.stationId)
        } else null

        // 旧数据（无 ID）：站名里可能残留完整 "线路 站点" 组合串
        // （旧版本曾把英文 "Line 1 Huadiwan" 误存成 station，line 只留了 "Line"）
        if (entry == null && base.contains(" ")) {
            entry = TransitData.resolveByCombined(base)
        }
        // 旧数据：线路/站名被按空格误拆（line='Line', station='1 Huadiwan'），重组后反查
        if (entry == null) {
            entry = TransitData.resolveByCombined("${t.lineName} $base".trim())
        }
        // 旧数据：站名正确但无 ID，按站名反查补回 stationId
        if (entry == null) {
            entry = TransitData.resolveByStationName(base)
        }
        if (entry == null) return t

        val line = entry.line
        val station = entry.station
        return t.copy(
            lineName = line.ifEmpty { t.lineName },
            lineColor = entry.lineColor ?: t.lineColor,
            stationName = if (direction.isNotEmpty()) "$station $direction" else station,
            lineId = entry.lineId ?: t.lineId,
            stationId = entry.stationId ?: t.stationId
        )
    }

    private fun cardId(profile: CardProfile, cardNumber: String, lastFour: String): String {
        // 卡号是稳定唯一标识；读不到卡号（旧卡/解析失败）时退回 "名字|尾号"
        return if (cardNumber.isNotEmpty()) cardNumber else "${cardDisplayName(profile, cardNumber)}|$lastFour"
    }

    private fun cardDisplayName(profile: CardProfile, cardNumber: String): String {
        // 优先用 cardname-tu.csv 按 IIN 最长前缀匹配出的卡名（如 羊城通 / 长安通 / 上海公共交通卡）
        // 传入完整卡号，让 cardName() 能做 8/10/12 位等变长 IIN 的最长前缀匹配
        if (cardNumber.isNotEmpty()) {
            val name = TransitData.cardName(cardNumber)
            if (!name.isNullOrEmpty()) return name
        }
        return when {
            profile.name.contains("深圳通") -> "深圳通"
            profile.name.contains("岭南通") || profile.name.contains("羊城通") -> "岭南通"
            profile.name.contains("交通联合") -> "交通联合卡"
            else -> profile.name.take(8)
        }
    }

    /** 20 色卡面调色板（起色 → 止色），新卡按顺序分配一个未被占用的颜色 */
    private val cardPalette: List<Pair<Long, Long>> = listOf(
        0xFF1A73E8 to 0xFF0D47A1,  // 蓝
        0xFF2E7D32 to 0xFF1B5E20,  // 绿
        0xFFE65100 to 0xFFBF360C,  // 深橙
        0xFF6A1B9A to 0xFF4A148C,  // 紫
        0xFFC62828 to 0xFFB71C1C,  // 红
        0xFF00838F to 0xFF006064,  // 青
        0xFFF9A825 to 0xFFF57F17,  // 金黄
        0xFF5D4037 to 0xFF3E2723,  // 棕
        0xFF455A64 to 0xFF263238,  // 蓝灰
        0xFFAD1457 to 0xFF880E4F,  // 玫红
        0xFF00796B to 0xFF004D40,  // 翡翠
        0xFF283593 to 0xFF1A237E,  // 靛蓝
        0xFFD81B60 to 0xFFC2185B,  // 粉红
        0xFFF4511E to 0xFFE64A19,  // 朱橙
        0xFF3949AB to 0xFF303F9F,  // 蓝紫
        0xFF43A047 to 0xFF2E7D32,  // 翠绿
        0xFF00ACC1 to 0xFF00838F,  // 天青
        0xFFE53935 to 0xFFD32F2F,  // 猩红
        0xFF8E24AA to 0xFF7B1FA2,  // 紫红
        0xFF00897B to 0xFF00695C   // 青绿
    )

    /** 为新卡挑一个尚未被现有卡片使用的颜色 */
    private fun nextCardColor(): Pair<Long, Long> {
        val used = (_cards.value ?: emptyList()).map { it.gradientStartColor }.toSet()
        for (color in cardPalette) {
            if (color.first !in used) return color
        }
        // 颜色用尽后按顺序循环
        return cardPalette[(_cards.value?.size ?: 0) % cardPalette.size]
    }

    /** 删除某张卡及其全部数据 */
    fun deleteCard(index: Int) {
        val list = _cards.value ?: return
        if (index !in list.indices) return
        cardStore.remove(list[index].id)
        val updated = list.toMutableList().apply { removeAt(index) }
        _cards.value = updated
        if (updated.isEmpty()) {
            _hasData.value = false
            _selectedIndex.value = -1
            _selectedCard.value = null
            _cardAdded.value = null
            _allTransactions.value = emptyList()
            _filteredTransactions.value = emptyList()
            _nfcLog.value = emptyList()
            _topStations.value = emptyList()
            _topLines.value = emptyList()
            _dailySpending.value = emptyList()
            _homeWeeklySpending.value = emptyList()
            _statsSummary.value = StatsSummary(0.0, 0, 0.0)
        } else {
            selectCardByIndex(index.coerceAtMost(updated.size - 1))
        }
        persist()
    }

    private fun lastFourFromTransactions(transactions: List<TransactionRecord>): String {
        val terminal = transactions.firstOrNull()?.terminal ?: return "----"
        return if (terminal.length >= 4) terminal.takeLast(4) else terminal
    }

    private fun mapToUiTransaction(
        txn: TransactionRecord,
        index: Int
    ): UiTransaction {
        // TypeHex "02" = 充值, negative amountYuan = recharge
        val isRecharge = txn.typeHex == "02" || txn.amountYuan < 0
        val amountAbs = kotlin.math.abs(txn.amountYuan)

        val (icon, iconBgColor, transitType, lineName) = when {
            isRecharge -> Quad("💳", 0xFFE8F5E9, "充值", "—")
            txn.transitType.contains("地铁") || txn.transitType.contains("Metro") ||
                txn.transitType.contains("轨道交通") ->
                Quad("🚇", 0xFFE3F2FD, "地铁", txn.lineName.ifEmpty { "—" })
            txn.transitType.contains("公交") || txn.transitType.contains("Bus") ->
                Quad("🚌", 0xFFFFF3E0, "公交", txn.lineName.ifEmpty { "—" })
            txn.transitType.contains("消费") -> Quad("🛒", 0xFFFCE4EC, "消费", "—")
            else -> {
                if (txn.lineName.isNotEmpty() && txn.lineName[0].isDigit())
                    Quad("🚇", 0xFFE3F2FD, "地铁", txn.lineName)
                else
                    Quad("🚌", 0xFFFFF3E0, "公交", txn.lineName.ifEmpty { "—" })
            }
        }

        val formattedDate = formatBcdDate(txn.date)
        val formattedTime = formatBcdTime(txn.time)
        val balanceAfterYuan = txn.balanceAfterFen / 100.0

        return UiTransaction(
            id = index,
            seq = txn.seq,
            amountYuan = txn.amountYuan,
            amountText = if (isRecharge) "+¥${String.format("%.2f", amountAbs)}"
                         else "-¥${String.format("%.2f", amountAbs)}",
            typeHex = txn.typeHex,
            transitType = transitType,
            terminal = txn.terminal,
            // 充值不是乘车记录，不显示站点/兜底线路名（避免出现 "轨道交通 (TU终端:…)"）
            stationName = if (isRecharge) "" else txn.stationName,
            cityName = txn.cityName,
            lineName = lineName,
            lineColor = txn.lineColor,
            lineId = txn.lineId,
            stationId = txn.stationId,
            date = formattedDate,
            time = formattedTime,
            displayDateTime = "$formattedDate $formattedTime",
            balanceAfterYuan = balanceAfterYuan,
            balanceAfterText = "余额 ¥${String.format("%.2f", balanceAfterYuan)}",
            icon = icon,
            iconBgColor = iconBgColor
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

    private fun computeTopStations(uiTxns: List<UiTransaction>): List<StationStat> {
        val counts = mutableMapOf<String, Int>()
        for (t in uiTxns) {
            // 充值不是乘车记录，不统计站点
            if (t.typeHex == "02" || t.amountYuan < 0) continue
            // 去掉末尾的方向箭头（"花地湾 ↑" -> "花地湾"）
            val name = t.stationName.replace(Regex(" [↑↓]$"), "")
            if (name.isNotEmpty() && name != "未知" &&
                !name.startsWith("轨道交通") && !name.startsWith("广州公交")) {
                counts[name] = (counts[name] ?: 0) + 1
            }
        }
        val max = counts.values.maxOrNull() ?: 1
        return counts.entries.sortedByDescending { it.value }
            .take(5)
            .map { StationStat(it.key, it.value, it.value.toFloat() / max) }
    }

    private fun computeTopLines(uiTxns: List<UiTransaction>): List<LineStat> {
        val counts = mutableMapOf<String, Int>()
        for (t in uiTxns) {
            // 充值不是乘车记录，不统计线路
            if (t.typeHex == "02" || t.amountYuan < 0) continue
            val lineName = t.lineName
            if (lineName.isNotEmpty() && lineName != "—" && lineName != "未知") {
                counts[lineName] = (counts[lineName] ?: 0) + 1
            }
        }
        val max = counts.values.maxOrNull() ?: 1
        return counts.entries.sortedByDescending { it.value }
            .take(5)
            .map { LineStat(it.key, it.value, it.value.toFloat() / max) }
    }

    private fun computeStatsSummary(uiTxns: List<UiTransaction>): StatsSummary {
        val totalSpending = uiTxns.filter { !it.amountText.startsWith("+") }.sumOf { it.amountYuan }
        val rideCount = uiTxns.filter { it.transitType == "地铁" || it.transitType == "公交" }.size
        val uniqueDays = uiTxns.map { it.date }.distinct().size.coerceAtLeast(1)
        return StatsSummary(totalSpending, rideCount, totalSpending / uniqueDays)
    }

    private fun computeDailySpending(
        uiTxns: List<UiTransaction>,
        period: String,
        winStart: String,
        winEnd: String
    ): List<DailySpending> {
        // 充值金额也是正数，必须排除，否则会把充值算进每日消费
        val dayMap = mutableMapOf<String, Double>() // "yyyy-MM-dd" -> 金额
        for (t in uiTxns) {
            if (t.typeHex == "02" || t.amountYuan < 0) continue
            if (t.amountYuan > 0) {
                dayMap[t.date] = (dayMap[t.date] ?: 0.0) + t.amountYuan
            }
        }
        val today = dayFmt.format(Date())

        // 本周：周一~周日完整 7 天，无数据补 0，标签为星期
        if (period == "本周") {
            val max = dayMap.values.maxOrNull() ?: 1.0
            return (0 until 7).map { i ->
                val date = addDays(winStart, i)
                val amt = dayMap[date] ?: 0.0
                DailySpending(
                    dayLabel = weekdayLabel(date),
                    amountYuan = amt,
                    barHeightPercent = (amt / max).toFloat(),
                    isToday = date == today,
                    date = date
                )
            }
        }

        // 本月：整月 1号~最后一天，只标 1/7/14/21/28 等关键日期
        if (period == "本月") {
            val cal = Calendar.getInstance().apply { time = dayFmt.parse(winStart)!! }
            val lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val max = dayMap.values.maxOrNull() ?: 1.0
            val keyDays = setOf(1, 7, 14, 21, 28)
            return (1..lastDay).map { d ->
                val date = addDays(winStart, d - 1)
                val amt = dayMap[date] ?: 0.0
                DailySpending(
                    dayLabel = if (d in keyDays) "${d}号" else "",
                    amountYuan = amt,
                    barHeightPercent = (amt / max).toFloat(),
                    isToday = date == today,
                    date = date
                )
            }
        }

        // 本年：1月~12月，按月汇总
        if (period == "本年") {
            val monthMap = mutableMapOf<String, Double>() // "yyyy-MM" -> 金额
            for ((date, amt) in dayMap) {
                monthMap[date.take(7)] = (monthMap[date.take(7)] ?: 0.0) + amt
            }
            val max = monthMap.values.maxOrNull() ?: 1.0
            val year = winStart.take(4)
            return (1..12).map { m ->
                val key = "$year-${m.toString().padStart(2, '0')}"
                val amt = monthMap[key] ?: 0.0
                DailySpending(
                    dayLabel = "${m}月",
                    amountYuan = amt,
                    barHeightPercent = (amt / max).toFloat(),
                    date = "$key-01"
                )
            }
        }

        // 自定义等：近 7 个有消费的天，标签用日期号数
        val max = dayMap.values.maxOrNull() ?: 1.0
        val sorted = dayMap.entries.sortedBy { it.key }.takeLast(7)
        return sorted.map { (date, amount) ->
            DailySpending(
                dayLabel = "${date.substring(8).toInt()}号",
                amountYuan = amount,
                barHeightPercent = (amount / max).toFloat(),
                isToday = date == today,
                date = date
            )
        }
    }

    private val weekdayNames = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /** 根据日期（"yyyy-MM-dd"）返回星期几标签 */
    private fun weekdayLabel(date: String): String {
        return try {
            val cal = Calendar.getInstance().apply { time = dayFmt.parse(date)!! }
            weekdayNames[cal.get(Calendar.DAY_OF_WEEK) - 1] // DAY_OF_WEEK: 1=周日…7=周六
        } catch (e: Exception) {
            date.substring(5)
        }
    }

    private data class Quad(
        val icon: String,
        val bgColor: Long,
        val transitType: String,
        val lineName: String
    )
}
