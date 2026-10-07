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
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.model.CardPalette
import com.example.nfctransit.data.RawRecord
import com.example.nfctransit.data.db.CardAppEntity
import com.example.nfctransit.data.toSfiHex
import com.example.nfctransit.databinding.FragmentCardInfoBinding
import com.example.nfctransit.databinding.ItemDetailRowBinding
import com.example.nfctransit.databinding.ItemDetailSectionBinding
import com.example.nfctransit.model.UiApplicationInfo
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
    private var accentColor = Palette.ACCENT
    private var rawHexToCopy = ""
    private var rawRecords = emptyList<RawRecord>()
    private var cardApps = emptyList<CardAppEntity>()

    private val colorNames get() = CardPalette.swatches.map { getString(it.nameRes) }

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

        binding.btnBack.setOnClickListener { (activity as? MainActivity)?.animatePredictiveBack() }
        binding.btnEditName.typeface = Typeface.createFromAsset(
            requireContext().assets,
            "fonts/fa-solid-900.otf"
        )
        binding.btnChangeColor.typeface = binding.btnEditName.typeface
        binding.rowEditName.setOnClickListener { showRenameDialog() }
        binding.rowChangeColor.setOnClickListener { showColorDialog() }
        binding.btnCopyRawData.setIconLabel(binding.btnEditName.typeface, "\uF0C5", getString(R.string.action_copy)) // fa-copy
        binding.btnCopyRawData.setOnClickListener {
            if (rawHexToCopy.isNotBlank()) {
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.card_info_copy_raw_cd), rawHexToCopy))
                Toast.makeText(requireContext(), R.string.card_raw_copied, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            accentColor = accent.toInt()
            binding.btnBack.setTextColor(accentColor)
            binding.btnEditName.setTextColor(accentColor)
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

    private var issuerCity: String? = null
    private var appProtocols: List<String> = emptyList()

    private fun bindMetadata(metadata: UiCardMetadata) {
        issuerCity = metadata.issuerCity
        appProtocols = metadata.appProtocols
        bindApplicationMetadata(metadata)
        viewModel.selectedCard.value?.let {
            updateHeroSubtitle(it)
            bindCardType(it)
        }
    }

    /** 卡片类型：列出卡上全部可用应用（如 LNT / TU）；元数据未就绪时退回内部卡型。 */
    private fun bindCardType(card: UiCard) {
        binding.tvCardType.text = appProtocols.joinToString(" / ")
            .ifEmpty { card.protocolType.ifBlank { card.cardType } }
    }

    /** 每个应用单独一个分区（如岭南通 / 交通联合）：卡号、发卡城市/机构、有效期及应用字段；无可展示字段的应用不显示。 */
    private fun bindApplicationMetadata(metadata: UiCardMetadata) {
        val container = binding.applicationMetadataContainer
        container.removeAllViews()
        metadata.applications.forEach { addApplicationSection(container, it) }
        container.visibility = if (container.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun addApplicationSection(container: LinearLayout, info: UiApplicationInfo) {
        val app = info.metadata
        val section = ItemDetailSectionBinding.inflate(layoutInflater, container, false)
        section.sectionTitle.text = (info.name ?: info.protocol.takeIf { it.isNotBlank() })
            ?.let { getString(R.string.card_info_application_named, it) }
            ?: getString(R.string.card_info_application)
        val panel = section.sectionRows
        fun add(label: String, value: String?, monospace: Boolean = false) {
            if (value == null) return
            val row = ItemDetailRowBinding.inflate(layoutInflater, panel, false)
            row.detailLabel.text = label
            row.detailValue.text = value
            if (monospace) {
                row.detailValue.typeface = Typeface.MONOSPACE
                row.detailValue.setTextIsSelectable(true)
            }
            panel.addView(row.root)
        }
        add(getString(R.string.card_info_number), info.cardNumber, monospace = true)
        add(getString(R.string.card_info_issuer_city), info.issuerCity)
        add(getString(R.string.card_info_issuer), app.issuer)
        add(getString(R.string.card_info_issue_date), app.issueDate)
        add(getString(R.string.card_info_valid_until), app.validUntil)
        run {
            fun yesNo(value: Boolean?) = value?.let { getString(if (it) R.string.card_info_enabled else R.string.card_info_disabled) }
            add(getString(R.string.card_info_card_kind), app.cardKind?.let {
                val label = when (it) {
                    1 -> R.string.card_kind_normal
                    2 -> R.string.card_kind_student
                    3 -> R.string.card_kind_senior
                    4 -> R.string.card_kind_test
                    5 -> R.string.card_kind_military
                    else -> null
                }
                label?.let(::getString) ?: "0x%02X".format(it)
            })
            add(getString(R.string.card_info_interoperability), yesNo(app.interoperabilityEnabled))
            add(getString(R.string.card_info_interoperability_code), app.interoperabilityCode)
            add(getString(R.string.card_info_application_version), app.applicationVersion)
            add(getString(R.string.card_info_country_code), app.countryCode)
            add(getString(R.string.card_info_province_code), app.provinceCode)
            add(getString(R.string.card_info_industry_code), app.industryCode)
            add(getString(R.string.card_info_algorithm_support), app.algorithmSupport)
        }
        if (panel.childCount == 0) return
        refreshRowDividers(panel)
        container.addView(section.root)
    }

    /** 摘要副标题：卡片类型 · 发卡城市 */
    private fun updateHeroSubtitle(card: UiCard) {
        binding.tvHeroSubtitle.text = listOfNotNull(
            card.protocolType.ifBlank { card.cardType }.takeIf { it.isNotBlank() },
            issuerCity?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
    }

    /** 分区卡片内只在可见行之间画分隔线（第二卡号/第二标准行可能隐藏） */
    private fun refreshRowDividers(section: LinearLayout) {
        var first = true
        for (i in 0 until section.childCount) {
            val row = section.getChildAt(i)
            if (row.visibility != View.VISIBLE) continue
            row.setBackgroundResource(if (first) 0 else R.drawable.bg_row_divider_top)
            first = false
        }
    }

    private fun bindCard(card: UiCard) {
        binding.tvCardName.text = card.name
        binding.tvNameValue.text = card.name
        updateHeroSubtitle(card)
        binding.tvCardBadge.text = "${card.name} · ${card.lastFour}"
        bindCardType(card)
        binding.tvBalance.text = card.balanceFen?.let { "¥${String.format(Locale.getDefault(), "%.2f", it / 100.0)}" } ?: "—"
        binding.tvLastRead.text = getString(R.string.last_read_format, TimeLabels.absolute(card.lastReadAt))
        binding.heroCardFace.background = cardGradient(card, dpToPx(6))
        binding.cardColorPreview.background = cardGradient(card, dpToPx(4))
        val colorIndex = viewModel.cardColorOptions().indexOfFirst {
            it.first == card.gradientStartColor && it.second == card.gradientEndColor
        }
        binding.tvColorName.text = colorNames.getOrNull(colorIndex) ?: getString(R.string.period_custom)
        refreshRowDividers(binding.sectionCardInfo)
    }

    private fun cardGradient(card: UiCard, radius: Int) = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
        intArrayOf(card.gradientStartColor.toInt(), card.gradientEndColor.toInt())
    ).apply { cornerRadius = radius.toFloat() }

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
                val fieldVariants = linkedSetOf<List<RawHexFormatter.FieldSpec>>()
                records.forEach { record ->
                    val data = runCatching { com.example.nfctransit.ApduUtil.hexToBytes(record.hex) }.getOrNull()
                    val isEc = record.selectedAid.equals(com.example.nfctransit.CardProfiles.TU_EC_AID, true)
                    val format = visibleRecords.firstOrNull {
                        isEc && it.selectedAid.equals(record.selectedAid, true) && it.sfi == sfi && it.recNo == 0
                    }?.hex
                    val fields = if (isEc && (record.recNo == 0 || sfi in setOf(1, 2, 3, 4, 8))) {
                        RawHexFormatter.fieldsForSelectResponse(record.hex)
                    } else RawHexFormatter.fieldsFor(sfi, data?.size ?: 0, record.protocol, record.hex, format)
                    if (fields.isNotEmpty()) fieldVariants.add(fields)
                    appendRawBlock(
                        panel,
                        RawHexFormatter.header(record),
                        record.hex,
                        fields,
                        addDivider = previousSfi != sfi || panel.childCount == 0,
                        showFields = false
                    )
                    previousSfi = sfi
                }
                for (fields in fieldVariants) {
                    addMonospaceLine(panel, "SFI ${sfi.toSfiHex()} · $protocol fields", dim = true)
                    fields.forEach { addLegendRow(panel, it) }
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
        addDivider: Boolean,
        showFields: Boolean = true
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
        if (showFields && fields.isNotEmpty()) {
            addMonospaceLine(panel, "$title fields", dim = true)
            fields.forEach { addLegendRow(panel, it) }
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
        val divider = View(requireContext()).apply { setBackgroundColor(Palette.NIGHT_2) }
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
            title = getString(R.string.card_color_title),
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
            title = getString(R.string.card_rename_title),
            initialValue = card.name,
            hint = getString(R.string.card_rename_hint),
            maxLength = 30,
            accentColor = accentColor
        ) { value ->
            if (!viewModel.renameSelectedCard(value)) {
                Toast.makeText(requireContext(), R.string.card_name_empty, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBadgeBackground() {
        val bg = ColorUtils.blendARGB(0xFFFFFFFF.toInt(), accentColor, 0.12f)
        binding.cardBadge.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dpToPx(999).toFloat()
            setColor(bg)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        renameDialog?.dismiss()
        renameDialog = null
        super.onDestroyView()
        _binding = null
    }
}
