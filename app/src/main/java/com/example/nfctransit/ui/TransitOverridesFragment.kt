package com.example.nfctransit.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.data.OverrideDuplicate
import com.example.nfctransit.data.OverrideDuplicateKind
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.TransitOverrideRow
import com.example.nfctransit.databinding.FragmentTransitOverridesBinding

class TransitOverridesFragment : Fragment(R.layout.fragment_transit_overrides) {
    private var _binding: FragmentTransitOverridesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })
    private var accentColor = Palette.ACCENT
    private var allRows: List<TransitOverrideRow> = emptyList()
    private var duplicates: Map<String, OverrideDuplicate> = emptyMap()
    private lateinit var fa: Typeface

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransitOverridesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.otf")
        binding.btnBack.setOnClickListener { (activity as? MainActivity)?.animatePredictiveBack() }
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                binding.clearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                renderFiltered()
            }
        })
        binding.clearButton.setOnClickListener {
            binding.searchInput.text?.clear()
            binding.searchInput.clearFocus()
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        }
        viewModel.mainAccent.observe(viewLifecycleOwner) {
            accentColor = it.toInt()
            binding.btnBack.setTextColor(accentColor)
            binding.tvOverrideCount.setTextColor(accentColor)
            renderRows(viewModel.overrideRows.value.orEmpty())
        }
        viewModel.overrideRows.observe(viewLifecycleOwner, Observer(::renderRows))
        viewModel.overrideDuplicates.observe(viewLifecycleOwner) { found ->
            duplicates = found.orEmpty().associateBy { it.row.mappingKey }
            renderDuplicateBanner()
            renderFiltered()
        }
        binding.duplicateIcon.typeface = fa
        binding.duplicateIcon.text = ""   // clone
        binding.btnReviewDuplicates.setOnClickListener { reviewDuplicates() }
        binding.duplicateBanner.setOnClickListener { reviewDuplicates() }
        viewModel.overrideStatus.observe(viewLifecycleOwner) { status ->
            if (!status.isNullOrBlank()) {
                Toast.makeText(requireContext(), status, Toast.LENGTH_LONG).show()
                viewModel.consumeOverrideStatus()
            }
        }
        viewModel.refreshOverrideRows()
    }

    private fun renderRows(rows: List<TransitOverrideRow>) {
        allRows = rows
        binding.tvOverrideCount.apply {
            text = rows.size.toString()
            visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        }
        binding.searchBox.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        // 重复结果可能早于行列表到达（或行已被删），只保留仍存在的
        val keys = rows.mapTo(HashSet()) { it.mappingKey }
        duplicates = duplicates.filterKeys { it in keys }
        renderDuplicateBanner()
        renderFiltered()
    }

    /** 「与映射库重复」提示条：点按打开预览，逐条对照后勾选删除 */
    private fun renderDuplicateBanner() {
        val same = duplicates.values.count { it.kind == OverrideDuplicateKind.SAME }
        val similar = duplicates.size - same
        binding.duplicateBanner.visibility = if (duplicates.isEmpty()) View.GONE else View.VISIBLE
        if (duplicates.isEmpty()) return
        binding.duplicateTitle.text = resources.getQuantityString(
            R.plurals.overrides_duplicates_title, duplicates.size, duplicates.size
        )
        binding.duplicateDetail.text = getString(R.string.overrides_duplicates_detail, same, similar)
    }

    private fun reviewDuplicates() {
        if (duplicates.isEmpty()) return
        AppDialogs.duplicateReview(requireContext(), duplicates.values.toList(), accentColor) { keys ->
            viewModel.deleteOverrides(keys)
        }
    }

    /** 按搜索词过滤后按交通类型分组：每组一个标题（类型 · 条数）+ 一个分段列表 */
    private fun renderFiltered() {
        val groups = binding.overrideGroups
        groups.removeAllViews()
        val cityNames = TransitData.cityOptions().associate { it.code to TransitLabels.city(it.name) }
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        val shown = if (query.isEmpty()) allRows else allRows.filter { row ->
            listOf(
                row.deviceCode, row.station, row.line, TransitLabels.type(row.type),
                row.locationCityCode?.let(cityNames::get).orEmpty()
            ).any { it.contains(query, ignoreCase = true) }
        }

        showEmpty(noData = allRows.isEmpty(), noMatch = shown.isEmpty())
        if (shown.isEmpty()) return

        shown.groupBy { it.type }
            .entries
            .sortedWith(compareBy({ typeOrder(it.key) }, { -it.value.size }, { it.key }))
            .forEachIndexed { i, (type, items) ->
                groups.addView(sectionHeader(TransitLabels.type(type), items.size, first = i == 0))
                val list = SegmentedListLayout(requireContext())
                items.sortedWith(compareBy({ it.line }, { it.station }, { it.deviceCode }))
                    .forEach { list.addView(buildRow(it, cityNames)) }
                groups.addView(list)
            }
    }

    private fun showEmpty(noData: Boolean, noMatch: Boolean) {
        binding.emptyState.visibility = if (noMatch) View.VISIBLE else View.GONE
        if (!noMatch) return
        binding.emptyIcon.apply {
            typeface = fa
            text = if (noData) "" else ""   // database / magnifying-glass
            setTextColor(accentColor)
            (background?.mutate() as? GradientDrawable)
                ?.setColor(ColorUtils.setAlphaComponent(accentColor, 0x1F))
        }
        binding.emptyTitle.setText(if (noData) R.string.overrides_empty else R.string.overrides_no_match)
        binding.emptyHint.setText(if (noData) R.string.overrides_empty_hint else R.string.overrides_no_match_hint)
    }

    private fun sectionHeader(label: String, count: Int, first: Boolean): View =
        TextView(requireContext()).apply {
            text = getString(R.string.overrides_group_header, label, count)
            setTextColor(Palette.INK_2)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(4), dp(if (first) 12 else 24), dp(4), dp(8))
        }

    /** 行：类型图标 / 站名 + 线路·城市 + 设备编号 / 删除；整行点按编辑。分段白底与圆角由 SegmentedListLayout 提供 */
    private fun buildRow(row: TransitOverrideRow, cityNames: Map<String, String>): View {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_override_row, binding.overrideGroups, false)
        val category = iconCategory(row.type)

        view.findViewById<TextView>(R.id.overrideIcon).apply {
            typeface = fa
            text = glyphFor(category)
            Palette.applyTransitIcon(this, this, category)
        }
        // 无站名（多见于公交）时以线路作标题，副行不再重复线路
        val title = row.station.ifBlank { row.line }
        view.findViewById<TextView>(R.id.overrideStation).text = title.ifBlank { getString(R.string.unknown) }
        view.findViewById<TextView>(R.id.overrideMeta).apply {
            val parts = listOfNotNull(
                row.line.takeIf { it.isNotBlank() && it != title },
                row.locationCityCode?.let(cityNames::get)?.takeIf { it.isNotBlank() }
            )
            text = parts.joinToString(" · ")
            visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.overrideCode).text = "${row.prefix} ${row.code}"
        view.findViewById<TextView>(R.id.overrideDuplicateTag).apply {
            val dup = duplicates[row.mappingKey]
            visibility = if (dup == null) View.GONE else View.VISIBLE
            text = when (dup?.kind) {
                OverrideDuplicateKind.SAME -> getString(R.string.overrides_tag_identical)
                OverrideDuplicateKind.SIMILAR -> getString(
                    R.string.overrides_tag_similar,
                    listOf(dup.dbLine, dup.dbStation).filter { it.isNotBlank() }.joinToString(" · ")
                )
                null -> ""
            }
        }
        view.findViewById<TextView>(R.id.overrideDelete).apply {
            typeface = fa
            text = ""   // trash-can
            setOnClickListener { showDeleteDialog(row) }
        }
        view.setOnClickListener { showEditDialog(row) }
        return view
    }

    /** 映射表原始类型 -> 图标配色/字形用的大类键（与 TransactionMapper 的归类一致） */
    private fun iconCategory(type: String): String = when {
        type.contains("充值") -> "充值"
        type in BUS_LIKE_TYPES -> "公交"
        else -> type
    }

    private fun glyphFor(category: String): String = when (category) {
        "充值" -> ""
        "地铁" -> ""
        "公交" -> ""
        "有轨电车" -> ""
        "城际" -> ""
        "便利店", "消费" -> ""
        else -> ""
    }

    private fun typeOrder(type: String): Int =
        TYPE_ORDER.indexOf(iconCategory(type)).let { if (it < 0) TYPE_ORDER.size else it }

    private fun showEditDialog(row: TransitOverrideRow) {
        AppDialogs.overrideEditor(
            context = requireContext(),
            row = row,
            accentColor = accentColor
        ) { prefix, code, type, line, station, cityCode, _, locationSource, publish ->
            val updated = TransitOverrideRow(
                prefix.trim(), code.trim(), type.trim(), line.trim(), station.trim(),
                cityCode.trim().takeIf { it.isNotEmpty() }
            )
            val error = validate(updated)
            if (error != null) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                false
            } else {
                viewModel.updateOverride(row.mappingKey, updated, locationSource, publish)
                true
            }
        }
    }

    private fun showDeleteDialog(row: TransitOverrideRow) {
        AppDialogs.confirm(
            context = requireContext(),
            title = getString(R.string.override_delete_title),
            message = getString(R.string.override_delete_message),
            confirmLabel = getString(R.string.action_delete),
            confirmColor = Palette.DANGER
        ) {
            viewModel.deleteOverride(row.mappingKey)
        }
    }

    private fun validate(row: TransitOverrideRow): String? {
        val codeRegex = Regex("[0-9A-Za-z]+")
        if (row.prefix.length !in 1..16 || !row.prefix.matches(codeRegex)) return getString(R.string.override_err_prefix)
        if (row.code.length !in 1..64 || !row.code.matches(codeRegex)) return getString(R.string.override_err_code)
        if (row.type.isBlank() || row.type.length > 32) return getString(R.string.override_err_type)
        if (row.line.length > 128) return getString(R.string.override_err_line)
        if (row.station.length > 128) return getString(R.string.override_err_station)
        val locationCityCode = row.locationCityCode
        if (locationCityCode.isNullOrBlank() ||
            TransitData.cityOptions().none { it.code == locationCityCode }
        ) return getString(R.string.override_err_city)
        if (listOf(row.type, row.line, row.station).any { it.contains('\n') || it.contains('\r') }) {
            return getString(R.string.override_err_newline)
        }
        return null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        val BUS_LIKE_TYPES = setOf("公交", "公共交通", "BRT", "bus", "定制公交")
        val TYPE_ORDER = listOf("地铁", "公交", "有轨电车", "城际", "充值", "便利店", "消费")
    }
}
