package com.example.nfctransit.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
    private var accentColor = 0xFF0066FF.toInt()

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

        // 回到当前周期按钮的 ↻ 图标用 FontAwesome 渲染（汉字部分自动回退系统字体）
        binding.cardTrend.btnBackCurrent.typeface =
            Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.ttf")

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
        // 图表右上角日期范围文字：自定义周期下点击同样弹出日期范围选择
        binding.cardTrend.tvPeriodRange.setOnClickListener {
            if (selectedSegment == "自定义") showCustomRangeDialog()
        }
        viewModel.customRange.observe(viewLifecycleOwner) { (start, end) ->
            binding.tvCustomStart.text = start.ifEmpty { "开始日期" }
            binding.tvCustomEnd.text = end.ifEmpty { "结束日期" }
        }

        // 主题色跟随卡片：◀/▶/返回按钮、badge、柱状图、排行条、选中 chip 一起变
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            accentColor = accent.toInt()
            binding.btnBack.setTextColor(accentColor)
            binding.cardTrend.btnPrevPeriod.setTextColor(accentColor)
            binding.cardTrend.btnNextPeriod.setTextColor(accentColor)
            binding.cardTrend.btnBackCurrent.setTextColor(accentColor)
            binding.cardSummary.sumRideCount.setTextColor(accentColor)
            binding.tvCardBadge.setTextColor(accentColor)
            updateCardBadgeBg()
            updateSegmentSelection()
        }

        // 上一期/下一期 / 回到当前
        binding.cardTrend.btnPrevPeriod.setOnClickListener { viewModel.shiftPeriod(-1) }
        binding.cardTrend.btnNextPeriod.setOnClickListener { viewModel.shiftPeriod(1) }
        binding.cardTrend.btnBackCurrent.setOnClickListener { viewModel.backToCurrentPeriod() }
        viewModel.periodOffset.observe(viewLifecycleOwner) { offset ->
            binding.cardTrend.btnBackCurrent.visibility =
                if (offset != 0) View.VISIBLE else View.GONE
        }

        viewModel.selectedCard.observe(viewLifecycleOwner) { card ->
            if (card != null) {
                binding.tvCardBadge.text = "${card.name} · ${card.lastFour}"
                binding.tvCardBadge.setTextColor(card.gradientStartColor.toInt())
            }
        }

        viewModel.periodRange.observe(viewLifecycleOwner) { range ->
            binding.cardTrend.tvPeriodRange.text = range
        }

        viewModel.statsSummary.observe(viewLifecycleOwner) { summary ->
            binding.cardSummary.sumTotalSpending.text =
                "¥${String.format("%.2f", summary.totalSpendingYuan)}"
            binding.cardSummary.sumRideCount.text = "${summary.rideCount} 次"
            binding.cardSummary.sumAvgDaily.text =
                "¥${String.format("%.2f", summary.avgDailyYuan)}"
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

        viewModel.categorySpending.observe(viewLifecycleOwner) { items ->
            bindDonutChart(binding.cardCategories.donutChart, binding.cardCategories.categoryLegend, items)
        }

        viewModel.citySpending.observe(viewLifecycleOwner) { items ->
            bindDonutChart(binding.cardCities.donutChart, binding.cardCities.cityLegend, items)
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
        // 卡信息标签背景用主题色淡色填充（保留 10dp 圆角）
        val bg = ColorUtils.blendARGB(0xFFFFFFFF.toInt(), accentColor, 0.12f)
        binding.cardBadge.background = GradientDrawable().apply {
            cornerRadius = dpToPx(10).toFloat()
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
                    cornerRadius = dpToPx(20).toFloat()
                    setColor(accentColor)
                }
                v.setTextColor(0xFFFFFFFF.toInt())
            } else {
                v.setBackgroundResource(R.drawable.bg_chip_default)
                v.setTextColor(0xFF555555.toInt())
            }
        }
        binding.customRangeRow.visibility =
            if (selectedSegment == "自定义") View.VISIBLE else View.GONE
        // 自定义周期下起止由日期范围决定，没有上/下一期，隐藏图表标题旁的两个箭头
        val customMode = selectedSegment == "自定义"
        binding.cardTrend.btnPrevPeriod.visibility = if (customMode) View.INVISIBLE else View.VISIBLE
        binding.cardTrend.btnNextPeriod.visibility = if (customMode) View.INVISIBLE else View.VISIBLE
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
        container.removeAllViews()
        if (data.isEmpty()) return

        // 柱子很多时（如整月 30+ 根）不显示每根柱子的金额文字，避免拥挤
        val showValues = data.size <= 12
        // 图表区可用高 180-20(padTop)-4(padBottom)=156，减去 value(13)+label(14)，
        // 最高柱占 129dp 正好顶满图表区，"归一化到视图 max"才直观
        val maxHeight = 129
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
                setTextColor(0xFF555555.toInt())
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
            val displayLabel = if (data.size > 15) d.dayLabel.removeSuffix("号") else d.dayLabel
            val label = TextView(requireContext()).apply {
                text = displayLabel
                setTextColor(0xFF8E8E93.toInt())
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

        // 手指划过图表即显示对应柱的金额弹窗（无需精确点中窄柱）：
        // DOWN/MOVE 跟随手指更新弹窗；DOWN 时禁止父级 ScrollView 拦截，纵向拖动不会滚动页面，
        // 松开（UP）后恢复拦截，页面可继续滚动；CANCEL（仍被系统接管时）收起弹窗。
        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    container.requestDisallowInterceptTouchEvent(true)
                    val idx = columnIndexAt(event.x, container)
                    if (idx in data.indices) {
                        showPopupFor(data[idx], container.getChildAt(idx) as LinearLayout)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = columnIndexAt(event.x, container)
                    if (idx in data.indices) {
                        showPopupFor(data[idx], container.getChildAt(idx) as LinearLayout)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    container.requestDisallowInterceptTouchEvent(false)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    container.requestDisallowInterceptTouchEvent(false)
                    popup.visibility = View.GONE
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
        return if (d.dayLabel.endsWith("月")) {
            "${d.dayLabel} $amount"
        } else if (d.date.isNotEmpty()) {
            val parts = d.date.split("-")
            "${parts[0]}.${parts[1].toInt()}.${parts[2].toInt()}\n$amount"
        } else {
            amount
        }
    }

    /** 环形图：中心总开销 + 右侧图例（分类/城市共用） */
    private fun bindDonutChart(donut: DonutChartView, legend: LinearLayout, items: List<CategorySpending>) {
        val total = items.sumOf { it.amountYuan }
        donut.setSegments(items, "¥${String.format("%.2f", total)}")
        bindSpendingLegend(legend, items)
    }

    private fun bindSpendingLegend(container: LinearLayout, items: List<CategorySpending>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            val emptyView = TextView(requireContext()).apply {
                text = "暂无支出数据，请先读取交通卡"
                setTextColor(0xFF8E8E93.toInt())
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
                text = item.name
                textSize = 13f
                setTextColor(0xFF1A1A1A.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val amount = TextView(requireContext()).apply {
                text = "¥${String.format("%.2f", item.amountYuan)}"
                textSize = 12f
                setTextColor(0xFF555555.toInt())
                typeface = Typeface.MONOSPACE
            }

            val percent = TextView(requireContext()).apply {
                text = "${(item.percent * 100).roundToInt()}%"
                textSize = 12f
                setTextColor(0xFF8E8E93.toInt())
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
                text = "暂无数据，请先读取交通卡"
                setTextColor(0xFF8E8E93.toInt())
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, dpToPx(16), 0, dpToPx(16))
            }
            container.addView(emptyView)
            return
        }

        for (item in items) {
            val (nameViews, count, barPercent) = when (item) {
                is StationStat -> Triple(buildStationViews(item), item.count, item.barWidthPercent)
                is LineStat -> Triple(buildLinePills(item), item.count, item.barWidthPercent)
                else -> continue
            }

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            nameViews.forEach { row.addView(it) }

            // 宽度用 weight 按占比填满行内剩余空间：最高项≈满宽，其余按 count 占比缩放（归一化）
            val barWeight = barPercent.coerceIn(0.05f, 1f)
            val bar = View(requireContext()).apply {
                setBackgroundColor(barColor)
                layoutParams = LinearLayout.LayoutParams(0, 8, barWeight).apply {
                    marginStart = 10
                    marginEnd = 10
                }
            }

            val countView = TextView(requireContext()).apply {
                text = "${count} 次"
                textSize = 12f
                setTextColor(0xFF555555.toInt())
                typeface = android.graphics.Typeface.MONOSPACE
            }

            // 剩余空间（1 - 占比）由尾部占位吸收，条与占比精确对应
            val filler = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, (1f - barWeight).coerceAtLeast(0f))
            }

            row.addView(bar)
            row.addView(countView)
            row.addView(filler)
            container.addView(row)
        }
    }

    /** 车站行：城市药丸 + 站名文本（城市未知时不加药丸） */
    private fun buildStationViews(station: StationStat): List<TextView> {
        val views = mutableListOf<TextView>()
        if (station.cityName.isNotBlank()) views.add(rankPill(station.cityName))
        views.add(rankNameView(station.name))
        return views
    }

    /** 车站排行名称（保持原文本样式） */
    private fun rankNameView(name: String): TextView = TextView(requireContext()).apply {
        text = name
        textSize = 13f
        setTextColor(0xFF1A1A1A.toInt())
    }

    /** 线路行药丸：先城市后线路；线路药丸按数据库线路颜色着色（无颜色保持灰色），与交易列表一致 */
    private fun buildLinePills(line: LineStat): List<TextView> {
        val pills = mutableListOf<TextView>()
        if (line.cityName.isNotBlank()) pills.add(rankPill(line.cityName))
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
