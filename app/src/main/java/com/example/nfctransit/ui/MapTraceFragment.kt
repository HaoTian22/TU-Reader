package com.example.nfctransit.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentMapTraceBinding

class MapTraceFragment : Fragment(R.layout.fragment_map_trace) {

    private var _binding: FragmentMapTraceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapTraceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        // 主题色跟随卡片：返回按钮、badge、播放进度条一起变
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            val color = accent.toInt()
            binding.tvCardBadge.setTextColor(color)
            binding.progressPlayback.setBackgroundColor(color)
        }

        viewModel.selectedCard.observe(viewLifecycleOwner) { card ->
            if (card != null) {
                binding.tvCardBadge.text = "${card.cardType} · ${card.lastFour}"
                binding.tvCardBadge.setTextColor(card.gradientStartColor.toInt())
            }
        }

        viewModel.allTransactions.observe(viewLifecycleOwner) { txns ->
            bindTripInfo(txns)
        }
    }

    private fun bindTripInfo(transactions: List<com.example.nfctransit.model.UiTransaction>) {
        val container = binding.tripListContainer
        // Remove previously added trip rows (keep the title)
        val childCount = container.childCount
        if (childCount > 1) {
            container.removeViews(1, childCount - 1)
        }

        // Filter to transit rides with station info
        val trips = transactions.filter {
            it.stationName.isNotEmpty() && it.stationName != "未知" &&
                (it.transitType == "地铁" || it.transitType == "公交")
        }.take(5)

        if (trips.isEmpty()) {
            val emptyView = TextView(requireContext()).apply {
                text = "暂无行程数据"
                setTextColor(0xFF8899AA.toInt())
                textSize = 12f
                setPadding(0, dpToPx(16), 0, dpToPx(16))
            }
            container.addView(emptyView)
            return
        }

        for (entry in trips) {
            // Divider
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(0x331B3A5C.toInt())
            }
            container.addView(divider)

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(10)
                    bottomMargin = dpToPx(10)
                }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val leftCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val routeView = TextView(requireContext()).apply {
                text = entry.stationName
                textSize = 13f
                setTextColor(0xFFCCDDEE.toInt())
                android.graphics.Typeface.DEFAULT_BOLD.also { typeface = it }
            }

            val timeView = TextView(requireContext()).apply {
                text = "${entry.date.substring(5)} ${entry.time.substring(0, 5)} · ${entry.lineName}"
                textSize = 11f
                setTextColor(0xFF668899.toInt())
            }

            leftCol.addView(routeView)
            leftCol.addView(timeView)

            val amountView = TextView(requireContext()).apply {
                text = entry.amountText.trimStart('-')
                textSize = 14f
                setTextColor(
                    if (entry.amountText.startsWith("+")) 0xFF34C759.toInt() else 0xFFFF6B6B.toInt()
                )
                typeface = android.graphics.Typeface.MONOSPACE
                android.graphics.Typeface.DEFAULT_BOLD.also { typeface = it }
            }

            row.addView(leftCol)
            row.addView(amountView)
            container.addView(row)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
