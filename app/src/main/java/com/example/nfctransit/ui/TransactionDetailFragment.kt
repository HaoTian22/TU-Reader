package com.example.nfctransit.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentTransactionDetailBinding
import com.example.nfctransit.model.UiTransaction

class TransactionDetailFragment : Fragment(R.layout.fragment_transaction_detail) {

    private var _binding: FragmentTransactionDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })
    private val args: TransactionDetailFragmentArgs by navArgs()

    /** 当前卡片主题色（跟随卡片渐变起点），默认蓝 */
    private var accentColor = 0xFF0066FF.toInt()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransactionDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        // 主题色跟随卡片：返回按钮、badge、原始数据开关一起变
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            val color = accent.toInt()
            accentColor = color
            binding.btnBack.setTextColor(color)
            binding.tvCardBadge.setTextColor(color)
            binding.btnToggleRaw.setTextColor(color)
            updateCardBadgeBg()
        }

        // Update card badge
        viewModel.selectedCard.observe(viewLifecycleOwner) { card ->
            if (card != null) {
                binding.tvCardBadge.text = "${card.cardType} · ${card.lastFour}"
                binding.tvCardBadge.setTextColor(card.gradientStartColor.toInt())
            }
        }

        // Look up transaction by ID
        val txn = viewModel.getTransactionById(args.transactionId)
        if (txn != null) {
            bindTransactionData(txn)
        }

        // Toggle raw data - show real NFC log
        binding.btnToggleRaw.setOnClickListener {
            if (binding.hexPanel.visibility == View.VISIBLE) {
                binding.hexPanel.visibility = View.GONE
                binding.btnToggleRaw.text = "▶ 查看原始数据"
            } else {
                binding.hexPanel.visibility = View.VISIBLE
                binding.btnToggleRaw.text = "▼ 查看原始数据"
                bindNfcLog(txn)
            }
        }
    }

    private fun bindTransactionData(txn: UiTransaction) {
        binding.tvAmountHeader.text = txn.amountText
        if (txn.amountText.startsWith("+")) {
            binding.tvAmountHeader.setTextColor(0xFF34C759.toInt())
        } else {
            binding.tvAmountHeader.setTextColor(0xFFFF3B30.toInt())
        }

        binding.hexPanel.visibility = View.GONE
        binding.btnToggleRaw.text = "▶ 查看原始数据"

        val detailContainer = binding.detailRowsContainer
        // 站名末尾的方向箭头只用于判定出入站，展示时去掉
        val cleanStation = txn.stationName.removeSuffix("↑").removeSuffix("↓").trim()
        val placeText = listOf(txn.cityName ?: "", txn.transitType, cleanStation)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        val isEntry = txn.stationName.endsWith("↓")
        val isExit = txn.stationName.endsWith("↑")
        val transactionType = when (txn.transitType) {
            "地铁" -> if (isEntry) "地铁入站" else if (isExit) "地铁出站" else "地铁"
            "公交" -> "公交乘车"
            "消费" -> "小额消费"
            "充值" -> "充值"
            else -> txn.transitType
        }
        // 进出站图标用 FontAwesome：入站 = 箭头进框，出站 = 箭头出框
        val fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.ttf")
        val fields = listOf(
            "交易时间" to txn.displayDateTime,
            "地点/站名" to placeText,
            "线路" to txn.lineName,
            "交易类型" to transactionType,
            "扣款金额" to txn.amountText,
            "交易后余额" to (txn.balanceAfterYuan?.let { "¥${String.format("%.2f", it)}" } ?: "无"),
            "协议" to txn.protocols.joinToString(" / "),
            "终端编号" to txn.terminal
        )

        for (i in fields.indices) {
            if (i < detailContainer.childCount) {
                val row = detailContainer.getChildAt(i)
                val label = row.findViewById<TextView>(R.id.detailLabel)
                val value = row.findViewById<TextView>(R.id.detailValue)
                val icon = row.findViewById<TextView>(R.id.detailIcon)
                label?.text = fields[i].first
                value?.text = fields[i].second
                value?.setTextColor(0xFF1A1A1A.toInt())
                icon?.visibility = View.GONE
                icon?.typeface = fa
                // 协议行为空（单协议卡）时整行隐藏
                if (fields[i].first == "协议") {
                    row.visibility = if (fields[i].second.isEmpty()) View.GONE else View.VISIBLE
                }
                // 交易类型行：入站绿色 ↓ 进框、出站红色 ↑ 出框
                if (fields[i].first == "交易类型" && (isEntry || isExit)) {
                    icon?.visibility = View.VISIBLE
                    // 入站 = U+F090 箭头进框，出站 = U+F08B 箭头出框
                    icon?.text = if (isEntry) "" else ""
                    icon?.setTextColor(if (isEntry) 0xFF34C759.toInt() else 0xFFFF3B30.toInt())
                }
                if (fields[i].first == "扣款金额" && txn.amountText.startsWith("+")) {
                    value?.setTextColor(0xFF34C759.toInt())
                }
                if (fields[i].first == "终端编号") {
                    value?.typeface = Typeface.MONOSPACE
                }
            }
        }
    }

    /** 原始数据：只展示对应该交易 SFI（0x18/0x1E/附加区）的原始记录 */
    private fun bindNfcLog(txn: UiTransaction?) {
        val hexContainer = binding.hexPanel
        hexContainer.removeAllViews()

        val sfi = txn?.sfi ?: run {
            addRawLine("无该交易原始数据", dim = true)
            return
        }
        val sfiHex = sfi.toString(16).uppercase()

        // 优先取本次会话 APDU 日志中该 SFI 的读取记录（带读取上下文）；无则回退到库内原始记录。
        // 日志格式与 TransitCardReader 一致：SFI=<大写 hex 不补零>，如 "READ RECORD SFI=18 rec=1 -> ..."
        val sessionLines = viewModel.nfcLog.value.orEmpty()
            .filter { Regex("SFI=$sfiHex\\b").containsMatchIn(it) }
        val storedRecords = viewModel.rawRecordsForSfi(sfi)

        if (sessionLines.isEmpty() && storedRecords.isEmpty()) {
            addRawLine("无 SFI $sfiHex 的原始数据", dim = true)
            return
        }

        if (sessionLines.isNotEmpty()) {
            sessionLines.forEach { addRawLine(it) }
        } else {
            storedRecords.forEach { rec ->
                val proto = if (rec.protocol.isEmpty()) "" else " [${rec.protocol}]"
                addRawLine("SFI=$sfiHex rec=${rec.recNo}$proto -> ${rec.hex}")
            }
        }
    }

    private fun addRawLine(text: String, dim: Boolean = false) {
        val lineView = TextView(requireContext()).apply {
            this.text = text
            textSize = 10f
            setTextColor(if (dim) 0xFF666688.toInt() else 0xFFAAAAFF.toInt())
            typeface = Typeface.MONOSPACE
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }
        binding.hexPanel.addView(lineView)
    }

    private fun updateCardBadgeBg() {
        // 卡信息标签背景用主题色淡色填充（保留 10dp 圆角）
        val bg = ColorUtils.blendARGB(0xFFFFFFFF.toInt(), accentColor, 0.12f)
        binding.cardBadge.background = GradientDrawable().apply {
            cornerRadius = dpToPx(10).toFloat()
            setColor(bg)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
