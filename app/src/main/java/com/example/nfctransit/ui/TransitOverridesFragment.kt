package com.example.nfctransit.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.TransitOverrideRow
import com.example.nfctransit.databinding.FragmentTransitOverridesBinding

class TransitOverridesFragment : Fragment(R.layout.fragment_transit_overrides) {
    private var _binding: FragmentTransitOverridesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })
    private var accentColor = Palette.ACCENT

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
        binding.btnBack.setOnClickListener { (activity as? MainActivity)?.animatePredictiveBack() }
        viewModel.mainAccent.observe(viewLifecycleOwner) {
            accentColor = it.toInt()
            binding.btnBack.setTextColor(accentColor)
            renderRows(viewModel.overrideRows.value.orEmpty())
        }
        viewModel.overrideRows.observe(viewLifecycleOwner, Observer(::renderRows))
        viewModel.overrideStatus.observe(viewLifecycleOwner) { status ->
            if (!status.isNullOrBlank()) {
                Toast.makeText(requireContext(), status, Toast.LENGTH_LONG).show()
                viewModel.consumeOverrideStatus()
            }
        }
        viewModel.refreshOverrideRows()
    }

    private fun renderRows(rows: List<TransitOverrideRow>) {
        val container = binding.overrideList
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                setText(R.string.overrides_empty)
                gravity = Gravity.CENTER
                setTextColor(Palette.INK_3)
                textSize = 14f
                setPadding(0, dp(48), 0, 0)
            })
            return
        }
        rows.forEach { row -> container.addView(buildRow(row)) }
    }

    private fun buildRow(row: TransitOverrideRow): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_content_card)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val codeView = TextView(requireContext()).apply {
            text = "${row.prefix}${row.code}"
            typeface = Typeface.MONOSPACE
            setTextColor(Palette.INK)
            textSize = 15f
        }
        val detailView = TextView(requireContext()).apply {
            text = "${TransitLabels.type(row.type)} · ${row.line} · ${row.station}"
            setTextColor(Palette.INK_2)
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        }
        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(TextView(requireContext()).apply {
            setText(R.string.action_edit)
            setTextColor(accentColor)
            textSize = 14f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { showEditDialog(row) }
        })
        actions.addView(TextView(requireContext()).apply {
            setText(R.string.action_delete)
            setTextColor(Palette.DANGER)
            textSize = 14f
            setPadding(dp(12), dp(6), dp(0), dp(6))
            setOnClickListener { showDeleteDialog(row) }
        })
        card.addView(codeView)
        card.addView(detailView)
        card.addView(actions)
        return card
    }

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
}
