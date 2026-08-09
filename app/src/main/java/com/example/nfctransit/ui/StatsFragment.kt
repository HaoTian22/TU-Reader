package com.example.nfctransit.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentStatsBinding
import com.example.nfctransit.model.DailySpending
import com.example.nfctransit.model.LineStat
import com.example.nfctransit.model.StationStat

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

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        setupSegments()

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
                binding.tvCardBadge.text = "${card.cardType} · ${card.lastFour}"
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
                text = if (showValues) "¥${d.amountYuan.toInt()}" else ""
                setTextColor(0xFF555555.toInt())
                textSize = 9f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
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

            // 点击该柱时，在柱上方弹出信息框（复用卡片根部的单个 overlay）
            col.setOnClickListener {
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
                trendBinding.root.getLocationInWindow(rootLoc)
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

            container.addView(col)
        }
    }

    private fun buildPopupText(d: DailySpending): String {
        val amount = "¥${d.amountYuan.toInt()}"
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
            val (name, count, barPercent) = when (item) {
                is StationStat -> Triple(item.name, item.count, item.barWidthPercent)
                is LineStat -> Triple(item.name, item.count, item.barWidthPercent)
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

            val nameView = TextView(requireContext()).apply {
                text = name
                textSize = 13f
                setTextColor(0xFF1A1A1A.toInt())
            }

            val barWidth = (barPercent * 100).toInt()
            val bar = View(requireContext()).apply {
                setBackgroundColor(barColor)
                layoutParams = LinearLayout.LayoutParams(barWidth, 8).apply {
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

            val filler = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }

            row.addView(nameView)
            row.addView(bar)
            row.addView(countView)
            row.addView(filler)
            container.addView(row)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
