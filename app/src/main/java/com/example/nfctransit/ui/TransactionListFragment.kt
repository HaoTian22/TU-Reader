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
            val desc = itemView.findViewById<TextView>(R.id.txnDesc)
            val time = itemView.findViewById<TextView>(R.id.txnTime)
            val amount = itemView.findViewById<TextView>(R.id.txnAmount)
            val balance = itemView.findViewById<TextView>(R.id.txnBalance)
            val dirIcon = itemView.findViewById<TextView>(R.id.txnDirIcon)

            // 站名末尾的方向箭头换成 FontAwesome 图标（跟在站名后面）
            val isEntry = txn.stationName.endsWith("↓")
            val isExit = txn.stationName.endsWith("↑")
            val cleanStation = txn.stationName
                .replace(Regex(" [↑↓]$"), "")
            icon.text = txn.icon
            desc.text = listOf(txn.cityName ?: "", txn.transitType, cleanStation)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            time.text = txn.displayDateTime
            amount.text = txn.amountText
            balance.text = txn.balanceAfterText

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
