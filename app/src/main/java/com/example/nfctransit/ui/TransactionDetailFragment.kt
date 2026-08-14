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
import com.example.nfctransit.ApduUtil
import com.example.nfctransit.R
import com.example.nfctransit.data.toSfiHex
import com.example.nfctransit.databinding.FragmentTransactionDetailBinding
import com.example.nfctransit.model.UiTransaction

class TransactionDetailFragment : Fragment(R.layout.fragment_transaction_detail) {

    private var _binding: FragmentTransactionDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })
    private val args: TransactionDetailFragmentArgs by navArgs()

    /** 当前卡片主题色（跟随卡片渐变起点），默认蓝 */
    private var accentColor = 0xFF0066FF.toInt()

    /** 当前交易的原始数据（0x18 + 0x1E），供复制按钮使用 */
    private var rawHexToCopy = ""

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

        // 主题色跟随卡片：返回按钮、badge、复制按钮一起变
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            val color = accent.toInt()
            accentColor = color
            binding.btnBack.setTextColor(color)
            binding.tvCardBadge.setTextColor(color)
            binding.btnCopyHex.setTextColor(color)
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
            bindRawHex(txn)
            bindParsedData(txn)
        }

        // 复制原始数据按钮
        binding.btnCopyHex.typeface =
            Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.ttf")
        binding.btnCopyHex.setOnClickListener {
            if (rawHexToCopy.isNotBlank()) {
                val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("原始数据", rawHexToCopy))
                android.widget.Toast.makeText(requireContext(), "✓ 已复制原始数据", android.widget.Toast.LENGTH_SHORT).show()
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

    /** 原始数据：展示该交易在 transactions_archive 中的 hex；TU 卡同时有 0x18 主交易与 0x1E 旅程记录时两份都显示 */
    private fun bindRawHex(txn: UiTransaction) {
        val hexContainer = binding.hexPanel
        hexContainer.removeAllViews()
        val mainHex = txn.hex
        val journeyHex = txn.journeyHex
        if (mainHex.isBlank() && journeyHex.isNullOrBlank()) {
            rawHexToCopy = ""
            addRawLine("无该交易原始数据", dim = true)
            return
        }
        val sb = StringBuilder()
        if (mainHex.isNotBlank()) {
            addRawLine("SFI ${txn.sfi.toSfiHex()}", dim = true)
            addRawLine(mainHex)
            sb.append(mainHex)
        }
        if (!journeyHex.isNullOrBlank() && journeyHex != mainHex) {
            addRawLine("SFI 0x1E（旅程）", dim = true)
            addRawLine(journeyHex)
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(journeyHex)
        }
        rawHexToCopy = sb.toString()
    }

    /** 解析数据：按 SFI 解析原始 hex 的关键字段 */
    private fun bindParsedData(txn: UiTransaction) {
        val sb = StringBuilder()
        if (txn.hex.isNotBlank()) {
            sb.appendLine(parseHexLine(txn.sfi, txn.hex))
        }
        val journeyHex = txn.journeyHex
        if (!journeyHex.isNullOrBlank() && journeyHex != txn.hex) {
            sb.appendLine(parseHexLine(0x1E, journeyHex))
        }
        val match = txn.deviceCode
        sb.append("Match ${match ?: "Null"}")
        binding.parsedPanel.text = sb.toString()
    }

    private fun parseHexLine(sfi: Int, hex: String): String {
        return try {
            val d = ApduUtil.hexToBytes(hex)
            if (sfi == 0x1E && d.size >= 42) {
                val type = String.format("%02X", d[0])
                val subtype = String.format("%02X", d[9])
                val terminal = ApduUtil.bcdToString(d.copyOfRange(1, 9))   // 带前缀终端号
                val lineStn = ApduUtil.bytesToHex(d.copyOfRange(10, 17))   // 线路+站点，含站点区更多位（hex 显示）
                val balance = ApduUtil.hexToLong(d.copyOfRange(21, 25))
                val ts = ApduUtil.bcdToString(d.copyOfRange(25, 32))
                val city = ApduUtil.bcdToString(d.copyOfRange(32, 34))
                val instA = ApduUtil.bytesToHex(d.copyOfRange(34, 38))
                val instB = ApduUtil.bytesToHex(d.copyOfRange(38, 42))
                "SFI 0x1E\n" +
                    "Type [0 hex] $type\n" +
                    "Subtype [9 hex] $subtype\n" +
                    "Terminal [1-9 bcd] $terminal\n" +
                    "Timestamp [25-32 bcd] $ts\n" +
                    "Line & Station [10-17] $lineStn\n" +   // 各城市编码不同，只保留位置不标方法
                    "Balance [21-25 hex] ${String.format("%08X", balance)}\n" +
                    "Area [32-34 bcd] $city\n" +
                    "Institution [34-42 hex] $instA $instB"
            } else if (d.size >= 23) {
                val seq = ApduUtil.hexToLong(d.copyOfRange(0, 2)).toInt()
                val type = String.format("%02X", d[9])
                val terminal = ApduUtil.bcdToString(d.copyOfRange(10, 16))
                val date = ApduUtil.bcdToString(d.copyOfRange(16, 20))
                val time = ApduUtil.bcdToString(d.copyOfRange(20, 23))
                "SFI 0x18\n" +
                    "Type [9 hex] $type\n" +
                    "Record No. [0-2 dec] $seq\n" +
                    "Terminal [10-16 bcd] $terminal\n" +
                    "Timestamp [16-23 bcd] $date$time"
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun addRawLine(text: String, dim: Boolean = false) {
        val lineView = TextView(requireContext()).apply {
            this.text = text
            textSize = 10f
            setTextColor(if (dim) 0xFF666688.toInt() else 0xFFAAAAFF.toInt())
            typeface = Typeface.MONOSPACE
            setPadding(0, dpToPx(2), 0, dpToPx(2))
            setTextIsSelectable(true)   // 长按可选中复制
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
