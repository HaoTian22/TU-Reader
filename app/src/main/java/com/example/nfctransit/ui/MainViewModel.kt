package com.example.nfctransit.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.nfctransit.CardProfile
import com.example.nfctransit.TransitCardReader
import com.example.nfctransit.data.RawRecord
import com.example.nfctransit.data.RecordDecoder
import com.example.nfctransit.data.StationDbUpdater
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.TransactionMapper.toUiCard
import com.example.nfctransit.data.TransactionMapper.toUiTransaction
import com.example.nfctransit.data.TuDiscountStats
import com.example.nfctransit.data.db.AppDatabase
import com.example.nfctransit.data.db.CardAppEntity
import com.example.nfctransit.data.db.CardEntity
import com.example.nfctransit.data.repo.TransitRepository
import com.example.nfctransit.model.CanonicalTransaction
import com.example.nfctransit.model.DailySpending
import com.example.nfctransit.model.LineStat
import com.example.nfctransit.model.StationStat
import com.example.nfctransit.model.StatsSummary
import com.example.nfctransit.model.UiCard
import com.example.nfctransit.model.UiTransaction
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TransitRepository(application)

    // ── In-memory working set（镜像持久层；UI 派生的唯一来源）──

    private val cardEntities = mutableListOf<CardEntity>()                 // 卡片列表（镜像 cards 表）
    private val canonicalByCard = mutableMapOf<String, List<CanonicalTransaction>>()  // 镜像 transactions_archive
    /** 卡内折扣统计（SFI 0x19 rec1）快照，镜像 raw_records；用于首页优惠卡片（比交易累加权威、无重复计数） */
    private val discountStatsByCard = mutableMapOf<String, TuDiscountStats?>()

    /** 本次会话 APDU 日志（仅内存，导出用；持久化走 SessionLogStore 文件） */
    private var currentSessionNfcLog: List<String> = emptyList()

    /** 最近一次成功读卡解码出的交易条数（供 MainActivity Toast 显示） */
    var lastReadCount: Int = 0
        private set

    // ── Observable state ──

    private val _hasData = MutableLiveData(false)
    val hasData: LiveData<Boolean> = _hasData

    private val _cards = MutableLiveData<List<UiCard>>(emptyList())
    val cards: LiveData<List<UiCard>> = _cards

    private val _selectedIndex = MutableLiveData(-1)
    val selectedIndex: LiveData<Int> = _selectedIndex

    private val _selectedCard = MutableLiveData<UiCard?>()
    val selectedCard: LiveData<UiCard?> = _selectedCard

    // 读卡后发出对应卡的下标，供首页自动滑动跳转到该卡（新卡或重复读同一张卡都会跳转）
    private val _cardAdded = MutableLiveData<Int?>()
    val cardAdded: LiveData<Int?> = _cardAdded

    private val _allTransactions = MutableLiveData<List<UiTransaction>>(emptyList())
    val allTransactions: LiveData<List<UiTransaction>> = _allTransactions

    /** 当前卡本月累计乘车金额（分），来自卡内 SFI 0x19 rec1 折扣统计；null = 无扇区数据/非本月 */
    private val _selectedDiscountMonthlyFen = MutableLiveData<Long?>(null)
    val selectedDiscountMonthlyFen: LiveData<Long?> = _selectedDiscountMonthlyFen

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

    /** 是否保留调试日志（关闭时不写会话日志文件，避免隐私与存储膨胀） */
    private val _keepDebugLogs = MutableLiveData(true)
    val keepDebugLogs: LiveData<Boolean> = _keepDebugLogs

    /** 站名映射表在线更新：是否进行中 / 结果文案（供设置页状态提示） */
    private val _stationDbUpdating = MutableLiveData(false)
    val stationDbUpdating: LiveData<Boolean> = _stationDbUpdating

    private val _stationDbUpdateStatus = MutableLiveData<String?>(null)
    val stationDbUpdateStatus: LiveData<String?> = _stationDbUpdateStatus

    // 当前统计周期（本周/本月/本年/自定义）与周期偏移（0=当前，-1=上一期，+1=下一期）
    private var currentPeriod = "本周"
    private var currentOffset = 0
    private val _periodOffset = MutableLiveData(0)
    val periodOffset: LiveData<Int> = _periodOffset
    private val _periodRange = MutableLiveData("")
    val periodRange: LiveData<String> = _periodRange
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    init {
        viewModelScope.launch { restore() }
    }

    /** 启动加载：从 DataStore + Room 用户库恢复，用 RecordDecoder 从归档重建交易 */
    private suspend fun restore() {
        _keepDebugLogs.value = repo.isKeepDebugLogs()
        val cards = repo.loadCards()
        if (cards.isEmpty()) return
        val order = repo.getCardOrder()
        val selectedId = repo.getSelectedCardId()

        // 按 cardOrder 排序（不在顺序里的放后面）
        cardEntities.clear()
        cardEntities.addAll(
            cards.sortedBy { c ->
                order.indexOf(c.cardId).let { if (it < 0) Int.MAX_VALUE else it }
            }
        )
        for (c in cardEntities) {
            val archive = repo.loadArchive(c.cardId)
            canonicalByCard[c.cardId] = RecordDecoder.decodeArchive(c.cardType, archive)
            val rawRecs = repo.loadRawRecords(c.cardId)
            discountStatsByCard[c.cardId] = RecordDecoder.parseTuDiscountStats(
                rawRecs.map { RawRecord(it.sfi, it.recNo, it.protocol, it.hex) }
            )
        }

        val uiCards = cardEntities.map { it.toUiCard() }
        _cards.value = uiCards
        _hasData.value = true
        val idx = if (selectedId != null) {
            uiCards.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it }
        } else 0
        selectCardByIndex(idx)
    }

    // ── NFC 数据加载（解码 → 内容去重合并 → 渲染 + 异步持久化）──

    /**
     * 处理 NFC 读取结果。返回新卡片在列表中的下标，若为已存在卡片或未读取到数据则返回 null。
     */
    fun onNfcDataLoaded(result: TransitCardReader.ReadResult): Int? {
        currentSessionNfcLog = result.rawLog
        val profile = result.matchedProfile ?: return null
        val cardNumber = result.cardInfo?.cardNumber ?: ""
        val secondCardNumber = result.secondCardInfo?.cardNumber ?: ""
        val balanceFen = result.balanceFen
        val displayName = cardDisplayName(profile, cardNumber)

        // 解码（读卡展示与归档共用同一条解析路径）。
        // 只把交易 SFI（0x18/0x1E + 各卡附加区）交给解码器；raw_records 里的信息/统计扇区
        // （0x15/0x19/0x08…）只存库不参与交易解析，否则会被 parseFareRecords 当交易误解析。
        val records = result.rawRecords
            .filter { it.sfi in profile.transactionSfis }
            .map {
                RecordDecoder.ZoneRecord(it.sfi, it.recNo, it.protocol, it.hex)
            }
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val decoded = RecordDecoder.decodeCard(
            profile.cardType, records, result.statsMonth, currentYear, balanceFen
        )
        if (decoded.display.isEmpty()) {
            lastReadCount = 0
            return null
        }
        lastReadCount = decoded.display.size

        val lastFour = if (cardNumber.isNotEmpty()) cardNumber.takeLast(4)
            else lastFourFromCanonical(decoded.display)
        val now = System.currentTimeMillis()

        // 匹配已有卡：按卡号复用 UUID；双协议卡换协议识别时（如坏数据存的纯 TU → YCT）按第二个卡号兜底；最后按尾号
        var existing = if (cardNumber.isNotEmpty()) {
            cardEntities.firstOrNull { it.cardNumber == cardNumber }
        } else null
        if (existing == null && secondCardNumber.isNotEmpty()) {
            existing = cardEntities.firstOrNull {
                it.cardNumber == secondCardNumber || it.secondCardNumber == secondCardNumber
            }
        }
        if (existing == null) existing = cardEntities.firstOrNull { it.lastFour == lastFour }
        val isNew = existing == null
        val cardId = existing?.cardId ?: UUID.randomUUID().toString()
        val (gradStart, gradEnd) = existing?.let {
            it.gradientStartColor to it.gradientEndColor
        } ?: nextCardColor()

        // 内容去重合并进内存（identity = content_hash，不含 rec_no）
        canonicalByCard[cardId] = mergeCanonical(canonicalByCard[cardId] ?: emptyList(), decoded.display)
        discountStatsByCard[cardId] = RecordDecoder.parseTuDiscountStats(result.rawRecords)

        val entity = CardEntity(
            cardId = cardId,
            cardNumber = cardNumber,
            secondCardNumber = secondCardNumber.ifEmpty { null },
            name = displayName,
            cardType = profile.cardType,
            lastFour = lastFour,
            gradientStartColor = gradStart,
            gradientEndColor = gradEnd,
            latestBalanceFen = balanceFen,
            createdAt = existing?.createdAt ?: now,
            lastReadAt = now
        )
        val listIndex = cardEntities.indexOfFirst { it.cardId == cardId }
        if (listIndex >= 0) cardEntities[listIndex] = entity else cardEntities.add(entity)

        val updatedCards = cardEntities.map { it.toUiCard() }
        _cards.value = updatedCards
        _hasData.value = true
        val index = updatedCards.indexOfFirst { it.id == cardId }
        selectCardByIndex(index)
        // 无论新卡还是重复读同一张卡，都让首页滑动到该卡
        _cardAdded.value = index

        // 异步持久化：卡片 + 槽位同步 + 内容去重归档（含 0x1E 旅程记录）+ 应用 SELECT/BALANCE + 顺序 + 会话日志
        val rawRecords = result.rawRecords
        val logLines = result.rawLog
        val appRows = result.appReads.map { app ->
            CardAppEntity(
                cardId = cardId,
                readAt = now,
                selectedAid = app.selectedAid,
                selectResp = app.selectResp,
                balanceFen = app.balanceFen,
                balanceResp = app.balanceResp
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            repo.upsertCard(entity)
            repo.syncRawRecords(cardId, rawRecords)
            repo.archiveTransactions(cardId, decoded.archive)
            repo.syncCardApps(cardId, appRows)
            repo.setCardOrder(cardEntities.map { it.cardId })
            repo.writeSessionLog(cardId, logLines)
        }
        return if (isNew) index else null
    }

    fun selectCardByIndex(index: Int) {
        val list = _cards.value ?: return
        if (index !in list.indices) return
        _selectedIndex.value = index
        val cardId = cardEntities.getOrNull(index)?.cardId ?: return
        viewModelScope.launch(Dispatchers.IO) { repo.setSelectedCardId(cardId) }
        emitCardData(cardId)
    }

    fun clearCardAdded() {
        _cardAdded.value = null
    }

    /** 卡片排序：按给定 cardId 顺序重排内存列表并持久化，保持当前选中卡不变 */
    fun applyCardOrder(orderedIds: List<String>) {
        if (cardEntities.size < 2) return
        val byId = cardEntities.associateBy { it.cardId }
        val reordered = mutableListOf<CardEntity>()
        val seen = mutableSetOf<String>()
        for (id in orderedIds) {
            val e = byId[id] ?: continue
            reordered.add(e)
            seen.add(id)
        }
        // 兜底：未出现在新顺序里的卡排到最后
        reordered.addAll(cardEntities.filter { it.cardId !in seen })
        cardEntities.clear()
        cardEntities.addAll(reordered)

        val selectedId = _selectedCard.value?.id
        val newIndex = reordered.indexOfFirst { it.cardId == selectedId }
        if (newIndex >= 0) _selectedIndex.value = newIndex
        _cards.value = reordered.map { it.toUiCard() }
        viewModelScope.launch(Dispatchers.IO) { repo.setCardOrder(reordered.map { it.cardId }) }
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

    /** 设置是否保留调试日志（设置页开关） */
    fun setKeepDebugLogs(keep: Boolean) {
        _keepDebugLogs.value = keep
        viewModelScope.launch(Dispatchers.IO) { repo.setKeepDebugLogs(keep) }
    }

    private fun emitStatsForSelected() {
        val index = _selectedIndex.value ?: return
        val cardId = cardEntities.getOrNull(index)?.cardId ?: return
        canonicalByCard[cardId]?.let { emitStats(it.toUiTransactions()) }
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
        cardEntities.clear()
        canonicalByCard.clear()
        currentSessionNfcLog = emptyList()
        lastReadCount = 0
        _hasData.value = false
        _cards.value = emptyList()
        _selectedIndex.value = -1
        _selectedCard.value = null
        _cardAdded.value = null
        _allTransactions.value = emptyList()
        _selectedDiscountMonthlyFen.value = null
        _filteredTransactions.value = emptyList()
        _nfcLog.value = emptyList()
        _topStations.value = emptyList()
        _topLines.value = emptyList()
        _dailySpending.value = emptyList()
        _homeWeeklySpending.value = emptyList()
        _statsSummary.value = StatsSummary(0.0, 0, 0.0)
        _keepDebugLogs.value = true  // DataStore 清空后恢复默认
        viewModelScope.launch(Dispatchers.IO) { repo.clearAll() }
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

    /** 导出数据为 JSON（当前选中卡的渲染交易） */
    fun exportJson(): String {
        return com.google.gson.Gson().toJson(_allTransactions.value.orEmpty())
    }

    // ── 数据库导入 / 导出 ──

    /** 导出用户库（user_data.db）为单文件到用户所选位置 */
    suspend fun exportDatabase(uri: Uri) {
        withContext(Dispatchers.IO) { repo.exportDatabase(uri) }
    }

    /** 导入用户库：复制所选文件到缓存 → 去重合并 → 重载界面，返回结果文案 */
    suspend fun importDatabase(uri: Uri): String {
        val tmp = withContext(Dispatchers.IO) { copyUriToCache(uri) }
        val summary = withContext(Dispatchers.IO) {
            try {
                repo.importDatabase(tmp)
            } finally {
                tmp.delete()
            }
        }
        restore()
        return "已导入：新增 ${summary.cards} 张卡、${summary.archive} 条交易、${summary.raw} 条原始记录"
    }

    private fun copyUriToCache(uri: Uri): File {
        val app = getApplication<Application>()
        val tmp = File(app.cacheDir, "import_${System.currentTimeMillis()}.db")
        app.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
        } ?: throw IOException("无法读取所选文件")
        return tmp
    }

    // ── 站名映射表在线更新 ──

    /**
     * 下载最新 transit.db 并替换本地站名映射表，成功后重载内存索引并刷新界面。
     * 下载或格式校验失败时原库保持不变。
     */
    fun updateStationDatabase() {
        if (_stationDbUpdating.value == true) return
        _stationDbUpdating.value = true
        viewModelScope.launch {
            try {
                val downloaded = withContext(Dispatchers.IO) {
                    StationDbUpdater.download(getApplication())
                }
                try {
                    withContext(Dispatchers.IO) {
                        AppDatabase.replaceWithDownloaded(getApplication(), downloaded)
                        TransitData.reload()
                    }
                } finally {
                    downloaded.delete()
                }
                _stationDbUpdateStatus.value = "✓ 站名映射表已更新"
                reloadDisplayLanguage()
            } catch (e: Exception) {
                _stationDbUpdateStatus.value = "更新失败: ${e.message}"
            } finally {
                _stationDbUpdating.value = false
            }
        }
    }

    // ── Private ──

    /** 从 canonical（镜像 archive）生成该卡的 UI 交易（名称按当前界面语言经 ID 解析） */
    private fun List<CanonicalTransaction>.toUiTransactions(): List<UiTransaction> =
        mapIndexed { idx, c -> c.toUiTransaction(idx) }

    private fun emitCardData(cardId: String) {
        val card = cardEntities.firstOrNull { it.cardId == cardId }?.toUiCard() ?: return
        val txns = canonicalByCard[cardId].orEmpty().toUiTransactions()
        _selectedCard.value = card
        _mainAccent.value = card.gradientStartColor
        _allTransactions.value = txns
        _selectedDiscountMonthlyFen.value = usableMonthlyFen(discountStatsByCard[cardId])
        _filteredTransactions.value = filterTransactions(txns, _currentFilter.value ?: "全部")
        _nfcLog.value = currentSessionNfcLog
        emitStats(txns)
        // 首页迷你图固定用"本周"视图（周一~周日），与统计页默认周期保持一致
        _homeWeeklySpending.value =
            computeDailySpending(txns, "本周", periodWindow("本周", 0).first, periodWindow("本周", 0).second)
    }

    /** 折扣统计的"本月累计金额"：卡内统计月与当前月一致才采用，避免跨月陈旧值 */
    private fun usableMonthlyFen(stats: TuDiscountStats?): Long? {
        if (stats?.statsMonth == null) return null
        val now = Calendar.getInstance()
        val current = now.get(Calendar.YEAR) * 100 + (now.get(Calendar.MONTH) + 1)
        return if (stats.statsMonth == current) stats.totalFen else null
    }

    /** 按当前统计周期与偏移从交易记录重算并发布统计（每次展示都重算，避免持久化旧快照过期） */
    private fun emitStats(txns: List<UiTransaction>) {
        val (start, end) = periodWindow(currentPeriod, currentOffset)
        val periodTxns = if (start.isEmpty()) {
            _periodRange.value = ""
            txns
        } else {
            _periodRange.value = "$start ~ ${end.substring(5)}"
            txns.filter { it.date in start..end }
        }
        _topStations.value = computeTopStations(periodTxns)
        _topLines.value = computeTopLines(periodTxns)
        _dailySpending.value = computeDailySpending(periodTxns, currentPeriod, start, end)
        _statsSummary.value = computeStatsSummary(periodTxns)
    }

    /** 内容去重合并（identity = content_hash；内容相同跳过，不同追加为新 key） */
    private fun mergeCanonical(
        existing: List<CanonicalTransaction>,
        fresh: List<CanonicalTransaction>
    ): List<CanonicalTransaction> {
        val seen = existing.map { it.identity }.toHashSet()
        val merged = existing.toMutableList()
        for (t in fresh) {
            if (t.identity !in seen) {
                merged.add(t)
                seen.add(t.identity)
            }
        }
        return merged.sortedWith(
            compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence }
        )
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

    /** 显示语言切换后，按 ID 重新解析当前卡的站名/线路名并刷新界面（名称由 ID 在映射时解析） */
    fun reloadDisplayLanguage() {
        val cardId = cardEntities.getOrNull(_selectedIndex.value ?: -1)?.cardId ?: return
        emitCardData(cardId)
    }

    private fun cardDisplayName(profile: CardProfile, cardNumber: String): String {
        // 优先用 cardname-tu.csv 按 IIN 最长前缀匹配出的卡名（如 羊城通 / 长安通 / 上海公共交通卡）
        if (cardNumber.isNotEmpty()) {
            val name = TransitData.cardName(cardNumber)
            if (!name.isNullOrEmpty()) return name
        }
        return when {
            profile.name.contains("深圳通") -> "深圳通"
            profile.name.contains("岭南通") || profile.name.contains("羊城通") -> "岭南通"
            profile.name.contains("苏州") -> "苏州通"
            profile.name.contains("天津") -> "天津通"
            profile.name.contains("数字城市") || profile.cardType == "CU" -> "数字城市一卡通"
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
        val cardId = cardEntities.getOrNull(index)?.cardId ?: return
        cardEntities.removeAt(index)
        canonicalByCard.remove(cardId)
        discountStatsByCard.remove(cardId)
        val updated = cardEntities.map { it.toUiCard() }
        _cards.value = updated
        if (updated.isEmpty()) {
            _hasData.value = false
            _selectedIndex.value = -1
            _selectedCard.value = null
            _cardAdded.value = null
            _allTransactions.value = emptyList()
            _selectedDiscountMonthlyFen.value = null
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
        viewModelScope.launch(Dispatchers.IO) { repo.deleteCard(cardId) }
    }

    private fun lastFourFromCanonical(canonical: List<CanonicalTransaction>): String {
        val terminal = canonical.firstOrNull()?.terminal ?: return "----"
        return if (terminal.length >= 4) terminal.takeLast(4) else terminal
    }

    private fun computeTopStations(uiTxns: List<UiTransaction>): List<StationStat> {
        val counts = mutableMapOf<String, Int>()
        for (t in uiTxns) {
            // 充值/无金额旅程事件不是乘车记录，不统计站点
            if (t.typeHex == "02" || t.amountYuan <= 0) continue
            // 去掉末尾的方向箭头（"花地湾 ↑" -> "花地湾"）
            val name = t.stationName.replace(Regex(" [↑↓]$"), "")
            if (name.isNotEmpty() && name != "未知" &&
                !name.startsWith("轨道交通") && !name.startsWith("公共交通") && !name.startsWith("广州公交")) {
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
            // 充值/无金额旅程事件不是乘车记录，不统计线路
            if (t.typeHex == "02" || t.amountYuan <= 0) continue
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
        // 乘车次数只统计有金额的乘车记录（1E 进站/出站事件 ¥0 不单独算一次）
        val rideCount = uiTxns.filter {
            (it.transitType == "地铁" || it.transitType == "公交") && it.amountYuan > 0
        }.size
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
}
