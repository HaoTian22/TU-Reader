package com.example.nfctransit.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.nfctransit.CardProfile
import com.example.nfctransit.TransitCardReader
import com.example.nfctransit.data.CardUiCache
import com.example.nfctransit.data.RawRecord
import com.example.nfctransit.data.RecordDecoder
import com.example.nfctransit.data.StationDbUpdater
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.TransitDbVersion
import com.example.nfctransit.data.TransactionMapper.toUiCard
import com.example.nfctransit.data.TransactionMapper.toUiTransaction
import com.example.nfctransit.data.TuDiscountStats
import com.example.nfctransit.data.UiCache
import com.example.nfctransit.data.toSfiInt
import com.example.nfctransit.data.db.AppDatabase
import com.example.nfctransit.data.db.CardAppEntity
import com.example.nfctransit.data.db.CardEntity
import com.example.nfctransit.data.prefs.AppPreferences
import com.example.nfctransit.data.repo.TransitRepository
import com.example.nfctransit.model.CanonicalTransaction
import com.example.nfctransit.model.CategorySpending
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

    /** 每卡原始记录快照（镜像 raw_records），交易详情页按 SFI 展示原始数据 */
    private val rawRecordsByCard = mutableMapOf<String, List<RawRecord>>()

    /**
     * 每卡已构建的 UI 交易（启动从 UiCache 恢复或构建后派生）。
     * emitCardData 优先用它渲染，避免每次切卡/筛选都重跑 toUiTransaction（并触发 27k 行站名索引加载）；
     * 任何 canonicalByCard 被改写的点都必须同步清掉对应项（重派生出最新 UI）。
     */
    private val cachedTxnsByCard = mutableMapOf<String, List<UiTransaction>>()

    /** 本次会话 APDU 日志（仅内存，导出用；持久化走 SessionLogStore 文件） */
    private var currentSessionNfcLog: List<String> = emptyList()

    /** 最近一次成功读卡解码出的交易条数（供 MainActivity Toast 显示） */
    var lastReadCount: Int = 0
        private set

    // ── Observable state ──

    private val _hasData = MutableLiveData(false)
    val hasData: LiveData<Boolean> = _hasData

    /** 启动恢复/重建中（restore() 完成前为 true），首页据此显示加载态而非误导性的空态 */
    private val _isRestoring = MutableLiveData(true)
    val isRestoring: LiveData<Boolean> = _isRestoring

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

    /** 当前卡本月累计乘车金额（分），来自卡内折扣统计（SFI 0x19 / LNT 0x08）；null = 无扇区数据/非本月 */
    private val _selectedDiscountMonthlyFen = MutableLiveData<Long?>(null)
    val selectedDiscountMonthlyFen: LiveData<Long?> = _selectedDiscountMonthlyFen

    /** 当前卡折扣统计对应月份（"2026-08"），首页优惠标题旁展示；null = 无折扣统计/非本月 */
    private val _selectedDiscountStatsMonth = MutableLiveData<String?>(null)
    val selectedDiscountStatsMonth: LiveData<String?> = _selectedDiscountStatsMonth

    /** 多选筛选：空集合 = 显示全部；非空 = 只显示 transitType 命中的类别 */
    private val _currentFilter = MutableLiveData<Set<String>>(emptySet())
    val currentFilter: LiveData<Set<String>> = _currentFilter

    /** 全文搜索：匹配时间/站点/原始值/类型/城市/金额/余额/协议等字段 */
    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

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

    private val _categorySpending = MutableLiveData<List<CategorySpending>>(emptyList())
    val categorySpending: LiveData<List<CategorySpending>> = _categorySpending

    private val _citySpending = MutableLiveData<List<CategorySpending>>(emptyList())
    val citySpending: LiveData<List<CategorySpending>> = _citySpending

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

    /** 清理缓存（UI 构建缓存 + transit.db 重置为内置版）：是否进行中 / 结果文案 */
    private val _cacheClearing = MutableLiveData(false)
    val cacheClearing: LiveData<Boolean> = _cacheClearing

    private val _cacheClearStatus = MutableLiveData<String?>(null)
    val cacheClearStatus: LiveData<String?> = _cacheClearStatus

    // 当前统计周期（本周/本月/本年/自定义）与周期偏移（0=当前，-1=上一期，+1=下一期）
    private var currentPeriod = "本周"
    private var currentOffset = 0
    private val _periodOffset = MutableLiveData(0)
    val periodOffset: LiveData<Int> = _periodOffset
    private val _periodRange = MutableLiveData("")
    val periodRange: LiveData<String> = _periodRange

    // 自定义统计范围（yyyy-MM-dd）；未设置时为空串
    private var customStart = ""
    private var customEnd = ""
    private val _customRange = MutableLiveData("" to "")
    val customRange: LiveData<Pair<String, String>> = _customRange

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    init {
        viewModelScope.launch { restore() }
    }

    /** 启动加载：从 DataStore + Room 用户库恢复；每张卡优先读上次的 UI 构建缓存，无缓存/失效再重建 */
    private suspend fun restore() {
        try {
            // 安装/升级后只清理一次 UI 构建缓存；普通重启沿用缓存。
            val app = getApplication<Application>()
            val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
            val installMarker = "${packageInfo.versionName}:${packageInfo.lastUpdateTime}"
            if (AppPreferences.markAppInstall(app, installMarker)) {
                withContext(Dispatchers.IO) { UiCache.clearAll(app) }
            }
            // 首次进入版本追踪时初始化 db_version；须在 createFromAsset 拷贝前执行，
            // 才能区分「全新安装（无库）」与「老库升级（库已存在）」。
            AppPreferences.initDbVersion(getApplication())
            // 预热站名索引（2.7 万行）到内存：后台加载，避免首次读卡/首屏渲染时才在主线程加载
            withContext(Dispatchers.Default) { TransitData.warmup() }
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
            // 每张卡的构建/加载都在 Default 线程（decodeArchive / Gson 解析是 CPU 密集），结果回主线程落内存；
            // 单张卡失败不影响其余卡片（缓存命中的直接恢复，未命中的重建）
            cachedTxnsByCard.clear()
            val dbVersion = AppPreferences.getDbVersion(getApplication())
            cardEntities.forEach { card ->
                val state = try {
                    withContext(Dispatchers.Default) { loadCardState(card, dbVersion = dbVersion) }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "加载卡片 ${card.cardId} 失败", e)
                    null
                }
                if (state != null) applyCardState(card.cardId, state)
            }

            val uiCards = cardEntities.map { it.toUiCard() }
            _cards.value = uiCards
            _hasData.value = true
            val idx = if (selectedId != null) {
                uiCards.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it }
            } else 0
            selectCardByIndex(idx)
        } catch (e: Exception) {
            Log.e("MainViewModel", "restore 失败", e)
        } finally {
            _isRestoring.value = false
        }
    }

    /** 一张卡构建完成的全部渲染状态（在后台线程组装，回主线程经 applyCardState 落内存） */
    private data class BuiltCardState(
        val canonicals: List<CanonicalTransaction>,
        val txns: List<UiTransaction>,
        val raws: List<RawRecord>,
        val discountStats: TuDiscountStats?
    )

    private fun applyCardState(cardId: String, state: BuiltCardState) {
        canonicalByCard[cardId] = state.canonicals
        cachedTxnsByCard[cardId] = state.txns
        rawRecordsByCard[cardId] = state.raws
        discountStatsByCard[cardId] = state.discountStats
    }

    /**
     * 载入一张卡的渲染状态：缓存命中（archive row_id 未变）→ 直接恢复已构建的 canonical + UI 交易；
     * 否则重建（decodeArchive → toUiTransaction）并回写缓存。raw_records 总是现读（折扣统计现算，
     * 避免跨月/重读后的陈旧快照），CPU 密集部分在调用方线程执行。不直接改内存 map，由调用方回主线程应用。
     *
     * @param forceRebuild 忽略缓存强制重建（站名映射表更新/清缓存后站名与 ID 可能变化，须重新解析）
     */
    private suspend fun loadCardState(card: CardEntity, forceRebuild: Boolean = false, dbVersion: String?): BuiltCardState {
        val ctx = getApplication<Application>()
        val cardId = card.cardId
        val archiveRowId = repo.maxArchiveRowId(cardId) ?: 0L
        val rawRecs = repo.loadRawRecords(cardId).map {
            RawRecord(it.sfi.toSfiInt(), it.recNo, it.protocol, it.hex)
        }
        val cached = if (forceRebuild) null else UiCache.load(ctx, cardId, archiveRowId, dbVersion)
        return if (cached != null) {
            BuiltCardState(
                cached.canonicals, cached.txns, rawRecs, RecordDecoder.parseTuDiscountStats(rawRecs)
            )
        } else {
            val archive = repo.loadArchive(cardId)
            val canonicals = RecordDecoder.decodeArchive(card.cardType, archive)
            val txns = enrichProtocols(canonicals, rawRecs).toUiTransactions(card.cardType)
            UiCache.save(ctx, cardId, CardUiCache(archiveRowId, canonicals, txns, dbVersion))
            BuiltCardState(canonicals, txns, rawRecs, RecordDecoder.parseTuDiscountStats(rawRecs))
        }
    }

    /** 站名映射表更新 / 清缓存后：所有卡强制重解码（站名与 ID 可能变化）并重建缓存，刷新当前选中卡 */
    private suspend fun rebuildAllCardsAndRefresh() {
        cachedTxnsByCard.clear()
        val dbVersion = AppPreferences.getDbVersion(getApplication())
        cardEntities.forEach { card ->
            try {
                val state = withContext(Dispatchers.Default) {
                    loadCardState(card, forceRebuild = true, dbVersion = dbVersion)
                }
                applyCardState(card.cardId, state)
            } catch (e: Exception) {
                // 单张卡失败不拖垮其余卡片（与 restore() 一致）
                Log.e("MainViewModel", "重建卡片 ${card.cardId} 失败", e)
            }
        }
        val cardId = cardEntities.getOrNull(_selectedIndex.value ?: -1)?.cardId
        if (cardId != null) emitCardData(cardId)
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
            profile.cardType, records, result.statsMonth, currentYear
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
        cachedTxnsByCard.remove(cardId)  // canonical 已更新 → 让 emitCardData 立刻从新 canonical 重派生
        rawRecordsByCard[cardId] = result.rawRecords
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
            // 写库完成后以数据库为唯一来源重建内存与 UI（读卡后与重启走同一条 decodeArchive 路径，
            // 保证界面与重启一致，不依赖读卡时的内存解码结果）
            val archive = repo.loadArchive(cardId)
            val rawRecs = repo.loadRawRecords(cardId).map {
                RawRecord(it.sfi.toSfiInt(), it.recNo, it.protocol, it.hex)
            }
            val archiveRowId = repo.maxArchiveRowId(cardId) ?: 0L
            // 解码/映射是 CPU 密集：放 Default 计算，主线程只做状态落盘与 UI 刷新，避免大库/大卡读卡后卡顿
            val canon = withContext(Dispatchers.Default) {
                RecordDecoder.decodeArchive(profile.cardType, archive)
            }
            val txns = withContext(Dispatchers.Default) {
                enrichProtocols(canon, rawRecs).toUiTransactions(profile.cardType)
            }
            val stats = withContext(Dispatchers.Default) { RecordDecoder.parseTuDiscountStats(rawRecs) }
            withContext(Dispatchers.Main) {
                canonicalByCard[cardId] = canon
                rawRecordsByCard[cardId] = rawRecs
                discountStatsByCard[cardId] = stats
                cachedTxnsByCard[cardId] = txns
                val idx = cardEntities.indexOfFirst { it.cardId == cardId }
                if (idx >= 0 && _selectedIndex.value == idx) {
                    emitCardData(cardId)
                }
            }
            // 以数据库为准重建完成 → 回写磁盘缓存（下次启动直接命中）
            val dbVersion = AppPreferences.getDbVersion(getApplication())
            UiCache.save(getApplication(), cardId, CardUiCache(archiveRowId, canon, txns, dbVersion))
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

    fun setFilter(selected: Set<String>) {
        _currentFilter.value = selected
        applyTransactionFilter()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyTransactionFilter()
    }

    private fun applyTransactionFilter() {
        val all = _allTransactions.value ?: return
        _filteredTransactions.value =
            filterTransactions(all, _currentFilter.value ?: emptySet(), _searchQuery.value ?: "")
    }

    /** 切换统计周期（本周/本月/本年/自定义），重置到当前周期 */
    fun setStatsPeriod(period: String) {
        currentPeriod = period
        currentOffset = 0
        _periodOffset.value = 0
        // 首次切到自定义时给一个默认范围（本月 1 号 ~ 今天），避免空窗口导致整表退化为全量
        if (period == "自定义" && (customStart.isEmpty() || customEnd.isEmpty())) {
            setCustomRange(
                dayFmt.format(Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.time),
                dayFmt.format(Calendar.getInstance().time)
            )
        }
        emitStatsForSelected()
    }

    /** 设置自定义日期范围并立即重算统计（起 <= 止才接受） */
    fun setCustomRange(start: String, end: String) {
        if (start.isBlank() || end.isBlank()) return
        if (start > end) return
        customStart = start
        customEnd = end
        currentPeriod = "自定义"
        currentOffset = 0
        _periodOffset.value = 0
        _customRange.value = start to end
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
        val cardType = cardEntities.getOrNull(index)?.cardType.orEmpty()
        val txns = cachedTxnsByCard[cardId] ?: canonicalByCard[cardId]?.toUiTransactions(cardType) ?: return
        emitStats(txns)
    }

    /** 某周期的日期窗口 [起, 止]，yyyy-MM-dd；自定义返回用户所选范围，未设置时返回空串 */
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
            "自定义" ->
                if (customStart.isNotEmpty() && customEnd.isNotEmpty()) customStart to customEnd else "" to ""
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
        rawRecordsByCard.clear()
        currentSessionNfcLog = emptyList()
        lastReadCount = 0
        _hasData.value = false
        _cards.value = emptyList()
        _selectedIndex.value = -1
        _selectedCard.value = null
        _cardAdded.value = null
        _allTransactions.value = emptyList()
        _selectedDiscountMonthlyFen.value = null
        _selectedDiscountStatsMonth.value = null
        _filteredTransactions.value = emptyList()
        _nfcLog.value = emptyList()
        _topStations.value = emptyList()
        _topLines.value = emptyList()
        _dailySpending.value = emptyList()
        _categorySpending.value = emptyList()
        _citySpending.value = emptyList()
        _homeWeeklySpending.value = emptyList()
        _statsSummary.value = StatsSummary(0.0, 0, 0.0)
        _keepDebugLogs.value = true  // DataStore 清空后恢复默认
        cachedTxnsByCard.clear()
        viewModelScope.launch(Dispatchers.IO) {
            UiCache.clearAll(getApplication())
            repo.clearAll()
        }
    }

    /** 导出数据为 CSV */
    fun exportCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("序号,交易时间,类型,线路,站点,金额,交易后余额,终端编号")
        for (t in _allTransactions.value.orEmpty()) {
            sb.appendLine(
                "${t.seq},${t.displayDateTime},${t.transitType},${t.lineName}," +
                "\"${t.stationName}\",${t.amountText},${t.balanceAfterText ?: "无"},${t.terminal}"
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

    /**
     * 导入数据库：自动识别 TripReader（card_table/tran_table）与本应用导出库（cards/…）两种格式，
     * 复制所选文件到缓存 → 去重合并 → 重载界面，返回结果文案。
     */
    suspend fun importDatabase(uri: Uri): String {
        val tmp = withContext(Dispatchers.IO) { copyUriToCache(uri) }
        val (summary, fromTripReader) = withContext(Dispatchers.IO) {
            try {
                if (repo.isTripReaderDatabase(tmp)) repo.importTripReaderDatabase(tmp) to true
                else repo.importDatabase(tmp) to false
            } finally {
                tmp.delete()
            }
        }
        restore()
        return if (fromTripReader) {
            "已导入：新增 ${summary.cards} 张卡、${summary.archive} 条交易"
        } else {
            "已导入：新增 ${summary.cards} 张卡、${summary.archive} 条交易、${summary.raw} 条原始记录"
        }
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
                        AppDatabase.replaceWithDownloaded(getApplication(), downloaded.file)
                        TransitData.reload()
                    }
                } finally {
                    downloaded.file.delete()
                }
                // 记录当前库版本：优先版本 sidecar，其次服务端 Last-Modified，最后本地时间
                val otaVersion = withContext(Dispatchers.IO) {
                    StationDbUpdater.downloadVersionString(getApplication())
                } ?: downloaded.lastModifiedMillis?.let { TransitDbVersion.formatTimestamp(it) }
                    ?: TransitDbVersion.formatTimestamp(System.currentTimeMillis())
                AppPreferences.setDbVersion(getApplication(), otaVersion)
                // 站名/线路 ID 可能已变 → 全部卡强制重解码并重建缓存（不能直接复用旧 canonical/UI 缓存）
                rebuildAllCardsAndRefresh()
                // 界面重建完成后再报成功，避免「提示已更新但界面还是旧站名」
                _stationDbUpdateStatus.value = "✓ 站名映射表已更新"
            } catch (e: Exception) {
                _stationDbUpdateStatus.value = "更新失败: ${e.message}"
            } finally {
                _stationDbUpdating.value = false
            }
        }
    }

    /**
     * 清理缓存：删除 UI 构建缓存。站名映射表仅在内置（asset）版本比当前库更新时才重置为内置版，
     * 否则保留当前版本（避免把较新的在线更新库回退成旧内置版）。随后全部卡片重解码刷新界面。
     * 不影响用户数据（卡片/交易/原始记录）。
     */
    fun clearCache() {
        if (_cacheClearing.value == true) return
        _cacheClearing.value = true
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val assetVersion = TransitDbVersion.readAssetVersion(ctx)
                val activeVersion = AppPreferences.getDbVersion(ctx)
                val resetToAsset = TransitDbVersion.isNewer(assetVersion, activeVersion)
                withContext(Dispatchers.IO) {
                    UiCache.clearAll(ctx)
                    if (resetToAsset) {
                        AppDatabase.resetToAsset(ctx)
                        TransitData.reload()
                    }
                }
                if (resetToAsset) AppPreferences.setDbVersion(ctx, assetVersion ?: "0")
                rebuildAllCardsAndRefresh()
                _cacheClearStatus.value = if (resetToAsset) {
                    "✓ 已清理缓存并重置站名映射表"
                } else {
                    "✓ 已清理缓存（站名映射表保持当前版本）"
                }
            } catch (e: Exception) {
                _cacheClearStatus.value = "清理失败: ${e.message}"
            } finally {
                _cacheClearing.value = false
            }
        }
    }

    // ── Private ──

    /** 从 canonical（镜像 archive）生成该卡的 UI 交易（名称按当前界面语言经 ID 解析） */
    private fun List<CanonicalTransaction>.toUiTransactions(cardType: String): List<UiTransaction> =
        mapIndexed { idx, c -> c.toUiTransaction(idx, cardType) }

    /**
     * 用 raw_records 反查 contentHash → 协议集合，回填 canonical.protocols（仅归档未合并出并集时兜底）：
     * 归档已按 (content_hash, protocol, sfi) 存全部变体并由 decodeArchive 合并，这里只补旧版单协议归档的数据。
     */
    private fun enrichProtocols(
        canonicals: List<CanonicalTransaction>,
        raws: List<RawRecord>
    ): List<CanonicalTransaction> {
        if (raws.isEmpty()) return canonicals
        val protocolsByHash = raws.groupBy { RecordDecoder.contentHash(it.hex) }
            .mapValues { (_, recs) -> recs.mapNotNull { it.protocol.ifBlank { null } }.distinct().toSet() }
        return canonicals.map { c ->
            if (c.protocols.isNotEmpty()) return@map c
            val union = protocolsByHash[c.identity].orEmpty()
            if (union.isNotEmpty() && union != setOf(c.protocol)) c.copy(protocols = union) else c
        }
    }

    private fun emitCardData(cardId: String) {
        val card = cardEntities.firstOrNull { it.cardId == cardId }?.toUiCard() ?: return
        // 优先用已构建的 UI 交易（启动缓存命中/读卡后已回写），避免切卡/筛选/统计时重跑 toUiTransaction
        // 并触发 27k 行站名索引加载；缺失（语言切换、数据变化的失效点）时从 canonical 重派生并回写内存
        val cardType = cardEntities.firstOrNull { it.cardId == cardId }?.cardType.orEmpty()
        val txns = cachedTxnsByCard[cardId]
            ?: enrichProtocols(canonicalByCard[cardId].orEmpty(), rawRecordsByCard[cardId].orEmpty())
                .toUiTransactions(cardType)
                .also { cachedTxnsByCard[cardId] = it }
        _selectedCard.value = card
        _mainAccent.value = card.gradientStartColor
        _allTransactions.value = txns
        val stats = discountStatsByCard[cardId]
        val monthlyFen = usableMonthlyFen(stats)
        _selectedDiscountMonthlyFen.value = monthlyFen
        _selectedDiscountStatsMonth.value = if (monthlyFen != null) statsMonthLabel(stats) else null
        _filteredTransactions.value = filterTransactions(txns, _currentFilter.value ?: emptySet(), _searchQuery.value ?: "")
        _nfcLog.value = currentSessionNfcLog
        emitStats(txns)
        // 首页迷你图固定用"本周"视图（周一~周日），与统计页默认周期保持一致。
        // 先按本周窗口过滤交易，柱高归一化到本周最大值（与统计页周视图一致）；
        // 若直接用全量交易，历史最高消费日会拉大分母，本周柱整体偏矮
        val (weekStart, weekEnd) = periodWindow("本周", 0)
        _homeWeeklySpending.value =
            computeDailySpending(txns.filter { it.date in weekStart..weekEnd }, "本周", weekStart, weekEnd)
    }

    /** 折扣统计的"本月累计金额"：卡内统计月与当前月一致才采用，避免跨月陈旧值 */
    private fun usableMonthlyFen(stats: TuDiscountStats?): Long? {
        if (stats?.statsMonth == null) return null
        val now = Calendar.getInstance()
        val current = now.get(Calendar.YEAR) * 100 + (now.get(Calendar.MONTH) + 1)
        return if (stats.statsMonth == current) stats.totalFen else null
    }

    /** 折扣统计月份 → "2026-08"；无数据返回 null */
    private fun statsMonthLabel(stats: TuDiscountStats?): String? {
        val m = stats?.statsMonth ?: return null
        return "${m / 100}-${String.format("%02d", m % 100)}"
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
        _categorySpending.value = computeCategorySpending(periodTxns)
        _citySpending.value = computeCitySpending(periodTxns)
        _statsSummary.value = computeStatsSummary(periodTxns, start, end)
    }

    /** 内容去重合并（identity = content_hash；同内容变体合并为一条并保留协议并集，内容不同追加为新 key） */
    private fun mergeCanonical(
        existing: List<CanonicalTransaction>,
        fresh: List<CanonicalTransaction>
    ): List<CanonicalTransaction> {
        val byId = existing.associateBy { it.identity }.toMutableMap()
        val order = existing.map { it.identity }.toMutableList()
        for (t in fresh) {
            val prev = byId[t.identity]
            if (prev == null) {
                byId[t.identity] = t
                order.add(t.identity)
            } else {
                val union = RecordDecoder.unionProtocols(prev, t)
                if (union.isNotEmpty() && union != prev.protocols) {
                    byId[t.identity] = prev.copy(protocols = union)
                }
            }
        }
        return order.map { byId[it]!! }.sortedWith(
            compareByDescending<CanonicalTransaction> { it.date + it.time }.thenByDescending { it.sequence }
        )
    }

    private fun filterTransactions(
        txns: List<UiTransaction>,
        selected: Set<String>,
        query: String
    ): List<UiTransaction> {
        var result = if (selected.isEmpty()) txns else txns.filter { it.transitType in selected }
        val q = query.trim()
        if (q.isNotEmpty()) result = result.filter { it.matchesSearch(q) }
        return result
    }

    /** 全文搜索：查询按空白分词，每个词都要命中（AND），可组合条件（如「前海湾 地铁」）；
     *  索引覆盖中英站/线/城市名、设备码、原始 hex、终端、协议、时间、金额。 */
    private fun UiTransaction.matchesSearch(query: String): Boolean {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val haystack = buildString {
            append(displayDateTime).append(' ')
            append(date).append(' ').append(time).append(' ')
            append(stationName).append(' ')
            append(cityName).append(' ')
            append(lineName).append(' ')
            append(transitType).append(' ')
            append(amountText).append(' ')
            append(balanceAfterText.orEmpty()).append(' ')
            append(hex).append(' ')
            append(journeyHex.orEmpty()).append(' ')
            append(protocols.joinToString(" ")).append(' ')
            append(terminal).append(' ')
            append(deviceCode.orEmpty()).append(' ')
            // 双语站/线/城市名：搜索时中英文都能命中（界面只显示当前语言的名字）
            TransitData.resolutionFor(lineId, stationId)?.let { r ->
                append(r.cityName).append(' ')
                append(r.cityNameEn.orEmpty()).append(' ')
                append(r.lineName.orEmpty()).append(' ')
                append(r.lineNameEn.orEmpty()).append(' ')
                append(r.stationName.orEmpty()).append(' ')
                append(r.stationNameEn.orEmpty()).append(' ')
            }
        }
        return tokens.all { haystack.contains(it, ignoreCase = true) }
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
        // 已构建的 UI 交易名按旧语言缓存 → 全部丢弃，让 emit 按当前语言从 canonical 重派生
        cachedTxnsByCard.clear()
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
        rawRecordsByCard.remove(cardId)
        discountStatsByCard.remove(cardId)
        cachedTxnsByCard.remove(cardId)
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
            _categorySpending.value = emptyList()
            _citySpending.value = emptyList()
            _homeWeeklySpending.value = emptyList()
            _statsSummary.value = StatsSummary(0.0, 0, 0.0)
        } else {
            selectCardByIndex(index.coerceAtMost(updated.size - 1))
        }
        viewModelScope.launch(Dispatchers.IO) {
            UiCache.delete(getApplication(), cardId)
            repo.deleteCard(cardId)
        }
    }

    private fun lastFourFromCanonical(canonical: List<CanonicalTransaction>): String {
        val terminal = canonical.firstOrNull()?.terminal ?: return "----"
        return if (terminal.length >= 4) terminal.takeLast(4) else terminal
    }

    private fun computeTopStations(uiTxns: List<UiTransaction>): List<StationStat> {
        // 以 (城市, 站点) 为 key：不同城市的同名车站（如多地都有"老街站"）分开统计
        val counts = mutableMapOf<Pair<String, String>, Int>()
        for (t in uiTxns) {
            // 充值/无金额旅程事件不是乘车记录，不统计站点
            if (t.typeHex == "02" || t.amountYuan <= 0) continue
            // 去掉末尾的方向箭头（"花地湾 ↑" -> "花地湾"）
            val name = t.stationName.replace(Regex(" [↑↓]$"), "")
            if (name.isNotEmpty() && name != "未知" &&
                !name.startsWith("轨道交通") && !name.startsWith("公共交通") && !name.startsWith("广州公交")) {
                val key = t.cityName to name
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        val max = counts.values.maxOrNull() ?: 1
        return counts.entries.sortedByDescending { it.value }
            .take(5)
            .map { StationStat(it.key.second, it.value, it.value.toFloat() / max, it.key.first) }
    }

    private fun computeTopLines(uiTxns: List<UiTransaction>): List<LineStat> {
        // 以 (城市, 线路) 为 key：不同城市的同名线路（如多地都有"1号线"）分开统计
        val counts = mutableMapOf<Pair<String, String>, Int>()
        val colors = mutableMapOf<Pair<String, String>, String?>()
        for (t in uiTxns) {
            // 充值/无金额旅程事件不是乘车记录，不统计线路
            if (t.typeHex == "02" || t.amountYuan <= 0) continue
            val lineName = t.lineName
            if (lineName.isNotEmpty() && lineName != "—" && lineName != "未知") {
                val key = t.cityName to lineName
                counts[key] = (counts[key] ?: 0) + 1
                // 记录该线路颜色（首个非空值，供药丸着色）
                if (colors[key] == null && !t.lineColor.isNullOrBlank()) colors[key] = t.lineColor
            }
        }
        val max = counts.values.maxOrNull() ?: 1
        return counts.entries.sortedByDescending { it.value }
            .take(5)
            .map { LineStat(it.key.second, it.value, it.value.toFloat() / max, it.key.first, colors[it.key]) }
    }

    /**
     * 日均消费分母：窗口内从起点到今天（含今天）的天数，把没有消费（金额 0）的日期也算上；
     * 过去周期（offset != 0）整段都在今天之前，取整个窗口；无窗口时回退到有记录的天数。
     */
    private fun computeStatsSummary(
        uiTxns: List<UiTransaction>,
        winStart: String,
        winEnd: String
    ): StatsSummary {
        val totalSpending = uiTxns.filter { !it.amountText.startsWith("+") }.sumOf { it.amountYuan }
        // 乘车次数只统计有金额的乘车记录（1E 进站/出站事件 ¥0 不单独算一次）
        val rideCount = uiTxns.filter {
            (it.transitType == "地铁" || it.transitType == "公交") && it.amountYuan > 0
        }.size
        val dayCount = if (winStart.isNotEmpty() && winEnd.isNotEmpty()) {
            val today = dayFmt.format(Date())
            val effectiveEnd = if (winEnd < today) winEnd else today
            if (winStart <= effectiveEnd) daysBetween(winStart, effectiveEnd) else 1
        } else {
            uiTxns.map { it.date }.distinct().size.coerceAtLeast(1)
        }
        return StatsSummary(totalSpending, rideCount, totalSpending / dayCount)
    }

    /** 两个日期（含首尾）之间的天数，yyyy-MM-dd */
    private fun daysBetween(start: String, end: String): Int {
        return try {
            val s = dayFmt.parse(start)!!.time
            val e = dayFmt.parse(end)!!.time
            (((e - s) / (24 * 60 * 60 * 1000)).toInt()) + 1
        } catch (ex: Exception) {
            1
        }
    }

    /** 分类开销：按交通类型聚合有金额的支出（排除充值 +¥ 与 0 金额旅程事件），按金额降序 */
    private fun computeCategorySpending(uiTxns: List<UiTransaction>): List<CategorySpending> {
        val byType = mutableMapOf<String, Double>()
        for (t in uiTxns) {
            if (t.amountText.startsWith("+") || t.amountYuan <= 0) continue
            byType[t.transitType] = (byType[t.transitType] ?: 0.0) + t.amountYuan
        }
        val total = byType.values.sum()
        if (total <= 0) return emptyList()
        return byType.entries.sortedByDescending { it.value }
            .map { (name, amt) -> CategorySpending(name, amt, (amt / total).toFloat(), categoryColor(name)) }
    }

    private fun categoryColor(type: String): Int = when (type) {
        "地铁" -> 0xFF1A73E8.toInt()
        "公交" -> 0xFFFB8C00.toInt()
        "BRT" -> 0xFF8E24AA.toInt()
        "有轨电车" -> 0xFF26A69A.toInt()
        "城际" -> 0xFF5C6BC0.toInt()
        "消费" -> 0xFFE91E63.toInt()
        "便利店" -> 0xFFE91E63.toInt()
        else -> 0xFF78909C.toInt()
    }

    /** 城市开销：按城市聚合有金额的支出（排除充值 +¥ 与 0 金额旅程事件），按金额降序 */
    private fun computeCitySpending(uiTxns: List<UiTransaction>): List<CategorySpending> {
        val byCity = mutableMapOf<String, Double>()
        for (t in uiTxns) {
            if (t.amountText.startsWith("+") || t.amountYuan <= 0) continue
            val city = t.cityName?.takeIf { it.isNotBlank() } ?: "未知"
            byCity[city] = (byCity[city] ?: 0.0) + t.amountYuan
        }
        val total = byCity.values.sum()
        if (total <= 0) return emptyList()
        return byCity.entries.sortedByDescending { it.value }
            .mapIndexed { i, (name, amt) ->
                CategorySpending(name, amt, (amt / total).toFloat(), cityPalette[i % cityPalette.size])
            }
    }

    private val cityPalette = listOf(
        0xFF4CAF50.toInt(),  // 绿
        0xFFFB8C00.toInt(),  // 橙
        0xFF9C27B0.toInt(),  // 紫
        0xFF00BCD4.toInt(),  // 青
        0xFFE91E63.toInt(),  // 玫红
        0xFF3F51B5.toInt(),  // 靛
        0xFF8BC34A.toInt(),  // 浅绿
        0xFF795548.toInt(),  // 棕
        0xFFF44336.toInt(),  // 红
        0xFF607D8B.toInt()   // 蓝灰
    )

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

        // 自定义：按所选起止范围逐日展示；范围较长时按月聚合避免柱过多（与本年视图一致）
        if (winStart.isNotEmpty() && winEnd.isNotEmpty()) {
            val max = dayMap.values.maxOrNull() ?: 1.0
            val dayCount = daysBetween(winStart, winEnd)
            if (dayCount <= 62) {
                return (0 until dayCount).map { i ->
                    val date = addDays(winStart, i)
                    val amt = dayMap[date] ?: 0.0
                    DailySpending(
                        dayLabel = "${date.substring(5, 7).toInt()}/${date.substring(8).toInt()}",
                        amountYuan = amt,
                        barHeightPercent = (amt / max).toFloat(),
                        isToday = date == today,
                        date = date
                    )
                }
            }
            // 长范围按月聚合
            val monthMap = mutableMapOf<String, Double>() // "yyyy-MM" -> 金额
            for ((date, amt) in dayMap) {
                monthMap[date.take(7)] = (monthMap[date.take(7)] ?: 0.0) + amt
            }
            val cal = Calendar.getInstance().apply { time = dayFmt.parse(winStart)!! }
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val endCal = Calendar.getInstance().apply { time = dayFmt.parse(winEnd)!! }
            val months = mutableListOf<DailySpending>()
            while (cal.timeInMillis <= endCal.timeInMillis) {
                val key = dayFmt.format(cal.time).take(7)
                val amt = monthMap[key] ?: 0.0
                months.add(
                    DailySpending(
                        dayLabel = "${key.substring(5).toInt()}月",
                        amountYuan = amt,
                        barHeightPercent = (amt / max).toFloat(),
                        date = "$key-01"
                    )
                )
                cal.add(Calendar.MONTH, 1)
            }
            return months
        }

        // 兜底：近 7 个有消费的天，标签用日期号数
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
