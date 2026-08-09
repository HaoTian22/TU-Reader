package com.example.nfctransit.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentHomeBinding
import com.example.nfctransit.model.DailySpending
import com.example.nfctransit.model.UiCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    private var cardAdapter: CardPagerAdapter? = null
    private var lastKnownSize = -1
    private var suppressPagerCallback = false
    private var accentColor = 0xFF0066FF.toInt()

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
    }

    private fun setupQuickActions() {
        // 快捷操作图标用 FontAwesome 字体渲染
        val fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.ttf")
        binding.iconTransactions.typeface = fa
        binding.iconStats.typeface = fa
        binding.iconMapTrace.typeface = fa
        // 设置齿轮图标也统一用 FontAwesome 渲染
        binding.btnSettings.typeface = fa

        binding.actionTransactions.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_transactionList)
        }
        binding.actionStats.setOnClickListener {
            // 首页进入统计页默认落在"本周"视图
            viewModel.setStatsPeriod("本周")
            findNavController().navigate(R.id.action_home_to_stats)
        }
        binding.actionMapTrace.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_mapTrace)
        }
        binding.btnSettings.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_settings)
        }
        binding.btnViewAll.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_transactionList)
        }
    }

    private fun observeViewModel() {
        viewModel.hasData.observe(viewLifecycleOwner) { hasData ->
            binding.emptyState.visibility = if (hasData) View.GONE else View.VISIBLE
            binding.contentWrapper.visibility = if (hasData) View.VISIBLE else View.GONE
        }

        // 主题色跟随卡片：快捷操作图标、进度文字/条、迷你图、指示点、查看全部一起变
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            accentColor = accent.toInt()
            binding.iconTransactions.setTextColor(accentColor)
            binding.iconStats.setTextColor(accentColor)
            binding.iconMapTrace.setTextColor(accentColor)
            binding.tvProgress.setTextColor(accentColor)
            binding.progressFill.setBackgroundColor(accentColor)
            binding.btnViewAll.setTextColor(accentColor)
            applyMiniChartTheme()
            updatePageDots(activeIndexNow())
        }

        viewModel.cards.observe(viewLifecycleOwner) { cards ->
            cardAdapter?.submitList(cards)
            binding.tvCardCount.text = getString(R.string.card_count_format, cards.size)
            val active = if (cards.isEmpty()) 0
                else (viewModel.selectedIndex.value?.coerceIn(0, cards.size - 1) ?: 0)
            updatePageDots(active)
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
                binding.tvLastRead.text =
                    "上次读取：今天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}"
                updateDiscountProgress()
            }
        }

        viewModel.allTransactions.observe(viewLifecycleOwner) { txns ->
            bindRecentTransactions(txns.take(4))
            updateDiscountProgress()
        }

        // 首页迷你图固定用"本周"视图（周一~周日 7 根柱，与固定标签一一对应）
        viewModel.homeWeeklySpending.observe(viewLifecycleOwner) { daily ->
            if (daily.isNotEmpty()) bindMiniChart(daily)
        }
    }

    private fun updateDiscountProgress() {
        val txns = viewModel.allTransactions.value ?: return
        val thisMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        val monthlyRides = txns.count {
            (it.transitType == "地铁" || it.transitType == "公交") && it.date.startsWith(thisMonth)
        }
        val target = 12
        val progress = monthlyRides.coerceAtMost(target)
        binding.tvProgress.text = "$progress/$target 次"

        val totalWidth = binding.root.width - 32.dpToPx()
        val fillWidth = if (totalWidth > 0) (totalWidth * progress / target) else 0
        val params = binding.progressFill.layoutParams
        params.width = fillWidth
        binding.progressFill.layoutParams = params
        binding.progressFill.setBackgroundColor(accentColor)

        val remaining = target - monthlyRides
        binding.tvDiscountHint.text = if (remaining > 0) {
            "还差 $remaining 次即可享受每次 ¥1.00 优惠"
        } else {
            "本月已达到优惠次数上限"
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

            // 站名末尾的方向箭头换成 FontAwesome 图标
            val isEntry = txn.stationName.endsWith("↓")
            val isExit = txn.stationName.endsWith("↑")
            // 去掉方向箭头后的完整站名（"1号线 体育中心"），站名只取站点部分
            val cleanStation = txn.stationName.replace(Regex(" [↑↓]$"), "")
            val parts = cleanStation.split(" ", limit = 2)
            val stationText = if (parts.size == 2) parts[1] else cleanStation

            icon.text = txn.icon
            // 第一行胶囊：城市 / 交通类型（两个独立胶囊）
            city.text = txn.cityName ?: "未知"
            type.text = txn.transitType
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

    private var miniChartCount = 0
    private var miniChartTodayIdx = -1

    private fun activeIndexNow(): Int {
        val count = cardAdapter?.itemCount ?: 0
        if (count <= 0) return 0
        return viewModel.selectedIndex.value?.coerceIn(0, count - 1) ?: 0
    }

    private fun applyMiniChartTheme() {
        val bars = listOf(
            binding.chartBar0, binding.chartBar1, binding.chartBar2,
            binding.chartBar3, binding.chartBar4, binding.chartBar5, binding.chartBar6
        )
        for (i in 0 until bars.size) {
            if (i < miniChartCount) {
                bars[i].setBackgroundColor(if (i == miniChartTodayIdx) accentColor else 0xFFCCCCCC.toInt())
            } else {
                bars[i].setBackgroundColor(0xFFE5E5EA.toInt())
            }
        }
    }

    private fun bindMiniChart(data: List<DailySpending>) {
        val bars = listOf(
            binding.chartBar0, binding.chartBar1, binding.chartBar2,
            binding.chartBar3, binding.chartBar4, binding.chartBar5, binding.chartBar6
        )
        // data 就是本周（周一~周日）7 根柱，与下方固定标签一一对应，不再过滤零消费天
        val recent = data.take(7)
        // maxHeight 是 dp 值，必须转成 px，否则高密度屏上柱子只有几成高
        val maxHeight = 70f.dpToPx()
        // 以本周最大值为基准归一化，零消费天柱高为 0
        val miniMax = recent.maxOfOrNull { it.amountYuan } ?: 1.0
        miniChartTodayIdx = recent.indexOfFirst { it.isToday }
        miniChartCount = recent.size

        for (i in 0 until bars.size) {
            val params = bars[i].layoutParams
            if (i < recent.size) {
                val d = recent[i]
                params.height = ((d.amountYuan / miniMax) * maxHeight).toInt().coerceAtLeast(2)
                // 除今天外也随高度在浅主题色~主题色之间渐变，保证柱子清晰可见
                val color = if (i == miniChartTodayIdx) accentColor
                    else ColorUtils.blendARGB(accentColor, 0xFFFFFFFF.toInt(), 0.75f)
                bars[i].setBackgroundColor(color)
            } else {
                params.height = 4
                bars[i].setBackgroundColor(0xFFE5E5EA.toInt())
            }
            bars[i].layoutParams = params
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

                val typeView = TextView(page.context).apply {
                    text = card.cardType
                    setTextColor(Color.argb(204, 255, 255, 255))
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }

                val numberView = TextView(page.context).apply {
                    text = "•••• ${card.lastFour}"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(0, 3.dpToPx(), 0, 0)
                }

                textCol.addView(typeView)
                textCol.addView(numberView)
                page.addView(textCol)

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
        AlertDialog.Builder(requireContext())
            .setTitle("删除卡片")
            .setMessage("确定删除这张卡及其全部交易数据吗？此操作无法撤销。")
            .setPositiveButton("删除") { _, _ -> viewModel.deleteCard(position) }
            .setNegativeButton("取消", null)
            .show()
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
