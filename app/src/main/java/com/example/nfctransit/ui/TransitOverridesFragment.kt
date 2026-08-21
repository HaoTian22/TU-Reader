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
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.R
import com.example.nfctransit.data.TransitOverrideRow
import com.example.nfctransit.databinding.FragmentTransitOverridesBinding

class TransitOverridesFragment : Fragment(R.layout.fragment_transit_overrides) {
    private var _binding: FragmentTransitOverridesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })
    private var accentColor = 0xFF0066FF.toInt()

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
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
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
                text = "暂无本地站名覆盖"
                gravity = Gravity.CENTER
                setTextColor(0xFF8E8E93.toInt())
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
            setTextColor(0xFF1A1A1A.toInt())
            textSize = 15f
        }
        val detailView = TextView(requireContext()).apply {
            text = "${row.type} · ${row.line} · ${row.station}"
            setTextColor(0xFF666666.toInt())
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        }
        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(TextView(requireContext()).apply {
            text = "编辑"
            setTextColor(accentColor)
            textSize = 14f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { showEditDialog(row) }
        })
        actions.addView(TextView(requireContext()).apply {
            text = "删除"
            setTextColor(0xFFFF3B30.toInt())
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
        ) { prefix, code, type, line, station ->
            val updated = TransitOverrideRow(
                prefix.trim(), code.trim(), type.trim(), line.trim(), station.trim()
            )
            val error = validate(updated)
            if (error != null) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.updateOverride(row.deviceCode, updated)
            }
        }
    }

    private fun showDeleteDialog(row: TransitOverrideRow) {
        AppDialogs.confirm(
            context = requireContext(),
            title = "删除站名覆盖",
            message = "删除后将恢复该设备原来的站名映射。确定删除吗？",
            confirmLabel = "删除",
            confirmColor = 0xFFFF3B30.toInt()
        ) {
            viewModel.deleteOverride(row.deviceCode)
        }
    }

    private fun validate(row: TransitOverrideRow): String? {
        val codeRegex = Regex("[0-9A-Za-z]+")
        if (row.prefix.length !in 1..16 || !row.prefix.matches(codeRegex)) return "Prefix 无效"
        if (row.code.length !in 1..64 || !row.code.matches(codeRegex)) return "Code 无效"
        if (row.type.isBlank() || row.type.length > 32) return "Type 无效"
        if (row.line.length > 128) return "线路过长"
        if (row.station.length > 128) return "站名过长"
        if (listOf(row.type, row.line, row.station).any { it.contains('\n') || it.contains('\r') }) {
            return "字段不能包含换行"
        }
        return null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
