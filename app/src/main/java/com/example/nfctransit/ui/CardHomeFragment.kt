package com.example.nfctransit.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentCardHomeBinding
import com.example.nfctransit.model.CityDiscountUi
import com.example.nfctransit.model.DailySpending
import com.example.nfctransit.model.DiscountPolicy
import com.example.nfctransit.model.TransitDirection
import com.example.nfctransit.model.UiCard
import com.example.nfctransit.model.UiTransaction
import com.example.nfctransit.model.amountLabel
import com.example.nfctransit.util.AppLanguage
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 单卡概览：卡面色头部（卡名·尾号 / 余额 / 上次读取）+ 浮起的快捷操作（交易记录 / 统计 / 轨迹），
 * 下方为本月优惠、最近交易、本周消费三个区块（数据与原首页一致）。「…」菜单进入卡片信息或删除卡片。
 */
class CardHomeFragment : Fragment(R.layout.fragment_card_home) {

    private var _binding: FragmentCardHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    private var accentColor = Palette.ACCENT
    private lateinit var fa: Typeface

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCardHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.otf")
        listOf(
            binding.btnBack, binding.btnMore,
            binding.iconTransactions, binding.iconStats, binding.iconMapTrace
        ).forEach { it.typeface = fa }

        binding.btnBack.setOnClickListener { (activity as? MainActivity)?.animatePredictiveBack() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
        binding.actionTransactions.setOnClickListener { go(R.id.action_cardHome_to_transactionList) }
        binding.btnViewAll.setOnClickListener { go(R.id.action_cardHome_to_transactionList) }
        binding.actionStats.setOnClickListener {
            viewModel.setStatsPeriod("本周")
            go(R.id.action_cardHome_to_stats)
        }
        binding.btnMiniStatsAll.setOnClickListener {
            // 迷你图固定为"本周"，跳转统计页也默认"本周"视图，保持一致
            viewModel.setStatsPeriod("本周")
            go(R.id.action_cardHome_to_stats)
        }
        binding.actionMapTrace.setOnClickListener { go(R.id.action_cardHome_to_mapTrace) }

        // 状态栏衬底只在内容滚动到其下方时出现，静止时让头部渐变完整延伸到状态栏
        binding.statusScrim.alpha = 0f
        binding.cardHomeScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.statusScrim.alpha = if (scrollY > 0) 1f else 0f
        }

        observeViewModel()
    }

    private fun go(actionId: Int) {
        (activity as? MainActivity)?.capturePredictiveBackSnapshot()
        findNavController().navigate(actionId)
    }

    private fun observeViewModel() {
        viewModel.selectedCard.observe(viewLifecycleOwner) { card ->
            if (card != null) bindHeader(card)
        }
        // 主题色跟随卡片：快捷操作图标、优惠胶囊、迷你图、查看全部一起变
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            accentColor = accent.toInt()
            val tile = ColorUtils.blendARGB(Color.WHITE, accentColor, 0.12f)
            listOf(binding.iconTransactions, binding.iconStats, binding.iconMapTrace).forEach {
                it.setTextColor(accentColor)
                it.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(tile)
                }
            }
            binding.btnViewAll.setTextColor(accentColor)
            binding.btnMiniStatsAll.setTextColor(accentColor)
            renderDiscount(viewModel.selectedCityDiscounts.value.orEmpty())
            viewModel.homeWeeklySpending.value?.takeIf { it.isNotEmpty() }?.let { bindMiniChart(it) }
        }
        viewModel.allTransactions.observe(viewLifecycleOwner) { txns ->
            bindRecentTransactions(txns.take(4))
        }
        // 城市优惠展示数据（DiscountRegistry 各方案按卡内月累乘统计解析）就绪后刷新优惠卡片
        viewModel.selectedCityDiscounts.observe(viewLifecycleOwner) { renderDiscount(it) }
        // 迷你图固定用"本周"视图（周一~周日 7 根柱，与固定标签一一对应）
        viewModel.homeWeeklySpending.observe(viewLifecycleOwner) { daily ->
            if (daily.isNotEmpty()) bindMiniChart(daily)
        }
        // 卡片全部删除后返回首页
        viewModel.cards.observe(viewLifecycleOwner) { cards ->
            if (cards.isEmpty()) findNavController().popBackStack(R.id.homeFragment, false)
        }
    }

    override fun onResume() {
        super.onResume()
        // 系统从后台恢复时，动态生成的区域可能没有再收到 LiveData 回调；下一轮消息用当前值补绘一次
        binding.root.post {
            if (_binding?.root?.isAttachedToWindow != true) return@post
            if (viewModel.isRestoring.value == true || viewModel.hasData.value != true) return@post
            viewModel.refreshSelectedCardData()
        }
    }

    // ── 头部 ──

    private fun bindHeader(card: UiCard) {
        val onCard = if (isDarkColor(card.gradientStartColor.toInt())) Color.WHITE else Palette.INK
        fun onCardAlpha(alpha: Int) = ColorUtils.setAlphaComponent(onCard, alpha)
        val chip = onCardAlpha(0x1F)

        binding.header.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(card.gradientStartColor.toInt(), card.gradientEndColor.toInt())
        ).apply {
            val r = 24f.dpToPx()
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        }
        binding.statusScrim.setBackgroundColor(card.gradientStartColor.toInt())
        listOf(binding.btnBack, binding.btnMore).forEach {
            it.setTextColor(onCard)
            it.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(chip)
            }
        }
        binding.tvHeaderPill.apply {
            text = "${card.name} · ${card.lastFour}"
            setTextColor(onCard)
            background = GradientDrawable().apply {
                cornerRadius = 16f.dpToPx()
                setColor(chip)
            }
        }
        binding.tvBalanceLabel.setText(R.string.label_current_balance)
        binding.tvBalanceLabel.setTextColor(onCardAlpha(0xCC))
        binding.tvBalance.text = SpannableStringBuilder(card.balanceFen?.let { "¥${String.format("%.2f", it / 100.0)}" } ?: "—").apply {
            setSpan(AbsoluteSizeSpan(24, true), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(onCardAlpha(0xB3)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.tvBalance.setTextColor(onCard)
        binding.tvLastRead.text = getString(R.string.last_read_format, TimeLabels.absolute(card.lastReadAt))
        binding.tvLastRead.setTextColor(onCardAlpha(0xB3))
    }

    // ── 优惠（原首页） ──

    /**
     * 城市累计票款优惠卡片：数据由 ViewModel 按 DiscountRegistry 配置解析卡内月累乘统计得到
     * （每城一条 [CityDiscountUi]，已过滤"该城无交易/统计非本月"的方案）。
     * 城市胶囊与进度行按方案动态生成，本页不含任何城市特判。
     */
    private fun renderDiscount(uis: List<CityDiscountUi>) {
        val pillRow = binding.discountPillsRow
        val hintColumn = binding.discountHintsColumn
        if (uis.isEmpty()) {
            binding.discountCard.visibility = View.GONE
            return
        }
        binding.discountCard.visibility = View.VISIBLE

        // 标题月份 + 大字号金额取第一个方案（各方案共享同一份统计，多城时金额一致）
        binding.tvDiscountMonth.text = uis.first().monthLabel ?: ""
        binding.tvProgress.text = "¥${String.format("%.2f", uis.first().monthlyFen / 100.0)}"

        pillRow.removeAllViews()
        hintColumn.removeAllViews()
        val density = resources.displayMetrics.density
        uis.forEachIndexed { index, ui ->
            val policy = DiscountPolicy.policyFor(ui.cityZh) ?: return@forEachIndexed
            // 城市胶囊：背景用卡片主题色，与快捷图标/按钮保持一致
            val pill = TextView(requireContext()).apply {
                text = TransitLabels.city(ui.cityZh)
                setTextColor(accentColor)
                textSize = 12f
                background = GradientDrawable().apply {
                    cornerRadius = density * 20
                    setColor(ColorUtils.blendARGB(Color.WHITE, accentColor, 0.12f))
                }
                setPadding((10 * density).toInt(), (3 * density).toInt(), (10 * density).toInt(), (3 * density).toInt())
            }
            pillRow.addView(pill, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) marginStart = (6 * density).toInt() })

            hintColumn.addView(TextView(requireContext()).apply {
                text = getString(R.string.discount_city_hint, TransitLabels.city(ui.cityZh), hintFor(policy, ui.monthlyFen))
                setTextColor(Palette.INK_2)
                textSize = 12f
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() })
        }
    }

    private fun hintFor(p: DiscountPolicy, monthlyFen: Long): String {
        val fmtDiff = { fen: Long -> String.format("%.2f", fen / 100.0) }
        // 折扣率：中文「9 折」，其他语言「10% off」
        val off = { percent: Int -> if (AppLanguage.isChinese()) "${percent / 10}" else "${100 - percent}%" }
        val t = p.tiers
        return if (t.first().minFen == 0L && t.first().discountPercent < 100) {
            // 首乘即打折的政策（杭州）：提示当前档与下一档门槛
            when {
                monthlyFen < t[1].minFen -> getString(R.string.discount_tiered_next,
                    off(t[0].discountPercent), t[1].minFen / 100, off(t[1].discountPercent), fmtDiff(t[1].minFen - monthlyFen))
                monthlyFen < t[2].minFen -> getString(R.string.discount_tiered_next,
                    off(t[1].discountPercent), t[2].minFen / 100, off(t[2].discountPercent), fmtDiff(t[2].minFen - monthlyFen))
                else -> getString(R.string.discount_tiered_max, t[2].minFen / 100, off(t[2].discountPercent))
            }
        } else {
            // 满 X 元后的部分才打折的政策（广州/佛山）
            val t1 = t[1].minFen
            val t2 = t[2].minFen
            when {
                monthlyFen < t1 -> getString(R.string.discount_threshold_first,
                    t1 / 100, off(t[1].discountPercent), fmtDiff(t1 - monthlyFen))
                monthlyFen < t2 -> getString(R.string.discount_threshold_next,
                    off(t[1].discountPercent), t2 / 100, off(t[2].discountPercent), fmtDiff(t2 - monthlyFen))
                else -> getString(R.string.discount_threshold_max, t2 / 100, off(t[2].discountPercent))
            }
        }
    }

    // ── 最近交易 ──

    /** 每行：类型图标 / 站名 + 「城市 · 类型 · 线路 · 时间」/ 金额 + 交易后余额；行间细线对齐文字起点 */
    private fun bindRecentTransactions(transactions: List<UiTransaction>) {
        val container = binding.recentTxnContainer
        container.removeAllViews()

        if (transactions.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                setText(R.string.empty_transactions)
                setTextColor(Palette.INK_3)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 24.dpToPx(), 0, 24.dpToPx())
            })
            return
        }

        transactions.forEachIndexed { idx, txn ->
            if (idx > 0) {
                container.addView(View(requireContext()).apply { setBackgroundColor(Palette.LINE) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        marginStart = 68.dpToPx()  // 16 边距 + 40 图标 + 12 间距：与文字左对齐
                    })
            }
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_card_txn, container, false)

            val icon = row.findViewById<TextView>(R.id.txnIcon)
            icon.typeface = fa
            icon.text = txn.icon
            Palette.applyTransitIcon(icon, icon, txn.transitType)

            row.findViewById<TextView>(R.id.txnStation).text =
                TransitLabels.station(txn.stationName.ifBlank { txn.transitType })
                    .ifBlank { getString(R.string.unknown) }

            // 入站 = U+F090 箭头进框，出站 = U+F08B 箭头出框
            row.findViewById<TextView>(R.id.txnDirIcon).apply {
                when (txn.direction) {
                    TransitDirection.ENTRY -> { typeface = fa; text = ""; visibility = View.VISIBLE }
                    TransitDirection.EXIT -> { typeface = fa; text = ""; visibility = View.VISIBLE }
                    else -> visibility = View.GONE
                }
            }

            // 城市 · 类型 · 线路（与站名不同时）· 时间；占位符（空 / - / —）不出现
            val line = txn.lineName.takeUnless { isPlaceholderPill(it) || it == txn.stationName }
            row.findViewById<TextView>(R.id.txnMeta).text = listOfNotNull(
                txn.cityName.takeUnless { isPlaceholderPill(it) }?.let(TransitLabels::city),
                txn.transitType.takeUnless { isPlaceholderPill(it) || it == txn.stationName }?.let(TransitLabels::type),
                line,
                "${txn.date.drop(5)} ${txn.time.take(5)}"
            ).joinToString(" · ")

            row.findViewById<TextView>(R.id.txnAmount).apply {
                val hasAmount = txn.amountText.startsWith("+") || txn.amountText.startsWith("-")
                text = txn.amountText
                setTextColor(if (hasAmount) Palette.amountColor(txn.amountText) else Palette.INK_3)
                if (!hasAmount) textSize = 13f
            }
            row.findViewById<TextView>(R.id.txnBalance).apply {
                text = txn.balanceAfterYuan?.let { getString(R.string.balance_after_format, String.format("%.2f", it)) }.orEmpty()
                visibility = if (txn.balanceAfterYuan == null) View.GONE else View.VISIBLE
            }

            row.setOnClickListener { go(R.id.action_cardHome_to_transactionList) }
            container.addView(row)
        }
    }

    /** 迷你图星期标签：中文用单字（一…日），其他语言用缩写（Mon…Sun） */
    private fun weekdayLabel(d: DailySpending): String {
        val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d.date) }.getOrNull()
            ?: return d.dayLabel
        val locale = AppLanguage.locale()
        return SimpleDateFormat(if (AppLanguage.isChinese()) "EEEEE" else "EEE", locale).format(date)
    }

    /** 无有效内容（空白、"-"、"—" 占位） */
    private fun isPlaceholderPill(text: String?): Boolean {
        val t = text?.trim() ?: return true
        return t.isEmpty() || t == "-" || t == "—"
    }

    // ── 本周消费 ──

    /**
     * 本周七天柱状图：有消费的柱显示金额并按相对高度从浅到深着主题色；
     * 无消费的日子只画一条细基线、不标 ¥0；今天的星期标签加粗着色。标题旁显示本周合计。
     */
    private fun bindMiniChart(data: List<DailySpending>) {
        val area = binding.chartMiniArea
        area.removeAllViews()
        val week = data.take(7)
        if (week.isEmpty()) return

        val total = week.sumOf { it.amountYuan }
        binding.tvWeekTotal.text = getString(R.string.week_total, String.format("%.2f", total))

        val maxBar = 84f.dpToPx()
        val lightAccent = ColorUtils.blendARGB(Color.WHITE, accentColor, 0.45f)

        week.forEachIndexed { i, d ->
            val col = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            val spent = d.amountYuan > 0
            col.addView(TextView(requireContext()).apply {
                text = if (spent) d.amountLabel() else ""
                setTextColor(Palette.INK_2)
                textSize = 10f
                setSingleLine(true)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 16.dpToPx()))
            col.addView(View(requireContext()).apply {
                background = GradientDrawable().apply {
                    cornerRadius = if (spent) 6f.dpToPx() else 1f.dpToPx()
                    setColor(
                        when {
                            !spent -> Palette.LINE
                            d.isToday -> accentColor
                            else -> ColorUtils.blendARGB(lightAccent, accentColor, d.barHeightPercent.coerceIn(0f, 1f))
                        }
                    )
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (spent) (d.barHeightPercent * maxBar).toInt().coerceAtLeast(4.dpToPx()) else 2.dpToPx()
            ).apply {
                marginStart = 6.dpToPx()
                marginEnd = 6.dpToPx()
            })
            col.addView(TextView(requireContext()).apply {
                text = weekdayLabel(d)
                textSize = 12f
                gravity = Gravity.CENTER
                if (d.isToday) {
                    setTextColor(accentColor)
                    typeface = Typeface.DEFAULT_BOLD
                } else {
                    setTextColor(Palette.INK_3)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 24.dpToPx()).apply {
                topMargin = 6.dpToPx()
            })
            area.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    // ── 「…」菜单 ──

    private fun showMoreMenu() {
        val card = viewModel.selectedCard.value ?: return
        AppDialogs.options(
            context = requireContext(),
            title = card.name,
            options = listOf(getString(R.string.card_info_title), getString(R.string.delete_card)),
            accentColor = accentColor
        ) { which ->
            when (which) {
                0 -> go(R.id.action_cardHome_to_cardInfo)
                1 -> AppDialogs.confirm(
                    context = requireContext(),
                    title = getString(R.string.delete_card),
                    message = getText(R.string.delete_card_message),
                    confirmLabel = getString(R.string.action_delete)
                ) {
                    val index = viewModel.selectedIndex.value ?: return@confirm
                    viewModel.deleteCard(index)
                    findNavController().popBackStack(R.id.homeFragment, false)
                }
            }
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
    private fun Float.dpToPx(): Float = this * resources.displayMetrics.density

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
