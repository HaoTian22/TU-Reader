package com.example.nfctransit.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentTransactionListBinding
import com.example.nfctransit.model.UiTransaction
import com.example.nfctransit.model.TransitDirection

class TransactionListFragment : Fragment(R.layout.fragment_transaction_list) {

    private var _binding: FragmentTransactionListBinding? = null
    private val binding get() = _binding!!

    private fun capturePredictiveBackSnapshot() {
        (activity as? MainActivity)?.capturePredictiveBackSnapshot()
    }

    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    /** 多选筛选中已勾选的类别（空 = 全部显示） */
    private val selectedFilters = mutableSetOf<String>()
    private var accentColor = Palette.ACCENT

    private val adapter = TransactionAdapter()

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROW = 1
    }

    /** 离开页面（进详情/切后台）时保存的滚动位置，返回后恢复一次；null = 无需恢复 */
    private var pendingScrollState: Parcelable? = null
    private var scrollRestored = false

    /** 点击「查看上下文」后，等待完整列表提交，再滚动到该交易。 */
    private var pendingContextTransactionId: Int? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransactionListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { (activity as? MainActivity)?.animatePredictiveBack() }

        binding.transactionList.layoutManager = LinearLayoutManager(requireContext())
        binding.transactionList.adapter = adapter
        binding.transactionList.addItemDecoration(GroupDividerDecoration())

        // 主题色跟随卡片：返回按钮、badge、漏斗图标一起变
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            accentColor = accent.toInt()
            binding.btnBack.setTextColor(accentColor)
            binding.tvCardBadge.setTextColor(accentColor)
            binding.filterButton.setTextColor(accentColor)
            updateCardBadgeBg()
            updateFilterButton()
        }

        // Update card badge from ViewModel
        viewModel.selectedCard.observe(viewLifecycleOwner) { card ->
            if (card != null) {
                binding.tvCardBadge.text = "${card.name} · ${card.lastFour}"
                binding.tvCardBadge.setTextColor(Palette.accentFor(card.gradientStartColor))
            }
        }

        setupSearchAndFilter()
        viewModel.filteredTransactions.observe(viewLifecycleOwner) { txns ->
            adapter.submit(txns)
            binding.tvListEmpty.visibility = if (txns.isEmpty()) View.VISIBLE else View.GONE
            // 返回本页时恢复滚动位置（RecyclerView 重建后需要显式恢复一次）
            if (!scrollRestored) {
                scrollRestored = true
                pendingScrollState?.let { binding.transactionList.layoutManager?.onRestoreInstanceState(it) }
            }
            scrollToPendingContext()
        }
    }

    private fun setupSearchAndFilter() {
        // 漏斗/搜索图标用 FontAwesome（fa-filter / fa-magnifying-glass）
        val fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.otf")
        binding.filterButton.typeface = fa
        binding.searchIcon.typeface = fa
        // 搜索框：输入即过滤（时间/站点/类型/城市/金额/协议/原始值等）
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        binding.filterButton.setOnClickListener { showFilterDialog() }
        updateFilterButton()
    }

    /** 漏斗 → App 风格多选类别弹窗；
     *  类别从当前卡交易集合动态收集：标准大类固定顺序在前，其余类型（轮渡/出租车等）
     *  按出现次数降序补充，无数据时回退到大类全集。 */
    private fun showFilterDialog() {
        val known = listOf("地铁", "公交", "充值", "消费", "便利店", "城际", "有轨电车")
        val counts = viewModel.allTransactions.value.orEmpty()
            .groupingBy { it.transitType }.eachCount()
        val categories = buildList {
            addAll(known.filter { it in counts })
            addAll(counts.entries.asSequence()
                .filter { it.key !in known }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key })
            if (isEmpty()) addAll(known)
        }
        AppDialogs.multiSelect(
            context = requireContext(),
            title = getString(R.string.filter_title),
            options = categories,
            optionLabel = TransitLabels::type,
            selected = selectedFilters,
            accentColor = accentColor,
            onClear = { selectedFilters.clear(); applyFilterAndButton() },
            onDone = { result -> selectedFilters.clear(); selectedFilters.addAll(result); applyFilterAndButton() }
        )
    }

    private fun applyFilterAndButton() {
        viewModel.setFilter(selectedFilters.toSet())
        updateFilterButton()
    }

    /** 漏斗图标：有筛选时主题色实底 + 白字 */
    private fun updateFilterButton() {
        if (selectedFilters.isNotEmpty()) {
            binding.filterButton.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 21.dpToPx().toFloat()
                setColor(accentColor)
            }
            binding.filterButton.setTextColor(0xFFFFFFFF.toInt())
        } else {
            binding.filterButton.setBackgroundResource(R.drawable.bg_chip_default)
            binding.filterButton.setTextColor(accentColor)
        }
    }

    /** 药丸无有效内容（空白、"-"、"—" 占位）时整个隐藏 */
    private fun isPlaceholderPill(text: String?): Boolean {
        val t = text?.trim() ?: return true
        return t.isEmpty() || t == "-" || t == "—"
    }

    /** 给线路胶囊着色：颜色来自数据库 line_color（"#RRGGBB"），空白/无效时保持灰色；
     *  深色背景自动改白字，避免深色底 + 深灰字难读（统一走 Pills.applyLinePill）。
     *  无有效颜色时必须重置为默认灰色，否则 RecyclerView 复用时会残留上一行的线路色。 */
    private fun applyLineColor(line: TextView, color: String?) = line.applyLinePill(color)

    /** 长按交易行：底部抽屉展示交易摘要 + 操作列表 */
    private fun showTransactionActions(txn: UiTransaction) {
        val ctx = context ?: return
        TransactionActionSheet.show(
            context = ctx,
            txn = txn,
            accentColor = viewModel.mainAccent.value?.toInt() ?: Palette.ACCENT,
            actions = listOf(
                TransactionActionSheet.Action("\uF15C", getString(R.string.action_view_details)) { openDetail(txn) },        // fa-file-lines
                TransactionActionSheet.Action("\uF03A", getString(R.string.action_view_context)) { showTransactionContext(txn) }, // fa-list
                TransactionActionSheet.Action("\uF0C5", getString(R.string.action_copy_transaction)) { copyTransaction(txn) }  // fa-copy
            )
        )
    }

    private fun openDetail(txn: UiTransaction) {
        if (_binding == null) return
        val action = TransactionListFragmentDirections.actionTransactionListToTransactionDetail(txn.id)
        capturePredictiveBackSnapshot()
        findNavController().navigate(action)
    }

    private fun copyTransaction(txn: UiTransaction) {
        val ctx = context ?: return
        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.transactions), TransactionActionSheet.plainText(txn)))
        // Android 13+ 系统会自行提示已复制
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(ctx, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTransactionContext(txn: UiTransaction) {
        pendingContextTransactionId = txn.id
        selectedFilters.clear()
        updateFilterButton()
        viewModel.setFilter(emptySet())
        binding.searchInput.setText("")
        viewModel.setSearchQuery("")
        scrollToPendingContext()
    }

    private fun scrollToPendingContext() {
        val id = pendingContextTransactionId ?: return
        val position = adapter.positionOf(id)
        if (position < 0) return
        binding.transactionList.post {
            val list = _binding?.transactionList ?: return@post
            val currentPosition = adapter.positionOf(id)
            if (currentPosition < 0) return@post
            (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                currentPosition,
                (list.height / 3).coerceAtLeast(0)
            )
            pendingContextTransactionId = null
        }
    }

    /** 组内行背景：白底，组首行圆上角、组末行圆下角，单行四角都圆 */
    private fun groupRowBackground(first: Boolean, last: Boolean) = GradientDrawable().apply {
        val r = 12.dpToPx().toFloat()
        val top = if (first) r else 0f
        val bottom = if (last) r else 0f
        cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        setColor(Palette.SURFACE)
    }

    /** 「今天」「昨天」「10月1日 周四」，非今年加年份 */
    private fun dayTitle(date: String): String {
        val parsed = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)
        }.getOrNull() ?: return date
        return TimeLabels.dayTitle(java.util.Calendar.getInstance().apply { time = parsed })
    }

    /** 组内行之间的细分隔线：从图标右侧（文字起点）画到行尾 */
    private inner class GroupDividerDecoration : RecyclerView.ItemDecoration() {
        private val paint = android.graphics.Paint().apply { color = Palette.LINE }

        override fun onDrawOver(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val inset = 62.dpToPx()  // 行左内边距 16 + 图标 36 + 间距 10
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION || !adapter.needsDivider(pos)) continue
                val top = child.top + child.translationY
                c.drawRect(child.left + inset.toFloat(), top, child.right.toFloat(), top + 1f, paint)
            }
        }
    }

    private fun updateCardBadgeBg() {
        // 卡信息标签背景用主题色淡色填充（胶囊形）
        val bg = ColorUtils.blendARGB(0xFFFFFFFF.toInt(), accentColor, 0.12f)
        binding.cardBadge.background = GradientDrawable().apply {
            cornerRadius = 999.dpToPx().toFloat()
            setColor(bg)
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        _binding?.let { pendingScrollState = it.transactionList.layoutManager?.onSaveInstanceState() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 离开列表页（退回主页）时清空搜索与筛选状态；进详情不触发 onDestroy，状态保留 */
    override fun onDestroy() {
        super.onDestroy()
        viewModel.setSearchQuery("")
        viewModel.setFilter(emptySet())
    }

    /**
     * 交易行适配器：RecyclerView 虚拟化渲染，只绑定可见行；按日分组，每组一张圆角卡片。
     * 行绑定逻辑原样迁移自旧 bindTransactionList（逐行 addView 全量重建 → 大数据量卡顿）。
     */
    /** 列表条目：按日分组的日期标题，或组内的一行交易（first/last 决定圆角与分隔线） */
    private sealed class Entry {
        data class Header(val title: String, val summary: String) : Entry()
        data class Row(val txn: UiTransaction, val first: Boolean, val last: Boolean) : Entry()
    }

    private inner class TransactionAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val entries = mutableListOf<Entry>()
        // lazy：适配器在 Fragment 构造时即创建（字段初始化，见 onViewCreated 前 adapter 字段），此时尚未 attach，
        // requireContext() 会抛 IllegalStateException；首次 bind（已 attach）时才真正加载字体
        private val fa by lazy { Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.otf") }

        fun submit(list: List<UiTransaction>) {
            entries.clear()
            entries.addAll(groupByDay(list))
            notifyDataSetChanged()
        }

        fun positionOf(id: Int): Int = entries.indexOfFirst { it is Entry.Row && it.txn.id == id }

        /** 该位置是组内非首行（需要在顶部画分隔线） */
        fun needsDivider(position: Int): Boolean =
            (entries.getOrNull(position) as? Entry.Row)?.first == false

        override fun getItemCount(): Int = entries.size

        override fun getItemViewType(position: Int): Int =
            if (entries[position] is Entry.Header) TYPE_HEADER else TYPE_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_HEADER) return HeaderHolder(buildHeaderView(parent))
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transaction_row, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val e = entries[position]) {
                is Entry.Header -> (holder as HeaderHolder).bind(e)
                is Entry.Row -> (holder as Holder).apply {
                    bind(e.txn)
                    itemView.background = groupRowBackground(e.first, e.last)
                }
            }
        }

        /** 交易已按时间倒序：相邻同日期的归为一组，标题为「今天 / 昨天 / 10月1日 周四」+「N 笔 · 支出 ¥X」 */
        private fun groupByDay(list: List<UiTransaction>): List<Entry> {
            val out = mutableListOf<Entry>()
            var i = 0
            while (i < list.size) {
                val date = list[i].date
                var j = i
                while (j < list.size && list[j].date == date) j++
                val day = list.subList(i, j)
                val spend = day.filter { it.amountText.startsWith("-") }.sumOf { it.amountYuan }
                val summary = buildString {
                    append(resources.getQuantityString(R.plurals.txn_day_count, day.size, day.size))
                    if (spend > 0) append(" · ").append(getString(R.string.txn_day_spend, String.format("%.2f", spend)))
                }
                out += Entry.Header(dayTitle(date), summary)
                day.forEachIndexed { k, txn -> out += Entry.Row(txn, first = k == 0, last = k == day.lastIndex) }
                i = j
            }
            return out
        }

        private fun buildHeaderView(parent: ViewGroup): View {
            val ctx = parent.context
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.BOTTOM
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(4.dpToPx(), 20.dpToPx(), 4.dpToPx(), 8.dpToPx())
                addView(TextView(ctx).apply {
                    id = R.id.groupTitle
                    setTextColor(Palette.INK)
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(ctx).apply {
                    id = R.id.groupSummary
                    setTextColor(Palette.INK_3)
                    textSize = 12f
                })
            }
        }

        inner class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(header: Entry.Header) {
                itemView.findViewById<TextView>(R.id.groupTitle).text = header.title
                itemView.findViewById<TextView>(R.id.groupSummary).text = header.summary
            }
        }

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon = itemView.findViewById<TextView>(R.id.txnIcon)
            private val city = itemView.findViewById<TextView>(R.id.txnCity)
            private val type = itemView.findViewById<TextView>(R.id.txnType)
            private val time = itemView.findViewById<TextView>(R.id.txnTime)
            private val amount = itemView.findViewById<TextView>(R.id.txnAmount)
            private val balance = itemView.findViewById<TextView>(R.id.txnBalance)
            private val protocol = itemView.findViewById<TextView>(R.id.txnProtocol)
            private val protocol2 = itemView.findViewById<TextView>(R.id.txnProtocol2)
            private val dirIcon = itemView.findViewById<TextView>(R.id.txnDirIcon)
            private val station = itemView.findViewById<TextView>(R.id.txnStation)
            private val line = itemView.findViewById<TextView>(R.id.txnLine)

            fun bind(txn: UiTransaction) {
                // 方向由交易字段统一提供，站名始终保持无标记文本。
                val isEntry = txn.direction == TransitDirection.ENTRY
                val isExit = txn.direction == TransitDirection.EXIT
                val stationText = txn.stationName
                val lineText = txn.lineName

                icon.typeface = fa
                icon.text = txn.icon
                (icon.parent as? View)?.let { Palette.applyTransitIcon(icon, it, txn.transitType) }
                // 第一行胶囊：城市 / 交通类型（两个独立胶囊）；空白或占位符（- / —）时整个隐藏
                val cityText = TransitLabels.city(txn.cityName)
                city.text = cityText
                city.visibility = if (isPlaceholderPill(cityText)) View.GONE else View.VISIBLE
                type.text = TransitLabels.type(txn.transitType)
                type.visibility = if (isPlaceholderPill(txn.transitType)) View.GONE else View.VISIBLE
                // 第三行：时间（日期已在分组标题里，这里只显示时分）
                time.text = txn.time.take(5)
                amount.text = txn.amountText
                balance.text = txn.balanceAfterText
                // 无余额数据（null）时整行隐藏余额，避免误显示 ¥0.00
                balance.visibility = if (txn.balanceAfterText == null) View.GONE else View.VISIBLE
                // 协议药丸：按 protocols 逐颗显示（最多两个）；空（单协议卡）隐藏
                protocol.text = txn.protocols.getOrNull(0).orEmpty()
                protocol.visibility = if (txn.protocols.size > 0) View.VISIBLE else View.GONE
                protocol2.text = txn.protocols.getOrNull(1).orEmpty()
                protocol2.visibility = if (txn.protocols.size > 1) View.VISIBLE else View.GONE

                // 第二行：出入站图标 + 站名
                station.text = TransitLabels.station(stationText).ifEmpty { getString(R.string.unknown) }
                // 线路胶囊（数据库线路颜色着色；空白/占位符保持隐藏）。FlowLayout 自动整行换行
                line.text = lineText
                applyLineColor(line, txn.lineColor)
                line.visibility = if (isPlaceholderPill(lineText)) View.GONE else View.VISIBLE

                if (isEntry || isExit) {
                    dirIcon.visibility = View.VISIBLE
                    dirIcon.typeface = fa
                    // 入站 = U+F090 箭头进框，出站 = U+F08B 箭头出框；形状已区分，统一中性色
                    dirIcon.text = if (isEntry) "" else ""
                    dirIcon.setTextColor(Palette.INK_3)
                } else {
                    dirIcon.visibility = View.GONE
                }

                amount.setTextColor(Palette.amountColor(txn.amountText))


                itemView.setOnClickListener { openDetail(txn) }
                itemView.setOnLongClickListener {
                    showTransactionActions(txn)
                    true
                }
            }
        }
    }
}
