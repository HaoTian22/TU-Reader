package com.example.nfctransit.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentTransactionListBinding
import com.example.nfctransit.model.UiTransaction

class TransactionListFragment : Fragment(R.layout.fragment_transaction_list) {

    private var _binding: FragmentTransactionListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    /** 多选筛选中已勾选的类别（空 = 全部显示） */
    private val selectedFilters = mutableSetOf<String>()
    private var accentColor = 0xFF0066FF.toInt()

    private val adapter = TransactionAdapter()

    /** 离开页面（进详情/切后台）时保存的滚动位置，返回后恢复一次；null = 无需恢复 */
    private var pendingScrollState: Parcelable? = null
    private var scrollRestored = false

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

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.transactionList.layoutManager = LinearLayoutManager(requireContext())
        binding.transactionList.adapter = adapter

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
                binding.tvCardBadge.text = "${card.cardType} · ${card.lastFour}"
                binding.tvCardBadge.setTextColor(card.gradientStartColor.toInt())
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
        }
    }

    private fun setupSearchAndFilter() {
        // 漏斗/搜索图标用 FontAwesome（fa-filter / fa-magnifying-glass）
        val fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.ttf")
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

    /** 漏斗 → App 风格多选类别弹窗 */
    private fun showFilterDialog() {
        val categories = listOf("地铁", "公交", "充值", "消费", "城际", "有轨电车")
        AppDialogs.multiSelect(
            context = requireContext(),
            title = "筛选类别",
            options = categories,
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

    private fun updateCardBadgeBg() {
        // 卡信息标签背景用主题色淡色填充（保留 10dp 圆角）
        val bg = ColorUtils.blendARGB(0xFFFFFFFF.toInt(), accentColor, 0.12f)
        binding.cardBadge.background = GradientDrawable().apply {
            cornerRadius = 10.dpToPx().toFloat()
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
     * 交易行适配器：RecyclerView 虚拟化渲染，只绑定可见行。
     * 行绑定逻辑原样迁移自旧 bindTransactionList（逐行 addView 全量重建 → 大数据量卡顿）。
     */
    private inner class TransactionAdapter : RecyclerView.Adapter<TransactionAdapter.Holder>() {

        private val items = mutableListOf<UiTransaction>()
        // lazy：适配器在 Fragment 构造时即创建（字段初始化，见 onViewCreated 前 adapter 字段），此时尚未 attach，
        // requireContext() 会抛 IllegalStateException；首次 bind（已 attach）时才真正加载字体
        private val fa by lazy { Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.ttf") }

        fun submit(list: List<UiTransaction>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transaction_row, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
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
                // 站名末尾的方向箭头换成 FontAwesome 图标
                val isEntry = txn.stationName.endsWith("↓")
                val isExit = txn.stationName.endsWith("↑")
                // 去掉方向箭头后的站名；线路名已由解析层单独提供
                val stationText = txn.stationName.replace(Regex(" [↑↓]$"), "")
                val lineText = txn.lineName

                icon.text = txn.icon
                // 第一行胶囊：城市 / 交通类型（两个独立胶囊）；空白或占位符（- / —）时整个隐藏
                val cityText = txn.cityName ?: "未知"
                city.text = cityText
                city.visibility = if (isPlaceholderPill(cityText)) View.GONE else View.VISIBLE
                type.text = txn.transitType
                type.visibility = if (isPlaceholderPill(txn.transitType)) View.GONE else View.VISIBLE
                // 第三行：时间（带年）
                time.text = txn.date + " " + txn.time.take(5)
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
                station.text = stationText.ifEmpty { "未知" }
                // 线路胶囊（数据库线路颜色着色；空白/占位符保持隐藏）。FlowLayout 自动整行换行
                line.text = lineText
                applyLineColor(line, txn.lineColor)
                line.visibility = if (isPlaceholderPill(lineText)) View.GONE else View.VISIBLE

                if (isEntry || isExit) {
                    dirIcon.visibility = View.VISIBLE
                    dirIcon.typeface = fa
                    // 入站 = U+F090 箭头进框（绿），出站 = U+F08B 箭头出框（红）
                    dirIcon.text = if (isEntry) "" else ""
                    dirIcon.setTextColor(if (isEntry) 0xFF34C759.toInt() else 0xFFFF3B30.toInt())
                } else {
                    dirIcon.visibility = View.GONE
                }

                if (txn.amountText.startsWith("+")) {
                    amount.setTextColor(0xFF34C759.toInt())
                } else {
                    amount.setTextColor(0xFFFF3B30.toInt())
                }

                // Set icon circle background color
                val iconParent = icon.parent as? ViewGroup
                iconParent?.background?.let { bg ->
                    if (bg is GradientDrawable) {
                        bg.setColor(txn.iconBgColor.toInt())
                    }
                }

                itemView.setOnClickListener {
                    val action = TransactionListFragmentDirections
                        .actionTransactionListToTransactionDetail(txn.id)
                    findNavController().navigate(action)
                }
            }
        }
    }
}
