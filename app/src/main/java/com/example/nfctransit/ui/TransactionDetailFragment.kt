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
import androidx.navigation.fragment.navArgs
import com.example.nfctransit.ApduUtil
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.data.toSfiHex
import com.example.nfctransit.databinding.FragmentTransactionDetailBinding
import com.example.nfctransit.model.UiTransaction
import com.example.nfctransit.model.TransitDirection

class TransactionDetailFragment : Fragment(R.layout.fragment_transaction_detail) {

    private var _binding: FragmentTransactionDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })
    private val args: TransactionDetailFragmentArgs by navArgs()

    /** 当前卡片主题色（跟随卡片渐变起点），默认蓝 */
    private var accentColor = Palette.ACCENT

    /** 当前交易的原始数据（0x18 + 0x1E），供复制按钮使用 */
    private var rawHexToCopy = ""
    /** 与原始记录面板使用同一组数据。 */
    private var rawRecordForFeedback = ""
    private data class RawBlock(val sfi: Int, val protocol: String, val hex: String)
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

        binding.btnBack.setOnClickListener { (activity as? MainActivity)?.animatePredictiveBack() }

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
                binding.tvCardBadge.setTextColor(Palette.accentFor(card.gradientStartColor))
            }
        }

        // 进程被系统回收后，Navigation 会先还原本页，而 ViewModel 仍在从磁盘恢复交易。
        // 因此不能只在创建时查询一次；恢复完成后 allTransactions 会再次发出真正的交易。
        viewModel.allTransactions.observe(viewLifecycleOwner) { transactions ->
            renderTransaction(transactions.firstOrNull { it.id == args.transactionId })
        }
        viewModel.isRestoring.observe(viewLifecycleOwner) {
            // allTransactions 初始值为空；恢复状态变化时刷新提示，避免把布局预览值当成交易数据。
            renderTransaction(viewModel.getTransactionById(args.transactionId))
        }

        // 复制原始数据按钮
        val fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.otf")
        binding.btnCopyHex.setIconLabel(fa, "\uF0C5", getString(R.string.action_copy))       // fa-copy
        binding.btnFeedbackHex.setIconLabel(fa, "\uF075", getString(R.string.action_report)) // fa-comment
        binding.btnCopyHex.setOnClickListener {
            if (rawHexToCopy.isNotBlank()) {
                val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.label_raw_data), rawHexToCopy))
                android.widget.Toast.makeText(requireContext(), R.string.raw_copied, android.widget.Toast.LENGTH_SHORT).show()
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
                requireContext(), R.string.feedback_saving, Toast.LENGTH_LONG
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
            .trim()
            .takeIf {
                it.isNotBlank() && it != "未知" && it != "—" && it != "公共交通" &&
                    txn.stationId != null && (!isTu || hasTuJourney)
            }
            .orEmpty()
        feedbackDialog?.dismiss()
        viewModel.consumeFeedbackStatus()
        // 在打开表单时固定当前展示内容，避免保存纠错后重新解析改变 Match 等信息。
        val rawRecord = rawRecordForFeedback
        feedbackDialog = AppDialogs.feedback(
            context = requireContext(),
            prefix = prefix,
            code = code,
            line = line,
            station = station,
            type = txn.transitType,
            actualCityCode = txn.actualCityCode,
            actualCityName = txn.cityName,
            hasRawRecord = rawRecord.isNotBlank(),
            accentColor = accentColor
        ) { enteredPrefix, enteredCode, enteredType, enteredLine, enteredStation, enteredCityCode, enteredCityName, locationSource, publish, includeRawRecord ->
            val normalizedPrefix = enteredPrefix.trim()
            val normalizedCode = enteredCode.trim()
            val normalizedLine = enteredLine.trim()
            val normalizedStation = enteredStation.trim()
            val codeRegex = Regex("[0-9A-Za-z]+")
            val error = when {
                !normalizedPrefix.matches(codeRegex) -> getString(R.string.err_invalid_prefix)
                !normalizedCode.matches(codeRegex) -> getString(R.string.err_invalid_code)
                normalizedLine.length > 128 || normalizedLine.contains('\n') || normalizedLine.contains('\r') ->
                    getString(R.string.err_line_format)
                normalizedStation.length > 128 || normalizedStation.contains('\n') || normalizedStation.contains('\r') ->
                    getString(R.string.err_station_format)
                else -> null
            }
            if (error != null) {
                android.widget.Toast.makeText(
                    requireContext(), error, android.widget.Toast.LENGTH_LONG
                ).show()
                return@feedback false
            }
            viewModel.saveFeedbackOverride(
                txn,
                normalizedPrefix,
                normalizedCode,
                enteredType,
                normalizedLine,
                normalizedStation,
                enteredCityCode,
                enteredCityName,
                locationSource,
                publish,
                rawRecord = rawRecord.takeIf { publish && includeRawRecord }
            )
            true
        }
    }

    /** 渲染交易或明确的恢复/缺失状态，绝不保留布局中的示例值。 */
    private fun renderTransaction(txn: UiTransaction?) {
        if (txn != null) {
            binding.detailRowsContainer.visibility = View.VISIBLE
            binding.btnCopyHex.isEnabled = true
            binding.btnFeedbackHex.isEnabled = true
            binding.btnCopyHex.alpha = 1f
            binding.btnFeedbackHex.alpha = 1f
            bindTransactionData(txn)
            bindRawHex(txn)
            return
        }

        binding.tvAmountHeader.setText(R.string.loading_data)
        binding.tvAmountHeader.setTextColor(Palette.INK_3)
        binding.tvHeroTitle.text = ""
        binding.tvHeroSubtitle.text = ""
        binding.tvHeroTime.text = ""
        binding.tvHeroDirection.visibility = View.GONE
        binding.detailRowsContainer.visibility = View.GONE
        binding.btnCopyHex.isEnabled = false
        binding.btnFeedbackHex.isEnabled = false
        binding.btnCopyHex.alpha = 0.45f
        binding.btnFeedbackHex.alpha = 0.45f
        rawHexToCopy = ""
        rawRecordForFeedback = ""
        binding.hexPanel.removeAllViews()
        addMonospaceLine(
            binding.hexPanel,
            getString(R.string.loading_data),
            dim = true
        )
    }

    /** 详情行：值为空 / 占位（"-"）的行不显示 */
    private data class DetailRow(
        val label: String,
        val value: String,
        val style: (label: TextView, value: TextView, icon: TextView) -> Unit = { _, _, _ -> }
    )

    private fun bindTransactionData(txn: UiTransaction) {
        val fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.otf")
        val isEntry = txn.direction == TransitDirection.ENTRY
        val isExit = txn.direction == TransitDirection.EXIT
        val transactionType = if (txn.ticketProcessing) {
            getString(R.string.amount_ticket_processing)
        } else {
            when (txn.transitType) {
                "地铁" -> getString(
                    if (isEntry) R.string.txn_type_metro_entry
                    else if (isExit) R.string.txn_type_metro_exit
                    else R.string.transit_metro
                )
                "公交" -> getString(R.string.txn_type_bus_ride)
                "消费" -> getString(R.string.txn_type_small_purchase)
                else -> TransitLabels.type(txn.transitType)
            }
        }
        fun known(v: String?) = v?.trim()?.takeIf { it.isNotEmpty() && it != "-" && it != "—" && it != "未知" }
        // 站名保持原样，进出站方向由独立字段提供；未命中站点库（stationId 为空）时不显示站名
        val station = known(txn.stationName)?.takeIf { txn.stationId != null }
        val city = known(txn.cityName)?.let(TransitLabels::city)
        val line = known(txn.lineName)

        // ── 第一层：摘要 ──
        binding.tvHeroIcon.typeface = fa
        binding.tvHeroIcon.text = txn.icon
        Palette.applyTransitIcon(binding.tvHeroIcon, binding.tvHeroIcon, txn.transitType)
        binding.tvAmountHeader.text = txn.amountText
        binding.tvAmountHeader.setTextColor(Palette.amountColor(txn.amountText))
        binding.tvHeroTitle.text = station ?: transactionType
        binding.tvHeroSubtitle.text = listOfNotNull(
            city,
            known(txn.transitType)?.let(TransitLabels::type)?.takeIf { station != null || it != transactionType },
            line?.takeIf { it != station }
        ).joinToString(" · ")
        binding.tvHeroSubtitle.visibility = if (binding.tvHeroSubtitle.text.isEmpty()) View.GONE else View.VISIBLE
        binding.tvHeroTime.text = txn.displayDateTime
        binding.tvHeroDirection.apply {
            if (isEntry || isExit) {
                visibility = View.VISIBLE
                text = getString(if (isEntry) R.string.amount_entry else R.string.amount_exit)
                val tone = if (isEntry) Palette.AMOUNT_IN else Palette.INK_2
                setTextColor(tone)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpToPx(12).toFloat()
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(tone, 0x1F))
                }
            } else {
                visibility = View.GONE
            }
        }

        // ── 第二层：交易信息 ──
        fillSection(binding.sectionTxn, fa, listOf(
            DetailRow(getString(R.string.txn_row_type), transactionType) { _, _, icon ->
                if (isEntry || isExit) {
                    // 入站 = U+F090 箭头进框，出站 = U+F08B 箭头出框
                    icon.visibility = View.VISIBLE
                    icon.text = if (isEntry) "" else ""
                    icon.setTextColor(Palette.INK_3)
                }
            },
            DetailRow(getString(R.string.txn_row_amount), if (txn.amountYuan == 0.0) "¥0.00" else txn.amountText) { _, value, _ ->
                if (txn.amountText.startsWith("+")) value.setTextColor(Palette.AMOUNT_IN)
            },
            DetailRow(getString(R.string.txn_row_balance), txn.balanceAfterYuan?.let { "¥${String.format("%.2f", it)}" } ?: "-")
        ))

        // ── 第三层：行程（全部未知时整块隐藏） ──
        val tripShown = fillSection(binding.sectionTrip, fa, listOf(
            DetailRow(getString(R.string.label_city), city ?: "-"),
            DetailRow(getString(R.string.label_station), station ?: "-"),
            DetailRow(getString(R.string.label_line), line ?: "-")
        ))
        binding.sectionTripWrap.visibility = if (tripShown) View.VISIBLE else View.GONE

        // ── 第四层：设备与协议 ──
        fillSection(binding.sectionDevice, fa, listOf(
            DetailRow(getString(R.string.txn_row_protocol), txn.protocols.joinToString(" / ").ifEmpty { "-" }),
            DetailRow(getString(R.string.txn_row_terminal), txn.terminal) { _, value, _ -> value.typeface = Typeface.MONOSPACE }
        ))
    }

    /** 把有值的行填进分区卡片；行间插入分隔线。返回是否至少有一行 */
    private fun fillSection(container: LinearLayout, fa: Typeface, rows: List<DetailRow>): Boolean {
        container.removeAllViews()
        val shown = rows.filter { it.value.isNotBlank() && it.value != "-" }
        shown.forEachIndexed { i, r ->
            val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_detail_row, container, false)
            val label = row.findViewById<TextView>(R.id.detailLabel)
            val value = row.findViewById<TextView>(R.id.detailValue)
            val icon = row.findViewById<TextView>(R.id.detailIcon)
            label.text = r.label
            value.text = r.value
            icon.typeface = fa
            r.style(label, value, icon)
            // 行本身透明，白底与圆角由分区卡片背景提供；分隔线单独成视图。
            // 不用 clipToOutline 裁圆角：返回手势的页面快照是软件绘制，不支持轮廓裁剪，会露出直角
            row.background = null
            if (i > 0) {
                container.addView(View(requireContext()).apply { setBackgroundColor(Palette.LINE) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                        marginStart = dpToPx(16)
                    })
            }
            container.addView(row)
        }
        return shown.isNotEmpty()
    }

    /** 原始数据：展示该交易在 transactions_archive 中的所有 hex，按解析字段位置着色。 */
    private fun bindRawHex(txn: UiTransaction) {
        val hexContainer = binding.hexPanel
        hexContainer.removeAllViews()
        val mainHex = txn.hex
        val journeyHex = txn.journeyHex
        val variants = txn.rawVariants.orEmpty()
        if (mainHex.isBlank() && journeyHex.isNullOrBlank() && variants.isEmpty()) {
            rawHexToCopy = ""
            rawRecordForFeedback = ""
            addMonospaceLine(binding.hexPanel, getString(R.string.txn_no_raw), dim = true)
            return
        }
        val blocks = mutableListOf<RawBlock>()
        val sb = StringBuilder()
        if (mainHex.isNotBlank()) {
            appendHexBlock(binding.hexPanel, txn.sfi, mainHex, txn.protocol)
            blocks.add(RawBlock(txn.sfi, txn.protocol, mainHex))
            sb.append(mainHex)
        }
        for (variant in variants) {
            if (variant.hex.isBlank() || blocks.any { it.hex == variant.hex } || variant.hex == journeyHex) continue
            appendHexBlock(binding.hexPanel, variant.sfi, variant.hex, variant.protocol)
            blocks.add(RawBlock(variant.sfi, variant.protocol, variant.hex))
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(variant.hex)
        }
        if (!journeyHex.isNullOrBlank() && journeyHex != mainHex && blocks.none { it.hex == journeyHex }) {
            appendHexBlock(binding.hexPanel, 0x1E, journeyHex, "TU")
            blocks.add(RawBlock(0x1E, "TU", journeyHex))
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(journeyHex)
        }
        // Match 行：标签与 SFI 同色，值新起一行；特殊匹配规则（广佛跨城/深圳）附加标记
        addMonospaceLine(binding.hexPanel, "Match", dim = true)
        val matchCode = txn.deviceCode ?: "Null"
        addMonospaceLine(binding.hexPanel, if (txn.spRule != null) "$matchCode ${txn.spRule}" else matchCode)
        // 颜色图例：含义 + 区域 + 解析方式 + 颜色
        addDivider(binding.hexPanel)
        for (block in blocks) {
            val fields = RawHexFormatter.fieldsFor(
                block.sfi, ApduUtil.hexToBytes(block.hex).size, block.protocol, block.hex
            )
            if (fields.isEmpty()) continue
            addMonospaceLine(binding.hexPanel, "SFI ${block.sfi.toSfiHex()} fields", dim = true)
            for (f in fields) addLegendRow(binding.hexPanel, f)
        }
        rawHexToCopy = buildCopyText(sb.toString(), blocks, txn)
        rawRecordForFeedback = buildString {
            for (block in blocks) {
                if (isNotEmpty()) append("\n\n")
                append("SFI ${block.sfi.toSfiHex()}")
                if (block.protocol.isNotBlank()) append(" (${block.protocol})")
                append('\n').append(block.hex)
            }
            append("\n\n[Match] ").append(matchCode)
            if (txn.spRule != null) append(' ').append(txn.spRule)
        }
    }

    private fun buildCopyText(
        rawValue: String,
        blocks: List<RawBlock>,
        txn: UiTransaction
    ): String {
        val out = StringBuilder(rawValue)
        out.append("\n\n")
        for (block in blocks) {
            val fields = RawHexFormatter.fieldsFor(
                block.sfi, ApduUtil.hexToBytes(block.hex).size, block.protocol, block.hex
            )
            if (fields.isEmpty()) continue
            out.append("SFI ${block.sfi.toSfiHex()}\n")
            for (f in fields) {
                val methodPart = if (f.method.isEmpty()) "" else " ${f.method}"
                out.append("[${f.label} ${rangeText(f.start, f.end)}$methodPart] ")
                    .append(hexRange(block.hex, f.start, f.end))
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
        val fields = RawHexFormatter.fieldsFor(sfi, ApduUtil.hexToBytes(hex).size, protocol, hex)
        addMonospaceLine(container, "SFI ${sfi.toSfiHex()}", dim = true)
        addColoredHexLine(container, RawHexFormatter.colorizeHex(hex, fields))
    }

    private fun addDivider(container: LinearLayout) {
        val divider = View(requireContext()).apply { setBackgroundColor(Palette.NIGHT_2) }
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
        // 卡信息标签背景用主题色淡色填充（胶囊形）
        val bg = ColorUtils.blendARGB(0xFFFFFFFF.toInt(), accentColor, 0.12f)
        binding.cardBadge.background = GradientDrawable().apply {
            cornerRadius = dpToPx(999).toFloat()
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
