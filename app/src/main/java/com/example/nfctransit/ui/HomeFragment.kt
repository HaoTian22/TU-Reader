package com.example.nfctransit.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentHomeBinding
import com.example.nfctransit.model.DailySpending
import com.example.nfctransit.model.DiscountPolicy
import com.example.nfctransit.model.UiCard
import com.example.nfctransit.model.TransitDirection
import com.example.nfctransit.model.amountLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private fun capturePredictiveBackSnapshot() {
        (activity as? MainActivity)?.capturePredictiveBackSnapshot()
    }

    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    private var cardAdapter: CardPagerAdapter? = null
    private var lastKnownSize = -1
    private var suppressPagerCallback = false
    private var accentColor = 0xFF0066FF.toInt()
    private var isImportingOldData = false

    private val importOldDataLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            isImportingOldData = false
            renderOldDataImportState(importing = false)
            return@registerForActivityResult
        }

        isImportingOldData = true
        renderOldDataImportState(importing = true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val message = viewModel.importDatabase(uri)
                isImportingOldData = false
                renderOldDataImportState(
                    importing = false,
                    message = message,
                    success = true
                )
                context?.let {
                    Toast.makeText(it, "✓ $message", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                isImportingOldData = false
                val detail = e.message?.takeIf { it.isNotBlank() } ?: "文件格式不受支持"
                renderOldDataImportState(
                    importing = false,
                    message = "导入失败：$detail",
                    success = false
                )
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 在首次布局前抑制 onPageSelected，避免初始 onPageSelected(0) 把
        // ViewModel 记录的选中卡片重置成第一张
        suppressPagerCallback = true

        setupOldDataImport()
        setupQuickActions()
        setupCardPager()
        observeViewModel()

        // 从二级页返回时，恢复之前选中的卡片（而不是跳回第一张）
        binding.cardPager.post {
            val idx = viewModel.selectedIndex.value ?: 0
            val count = binding.cardPager.adapter?.itemCount ?: 0
            if (count > 0 && idx in 0 until count) {
                binding.cardPager.setCurrentItem(idx, false)
                updatePageDots(idx)
            }
            // 等恢复布局完成后放开回调，允许用户滑动切换卡片
            binding.cardPager.post { suppressPagerCallback = false }
        }
    }

    /** 首页无数据时直接导入 TransitU / TripReader 数据库备份。 */
    private fun setupOldDataImport() {
        renderOldDataImportState(importing = isImportingOldData)
        binding.btnImportOldData.setOnClickListener {
            if (isImportingOldData) return@setOnClickListener
            renderOldDataImportState(importing = false)
            importOldDataLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun renderOldDataImportState(
        importing: Boolean,
        message: String? = null,
        success: Boolean = false
    ) {
        val currentBinding = _binding ?: return
        currentBinding.btnImportOldData.isEnabled = !importing
        currentBinding.btnImportOldData.text = if (importing) "正在导入…" else "导入旧数据"
        currentBinding.tvImportOldDataStatus.apply {
            text = message.orEmpty()
            setTextColor(
                when {
                    importing -> 0xFF8E8E93.toInt()
                    success -> 0xFF34C759.toInt()
                    else -> 0xFFFF3B30.toInt()
                }
            )
            visibility = if (importing || message != null) View.VISIBLE else View.GONE
            if (importing) text = "正在读取并合并备份数据…"
        }
    }

    private fun setupCardPager() {
        cardAdapter = CardPagerAdapter { index ->
            viewModel.selectCardByIndex(index)
        }
        binding.cardPager.adapter = cardAdapter
        binding.cardPager.offscreenPageLimit = 3

        // 卡片之间的空隙
        val rv = binding.cardPager.getChildAt(0) as? RecyclerView
        rv?.addItemDecoration(CardSpacingDecoration(6.dpToPx()))

        // 注册页面切换回调：切换卡片时刷新对应数据 + 更新指示点
        binding.cardPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageDots(position)
                if (suppressPagerCallback) return
                val vmIndex = viewModel.selectedIndex.value ?: return
                if (vmIndex != position) {
                    viewModel.selectCardByIndex(position)
                }
            }
        })
    }

    /** 根据卡片数量动态构建指示点，高亮当前页 */
    private fun updatePageDots(activeIndex: Int) {
        val count = cardAdapter?.itemCount ?: 0
        binding.pageIndicator.removeAllViews()
        for (i in 0 until count) {
            val dot = View(requireContext()).apply {
                val size = if (i == activeIndex) 8.dpToPx() else 6.dpToPx()
                // 纯 View 无 intrinsic 尺寸，ScrollView 内 UNSPECIFIED 测量下会量成 0；设最小宽高兜底
                setMinimumWidth(size)
                setMinimumHeight(size)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = 3.dpToPx()
                    marginEnd = 3.dpToPx()
                }
                // 圆点用圆角矩形，当前页填充主题色，其余浅灰
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = size / 2f
                    setColor(if (i == activeIndex) accentColor else 0xFFD5D5DA.toInt())
                }
            }
            binding.pageIndicator.addView(dot)
        }
        // 确保新增圆点触发重排：ScrollView 内动态 addView 后容器不重新测量子视图，
        // 纯 View 圆点保持 0 尺寸；forceLayout 强制 onMeasure 重跑
        binding.pageIndicator.requestLayout()
        val pi = binding.pageIndicator
        pi.post {
            if (pi.isAttachedToWindow && pi.measuredWidth > 0) {
                pi.forceLayout()
                pi.measure(
                    View.MeasureSpec.makeMeasureSpec(pi.measuredWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(pi.measuredHeight, View.MeasureSpec.EXACTLY)
                )
                pi.layout(pi.left, pi.top, pi.right, pi.bottom)
            }
        }
    }

    private fun setupQuickActions() {
        // 快捷操作图标用 FontAwesome 字体渲染
        val fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.ttf")
        binding.iconCardInfo.typeface = fa
        binding.iconTransactions.typeface = fa
        binding.iconStats.typeface = fa
        binding.iconMapTrace.typeface = fa
        // 设置齿轮图标也统一用 FontAwesome 渲染
        binding.btnSettings.typeface = fa

        binding.actionCardInfo.setOnClickListener {
            capturePredictiveBackSnapshot()
            findNavController().navigate(R.id.action_home_to_cardInfo)
        }
        binding.actionTransactions.setOnClickListener {
            capturePredictiveBackSnapshot()
            findNavController().navigate(R.id.action_home_to_transactionList)
        }
        binding.actionStats.setOnClickListener {
            // 首页进入统计页默认落在"本周"视图
            viewModel.setStatsPeriod("本周")
            capturePredictiveBackSnapshot()
            findNavController().navigate(R.id.action_home_to_stats)
        }
        binding.actionMapTrace.setOnClickListener {
            capturePredictiveBackSnapshot()
            findNavController().navigate(R.id.action_home_to_mapTrace)
        }
        binding.btnSettings.setOnClickListener {
            capturePredictiveBackSnapshot()
            findNavController().navigate(R.id.action_home_to_settings)
        }
        binding.btnViewAll.setOnClickListener {
            capturePredictiveBackSnapshot()
            findNavController().navigate(R.id.action_home_to_transactionList)
        }
        binding.btnMiniStatsAll.setOnClickListener {
            // 迷你图固定为"本周"，跳转统计页也默认"本周"视图，保持一致
            viewModel.setStatsPeriod("本周")
            capturePredictiveBackSnapshot()
            findNavController().navigate(R.id.action_home_to_stats)
        }
    }

    private fun observeViewModel() {
        // 启动恢复中显示加载态；恢复完成后再按 hasData 切空态/内容，避免重建缓存期间误显示"请靠近交通卡"
        viewModel.isRestoring.observe(viewLifecycleOwner) { updateRootVisibility() }
        viewModel.hasData.observe(viewLifecycleOwner) { updateRootVisibility() }

        // 主题色跟随卡片：快捷操作图标、进度文字/条、迷你图、指示点、查看全部一起变
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            accentColor = accent.toInt()
            binding.iconCardInfo.setTextColor(accentColor)
            binding.iconTransactions.setTextColor(accentColor)
            binding.iconStats.setTextColor(accentColor)
            binding.iconMapTrace.setTextColor(accentColor)
            binding.btnViewAll.setTextColor(accentColor)
            binding.btnMiniStatsAll.setTextColor(accentColor)
            updatePageDots(activeIndexNow())
        }

        viewModel.cards.observe(viewLifecycleOwner) { cards ->
            cardAdapter?.submitList(cards)
            binding.tvCardCount.text = getString(R.string.card_count_format, cards.size)
            val active = if (cards.isEmpty()) 0
                else (viewModel.selectedIndex.value?.coerceIn(0, cards.size - 1) ?: 0)
            updatePageDots(active)
            // 首次启动数据晚于 UI 就绪：布局完成后补渲染一次圆点，避免指示点缺失
            binding.pageIndicator.post { updatePageDots(active) }
        }

        // 新卡读取后自动滑动跳转到新卡片
        viewModel.cardAdded.observe(viewLifecycleOwner) { index ->
            if (index != null) {
                binding.cardPager.post {
                    binding.cardPager.setCurrentItem(index, true)
                }
                viewModel.clearCardAdded()
            }
        }

        viewModel.selectedCard.observe(viewLifecycleOwner) { card ->
            if (card != null) {
                binding.tvBalance.text = "¥${String.format("%.2f", card.balanceYuan)}"
                binding.tvLastRead.text = formatLastRead(card.lastReadAt)
                updateDiscountProgress()
            }
        }

        viewModel.allTransactions.observe(viewLifecycleOwner) { txns ->
            bindRecentTransactions(txns.take(4))
            updateDiscountProgress()
        }

        // 卡内折扣统计（SFI 0x19 / LNT 0x08）就绪后刷新优惠卡片
        viewModel.selectedDiscountMonthlyFen.observe(viewLifecycleOwner) {
            updateDiscountProgress()
        }
        // 优惠标题旁展示统计月份
        viewModel.selectedDiscountStatsMonth.observe(viewLifecycleOwner) {
            binding.tvDiscountMonth.text = it ?: ""
        }

        // 首页迷你图固定用"本周"视图（周一~周日 7 根柱，与固定标签一一对应）
        viewModel.homeWeeklySpending.observe(viewLifecycleOwner) { daily ->
            if (daily.isNotEmpty()) bindMiniChart(daily)
        }
    }

    /** 首页根视图三态切换：恢复中显示加载态；否则按是否有卡数据切空态（请靠近读卡）或内容 */
    private fun updateRootVisibility() {
        val restoring = viewModel.isRestoring.value == true
        val hasData = viewModel.hasData.value == true
        binding.loadingState.visibility = if (restoring) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (!restoring && !hasData) View.VISIBLE else View.GONE
        binding.contentWrapper.visibility = if (!restoring && hasData) View.VISIBLE else View.GONE
    }

    /**
     * 城市累计票款优惠：按卡内折扣统计（广州/佛山 TU 0x19 或岭南通 YCT LNT 0x08）当月累计金额计算，
     * 广州/佛山政策自动识别；未读到折扣统计时隐藏卡片，不做交易累加回退。
     */
    private fun updateDiscountProgress() {
        val txns = viewModel.allTransactions.value ?: return
        val cityName = txns.firstNotNullOfOrNull { t ->
            (t.cityName ?: "").takeIf { it.isNotBlank() && (it.startsWith("广州") || it.startsWith("佛山")) }
        } ?: txns.firstNotNullOfOrNull { t -> (t.cityName ?: "").takeIf { it.isNotBlank() } }

        val policy = DiscountPolicy.policyFor(cityName)
        if (policy == null) {
            binding.discountCard.visibility = View.GONE
            return
        }
        // 当月实际支出票款取自卡内折扣统计（广州/佛山 TU 0x19 或岭南通 YCT LNT 0x08，卡自行维护、无重复计数）。
        // 未读到（尚未读卡/无该扇区）时直接隐藏优惠卡片，不做本月交易累加回退。
        val monthlyFen = viewModel.selectedDiscountMonthlyFen.value
        if (monthlyFen == null) {
            binding.discountCard.visibility = View.GONE
            return
        }
        binding.discountCard.visibility = View.VISIBLE

        // 城市胶囊（广州/佛山）背景用卡片主题色，与快捷图标/按钮保持一致
        val pillBg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * 20
            setColor(accentColor)
        }
        binding.tvDiscountCity.text = "广州"
        binding.tvDiscountCity.background = pillBg
        binding.tvDiscountCityFoshan.text = "佛山"
        binding.tvDiscountCityFoshan.background = pillBg

        binding.tvProgress.text = "¥${String.format("%.2f", monthlyFen / 100.0)}"

        // 广州行：动态进度（前缀"广州："）
        val gz = DiscountPolicy.policyFor("广州") ?: return
        binding.tvDiscountHint.text = "广州：" + hintFor(gz, monthlyFen)

        // 佛山行：动态进度（前缀"佛山："）
        val fs = DiscountPolicy.policyFor("佛山") ?: return
        binding.tvDiscountHintFoshan.text = "佛山：" + hintFor(fs, monthlyFen)
    }

    private fun hintFor(p: DiscountPolicy, monthlyFen: Long): String {
        val t1 = p.tier1ThresholdFen
        val t2 = p.tier2ThresholdFen
        return when {
            monthlyFen < t1 ->
                "当月消费满 ¥${t1 / 100} 享 8 折 · 还差 ¥${String.format("%.2f", (t1 - monthlyFen) / 100.0)}"
            monthlyFen < t2 ->
                "已享 8 折 · 超 ¥${t2 / 100} 部分享 5 折，还差 ¥${String.format("%.2f", (t2 - monthlyFen) / 100.0)}"
            else ->
                "已超 ¥${t2 / 100}，超出部分享 5 折"
        }
    }

    private fun bindRecentTransactions(transactions: List<com.example.nfctransit.model.UiTransaction>) {
        binding.recentTxnContainer.removeAllViews()

        if (transactions.isEmpty()) {
            val emptyView = TextView(requireContext()).apply {
                text = "暂无交易记录"
                setTextColor(0xFF8E8E93.toInt())
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 16.dpToPx(), 0, 16.dpToPx())
            }
            binding.recentTxnContainer.addView(emptyView)
            return
        }

        transactions.forEachIndexed { idx, txn ->
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_home_transaction, binding.recentTxnContainer, false)

            val icon = itemView.findViewById<TextView>(R.id.txnIcon)
            val city = itemView.findViewById<TextView>(R.id.txnCity)
            val type = itemView.findViewById<TextView>(R.id.txnType)
            val amount = itemView.findViewById<TextView>(R.id.txnAmount)
            val dirIcon = itemView.findViewById<TextView>(R.id.txnDirIcon)
            val station = itemView.findViewById<TextView>(R.id.txnStation)
            val time = itemView.findViewById<TextView>(R.id.txnTime)

            // 方向由交易字段统一提供，站名始终保持无标记文本。
            val isEntry = txn.direction == TransitDirection.ENTRY
            val isExit = txn.direction == TransitDirection.EXIT
            val stationText = txn.stationName

            icon.text = txn.icon
            // 第一行胶囊：城市 / 交通类型（两个独立胶囊）；空白或占位符（- / —）时整个隐藏
            val cityText = txn.cityName ?: "未知"
            city.text = cityText
            city.visibility = if (isPlaceholderPill(cityText)) View.GONE else View.VISIBLE
            type.text = txn.transitType
            type.visibility = if (isPlaceholderPill(txn.transitType)) View.GONE else View.VISIBLE
            amount.text = txn.amountText
            amount.setTextColor(
                if (txn.amountText.startsWith("+")) 0xFF34C759.toInt() else 0xFFFF3B30.toInt()
            )

            // 第一行：出入站图标 + 站名
            station.text = stationText.ifEmpty { "未知" }
            if (isEntry || isExit) {
                dirIcon.visibility = View.VISIBLE
                dirIcon.typeface =
                    Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.ttf")
                // 入站 = U+F090 箭头进框（绿），出站 = U+F08B 箭头出框（红）
                dirIcon.text = if (isEntry) "" else ""
                dirIcon.setTextColor(if (isEntry) 0xFF34C759.toInt() else 0xFFFF3B30.toInt())
            } else {
                dirIcon.visibility = View.GONE
            }

            // 第二行：时间（带年）
            time.text = txn.date + " " + txn.time.substring(0, 5)

            itemView.setOnClickListener {
                capturePredictiveBackSnapshot()
                findNavController().navigate(R.id.action_home_to_transactionList)
            }

            binding.recentTxnContainer.addView(itemView)

            // 每项之间用虚线 divider 隔开（带上下间距）
            if (idx < transactions.lastIndex) {
                binding.recentTxnContainer.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        3.dpToPx()
                    ).apply {
                        topMargin = 6.dpToPx()
                        bottomMargin = 6.dpToPx()
                    }
                    setBackgroundResource(R.drawable.bg_divider_dashed)
                })
            }
        }
    }

    /** 药丸无有效内容（空白、"-"、"—" 占位）时整个隐藏 */
    private fun isPlaceholderPill(text: String?): Boolean {
        val t = text?.trim() ?: return true
        return t.isEmpty() || t == "-" || t == "—"
    }

    private fun activeIndexNow(): Int {
        val count = cardAdapter?.itemCount ?: 0
        if (count <= 0) return 0
        return viewModel.selectedIndex.value?.coerceIn(0, count - 1) ?: 0
    }

    /** 用卡片在数据库里记录的最近读卡时间格式化"上次读取"，而非当前时刻 */
    private fun formatLastRead(lastReadAt: Long): String {
        if (lastReadAt <= 0L) return "上次读取：—"
        val cal = Calendar.getInstance().apply { timeInMillis = lastReadAt }
        val now = Calendar.getInstance()
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
        if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        ) {
            return "上次读取：今天 $timeStr"
        }
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
        ) {
            return "上次读取：昨天 $timeStr"
        }
        val datePattern = if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "M月d日" else "yyyy年M月d日"
        return "上次读取：${SimpleDateFormat(datePattern, Locale.getDefault()).format(cal.time)} $timeStr"
    }

    /** 构建迷你消费统计柱状图：样式与统计页周视图一致（数值+圆角渐变柱+星期标签） */
    private fun bindMiniChart(data: List<DailySpending>) {
        val area = binding.chartMiniArea
        area.removeAllViews()

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val recent = data.take(7)
        if (recent.isEmpty()) return

        val max = recent.maxOfOrNull { it.amountYuan } ?: 1.0
        // 图表区可用高 150-16(padTop)-4(padBottom)=130，减去 value(13)+label(14)，最高柱占 ~103dp
        val maxHeight = 103f.dpToPx()
        val showValues = recent.size <= 12

        for (d in recent) {
            val col = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                )
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            }

            val value = TextView(requireContext()).apply {
                text = if (showValues) d.amountLabel() else ""
                setTextColor(0xFF555555.toInt())
                // 7 列窄列里金额放不下（¥350.00 / 全角￥会溢出或换行被 13dp 高度裁掉），字号调小并强制单行
                textSize = 7f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setSingleLine(true)
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    13.dpToPx()
                )
            }

            // 颜色随相对最大值的比例从淡主题色渐变到深主题色，今天的柱固定深主题色
            val lightAccent = ColorUtils.blendARGB(0xFFFFFFFF.toInt(), accentColor, 0.55f)
            val barColor = if (d.isToday) accentColor
                else ColorUtils.blendARGB(lightAccent, accentColor, d.barHeightPercent.coerceIn(0f, 1f))
            val bar = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (d.barHeightPercent * maxHeight).toInt().coerceAtLeast(2)
                ).apply {
                    marginStart = 2.dpToPx()
                    marginEnd = 2.dpToPx()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    val r = 4.dpToPx().toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    setColor(barColor)
                }
            }

            // 第一行标签始终为日期（M/d），下方静态行始终为星期（一~日）
            val parts = d.date.split("-")
            val dateLabel = if (parts.size == 3) "${parts[1].toInt()}/${parts[2].toInt()}" else d.dayLabel
            val label = TextView(requireContext()).apply {
                text = dateLabel
                setTextColor(0xFF8E8E93.toInt())
                textSize = 8f
                gravity = android.view.Gravity.CENTER
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    14.dpToPx()
                )
            }

            col.addView(value)
            col.addView(bar)
            col.addView(label)
            area.addView(col)
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
    private fun Float.dpToPx(): Float = this * resources.displayMetrics.density

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * ViewPager2 卡片适配器：每页一张渐变卡片，宽度相对屏幕居中。
     */
    private inner class CardPagerAdapter(
        private val onSelect: (Int) -> Unit
    ) : RecyclerView.Adapter<CardPagerAdapter.CardHolder>() {

        private var cards: List<UiCard> = emptyList()

        fun submitList(list: List<UiCard>) {
            cards = list
            notifyDataSetChanged()
            // 不做自动选中：新卡跳转由 cardAdded 观察器负责，
            // 视图重建时保持 ViewModel 记录的 selectedIndex，避免跳回末张
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            // 用 FrameLayout 填充整页，卡片背景即页宽（相对屏幕宽度，居中）
            val page = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
            }
            return CardHolder(page)
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            holder.bind(cards[position])
        }

        override fun getItemCount(): Int = cards.size

        inner class CardHolder(private val page: FrameLayout) :
            RecyclerView.ViewHolder(page) {

            init {
                page.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onSelect(pos)
                }
            }

            fun bind(card: UiCard) {
                page.removeAllViews()

                // 渐变背景
                val gradient = android.graphics.drawable.GradientDrawable().apply {
                    colors = intArrayOf(card.gradientStartColor.toInt(), card.gradientEndColor.toInt())
                    orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                    cornerRadius = 16f.dpToPx()
                }
                page.background = gradient

                // 底部文字区
                val textCol = LinearLayout(page.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM or android.view.Gravity.START
                    ).apply { leftMargin = 18.dpToPx(); bottomMargin = 16.dpToPx() }
                }

                val typeLabel = TextView(page.context).apply {
                    text = card.name
                    setTextColor(Color.argb(204, 255, 255, 255))
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val logoResId = when (card.name) {
                    "深圳通" -> R.drawable.shenzhentong
                    "珠海通" -> R.drawable.zhuhaitong
                    "羊城通" -> R.drawable.yangchengtong
                    else -> null
                }

                val numberView = TextView(page.context).apply {
                    // 首页展示完整卡号；读不到卡号时展示尾号
                    text = if (!card.cardNumber.isNullOrEmpty()) card.cardNumber else "•••• ${card.lastFour}"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(0, 3.dpToPx(), 0, 0)
                }

                textCol.addView(typeLabel)
                textCol.addView(numberView)
                // 双协议卡：第二行显示第二个卡号（如 TU 卡号）
                if (!card.secondCardNumber.isNullOrEmpty()) {
                    val secondNumberView = TextView(page.context).apply {
                        text = card.secondCardNumber
                        setTextColor(0xD9FFFFFF.toInt())
                        textSize = 11f
                        typeface = android.graphics.Typeface.MONOSPACE
                        setPadding(0, 2.dpToPx(), 0, 0)
                    }
                    textCol.addView(secondNumberView)
                }
                page.addView(textCol)

                logoResId?.let { logo ->
                    page.addView(ImageView(page.context).apply {
                        setImageResource(logo)
                        contentDescription = card.name
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = FrameLayout.LayoutParams(
                            90.dpToPx(),
                            50.dpToPx(),
                            android.view.Gravity.BOTTOM or android.view.Gravity.END
                        ).apply {
                            rightMargin = 18.dpToPx()
                            bottomMargin = 16.dpToPx()
                        }
                    })
                }

                // 右上角删除按钮
                val deleteBtn = TextView(page.context).apply {
                    text = ""
                    typeface = Typeface.createFromAsset(page.context.assets, "fonts/fa-solid-900.ttf")
                    setTextColor(0xE6FFFFFF.toInt())
                    textSize = 17f
                    gravity = android.view.Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.TOP or android.view.Gravity.END
                    ).apply { topMargin = 4.dpToPx(); rightMargin = 12.dpToPx() }
                    setOnClickListener {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) showDeleteConfirm(pos)
                    }
                }
                page.addView(deleteBtn)
            }
        }
    }

    /** 删除卡片前的确认弹窗 */
    private fun showDeleteConfirm(position: Int) {
        AppDialogs.confirm(
            context = requireContext(),
            title = "删除卡片",
            message = "确定删除这张卡及其全部交易数据吗？此操作无法撤销。",
            confirmLabel = "删除",
            onConfirm = { viewModel.deleteCard(position) }
        )
    }

    /** 在 ViewPager2 卡片之间增加空隙 */
    private inner class CardSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.left = spacing
            outRect.right = spacing
        }
    }
}
