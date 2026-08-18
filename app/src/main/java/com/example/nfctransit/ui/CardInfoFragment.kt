package com.example.nfctransit.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.R
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
        binding.btnEditName.typeface = android.graphics.Typeface.createFromAsset(
            requireContext().assets,
            "fonts/fa-solid-900.ttf"
        )
        binding.btnChangeColor.typeface = binding.btnEditName.typeface
        binding.btnEditName.setOnClickListener { showRenameDialog() }
        binding.btnChangeColor.setOnClickListener { showColorDialog() }

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
    }

    private fun bindMetadata(metadata: UiCardMetadata) {
        binding.tvIssuerCity.text = metadata.issuerCity ?: "—"
        binding.tvIssuer.text = metadata.issuer ?: "—"
        binding.tvIssueDate.text = metadata.issueDate ?: "—"
        binding.tvValidUntil.text = metadata.validUntil ?: "—"
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
            cornerRadius = 8.dpToPx().toFloat()
        }
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
            cornerRadius = 10.dpToPx().toFloat()
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

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        renameDialog?.dismiss()
        renameDialog = null
        super.onDestroyView()
        _binding = null
    }
}
