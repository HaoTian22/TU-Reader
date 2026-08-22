package com.example.nfctransit.ui

import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private var feedbackDialog: Dialog? = null
    private var feedbackProgressToast: Toast? = null
    private val feedbackToastHandler = Handler(Looper.getMainLooper())
    private val repeatFeedbackProgressToast = object : Runnable {
        override fun run() {
            feedbackProgressToast?.show()
            if (viewModel.feedbackSaving.value == true) {
                feedbackToastHandler.postDelayed(this, 2_500)
            }
        }
    }

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
            binding.btnFeedbackHex.setTextColor(color)
            updateCardBadgeBg()
        }

        // Update card badge
        viewModel.selectedCard.observe(viewLifecycleOwner) { card ->
            if (card != null) {
                binding.tvCardBadge.text = "${card.name} · ${card.lastFour}"
                binding.tvCardBadge.setTextColor(card.gradientStartColor.toInt())
            }
        }

        // Look up transaction by ID
        val txn = viewModel.getTransactionById(args.transactionId)
        if (txn != null) {
            bindTransactionData(txn)
            bindRawHex(txn)
        }

        // 复制原始数据按钮
        binding.btnCopyHex.typeface =
            Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.ttf")
        binding.btnFeedbackHex.typeface = binding.btnCopyHex.typeface
        binding.btnCopyHex.setOnClickListener {
            if (rawHexToCopy.isNotBlank()) {
                val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("原始数据", rawHexToCopy))
                android.widget.Toast.makeText(requireContext(), "✓ 已复制原始数据", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnFeedbackHex.setOnClickListener { showFeedbackDialog() }

        viewModel.feedbackStatus.observe(viewLifecycleOwner) { status ->
            if (!status.isNullOrBlank()) {
                stopFeedbackProgressToast()
                Toast.makeText(requireContext(), status, Toast.LENGTH_LONG).show()
                viewModel.consumeFeedbackStatus()
            }
        }
        viewModel.feedbackSaving.observe(viewLifecycleOwner) { saving ->
            if (saving) startFeedbackProgressToast() else stopFeedbackProgressToast()
        }

    }

    private fun startFeedbackProgressToast() {
        if (feedbackProgressToast == null) {
            feedbackProgressToast = Toast.makeText(
                requireContext(), "正在保存并上传…", Toast.LENGTH_LONG
            )
        }
        feedbackToastHandler.removeCallbacks(repeatFeedbackProgressToast)
        repeatFeedbackProgressToast.run()
    }

    private fun stopFeedbackProgressToast() {
        feedbackToastHandler.removeCallbacks(repeatFeedbackProgressToast)
        feedbackProgressToast?.cancel()
        feedbackProgressToast = null
    }

    private fun showFeedbackDialog() {
        val txn = viewModel.getTransactionById(args.transactionId) ?: return
        val fullCode = txn.deviceCode.orEmpty()
        val isLnt = txn.protocol == "LNT"
        val isTu = txn.protocol == "TU"
        val tuLineStationCode = txn.journeyHex
            ?.takeIf { it.isNotBlank() }
            ?.let { hexRange(it, 10, 17) }
            .orEmpty()
        val rawCode = when {
            isLnt -> txn.terminal
            isTu -> tuLineStationCode
            else -> fullCode.ifBlank { txn.terminal }
        }
        val prefix = when {
            isLnt -> "0100"
            isTu -> txn.cityCode.orEmpty()
            else -> txn.cityCode?.takeIf { it.isNotBlank() }
                ?: rawCode.takeIf { it.length > 4 }?.take(4)
                ?: ""
        }
        val code = if (isLnt) rawCode.removePrefix(prefix) else rawCode
        val hasTuJourney = isTu && (txn.sfi == 0x1E || !txn.journeyHex.isNullOrBlank())
        val line = txn.lineName
            .takeIf {
                it.isNotBlank() && it != "—" && it != "未知" &&
                    txn.lineId != null && (!isTu || hasTuJourney)
            }
            .orEmpty()
        val station = txn.stationName
            .removeSuffix("↑")
            .removeSuffix("↓")
            .trim()
            .takeIf {
                it.isNotBlank() && it != "未知" && it != "—" && it != "公共交通" &&
                    txn.stationId != null && (!isTu || hasTuJourney)
            }
            .orEmpty()
        feedbackDialog?.dismiss()
        viewModel.consumeFeedbackStatus()
        feedbackDialog = AppDialogs.feedback(
            context = requireContext(),
            prefix = prefix,
            code = code,
            line = line,
            station = station,
            type = txn.transitType,
            accentColor = accentColor
        ) { enteredPrefix, enteredCode, enteredType, enteredLine, enteredStation, publish ->
            val normalizedPrefix = enteredPrefix.trim()
            val normalizedCode = enteredCode.trim()
            val normalizedLine = enteredLine.trim()
            val normalizedStation = enteredStation.trim()
            val codeRegex = Regex("[0-9A-Za-z]+")
            val error = when {
                !normalizedPrefix.matches(codeRegex) -> "请填写有效的 Prefix"
                !normalizedCode.matches(codeRegex) -> "请填写有效的 Code"
                normalizedLine.length > 128 || normalizedLine.contains('\n') || normalizedLine.contains('\r') ->
                    "线路不能包含换行且最多 128 个字符"
                normalizedStation.length > 128 || normalizedStation.contains('\n') || normalizedStation.contains('\r') ->
                    "站名不能包含换行且最多 128 个字符"
                else -> null
            }
            if (error != null) {
                android.widget.Toast.makeText(
                    requireContext(), error, android.widget.Toast.LENGTH_LONG
                ).show()
                return@feedback
            }
            viewModel.saveFeedbackOverride(
                txn,
                normalizedPrefix,
                normalizedCode,
                enteredType,
                normalizedLine,
                normalizedStation,
                publish
            )
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
        val transactionType = if (txn.amountText == "票务处理") {
            "票务处理"
        } else {
            when (txn.transitType) {
                "地铁" -> if (isEntry) "地铁入站" else if (isExit) "地铁出站" else "地铁"
                "公交" -> "公交乘车"
                "消费" -> "小额消费"
                "便利店" -> "便利店"
                "充值" -> "充值"
                else -> txn.transitType
            }
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

    /** 原始数据：展示该交易在 transactions_archive 中的 hex，按解析字段位置着色；TU 卡同时有 0x18 与 0x1E 时两份都显示，框底附颜色图例 */
    private fun bindRawHex(txn: UiTransaction) {
        val hexContainer = binding.hexPanel
        hexContainer.removeAllViews()
        val mainHex = txn.hex
        val journeyHex = txn.journeyHex
        if (mainHex.isBlank() && journeyHex.isNullOrBlank()) {
            rawHexToCopy = ""
            addMonospaceLine(binding.hexPanel, "无该交易原始数据", dim = true)
            return
        }
        val blocks = mutableListOf<Pair<Int, String>>()  // (sfi, hex)
        val sb = StringBuilder()
        if (mainHex.isNotBlank()) {
            appendHexBlock(binding.hexPanel, txn.sfi, mainHex, txn.protocol)
            blocks.add(txn.sfi to mainHex)
            sb.append(mainHex)
        }
        if (!journeyHex.isNullOrBlank() && journeyHex != mainHex) {
            appendHexBlock(binding.hexPanel, 0x1E, journeyHex, "TU")
            blocks.add(0x1E to journeyHex)
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(journeyHex)
        }
        // Match 行：标签与 SFI 同色，值新起一行；特殊匹配规则（广佛跨城/深圳）附加标记
        addMonospaceLine(binding.hexPanel, "Match", dim = true)
        val matchCode = txn.deviceCode ?: "Null"
        addMonospaceLine(binding.hexPanel, if (txn.spRule != null) "$matchCode ${txn.spRule}" else matchCode)
        // 颜色图例：含义 + 区域 + 解析方式 + 颜色
        addDivider(binding.hexPanel)
        for ((sfi, hex) in blocks) {
            val fields = RawHexFormatter.fieldsFor(sfi, ApduUtil.hexToBytes(hex).size, txn.protocol)
            if (fields.isEmpty()) continue
            addMonospaceLine(binding.hexPanel, "SFI ${sfi.toSfiHex()} fields", dim = true)
            for (f in fields) addLegendRow(binding.hexPanel, f)
        }
        rawHexToCopy = buildCopyText(sb.toString(), blocks, txn)
    }

    private fun buildCopyText(
        rawValue: String,
        blocks: List<Pair<Int, String>>,
        txn: UiTransaction
    ): String {
        val out = StringBuilder(rawValue)
        out.append("\n\n")
        for ((sfi, hex) in blocks) {
            val fields = RawHexFormatter.fieldsFor(sfi, ApduUtil.hexToBytes(hex).size, txn.protocol)
            if (fields.isEmpty()) continue
            out.append("SFI ${sfi.toSfiHex()}\n")
            for (f in fields) {
                val methodPart = if (f.method.isEmpty()) "" else " ${f.method}"
                out.append("[${f.label} ${rangeText(f.start, f.end)}$methodPart] ")
                    .append(hexRange(hex, f.start, f.end))
                    .append('\n')
            }
        }
        val matchCode = txn.deviceCode ?: "Null"
        out.append("[Match] ")
            .append(if (txn.spRule != null) "$matchCode ${txn.spRule}" else matchCode)
        return out.toString()
    }

    private fun hexRange(hex: String, start: Int, end: Int): String {
        val compact = hex.filterNot { it.isWhitespace() }
        val from = (start * 2).coerceIn(0, compact.length)
        val to = (end * 2).coerceIn(from, compact.length)
        return compact.substring(from, to)
    }

    private fun appendHexBlock(
        container: LinearLayout,
        sfi: Int,
        hex: String,
        protocol: String
    ) {
        val fields = RawHexFormatter.fieldsFor(sfi, ApduUtil.hexToBytes(hex).size, protocol)
        addMonospaceLine(container, "SFI ${sfi.toSfiHex()}", dim = true)
        addColoredHexLine(container, RawHexFormatter.colorizeHex(hex, fields))
    }

    private fun addDivider(container: LinearLayout) {
        val divider = View(requireContext()).apply { setBackgroundColor(0xFF333366.toInt()) }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1))
        lp.setMargins(0, dpToPx(8), 0, dpToPx(8))
        container.addView(divider, lp)
    }

    private fun addColoredHexLine(container: LinearLayout, spannable: Spannable) {
        val lineView = TextView(requireContext()).apply {
            this.text = spannable
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(RawHexFormatter.RAW)             // 未解析字节保持原始 hex 色
            setPadding(0, dpToPx(2), 0, dpToPx(2))
            setTextIsSelectable(true)   // 长按可选中复制
        }
        container.addView(lineView)
    }

    private fun addLegendRow(container: LinearLayout, f: RawHexFormatter.FieldSpec) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(1), 0, dpToPx(1))
        }
        row.addView(TextView(requireContext()).apply {
            text = "●"
            textSize = 8f
            setTextColor(f.color)
            setPadding(0, 0, dpToPx(6), 0)
        })
        row.addView(TextView(requireContext()).apply {
            text = f.label
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(f.color)
        })
        val methodPart = if (f.method.isNotEmpty()) " ${f.method}" else ""
        row.addView(TextView(requireContext()).apply {
            text = " [${rangeText(f.start, f.end)}$methodPart]"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(RawHexFormatter.LEGEND_TEXT)
            setTextIsSelectable(true)
        })
        container.addView(row)
    }

    private fun rangeText(start: Int, end: Int): String =
        if (end - start == 1) "$start" else "$start-${end - 1}"

    private fun addMonospaceLine(container: LinearLayout, text: String, dim: Boolean = false) {
        val lineView = TextView(requireContext()).apply {
            this.text = text
            textSize = 10f
            setTextColor(if (dim) RawHexFormatter.DIM else RawHexFormatter.RAW)
            typeface = Typeface.MONOSPACE
            setPadding(0, dpToPx(2), 0, dpToPx(2))
            setTextIsSelectable(true)   // 长按可选中复制
        }
        container.addView(lineView)
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
        stopFeedbackProgressToast()
        feedbackDialog?.dismiss()
        feedbackDialog = null
        super.onDestroyView()
        _binding = null
    }
}
