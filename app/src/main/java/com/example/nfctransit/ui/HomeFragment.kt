package com.example.nfctransit.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentHomeBinding
import com.example.nfctransit.model.UiCard
import kotlinx.coroutines.launch

/**
 * 首页卡包：总余额 / 卡片数 + 叠放的卡片（后一张压住前一张，只露出标题条，最后一张完整展示）。
 * 点卡进入单卡概览 [CardHomeFragment]；读到卡片后自动打开该卡。
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    private var isImportingOldData = false

    private val importOldDataLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            isImportingOldData = false
            renderOldDataImportState(importing = false)
            return@registerForActivityResult
        }

        isImportingOldData = true
        renderOldDataImportState(importing = true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val message = viewModel.importDatabase(uri)
                isImportingOldData = false
                renderOldDataImportState(
                    importing = false,
                    message = message,
                    success = true
                )
                context?.let {
                    Toast.makeText(it, "✓ $message", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                isImportingOldData = false
                val detail = e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.err_unsupported_format)
                renderOldDataImportState(
                    importing = false,
                    message = getString(R.string.import_failed, detail),
                    success = false
                )
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupOldDataImport()
        binding.btnSettings.typeface =
            Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.otf")
        binding.btnSettings.setOnClickListener {
            capturePredictiveBackSnapshot()
            findNavController().navigate(R.id.action_home_to_settings)
        }
        binding.btnAddCard.setOnClickListener {
            AppDialogs.confirm(
                context = requireContext(),
                title = getString(R.string.add_card_title),
                message = getString(R.string.add_card_message),
                confirmLabel = getString(R.string.action_got_it),
                confirmColor = Palette.ACCENT,
                cancelLabel = "",
                onConfirm = {}
            )
        }
        // 卡多时只滚动卡片区：卡片到顶后逐张叠起
        binding.contentScroll.setOnScrollChangeListener { _, _, scrollY, _, _ -> updateDeck(scrollY) }
        // 卡片重建或尺寸变化后（首帧、增删卡）按当前滚动位置重算一次。
        // 监听 ScrollView 本身而非子视图：返回本页时 ScrollView 在自身 onLayout 末尾才直接写回保存的 scrollY
        // （不触发滚动回调），子视图的布局回调早于此，会按 scrollY=0 叠卡，导致返回首帧卡片错位闪一下
        binding.contentScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            _binding?.let { updateDeck(it.contentScroll.scrollY) }
        }
        observeViewModel()
    }

    /**
     * 叠卡滚动：卡片滚到卡片区顶边后吸附不动，下一张卡滑上来把它完整盖住，依次叠成一摞；
     * 每张卡最多下移到最后一张卡的位置（始终留在叠卡区域内），最后一张到顶后整摞随内容滚走。
     * 底部的渐隐遮罩（布局里的 bottomFade）让下方卡片看起来是逐渐显现的。
     */
    private fun updateDeck(scrollY: Int) {
        val stack = _binding?.cardStack ?: return
        val count = stack.childCount
        val peek = PEEK_DP.dpToPx().toFloat()
        val pinLine = scrollY - stack.top.toFloat()
        for (i in 0 until count) {
            val maxShift = (count - 1 - i) * peek
            val shift = (pinLine - i * peek).coerceIn(0f, maxShift)
            val item = stack.getChildAt(i) as ViewGroup
            item.translationY = shift
            // 吸附在顶部的卡上方已无前一张卡可投影，阴影随吸附渐隐，避免多层阴影叠成一道深色带
            item.getChildAt(0).alpha = 1f - (shift / (SHADOW_DP.dpToPx() * 2f)).coerceIn(0f, 1f)
        }
    }

    private fun capturePredictiveBackSnapshot() {
        (activity as? MainActivity)?.capturePredictiveBackSnapshot()
    }

    /** 首页无数据时直接导入 TransitU / TripReader 数据库备份。 */
    private fun setupOldDataImport() {
        renderOldDataImportState(importing = isImportingOldData)
        binding.btnImportOldData.setOnClickListener {
            if (isImportingOldData) return@setOnClickListener
            renderOldDataImportState(importing = false)
            importOldDataLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun renderOldDataImportState(
        importing: Boolean,
        message: String? = null,
        success: Boolean = false
    ) {
        val currentBinding = _binding ?: return
        currentBinding.btnImportOldData.isEnabled = !importing
        currentBinding.btnImportOldData.setText(if (importing) R.string.importing else R.string.home_import_old)
        currentBinding.tvImportOldDataStatus.apply {
            text = message.orEmpty()
            setTextColor(
                when {
                    importing -> Palette.INK_3
                    success -> Palette.SUCCESS
                    else -> Palette.DANGER
                }
            )
            visibility = if (importing || message != null) View.VISIBLE else View.GONE
            if (importing) setText(R.string.import_old_progress)
        }
    }

    private fun observeViewModel() {
        // 启动恢复中显示加载态；恢复完成后再按 hasData 切空态/内容，避免重建缓存期间误显示"请靠近交通卡"
        viewModel.isRestoring.observe(viewLifecycleOwner) { updateRootVisibility() }
        viewModel.hasData.observe(viewLifecycleOwner) { updateRootVisibility() }

        viewModel.cards.observe(viewLifecycleOwner) { cards -> renderWallet(cards) }

        // 读到卡片（新卡或重复读同一张）后直接打开该卡的概览页
        viewModel.cardAdded.observe(viewLifecycleOwner) { event ->
            if (event != null) {
                viewModel.clearCardAdded()
                openCard(event.index)
            }
        }
    }

    /** 首页根视图三态切换：恢复中显示加载态；否则按是否有卡数据切空态（请靠近读卡）或内容 */
    private fun updateRootVisibility() {
        val restoring = viewModel.isRestoring.value == true
        val hasData = viewModel.hasData.value == true
        binding.loadingState.visibility = if (restoring) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (!restoring && !hasData) View.VISIBLE else View.GONE
        binding.contentWrapper.visibility = if (!restoring && hasData) View.VISIBLE else View.GONE
    }

    private fun openCard(index: Int) {
        val root = _binding?.root ?: return
        if (findNavController().currentDestination?.id != R.id.homeFragment) return
        viewModel.selectCardByIndex(index)
        // 读卡时 cards 刚更新、叠卡视图刚重建尚未布局，立即截图会得到空白卡包（返回动画底图空白）；
        // 等这一轮布局完成后再截图跳转
        val go = {
            if (_binding != null && findNavController().currentDestination?.id == R.id.homeFragment) {
                capturePredictiveBackSnapshot()
                findNavController().navigate(R.id.action_home_to_cardHome)
            }
        }
        if (root.isLaidOut && !root.isLayoutRequested) {
            go()
        } else {
            root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int
                ) {
                    v.removeOnLayoutChangeListener(this)
                    go()
                }
            })
        }
    }

    private var walletRendered = false

    private fun renderWallet(cards: List<UiCard>) {
        val totalFen = cards.sumOf { it.balanceFen ?: 0L }
        binding.tvTotalBalance.text = "¥${String.format("%.2f", totalFen / 100.0)}"
        binding.tvCardCount.text = resources.getQuantityString(R.plurals.home_card_count, cards.size, cards.size)

        val stack = binding.cardStack
        stack.removeAllViews()
        // 卡片阴影会超出叠卡区域左右边界：叠卡区与内容容器都不裁剪子视图，
        // 内容容器还要关闭 clipToPadding（否则仍按左右 20dp 内边距裁掉阴影）
        stack.clipChildren = false
        (stack.parent as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }
        val peek = PEEK_DP.dpToPx()
        val cardHeight = CARD_HEIGHT_DP.dpToPx()
        // 首次进入从顶部开始（叠卡状态由 scrollY 决定，不能带着残留滚动位置）
        if (!walletRendered) {
            walletRendered = true
            binding.contentScroll.post { _binding?.contentScroll?.scrollTo(0, 0) }
        }
        // 按卡片顺序依次叠放：后加入的视图绘制在上层，压住前一张的下半部分
        cards.forEachIndexed { index, card ->
            stack.addView(
                withTopShadow(
                    buildStackCard(card, index),
                    cardHeight,
                    // 被下一张卡压住的部分不投影：只在露出的标题条范围画阴影，避免两侧阴影层层叠加
                    shadowedHeight = if (index == cards.lastIndex) cardHeight else peek + CARD_CORNER_DP.dpToPx()
                ),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardHeight + SHADOW_DP.dpToPx()).apply {
                    topMargin = index * peek
                }
            )
        }
    }

    /** 一张卡面：标题行（卡名 / 城市·读取时间 + 余额）、左下卡面 logo、右下尾号 */
    private fun buildStackCard(card: UiCard, index: Int): View {
        val ctx = requireContext()
        val onCard = if (isDarkColor(card.gradientStartColor.toInt())) Color.WHITE else Palette.INK
        fun onCardAlpha(alpha: Int) = ColorUtils.setAlphaComponent(onCard, alpha)

        val face = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(card.gradientStartColor.toInt(), card.gradientEndColor.toInt())
            ).apply { cornerRadius = CARD_CORNER_DP.dpToPx().toFloat() }
            isClickable = true
            // 触屏模式下不抢焦点：避免 ScrollView 为「把焦点卡片滚进可视区」自动滚动，导致首屏位置偏移
            isFocusable = true
            isFocusableInTouchMode = false
            contentDescription = getString(R.string.card_cd, card.name, String.format("%.2f", card.balanceYuan))
            setOnClickListener { openCard(index) }
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22.dpToPx(), 18.dpToPx(), 22.dpToPx(), 0)
        }
        val titleCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(ctx).apply {
            text = card.name
            setTextColor(onCard)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        })
        val city = viewModel.issuerCityFor(card.id)
        titleCol.addView(TextView(ctx).apply {
            text = listOfNotNull(city, getString(R.string.read_at, TimeLabels.relative(card.lastReadAt))).joinToString(" · ")
            setTextColor(onCardAlpha(0xBF))
            textSize = 12f
            maxLines = 1
            setPadding(0, 3.dpToPx(), 0, 0)
        })
        titleRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(ctx).apply {
            text = if (card.balanceFen == null) "—" else balanceText(card.balanceYuan)
            setTextColor(onCard)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        })
        face.addView(titleRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        logoFor(card.name)?.let { logo ->
            face.addView(ImageView(ctx).apply {
                setImageResource(logo)
                scaleType = ImageView.ScaleType.FIT_START
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(84.dpToPx(), 40.dpToPx(), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = 22.dpToPx()
                bottomMargin = 18.dpToPx()
            })
        }

        face.addView(TextView(ctx).apply {
            text = "•  •  •  •   ${card.lastFour}"
            setTextColor(onCardAlpha(0xCC))
            textSize = 14f
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            rightMargin = 22.dpToPx()
            bottomMargin = 20.dpToPx()
        })
        return face
    }

    /**
     * 卡面外包一层，上方留出 [SHADOW_DP] 的投影空间：卡面背后画一个同形状（同圆角）的圆角矩形并加模糊阴影，
     * 矩形本身被卡面完全盖住，只露出卡片顶边与圆角外的一圈阴影，落在前一张卡上，让叠放边界清晰。
     * 系统 elevation 阴影主要落在卡片下方、压不到上一张卡，故自绘。
     */
    private fun withTopShadow(face: View, cardHeight: Int, shadowedHeight: Int): View {
        val shadowHeight = SHADOW_DP.dpToPx()
        return FrameLayout(requireContext()).apply {
            clipChildren = false
            // 阴影视图左右下各外扩 SIDE_SHADOW_DP，让卡片两侧与底边也有柔和投影（漂浮感）
            val side = SIDE_SHADOW_DP.dpToPx()
            addView(
                CardShadowView(
                    context,
                    top = shadowHeight.toFloat(),
                    side = side.toFloat(),
                    shadowedHeight = shadowedHeight.toFloat(),
                    corner = CARD_CORNER_DP.dpToPx().toFloat()
                ),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardHeight + shadowHeight + side).apply {
                    leftMargin = -side
                    rightMargin = -side
                    bottomMargin = -side
                }
            )
            addView(face, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardHeight).apply {
                topMargin = shadowHeight
            })
        }
    }

    /** 在 [top] 以下、左右下各缩进 [side] 处画与卡面同形的圆角矩形，并投下柔和的模糊阴影（软件层 setShadowLayer） */
    private class CardShadowView(
        context: android.content.Context,
        private val top: Float,
        private val side: Float,
        private val shadowedHeight: Float,
        private val corner: Float
    ) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            val d = context.resources.displayMetrics.density
            setShadowLayer(10f * d, 0f, 0f, 0x26000000)
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val bottom = minOf(top + shadowedHeight, height - side)
            canvas.drawRoundRect(side, top, width - side, bottom, corner, corner, paint)
        }
    }

    /** 余额：「¥」小一号，金额大字 */
    private fun balanceText(yuan: Double): CharSequence {
        val amount = String.format("%.2f", yuan)
        return SpannableStringBuilder("¥$amount").apply {
            setSpan(AbsoluteSizeSpan(15, true), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(30, true), 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun logoFor(name: String): Int? = when (name) {
        "深圳通" -> R.drawable.shenzhentong
        "珠海通" -> R.drawable.zhuhaitong
        "羊城通" -> R.drawable.yangchengtong
        else -> null
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
    private fun Float.dpToPx(): Float = this * resources.displayMetrics.density

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** 卡面高度与每张被压住的卡露出的高度（露出部分正好容纳标题行） */
        const val CARD_HEIGHT_DP = 200
        const val PEEK_DP = 78
        /** 卡面上方留给投影的高度、卡面圆角 */
        const val SHADOW_DP = 12
        const val CARD_CORNER_DP = 20
        const val SIDE_SHADOW_DP = 10
    }
}
