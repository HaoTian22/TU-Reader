package com.example.nfctransit.ui

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentStatsBinding
import com.example.nfctransit.model.CategorySpending
import com.example.nfctransit.model.CustomChartConfig
import com.example.nfctransit.model.CustomChartData
import com.example.nfctransit.model.CustomChartItem
import com.example.nfctransit.model.StatsChartType
import com.example.nfctransit.model.StatsDimension
import com.example.nfctransit.model.StatsMetric
import com.example.nfctransit.util.AppLanguage
import java.text.SimpleDateFormat
import java.util.Calendar
import com.example.nfctransit.model.DailySpending
import com.example.nfctransit.model.LineStat
import com.example.nfctransit.model.StationStat
import com.example.nfctransit.model.amountLabel
import kotlin.math.roundToInt

class StatsFragment : Fragment(R.layout.fragment_stats) {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    /** 当前卡片主题色（跟随卡片渐变起点），默认蓝 */
    private var accentColor = Palette.ACCENT

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { (activity as? MainActivity)?.animatePredictiveBack() }

        // 回到当前周期按钮的 ↻ 图标与前后周期箭头用 FontAwesome 渲染（汉字部分自动回退系统字体）
        val fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.otf")
        binding.btnBackCurrent.setIconLabel(fa, "\uF2EA", getString(R.string.stats_now)) // fa-rotate-left
        binding.btnPrevPeriod.typeface = fa
        binding.btnNextPeriod.typeface = fa
        setupCustomChart(fa)

        // 点击页面任意非柱体区域（图表空白、汇总卡、排行卡等）时收起柱状图小弹窗。
        // 监听挂在 ScrollView 的内容容器 contentContainer（普通 LinearLayout，走 View 默认
        // onTouchEvent，会调用 OnTouchListener）上：点击柱体时柱（clickable）消费 DOWN，
        // 容器 onTouch 不触发，弹窗保持；点击其他区域时 DOWN 穿透到容器自身，onTouch 触发收起。
        binding.contentContainer.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val popup = binding.cardTrend.chartPopup
                if (popup.visibility == View.VISIBLE) {
                    popup.visibility = View.GONE
                }
            }
            false
        }

        setupSegments()

        // 自定义日期范围：起止两个字段都点开同一个 MD3 日期范围弹窗，改完实时重算
        binding.tvCustomStart.setOnClickListener { showCustomRangeDialog() }
        binding.tvCustomEnd.setOnClickListener { showCustomRangeDialog() }
        viewModel.customRange.observe(viewLifecycleOwner) { (start, end) ->
            binding.tvCustomStart.text = start.ifEmpty { getString(R.string.label_start_date) }
            binding.tvCustomEnd.text = end.ifEmpty { getString(R.string.label_end_date) }
        }

        // 主题色跟随卡片：◀/▶/返回按钮、badge、柱状图、排行条、选中 chip 一起变
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            accentColor = accent.toInt()
            binding.btnBack.setTextColor(accentColor)
            binding.btnPrevPeriod.setTextColor(accentColor)
            binding.btnNextPeriod.setTextColor(accentColor)
            binding.btnBackCurrent.setTextColor(accentColor)
            binding.tvCardBadge.setTextColor(accentColor)
            updateCardBadgeBg()
            updateSegmentSelection()
            viewModel.customChartConfig.value?.let { bindCustomOptions(it) }
            viewModel.customChart.value?.let { bindCustomChart(it) }
        }

        // 上一期/下一期 / 回到当前
        binding.btnPrevPeriod.setOnClickListener { viewModel.shiftPeriod(-1) }
        binding.btnNextPeriod.setOnClickListener { viewModel.shiftPeriod(1) }
        binding.btnBackCurrent.setOnClickListener { viewModel.backToCurrentPeriod() }
        viewModel.periodOffset.observe(viewLifecycleOwner) { offset ->
            binding.btnBackCurrent.visibility =
                if (offset != 0) View.VISIBLE else View.GONE
        }

        viewModel.selectedCard.observe(viewLifecycleOwner) { card ->
            if (card != null) {
                binding.tvCardBadge.text = MetaText.cardBadge(card.name, card.lastFour)
                binding.tvCardBadge.setTextColor(Palette.accentFor(card.gradientStartColor))
            }
        }

        viewModel.periodRange.observe(viewLifecycleOwner) { range ->
            binding.tvPeriodRange.text = range
        }

        viewModel.statsSummary.observe(viewLifecycleOwner) { summary ->
            binding.cardSummary.sumTotalSpending.text =
                "${summary.currency}${String.format("%.2f", summary.totalSpendingYuan)}"
            binding.cardSummary.sumRideCount.text =
                resources.getQuantityString(R.plurals.rides_count, summary.rideCount, summary.rideCount)
            binding.cardSummary.sumAvgDaily.text =
                "${summary.currency}${String.format("%.2f", summary.avgDailyYuan)}"
        }

        viewModel.topStations.observe(viewLifecycleOwner) { stations ->
            bindRankList(binding.cardStations.stationListContainer, stations, accentColor)
        }

        viewModel.topLines.observe(viewLifecycleOwner) { lines ->
            bindRankList(binding.cardLines.lineListContainer, lines, accentColor)
        }

        viewModel.dailySpending.observe(viewLifecycleOwner) { daily ->
            bindBarChart(daily)
        }

        // 多币种卡：币种切换胶囊（金额类统计只统计所选币种）
        viewModel.statsCurrencies.observe(viewLifecycleOwner) { bindCurrencyChips() }
        viewModel.statsCurrency.observe(viewLifecycleOwner) { bindCurrencyChips() }

        viewModel.categorySpending.observe(viewLifecycleOwner) { items ->
            bindDonutChart(binding.cardCategories.donutChart, binding.cardCategories.categoryLegend, items, TransitLabels::type)
        }

        viewModel.citySpending.observe(viewLifecycleOwner) { items ->
            bindDonutChart(binding.cardCities.donutChart, binding.cardCities.cityLegend, items, TransitLabels::city)
        }

        viewModel.customChartConfig.observe(viewLifecycleOwner) { bindCustomOptions(it) }
        viewModel.customChart.observe(viewLifecycleOwner) { bindCustomChart(it) }
    }

    // ── 自定义图表（Google Analytics 探索报告风格） ──

    private val dimensionOptions = listOf(
        StatsDimension.DATE to R.string.stats_dim_date,
        StatsDimension.WEEKDAY to R.string.stats_dim_weekday,
        StatsDimension.HOUR to R.string.stats_dim_hour,
        StatsDimension.MONTH to R.string.stats_dim_month,
        StatsDimension.TYPE to R.string.stats_dim_type,
        StatsDimension.CITY to R.string.stats_dim_city,
        StatsDimension.LINE to R.string.stats_dim_line,
        StatsDimension.STATION to R.string.stats_dim_station
    )
    private val metricOptions = listOf(
        StatsMetric.SPENDING to R.string.stats_metric_spending,
        StatsMetric.RIDES to R.string.stats_metric_rides,
        StatsMetric.AVG_FARE to R.string.stats_metric_avg_fare
    )
    /** 图表类型切换：FontAwesome 图标 + 无障碍描述 */
    private val chartTypeOptions = listOf(
        Triple(StatsChartType.LINE, "", R.string.stats_chart_line),     // fa-chart-line
        Triple(StatsChartType.COLUMN, "", R.string.stats_chart_column), // fa-chart-column
        Triple(StatsChartType.BAR, "", R.string.stats_chart_bar),       // fa-chart-bar
        Triple(StatsChartType.DONUT, "", R.string.stats_chart_donut)    // fa-chart-pie
    )
    /** 数据表默认显示的行数，超出折叠 */
    private val tableCollapsedRows = 10
    private var tableExpanded = false
    private var faTypeface: Typeface? = null

    private fun metricLabel(metric: StatsMetric) = getString(metricOptions.first { it.first == metric }.second)
    private fun dimensionLabel(dimension: StatsDimension) = getString(dimensionOptions.first { it.first == dimension }.second)

    private fun setupCustomChart(fa: Typeface) {
        faTypeface = fa
        val card = binding.cardCustom
        card.chevDimension.typeface = fa
        card.chevMetric.typeface = fa
        card.pickDimension.setOnClickListener { anchor ->
            val config = viewModel.customChartConfig.value ?: return@setOnClickListener
            showPicker(anchor, dimensionOptions, config.dimension) {
                tableExpanded = false
                viewModel.setCustomChartConfig(config.copy(dimension = it))
            }
        }
        card.pickMetric.setOnClickListener { anchor ->
            val config = viewModel.customChartConfig.value ?: return@setOnClickListener
            showPicker(anchor, metricOptions, config.metric) { viewModel.setCustomChartConfig(config.copy(metric = it)) }
        }
    }

    /** 下拉选择：与反馈表单城市选择框同款候选列表（白色圆角外壳 + 48dp 行），宽度对齐选择框 */
    private fun <T> showPicker(anchor: View, options: List<Pair<T, Int>>, selected: T, onPick: (T) -> Unit) {
        val popup = ListPopupWindow(requireContext())
        popup.anchorView = anchor
        popup.width = anchor.width
        popup.verticalOffset = dpToPx(6)
        popup.isModal = true
        popup.setBackgroundDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.bg_dropdown_popup))
        popup.setAdapter(DropdownChoiceAdapter(requireContext(), options, label = { getString(it.second) }, keys = null))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            if (options[position].first != selected) onPick(options[position].first)
        }
        popup.show()
    }

    private fun bindCustomOptions(config: CustomChartConfig) {
        val card = binding.cardCustom
        card.tvDimension.text = dimensionLabel(config.dimension)
        card.tvMetric.text = metricLabel(config.metric)
        card.chartTypeGroup.removeAllViews()
        chartTypeOptions.forEach { (type, icon, res) ->
            // 折线只适用于时间类维度，环形图不适用于平均票价：不可用时置灰
            val available = config.isChartTypeAvailable(type)
            val selected = type == config.effectiveChartType
            card.chartTypeGroup.addView(TextView(requireContext()).apply {
                typeface = faTypeface
                text = icon
                textSize = 14f
                gravity = Gravity.CENTER
                contentDescription = getString(res)
                setTextColor(
                    when {
                        selected -> 0xFFFFFFFF.toInt()
                        available -> Palette.INK_2
                        else -> Palette.INK_DISABLED
                    }
                )
                // 未选中也给透明胶囊背景：全局水波纹按背景形状取蒙版
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(999).toFloat()
                    setColor(if (selected) accentColor else Color.TRANSPARENT)
                }
                isEnabled = available
                if (available) setOnClickListener { viewModel.setCustomChartConfig(config.copy(chartType = type)) }
            }, LinearLayout.LayoutParams(dpToPx(40), dpToPx(30)))
        }
    }

    private fun bindCustomChart(data: CustomChartData) {
        val card = binding.cardCustom
        val config = data.config
        val type = config.effectiveChartType
        val empty = data.totals.isEmpty
        card.tvScoreLabel.text = metricLabel(config.metric)
        card.tvScoreValue.text = metricText(data, config.metric, data.total)
        card.customEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        val axis = type == StatsChartType.LINE || type == StatsChartType.COLUMN
        card.axisChart.visibility = if (!empty && axis) View.VISIBLE else View.GONE
        card.donutRow.visibility = if (!empty && type == StatsChartType.DONUT) View.VISIBLE else View.GONE
        card.barList.visibility = if (!empty && type == StatsChartType.BAR) View.VISIBLE else View.GONE

        val labeler = CustomChartLabeler(data)
        bindDataTable(data, labeler)
        if (empty) return
        when (type) {
            StatsChartType.LINE, StatsChartType.COLUMN -> card.axisChart.setData(
                data.items.map {
                    AxisChartView.Point(it.value, labeler.short(it), labeler.full(it) + "  " + metricText(data, config.metric, it.value))
                },
                if (type == StatsChartType.LINE) AxisChartView.Style.LINE else AxisChartView.Style.COLUMN,
                accentColor
            ) { axisText(data, it) }
            StatsChartType.DONUT -> card.donutChart.setSegments(
                data.items.filter { it.value > 0 }
                    .map { CategorySpending(it.key, it.value, it.percent, it.color, data.currency) },
                metricText(data, config.metric, data.total),
                metricLabel(config.metric)
            )
            StatsChartType.BAR -> {
                card.barList.removeAllViews()
                data.items.forEachIndexed { index, item ->
                    card.barList.addView(rankBlock(
                        index, customNameViews(item, config.dimension, labeler),
                        metricText(data, config.metric, item.value), item.barPercent, accentColor
                    ))
                }
            }
        }
    }

    /**
     * 数据表（GA 风格）：表头 = 维度名 + 三个指标（点击切换图表指标，当前指标着主题色并带排序图标）；
     * 合计行；分组行显示三个指标，当前指标下附占合计百分比；超过 10 行折叠。
     * 环形图时行首圆点与图表颜色对应。
     */
    private fun bindDataTable(data: CustomChartData, labeler: CustomChartLabeler) {
        val table = binding.cardCustom.dataTable
        table.removeAllViews()
        if (data.totals.isEmpty) {
            table.visibility = View.GONE
            return
        }
        table.visibility = View.VISIBLE
        val config = data.config
        val columns = listOf(StatsMetric.RIDES, StatsMetric.SPENDING, StatsMetric.AVG_FARE)
        val showDots = config.effectiveChartType == StatsChartType.DONUT

        // 表头：同数据库查询结果表头（FILL 底、medium 字重、灰字），圆角行
        table.addView(tableRow(
            nameView = tableText(dimensionLabel(config.dimension), 11f, Palette.INK_3, bold = true),
            cells = columns.map { m ->
                val active = m == config.metric
                tableText(metricLabel(m), 11f, if (active) accentColor else Palette.INK_3, bold = true, end = true).apply {
                    if (active) faTypeface?.let { fa ->
                        text = SpannableString("${metricLabel(m)} ").apply { // fa-sort-down
                            setSpan(IconFontSpan(fa), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    // 窄屏表头可折成两行，不截断
                    isSingleLine = false
                    maxLines = 2
                    if (!active) setOnClickListener { viewModel.setCustomChartConfig(config.copy(metric = m)) }
                }
            },
            verticalPad = 9
        ).apply {
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(10).toFloat()
                setColor(Palette.FILL)
            }
        })

        // 合计行
        table.addView(tableRow(
            nameView = tableText(getString(R.string.stats_custom_table_total), 13f, Palette.INK, bold = true),
            cells = columns.map { m -> tableText(metricText(data, m, data.totals.of(m)), 12f, Palette.INK, bold = true, end = true) },
            verticalPad = 11
        ))

        val rows = data.tableItems
        val visible = if (tableExpanded) rows else rows.take(tableCollapsedRows)
        visible.forEach { item ->
            table.addView(tableDivider())
            val name = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (showDots) name.addView(View(requireContext()).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(item.color)
                }
            }, LinearLayout.LayoutParams(dpToPx(8), dpToPx(8)).apply { marginEnd = dpToPx(6) })
            name.addView(tableText(labeler.full(item), 13f, Palette.INK).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            table.addView(tableRow(
                nameView = name,
                cells = columns.map { m ->
                    val value = item.metrics.of(m)
                    val text = metricText(data, m, value)
                    val total = data.totals.of(m)
                    // 当前指标（可相加）下附占合计百分比，如 GA 的 "(12.3%)"
                    if (m == config.metric && m != StatsMetric.AVG_FARE && total > 0) {
                        LinearLayout(requireContext()).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.END
                            addView(tableText(text, 12f, Palette.INK, end = true))
                            addView(tableText(String.format("%.1f%%", value / total * 100), 10f, Palette.INK_3, end = true))
                        }
                    } else {
                        tableText(text, 12f, Palette.INK_2, end = true)
                    }
                },
                verticalPad = 10
            ))
        }

        if (rows.size > tableCollapsedRows) {
            table.addView(tableDivider())
            table.addView(tableText(
                if (tableExpanded) getString(R.string.stats_custom_show_less)
                else getString(R.string.stats_custom_show_all, rows.size),
                13f, accentColor, bold = true
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(12), 0, dpToPx(4))
                setOnClickListener {
                    tableExpanded = !tableExpanded
                    viewModel.customChart.value?.let { bindDataTable(it, CustomChartLabeler(it)) }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** 表格行：按比例分宽（名称列 1.4 份，三个指标列各 1 份右对齐），窄屏（~360dp）下名称列也不被挤没 */
    private fun tableRow(nameView: View, cells: List<View>, verticalPad: Int): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), dpToPx(verticalPad), dpToPx(8), dpToPx(verticalPad))
            addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
            cells.forEach { cell ->
                addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dpToPx(4) })
            }
        }

    private fun tableText(text: String, size: Float, color: Int, bold: Boolean = false, end: Boolean = false): TextView =
        TextView(requireContext()).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (end) {
                gravity = Gravity.END
                setSingleLine(true)
            }
        }

    private fun tableDivider(): View = View(requireContext()).apply {
        setBackgroundColor(Palette.LINE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1))
    }

    /** 条形图名称：线路/车站沿用排行的城市药丸 + 线路色药丸，其余为纯文本 */
    private fun customNameViews(item: CustomChartItem, dimension: StatsDimension, labeler: CustomChartLabeler): List<View> {
        if (item.key == CustomChartItem.OTHER_KEY) return listOf(rankNameView(labeler.full(item)))
        return when (dimension) {
            StatsDimension.LINE -> buildLinePills(LineStat(item.key, 0, 0f, item.cityName, item.lineColor))
            StatsDimension.STATION -> buildStationViews(StationStat(item.key, 0, 0f, item.cityName))
            else -> listOf(rankNameView(labeler.full(item)).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
    }

    /** 指标数值：次数为整数，金额保留两位 */
    private fun metricText(data: CustomChartData, metric: StatsMetric, value: Double): String =
        if (metric == StatsMetric.RIDES) value.roundToInt().toString()
        else "${data.currency}${String.format("%.2f", value)}"

    /** 坐标轴刻度：去掉多余小数，金额 ≥1000 用 k */
    private fun axisText(data: CustomChartData, value: Double): String {
        val prefix = if (data.config.metric == StatsMetric.RIDES) "" else data.currency
        val (v, suffix) = if (value >= 1000) value / 1000 to "k" else value to ""
        return prefix + String.format("%.2f", v).trimEnd('0').trimEnd('.') + suffix
    }

    /** 自定义图表分组键 → 当前界面语言的显示名（数据键保持中文/数字，显示时翻译） */
    private inner class CustomChartLabeler(data: CustomChartData) {
        private val dimension = data.config.dimension
        private val multiCity = data.tableItems.map { it.cityName }.filter { it.isNotBlank() }.distinct().size > 1
        private val multiYear = dimension == StatsDimension.MONTH &&
            data.items.map { it.key.take(4) }.distinct().size > 1
        private val locale = AppLanguage.locale()

        /** 表格/气泡/条形图用的完整名 */
        fun full(item: CustomChartItem): String = when {
            item.key == CustomChartItem.OTHER_KEY -> getString(R.string.stats_custom_other)
            dimension == StatsDimension.HOUR -> "${item.key.padStart(2, '0')}:00"
            dimension == StatsDimension.DATE || dimension == StatsDimension.MONTH -> item.key
            dimension == StatsDimension.LINE || dimension == StatsDimension.STATION -> {
                val name = if (dimension == StatsDimension.STATION) TransitLabels.station(item.key) else item.key
                if (multiCity && item.cityName.isNotBlank()) "${TransitLabels.city(item.cityName)} $name" else name
            }
            else -> short(item)
        }

        /** 坐标轴标签用的短名 */
        fun short(item: CustomChartItem): String {
            if (item.key == CustomChartItem.OTHER_KEY) return getString(R.string.stats_custom_other)
            return when (dimension) {
                StatsDimension.TYPE -> TransitLabels.type(item.key)
                StatsDimension.CITY ->
                    if (item.key.isBlank()) getString(R.string.unknown) else TransitLabels.city(item.key)
                StatsDimension.LINE -> item.key
                StatsDimension.STATION -> TransitLabels.station(item.key)
                StatsDimension.WEEKDAY -> {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                        add(Calendar.DAY_OF_YEAR, item.key.toIntOrNull() ?: 0)
                    }
                    SimpleDateFormat("EEE", locale).format(cal.time)
                }
                StatsDimension.HOUR -> item.key.padStart(2, '0')
                StatsDimension.MONTH -> if (multiYear) item.key else {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.MONTH, (item.key.substring(5, 7).toIntOrNull() ?: 1) - 1)
                    }
                    SimpleDateFormat("MMM", locale).format(cal.time)
                }
                StatsDimension.DATE -> item.key.substring(5)
            }
        }
    }

    private fun bindCurrencyChips() {
        val row = binding.currencyRow
        val currencies = viewModel.statsCurrencies.value.orEmpty()
        row.removeAllViews()
        row.visibility = if (currencies.size > 1) View.VISIBLE else View.GONE
        if (currencies.size <= 1) return
        val selected = viewModel.statsCurrency.value
        currencies.forEachIndexed { i, currency ->
            row.addView(TextView(requireContext(), null, 0, R.style.StatsPeriodChip).apply {
                text = currency.trim()
                if (currency == selected) {
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(999).toFloat()
                        setColor(accentColor)
                    }
                    setTextColor(0xFFFFFFFF.toInt())
                    typeface = Typeface.DEFAULT_BOLD
                } else {
                    setBackgroundResource(R.drawable.bg_chip_default)
                    setTextColor(Palette.INK_2)
                }
                setOnClickListener { viewModel.setStatsCurrency(currency) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dpToPx(8)
            })
        }
    }

    private var selectedSegment = "本周"

    private fun setupSegments() {
        val segments = mapOf(
            "本周" to binding.segWeek,
            "本月" to binding.segMonth,
            "本年" to binding.segYear,
            "自定义" to binding.segCustom
        )

        segments.forEach { (name, view) ->
            view.setOnClickListener {
                selectedSegment = name
                updateSegmentSelection()
                viewModel.setStatsPeriod(name)
            }
        }
    }

    private fun updateCardBadgeBg() {
        // 卡信息标签背景用主题色淡色填充（胶囊形）
        val bg = ColorUtils.blendARGB(0xFFFFFFFF.toInt(), accentColor, 0.12f)
        binding.cardBadge.background = GradientDrawable().apply {
            cornerRadius = dpToPx(999).toFloat()
            setColor(bg)
        }
    }

    private fun updateSegmentSelection() {
        val segments = mapOf(
            "本周" to binding.segWeek,
            "本月" to binding.segMonth,
            "本年" to binding.segYear,
            "自定义" to binding.segCustom
        )
        segments.forEach { (n, v) ->
            if (n == selectedSegment) {
                // 用圆角 GradientDrawable 填充主题色，避免 setBackgroundColor 丢失圆角
                v.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(999).toFloat()
                    setColor(accentColor)
                }
                v.setTextColor(0xFFFFFFFF.toInt())
                v.typeface = Typeface.DEFAULT_BOLD
            } else {
                v.setBackgroundResource(R.drawable.bg_chip_default)
                v.setTextColor(Palette.INK_2)
                v.typeface = Typeface.DEFAULT
            }
        }
        binding.customRangeRow.visibility =
            if (selectedSegment == "自定义") View.VISIBLE else View.GONE
        // 自定义周期下起止由日期范围行决定，没有上/下一期，整行周期导航隐藏
        binding.periodNavRow.visibility =
            if (selectedSegment == "自定义") View.GONE else View.VISIBLE
    }

    /** 弹出 MD3 风格日期范围弹窗（主题色跟随卡片），确定后实时重算统计 */
    private fun showCustomRangeDialog() {
        val current = viewModel.customRange.value ?: ("" to "")
        DateRangeDialog.show(
            requireContext(),
            accentColor,
            current.first.ifEmpty { null },
            current.second.ifEmpty { null }
        ) { start, end ->
            viewModel.setCustomRange(start, end)
        }
    }

    private fun bindBarChart(data: List<DailySpending>) {
        val trendBinding = binding.cardTrend
        val container = trendBinding.chartContainer
        val grid = trendBinding.chartGrid
        container.removeAllViews()
        // 图表区可用高 180-20(padTop)-4(padBottom)=156，减去 value(13)+label(14)，
        // 最高柱占 129dp 正好顶满图表区，"归一化到视图 max"才直观
        val maxHeight = 129
        // 满高对应的金额：由任一非零柱反推（ViewModel 的 max 可能取自窗口外日期），
        // 网格虚线按同一比例画，基线 = 底部 padding(4) + 标签(14)
        val scaleMax = data.firstOrNull { it.barHeightPercent > 0f }
            ?.let { it.amountYuan / it.barHeightPercent } ?: 0.0
        grid.setScale(scaleMax, data.firstOrNull()?.currency ?: "", dpToPx(maxHeight), dpToPx(18))
        container.setPaddingRelative(grid.gutterWidth, container.paddingTop, 0, container.paddingBottom)
        if (data.isEmpty()) return

        // 柱子很多时（如整月 30+ 根）不显示每根柱子的金额文字，避免拥挤
        val showValues = data.size <= 12
        // 弹窗放在卡片根部 overlay（不受窄列宽度约束）
        val popup = trendBinding.chartPopup

        for (d in data) {
            // 单列：金额文字(固定高) + 圆角柱 + 底部标签(固定高)，整体贴底，
            // value/label 固定高度保证所有列同构，柱子底部严格对齐
            val col = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                // 等宽列：宽度用 0dp + weight=1f 平分图表区，高度铺满
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                )
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }

            val value = TextView(requireContext()).apply {
                text = if (showValues) d.amountLabel() else ""
                setTextColor(Palette.INK_2)
                // 窄列放不下金额（年视图每列 ~25dp），调小并强制单行，避免被裁
                textSize = 6f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setSingleLine(true)
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(13)
                )
            }

            // 颜色随相对最大值的比例从淡主题色渐变到深主题色，今天的柱固定深主题色
            val lightAccent = ColorUtils.blendARGB(0xFFFFFFFF.toInt(), accentColor, 0.55f)
            val barColor = if (d.isToday) accentColor
                else ColorUtils.blendARGB(lightAccent, accentColor, d.barHeightPercent.coerceIn(0f, 1f))
            val bar = View(requireContext()).apply {
                // 柱体左右留边距，柱子之间产生空隙（标签仍全宽均匀分布）
                // maxHeight 是 dp 值，必须转成 px，否则高密度屏上柱子只有几成高
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (d.barHeightPercent * dpToPx(maxHeight)).toInt().coerceAtLeast(2)
                ).apply {
                    marginStart = dpToPx(2)
                    marginEnd = dpToPx(2)
                }
                // 顶部圆角，底部直角，柱从底部向上生长
                background = GradientDrawable().apply {
                    val r = dpToPx(4).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    setColor(barColor)
                }
            }

            // 稠密视图（整月 30+ 列）每列很窄，"14号" 三字符放不下被裁，去掉"号"只显示数字
            val displayLabel = if (data.size > 15) d.compactLabel else d.dayLabel
            val label = TextView(requireContext()).apply {
                text = displayLabel
                setTextColor(Palette.INK_3)
                textSize = if (data.size > 15) 7f else 8f
                gravity = Gravity.CENTER
                // 横排不换行，窄列下也不会折行显示
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(14)
                )
            }

            col.addView(value)
            col.addView(bar)
            col.addView(label)
            container.addView(col)
        }

        // 手势按方向分流：纵向拖动交给页面滚动；横向拖动锁定图表、弹窗跟随手指；轻点显示所点柱的弹窗。
        // DOWN 时不禁止父级拦截，纵向位移超过 touchSlop 时 ScrollView 自行接管（本视图收到 CANCEL）；
        // 横向位移先超过 touchSlop 时再禁止拦截，之后的纵向抖动不会滚动页面。
        val touchSlop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var scrubbing = false
        fun revealAt(x: Float) {
            val idx = columnIndexAt(x, container)
            if (idx in data.indices) showPopupFor(data[idx], container.getChildAt(idx) as LinearLayout)
        }
        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    scrubbing = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scrubbing) {
                        val dx = kotlin.math.abs(event.x - downX)
                        val dy = kotlin.math.abs(event.y - downY)
                        if (dx > touchSlop && dx > dy) {
                            scrubbing = true
                            container.parent.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    if (scrubbing) revealAt(event.x)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 未进入横向拖动 = 轻点（纵向拖动早已被 ScrollView 接管，走不到这里）
                    if (!scrubbing) revealAt(event.x)
                    container.parent.requestDisallowInterceptTouchEvent(false)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 被 ScrollView 接管（纵向滚动）：不显示弹窗
                    container.parent.requestDisallowInterceptTouchEvent(false)
                    if (scrubbing) popup.visibility = View.GONE
                    scrubbing = false
                    true
                }
                else -> true
            }
        }
    }

    private fun showPopupFor(d: DailySpending, col: LinearLayout) {
        val popup = binding.cardTrend.chartPopup
        popup.text = buildPopupText(d)
        popup.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupW = popup.measuredWidth
        val popupH = popup.measuredHeight
        // popup 挂在 trendRoot 上，用窗口坐标把柱中心换算成相对 trendRoot 的位置
        val colLoc = IntArray(2)
        val rootLoc = IntArray(2)
        col.getLocationInWindow(colLoc)
        binding.cardTrend.root.getLocationInWindow(rootLoc)
        val px = (colLoc[0] + col.width / 2 - popupW / 2) - rootLoc[0]
        // 柱所在列顶往下移一些（图表区有 20dp padTop，数值文字也占 13dp），
        // 弹窗底部贴近数值文字上方，避免浮到标题行上方
        val py = colLoc[1] - popupH - dpToPx(4) - rootLoc[1] + dpToPx(16)
        (popup.layoutParams as FrameLayout.LayoutParams).apply {
            this.leftMargin = px
            this.topMargin = py
        }
        if (popup.visibility != View.VISIBLE) popup.visibility = View.VISIBLE
    }

    /** 手指横向位置落在第几列（按列左边界判断） */
    private fun columnIndexAt(x: Float, container: LinearLayout): Int {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (x >= child.left && x < child.right) return i
        }
        return -1
    }

    private fun buildPopupText(d: DailySpending): String {
        val amount = d.amountLabel()
        // 年视图的柱代表月份，用 "1月" 更直观；其余显示日期
        return if (d.isMonth) {
            "${d.dayLabel} $amount"
        } else if (d.date.isNotEmpty()) {
            val parts = d.date.split("-")
            "${parts[0]}.${parts[1].toInt()}.${parts[2].toInt()}\n$amount"
        } else {
            amount
        }
    }

    /** 环形图：中心总开销 + 右侧图例（分类/城市共用） */
    private fun bindDonutChart(
        donut: DonutChartView,
        legend: LinearLayout,
        items: List<CategorySpending>,
        label: (String) -> String
    ) {
        val total = items.sumOf { it.amountYuan }
        donut.setSegments(items, "${items.firstOrNull()?.currency ?: viewModel.statsCurrency.value ?: "¥"}${String.format("%.2f", total)}")
        bindSpendingLegend(legend, items, label)
    }

    private fun bindSpendingLegend(container: LinearLayout, items: List<CategorySpending>, label: (String) -> String) {
        container.removeAllViews()
        if (items.isEmpty()) {
            val emptyView = TextView(requireContext()).apply {
                setText(R.string.stats_empty_spending)
                setTextColor(Palette.INK_3)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(16), 0, dpToPx(16))
            }
            container.addView(emptyView)
            return
        }
        for (item in items) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(10) }
            }

            val dot = View(requireContext()).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(item.color)
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply {
                    marginEnd = dpToPx(8)
                }
            }

            val name = TextView(requireContext()).apply {
                text = label(item.name)
                textSize = 13f
                setTextColor(Palette.INK)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val amount = TextView(requireContext()).apply {
                text = "${item.currency}${String.format("%.2f", item.amountYuan)}"
                textSize = 13f
                setTextColor(Palette.INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }

            val percent = TextView(requireContext()).apply {
                text = "${(item.percent * 100).roundToInt()}%"
                textSize = 12f
                setTextColor(Palette.INK_3)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dpToPx(6) }
            }

            row.addView(dot)
            row.addView(name)
            row.addView(amount)
            row.addView(percent)
            container.addView(row)
        }
    }

    private fun bindRankList(
        container: LinearLayout,
        items: List<Any>,
        barColor: Int
    ) {
        container.removeAllViews()

        if (items.isEmpty()) {
            val emptyView = TextView(requireContext()).apply {
                setText(R.string.stats_empty)
                setTextColor(Palette.INK_3)
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, dpToPx(16), 0, dpToPx(16))
            }
            container.addView(emptyView)
            return
        }

        items.forEachIndexed { index, item ->
            val (nameViews, count, barPercent) = when (item) {
                is StationStat -> Triple(buildStationViews(item), item.count, item.barWidthPercent)
                is LineStat -> Triple(buildLinePills(item), item.count, item.barWidthPercent)
                else -> return@forEachIndexed
            }

            container.addView(rankBlock(
                index, nameViews, resources.getQuantityString(R.plurals.rides_count, count, count), barPercent, barColor
            ))
        }
    }

    /** 排行行：上行 名次 + 名称（药丸）+ 数值；下行 按占比缩放的圆角条（最高项满宽） */
    private fun rankBlock(
        index: Int,
        nameViews: List<View>,
        valueText: String,
        barPercent: Float,
        barColor: Int
    ): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(requireContext()).apply {
            text = "${index + 1}"
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (index == 0) barColor else Palette.INK_3)
            layoutParams = LinearLayout.LayoutParams(dpToPx(20), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dpToPx(8) }
        })
        val names = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameViews.forEach { names.addView(it) }
        row.addView(names)
        row.addView(TextView(requireContext()).apply {
            text = valueText
            textSize = 13f
            setTextColor(Palette.INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        val barWeight = barPercent.coerceIn(0.03f, 1f)
        val track = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(999).toFloat()
                setColor(Palette.FILL)
            }
            weightSum = 1f
        }
        track.addView(View(requireContext()).apply {
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(999).toFloat()
                setColor(barColor)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, barWeight)
        })

        val block = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) topMargin = dpToPx(14) }
        }
        block.addView(row)
        block.addView(track, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(6)
        ).apply {
            topMargin = dpToPx(6)
            marginStart = dpToPx(28)
        })
        return block
    }

    /** 车站行：城市药丸 + 站名文本（城市未知时不加药丸） */
    private fun buildStationViews(station: StationStat): List<TextView> {
        val views = mutableListOf<TextView>()
        if (station.cityName.isNotBlank()) views.add(rankPill(TransitLabels.city(station.cityName)))
        views.add(rankNameView(TransitLabels.station(station.name)))
        return views
    }

    /** 车站排行名称（保持原文本样式） */
    private fun rankNameView(name: String): TextView = TextView(requireContext()).apply {
        text = name
        textSize = 13f
        setTextColor(Palette.INK)
    }

    /** 线路行药丸：先城市后线路；线路药丸按数据库线路颜色着色（无颜色保持灰色），与交易列表一致 */
    private fun buildLinePills(line: LineStat): List<TextView> {
        val pills = mutableListOf<TextView>()
        if (line.cityName.isNotBlank()) pills.add(rankPill(TransitLabels.city(line.cityName)))
        pills.add(rankPill(line.name, line.lineColor))
        return pills
    }

    private fun rankPill(text: String, color: String? = null): TextView = requireContext().linePill(text, color).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dpToPx(6) }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
