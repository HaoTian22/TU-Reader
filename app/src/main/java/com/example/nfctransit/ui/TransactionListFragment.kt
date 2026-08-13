package com.example.nfctransit.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentTransactionListBinding
import com.example.nfctransit.model.UiTransaction

class TransactionListFragment : Fragment(R.layout.fragment_transaction_list) {

    private var _binding: FragmentTransactionListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    private var currentFilter = "全部"
    private val filterViews = mutableMapOf<String, TextView>()
    private var accentColor = 0xFF0066FF.toInt()

    /** 离开页面（进详情/切后台）时记录的滚动位置，返回后恢复；-1 = 无需恢复 */
    private var scrollToRestore = -1

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

        // 主题色跟随卡片：返回按钮、badge、选中的筛选 chip 一起变
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            accentColor = accent.toInt()
            binding.btnBack.setTextColor(accentColor)
            binding.tvCardBadge.setTextColor(accentColor)
            updateCardBadgeBg()
            refreshFilterChips()
        }

        // Update card badge from ViewModel
        viewModel.selectedCard.observe(viewLifecycleOwner) { card ->
            if (card != null) {
                binding.tvCardBadge.text = "${card.cardType} · ${card.lastFour}"
                binding.tvCardBadge.setTextColor(card.gradientStartColor.toInt())
            }
        }

        setupFilters()
        viewModel.filteredTransactions.observe(viewLifecycleOwner) { txns ->
            bindTransactionList(txns)
        }
    }

    private fun setupFilters() {
        filterViews["全部"] = binding.filterAll
        filterViews["地铁"] = binding.filterMetro
        filterViews["公交"] = binding.filterBus
        filterViews["消费"] = binding.filterConsumption
        filterViews["充值"] = binding.filterRecharge

        filterViews.forEach { (type, view) ->
            view.setOnClickListener {
                selectFilter(type)
            }
        }
    }

    private fun selectFilter(type: String) {
        currentFilter = type
        refreshFilterChips()
        viewModel.setFilter(type)
    }

    private fun refreshFilterChips() {
        filterViews.forEach { (t, view) ->
            if (t == currentFilter) {
                // 用圆角 GradientDrawable 填充主题色，避免 setBackgroundColor 丢失圆角
                view.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 20.dpToPx().toFloat()
                    setColor(accentColor)
                }
                view.setTextColor(0xFFFFFFFF.toInt())
            } else {
                view.setBackgroundResource(R.drawable.bg_chip_default)
                view.setTextColor(0xFF555555.toInt())
            }
        }
    }

    private fun bindTransactionList(transactions: List<UiTransaction>) {
        binding.transactionListContainer.removeAllViews()

        if (transactions.isEmpty()) {
            val emptyView = TextView(requireContext()).apply {
                text = "暂无交易记录"
                setTextColor(0xFF8E8E93.toInt())
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 40.dpToPx(), 0, 40.dpToPx())
            }
            binding.transactionListContainer.addView(emptyView)
            return
        }

        for (txn in transactions) {
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_transaction_row, binding.transactionListContainer, false)

            val icon = itemView.findViewById<TextView>(R.id.txnIcon)
            val city = itemView.findViewById<TextView>(R.id.txnCity)
            val type = itemView.findViewById<TextView>(R.id.txnType)
            val time = itemView.findViewById<TextView>(R.id.txnTime)
            val amount = itemView.findViewById<TextView>(R.id.txnAmount)
            val balance = itemView.findViewById<TextView>(R.id.txnBalance)
            val protocol = itemView.findViewById<TextView>(R.id.txnProtocol)
            val protocol2 = itemView.findViewById<TextView>(R.id.txnProtocol2)
            val dirIcon = itemView.findViewById<TextView>(R.id.txnDirIcon)
            val station = itemView.findViewById<TextView>(R.id.txnStation)
            val line = itemView.findViewById<TextView>(R.id.txnLine)

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
            time.text = txn.date + " " + txn.time.substring(0, 5)
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
            // 第一行：线路胶囊（用数据库线路颜色着色；空白/占位符保持隐藏）
            if (isPlaceholderPill(lineText)) {
                line.visibility = View.GONE
            } else {
                line.visibility = View.VISIBLE
                line.text = lineText
                applyLineColor(line, txn.lineColor)
            }

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

            binding.transactionListContainer.addView(itemView)
        }

        // 返回本页时 observer 会再次触发绑定重建列表，滚动位置在重建后恢复
        if (scrollToRestore >= 0) {
            val target = scrollToRestore
            scrollToRestore = -1
            binding.transactionListScroll.post { binding.transactionListScroll.scrollTo(0, target) }
        }
    }

    /** 药丸无有效内容（空白、"-"、"—" 占位）时整个隐藏 */
    private fun isPlaceholderPill(text: String?): Boolean {
        val t = text?.trim() ?: return true
        return t.isEmpty() || t == "-" || t == "—"
    }

    /** 给线路胶囊着色：颜色来自数据库 line_color（"#RRGGBB"），空白/无效时保持灰色 */
    private fun applyLineColor(line: TextView, color: String?) {
        val parsed = try {
            android.graphics.Color.parseColor(color?.trim() ?: return)
        } catch (e: IllegalArgumentException) {
            return
        }
        line.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(parsed)
        }
    }

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
        _binding?.let { scrollToRestore = it.transactionListScroll.scrollY }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
