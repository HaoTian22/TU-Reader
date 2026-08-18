package com.example.nfctransit.ui

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
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
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.R
import com.example.nfctransit.data.RawRecord
import com.example.nfctransit.data.db.CardAppEntity
import com.example.nfctransit.data.toSfiHex
import com.example.nfctransit.databinding.FragmentCardInfoBinding
import com.example.nfctransit.model.UiCard
import com.example.nfctransit.model.UiCardMetadata
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CardInfoFragment : Fragment(R.layout.fragment_card_info) {

    private var _binding: FragmentCardInfoBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })
    private var renameDialog: Dialog? = null
    private var accentColor = 0xFF0066FF.toInt()
    private var rawHexToCopy = ""
    private var rawRecords = emptyList<RawRecord>()
    private var cardApps = emptyList<CardAppEntity>()

    private val colorNames = listOf(
        "蓝色", "绿色", "橙色", "紫色", "红色", "青色", "金色", "棕色", "蓝灰色", "玫红色",
        "翡翠色", "靛蓝色", "粉红色", "朱橙色", "蓝紫色", "翠绿色", "天青色", "猩红色", "紫红色", "青绿色"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCardInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnEditName.typeface = Typeface.createFromAsset(
            requireContext().assets,
            "fonts/fa-solid-900.ttf"
        )
        binding.btnChangeColor.typeface = binding.btnEditName.typeface
        binding.btnEditName.setOnClickListener { showRenameDialog() }
        binding.btnChangeColor.setOnClickListener { showColorDialog() }
        binding.btnCopyRawData.typeface = binding.btnEditName.typeface
        binding.btnCopyRawData.setOnClickListener {
            if (rawHexToCopy.isNotBlank()) {
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("卡片原始数据", rawHexToCopy))
                Toast.makeText(requireContext(), "已复制卡片原始数据", Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            accentColor = accent.toInt()
            binding.btnBack.setTextColor(accentColor)
            binding.btnEditName.setTextColor(accentColor)
            binding.btnChangeColor.setTextColor(accentColor)
            binding.tvCardBadge.setTextColor(accentColor)
            updateBadgeBackground()
        }
        viewModel.selectedCard.observe(viewLifecycleOwner) { card ->
            if (card != null) bindCard(card)
        }
        viewModel.selectedCardMetadata.observe(viewLifecycleOwner) { metadata ->
            bindMetadata(metadata)
        }
        viewModel.selectedRawRecords.observe(viewLifecycleOwner) { records ->
            rawRecords = records
            bindRawData()
        }
        viewModel.selectedCardApps.observe(viewLifecycleOwner) { apps ->
            cardApps = apps
            bindRawData()
        }
    }

    private fun bindMetadata(metadata: UiCardMetadata) {
        binding.tvIssuerCity.text = metadata.issuerCity ?: "—"
        binding.tvIssuer.text = metadata.issuer ?: "—"
        binding.tvIssueDate.text = metadata.issueDate ?: "—"
        binding.tvValidUntil.text = metadata.validUntil ?: "—"
        val secondStandard = metadata.secondStandard
        val hasSecondStandard = secondStandard != null
        binding.secondStandardIssueRow.visibility = if (hasSecondStandard) View.VISIBLE else View.GONE
        binding.secondStandardValidRow.visibility = if (hasSecondStandard) View.VISIBLE else View.GONE
        binding.tvSecondIssueLabel.text = "${secondStandard ?: "第二标准"}发行日期"
        binding.tvSecondValidLabel.text = "${secondStandard ?: "第二标准"}有效期至"
        binding.tvSecondIssueDate.text = metadata.secondIssueDate ?: "—"
        binding.tvSecondValidUntil.text = metadata.secondValidUntil ?: "—"
    }

    private fun bindCard(card: UiCard) {
        binding.tvCardName.text = card.name
        binding.tvCardBadge.text = "${card.name} · ${card.lastFour}"
        binding.tvCardType.text = card.protocolType.ifBlank { card.cardType }
        binding.tvCardNumber.text = card.cardNumber.ifBlank { "•••• ${card.lastFour}" }
        binding.secondCardNumberRow.visibility =
            if (card.secondCardNumber.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.tvSecondCardNumber.text = card.secondCardNumber.orEmpty()
        binding.tvBalance.text = "¥${String.format(Locale.getDefault(), "%.2f", card.balanceYuan)}"
        binding.tvLastRead.text = formatLastRead(card.lastReadAt)
        binding.cardColorPreview.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(card.gradientStartColor.toInt(), card.gradientEndColor.toInt())
        ).apply {
            cornerRadius = dpToPx(8).toFloat()
        }
    }

    private fun bindRawData() {
        val visibleRecords = rawRecords.filter { it.hex.isNotBlank() }
        val visibleApps = cardApps.filter {
            it.selectedAid.isNotBlank() || it.selectResp.isNotBlank() || !it.balanceResp.isNullOrBlank()
        }
        if (visibleRecords.isEmpty() && visibleApps.isEmpty()) {
            rawHexToCopy = ""
            binding.rawDataSection.visibility = View.GONE
            return
        }

        val ordered = visibleRecords.sortedWith(
            compareBy<RawRecord> { it.protocol }.thenBy { it.sfi }.thenBy { it.recNo }
        )
        val panel = binding.rawDataPanel
        panel.removeAllViews()
        visibleApps.forEachIndexed { index, app ->
            val appDivider = index > 0
            appendRawBlock(panel, "selected_aid", app.selectedAid, emptyList(), appDivider)
            if (app.selectResp.isNotBlank()) {
                appendRawBlock(
                    panel,
                    "select_resp",
                    app.selectResp,
                    RawHexFormatter.fieldsForSelectResponse(app.selectResp),
                    false
                )
            }
            if (!app.balanceResp.isNullOrBlank()) {
                appendRawBlock(panel, "balance_resp", app.balanceResp, emptyList(), false)
            }
        }

        ordered.groupBy { it.protocol.ifBlank { "GEN" } }.forEach { (protocol, appRecords) ->
            val groups = appRecords.groupBy { it.sfi }.toSortedMap()
            var previousSfi: Int? = null
            groups.forEach { (sfi, records) ->
                val first = records.first()
                val data = runCatching { com.example.nfctransit.ApduUtil.hexToBytes(first.hex) }.getOrNull()
                val fields = RawHexFormatter.fieldsFor(sfi, data?.size ?: 0, first.protocol)
                val isLoopRecord = records.size > 1 || sfi == 0x18 || sfi == 0x1E
                records.forEach { record ->
                    appendRawBlock(
                        panel,
                        RawHexFormatter.header(record),
                        record.hex,
                        if (isLoopRecord) emptyList() else fields,
                        addDivider = previousSfi != sfi || panel.childCount == 0
                    )
                    previousSfi = sfi
                }
                if (isLoopRecord && fields.isNotEmpty()) {
                    addDivider(panel)
                    addMonospaceLine(panel, "SFI ${sfi.toSfiHex()} · $protocol fields", dim = true)
                    addFieldDetails(panel, first.hex, fields)
                }
            }
        }

        binding.rawDataSection.visibility = View.VISIBLE
        rawHexToCopy = buildRawCopy(ordered, visibleApps)
    }

    private fun appendRawBlock(
        panel: LinearLayout,
        title: String,
        hex: String,
        fields: List<RawHexFormatter.FieldSpec>,
        addDivider: Boolean
    ) {
        if (hex.isBlank()) return
        if (addDivider && panel.childCount > 0) addDivider(panel)
        addMonospaceLine(panel, title, dim = true)
        val displayHex = wrappedHex(hex)
        val hexLine = TextView(requireContext()).apply {
            text = RawHexFormatter.colorizeHex(displayHex, fields)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(RawHexFormatter.RAW)
            setPadding(0, dpToPx(2), 0, dpToPx(2))
            setTextIsSelectable(true)
            setHorizontallyScrolling(false)
        }
        panel.addView(hexLine, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        if (fields.isNotEmpty()) {
            addMonospaceLine(panel, "$title fields", dim = true)
            addFieldDetails(panel, hex, fields)
        }
    }

    private fun addFieldDetails(
        panel: LinearLayout,
        hex: String,
        fields: List<RawHexFormatter.FieldSpec>
    ) {
        fields.forEach { field ->
            val value = TextView(requireContext()).apply {
                text = RawHexFormatter.hexRange(hex, field.start, field.end)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(field.color)
                setPadding(0, dpToPx(1), 0, 0)
                setTextIsSelectable(true)
            }
            panel.addView(value, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addLegendRow(panel, field)
        }
    }

    private fun wrappedHex(hex: String): String = hex
        .filterNot { it.isWhitespace() }
        .chunked(40)
        .joinToString("\n")

    private fun buildRawCopy(
        records: List<RawRecord>,
        apps: List<CardAppEntity>
    ): String {
        val out = StringBuilder()
        apps.forEach { app ->
            if (app.selectedAid.isNotBlank()) {
                if (out.isNotEmpty()) out.append("\n\n")
                out.append("selected_aid\n").append(app.selectedAid)
            }
            if (app.selectResp.isNotBlank()) {
                if (out.isNotEmpty()) out.append("\n\n")
                out.append("select_resp\n").append(app.selectResp)
                val fields = RawHexFormatter.fieldsForSelectResponse(app.selectResp)
                fields.forEach { field ->
                    val method = field.method.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
                    out.append('\n').append("[${field.label} ${RawHexFormatter.rangeText(field.start, field.end)}$method] ")
                        .append(RawHexFormatter.hexRange(app.selectResp, field.start, field.end))
                }
            }
            if (!app.balanceResp.isNullOrBlank()) {
                if (out.isNotEmpty()) out.append("\n\n")
                out.append("balance_resp\n").append(app.balanceResp)
            }
        }
        if (records.isNotEmpty()) {
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(RawHexFormatter.copyText(records))
        }
        return out.toString()
    }

    private fun addDivider(panel: LinearLayout) {
        val divider = View(requireContext()).apply { setBackgroundColor(0xFF333366.toInt()) }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1))
        lp.setMargins(0, dpToPx(8), 0, dpToPx(8))
        panel.addView(divider, lp)
    }

    private fun addMonospaceLine(panel: LinearLayout, text: String, dim: Boolean = false) {
        val line = TextView(requireContext()).apply {
            this.text = text
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(if (dim) RawHexFormatter.DIM else RawHexFormatter.RAW)
            setPadding(0, dpToPx(2), 0, dpToPx(2))
            setTextIsSelectable(true)
        }
        panel.addView(line)
    }

    private fun addLegendRow(panel: LinearLayout, field: RawHexFormatter.FieldSpec) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(1), 0, dpToPx(1))
        }
        row.addView(TextView(requireContext()).apply {
            text = "●"
            textSize = 8f
            setTextColor(field.color)
            setPadding(0, 0, dpToPx(6), 0)
        })
        row.addView(TextView(requireContext()).apply {
            text = field.label
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(field.color)
        })
        val method = field.method.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        row.addView(TextView(requireContext()).apply {
            text = " [${RawHexFormatter.rangeText(field.start, field.end)}$method]"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(RawHexFormatter.LEGEND_TEXT)
            setTextIsSelectable(true)
        })
        panel.addView(row)
    }

    private fun showColorDialog() {
        val card = viewModel.selectedCard.value ?: return
        val colors = viewModel.cardColorOptions()
        val selected = colors.indexOfFirst {
            it.first == card.gradientStartColor && it.second == card.gradientEndColor
        }
        AppDialogs.options(
            context = requireContext(),
            title = "选择卡面颜色",
            options = colorNames.take(colors.size),
            selectedIndex = selected,
            accentColor = accentColor,
            maxHeightDp = 320,
            onSelect = { index ->
                colors.getOrNull(index)?.let { (start, end) ->
                    viewModel.setSelectedCardColors(start, end)
                }
            }
        )
    }

    private fun showRenameDialog() {
        val card = viewModel.selectedCard.value ?: return
        renameDialog = AppDialogs.textInput(
            context = requireContext(),
            title = "自定义卡片名称",
            initialValue = card.name,
            hint = "请输入卡片名称",
            maxLength = 30,
            accentColor = accentColor
        ) { value ->
            if (!viewModel.renameSelectedCard(value)) {
                Toast.makeText(requireContext(), "卡片名称不能为空", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBadgeBackground() {
        val bg = ColorUtils.blendARGB(0xFFFFFFFF.toInt(), accentColor, 0.12f)
        binding.cardBadge.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dpToPx(10).toFloat()
            setColor(bg)
        }
    }

    private fun formatLastRead(lastReadAt: Long): String {
        if (lastReadAt <= 0L) return "—"
        val cal = Calendar.getInstance().apply { timeInMillis = lastReadAt }
        val now = Calendar.getInstance()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
        if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        ) return "今天 $time"
        val datePattern = if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            "M月d日"
        } else {
            "yyyy年M月d日"
        }
        return "${SimpleDateFormat(datePattern, Locale.getDefault()).format(cal.time)} $time"
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        renameDialog?.dismiss()
        renameDialog = null
        super.onDestroyView()
        _binding = null
    }
}
