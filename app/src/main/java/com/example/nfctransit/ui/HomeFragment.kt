package com.example.nfctransit.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.CardProfiles
import com.example.nfctransit.databinding.FragmentHomeBinding
import com.example.nfctransit.model.UiCard
import com.example.nfctransit.util.HomePrefs

/**
 * 首页卡包：总余额 / 卡片数 + 叠放的卡片（后一张压住前一张，只露出标题条，最后一张完整展示）。
 * 点卡进入单卡概览 [CardHomeFragment]；读到卡片后自动打开该卡。
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    // 导入在 ViewModel 里跑，进行中/结果提示由 MainActivity 统一显示
    private val importOldDataLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importDatabase(uri)
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
            _binding?.let {
                // 展开动画中叠卡区底部留白逐帧增高，滚动范围要到本轮布局后才扩大：此处补上被截断的目标滚动位置
                if (pendingFocusScroll >= 0) {
                    it.contentScroll.scrollTo(0, pendingFocusScroll)
                    if (focusAnimator?.isRunning != true) pendingFocusScroll = -1
                }
                updateDeck(it.contentScroll.scrollY)
            }
        }
        // 长按展开卡片时：返回键 / 点空白处收起
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, focusBackCallback)
        (binding.cardStack.parent as View).setOnClickListener { collapseFocus() }
        // 展开时不允许滚动：拖动即视为取消（收起），整个手势都不滚动
        binding.contentScroll.setOnTouchListener { _, event ->
            if (focusIndex < 0 && !blockingScroll) return@setOnTouchListener false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> blockingScroll = false
                else -> if (!blockingScroll) {
                    blockingScroll = true
                    collapseFocus()
                }
            }
            true
        }
        applyTotalBalanceVisibility()
        observeViewModel()
    }

    /**
     * 叠卡滚动：卡片滚到卡片区顶边后吸附不动，下一张卡滑上来把它完整盖住，依次叠成一摞；
     * 每张卡最多下移到最后一张卡的位置（始终留在叠卡区域内），最后一张到顶后整摞随内容滚走。
     * 底部的渐隐遮罩（布局里的 bottomFade）让下方卡片看起来是逐渐显现的。
     */
    private fun updateDeck(scrollY: Int) {
        val stack = _binding?.cardStack ?: return
        val count = minOf(stack.childCount, deckTops.size)
        if (count == 0) return
        val focus = focusIndex.takeIf { it in 0 until count } ?: -1
        val p = if (focus >= 0) focusProgress else 0f
        // 长按展开：展开卡之后的卡整体下移，露出整张卡与下方操作栏
        val extra = if (focus >= 0) focusShift(focus) * p else 0f
        fun effTop(i: Int) = deckTops[i] + if (focus >= 0 && i > focus) extra else 0f
        val pinLine = scrollY - stack.top.toFloat()
        val lastTop = effTop(count - 1)
        for (i in 0 until count) {
            // 各卡标题条高度不同（长卡名换行）：按各自的顶边位置计算吸附位移
            val top = effTop(i)
            var pin = (pinLine - top).coerceIn(0f, lastTop - top)
            // 展开期间展开卡及其后的卡不吸附，免得展开卡被后面的卡盖住
            if (focus >= 0 && i >= focus) pin *= 1f - p
            val item = stack.getChildAt(i) as ViewGroup
            item.translationY = (top - deckTops[i]) + pin
            // 吸附在顶部的卡上方已无前一张卡可投影，阴影随吸附渐隐，避免多层阴影叠成一道深色带
            item.getChildAt(0).alpha = 1f - (pin / (SHADOW_DP.dpToPx() * 2f)).coerceIn(0f, 1f)
            if (focus < 0) continue
            if (i == focus) {
                // 展开卡：投影随展开铺满整张卡；操作栏紧贴其下方，淡入并轻微上滑
                (item.getChildAt(0) as? CardShadowView)?.shadowedHeight =
                    baseShadowHeights[i] + (cardHeights[i] - baseShadowHeights[i]) * p
                focusActions?.let { bar ->
                    bar.translationY = item.translationY + deckTops[i] + SHADOW_DP.dpToPx() + cardHeights[i] +
                        ACTION_GAP_DP.dpToPx() - (1f - p) * 12.dpToPx()
                    bar.alpha = p
                }
            } else {
                // 聚焦：其余卡片退后（略缩小，卡面蒙一层底色变淡；不用透明度，免得叠放的卡互相透出）
                item.getChildAt(1).foreground?.alpha = (DIM_SCRIM * p * 255).toInt()
                item.scaleX = 1f - (1f - DIM_SCALE) * p
                item.scaleY = item.scaleX
            }
        }
    }

    // ---- 长按聚焦：展开整张卡 + 下方操作栏 ----

    private var focusIndex = -1
    private var focusProgress = 0f
    private var focusAnimator: ValueAnimator? = null
    private var focusActions: View? = null
    /** 居中靠下的卡时叠卡区底部额外补的留白（px） */
    private var focusExtraPad = 0
    /** 展开动画当前帧的目标滚动位置，布局后补滚（-1 表示无） */
    private var pendingFocusScroll = -1
    /** 展开时拖动触发收起后，本次手势余下的事件都吞掉（不滚动） */
    private var blockingScroll = false
    /** 视图重建（从卡片页返回）时要恢复的展开卡与底部补白；Fragment 字段跨视图保留 */
    private var savedFocusIndex = -1
    private var savedFocusExtraPad = 0
    private val focusBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = collapseFocus()
    }

    /** 展开第 [index] 张卡时其后卡片的下移量 / 叠卡区增高量（px）：被压住的卡面 + 操作栏及上下留白 */
    private fun focusShift(index: Int): Float {
        val hidden = if (index == deckTops.lastIndex) 0 else cardHeights[index] - peeks[index]
        return (hidden + ACTION_BAR_DP.dpToPx() + ACTION_GAP_DP.dpToPx() * 2).toFloat()
    }

    private fun focusCard(index: Int, card: UiCard) {
        val b = _binding ?: return
        if (index !in deckTops.indices || focusIndex == index) return
        if (focusIndex >= 0) {
            collapseFocus { focusCard(index, card) }
            return
        }
        focusAnimator?.cancel()
        focusIndex = index
        focusBackCallback.isEnabled = true
        val bar = buildFocusActions(index, card)
        bar.alpha = 0f
        b.cardStack.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ACTION_BAR_DP.dpToPx()))
        focusActions = bar

        // 展开后把「整张卡 + 操作栏」滚到整个屏幕（而非卡片滚动区）的垂直居中；
        // 靠下的卡内容不够滚时，底部补足留白
        val scroll = b.contentScroll
        val startScroll = scroll.scrollY
        val groupTop = b.cardStack.top + deckTops[index] + SHADOW_DP.dpToPx()
        val groupHeight = cardHeights[index] + ACTION_GAP_DP.dpToPx() + ACTION_BAR_DP.dpToPx()
        val scrollLoc = IntArray(2).also { scroll.getLocationInWindow(it) }
        val rootLoc = IntArray(2).also { b.root.getLocationInWindow(it) }
        val screenCenter = b.root.height / 2 - (scrollLoc[1] - rootLoc[1])
        val center = groupTop + groupHeight / 2 - screenCenter
        val maxScroll = (scroll.getChildAt(0)?.height ?: 0) + focusShift(index).toInt() - scroll.height
        focusExtraPad = maxOf(0, center - maxScroll)
        animateFocus(to = 1f, startScroll = startScroll, targetScroll = maxOf(0, center), onEnd = null)
    }

    /** 收起展开的卡片；[then] 在收起动画结束后执行（如跳转、切换到另一张卡） */
    private fun collapseFocus(then: (() -> Unit)? = null) {
        val scroll = _binding?.contentScroll
        if (focusIndex < 0 || scroll == null) {
            then?.invoke()
            return
        }
        // 已在收起中：不重启动画
        if (!focusBackCallback.isEnabled && then == null) return
        focusBackCallback.isEnabled = false
        animateFocus(to = 0f, startScroll = scroll.scrollY, targetScroll = scroll.scrollY) {
            clearFocusState()
            then?.invoke()
        }
    }

    private fun animateFocus(to: Float, startScroll: Int, targetScroll: Int, onEnd: (() -> Unit)?) {
        focusAnimator?.cancel()
        val from = focusProgress
        focusAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = if (to > from) 340L else 260L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { anim ->
                val b = _binding ?: return@addUpdateListener
                focusProgress = anim.animatedValue as Float
                val index = focusIndex
                if (index in deckTops.indices) {
                    // 叠卡区底部同步增高：下方「添加卡片」随之下移，滚动范围随之扩大
                    b.cardStack.setPadding(0, 0, 0, ((focusShift(index) + focusExtraPad) * focusProgress).toInt())
                }
                if (startScroll != targetScroll) {
                    pendingFocusScroll = startScroll + ((targetScroll - startScroll) * anim.animatedFraction).toInt()
                    b.contentScroll.scrollTo(0, pendingFocusScroll)
                }
                updateDeck(b.contentScroll.scrollY)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd?.invoke()
                }
            })
            start()
        }
    }

    /** 立即还原聚焦状态（重建叠卡前、收起动画结束时） */
    private fun clearFocusState() {
        focusAnimator?.cancel()
        focusAnimator = null
        focusBackCallback.isEnabled = false
        val stack = _binding?.cardStack
        focusActions?.let { stack?.removeView(it) }
        focusActions = null
        val old = focusIndex
        focusIndex = -1
        focusProgress = 0f
        focusExtraPad = 0
        pendingFocusScroll = -1
        if (stack == null) return
        stack.setPadding(0, 0, 0, 0)
        for (i in 0 until minOf(stack.childCount, deckTops.size)) {
            val item = stack.getChildAt(i) as ViewGroup
            item.getChildAt(1).foreground?.alpha = 0
            item.scaleX = 1f
            item.scaleY = 1f
            if (i == old) (item.getChildAt(0) as? CardShadowView)?.shadowedHeight = baseShadowHeights[i]
        }
        updateDeck(_binding?.contentScroll?.scrollY ?: 0)
    }

    /** 展开卡下方的操作栏：每项为圆形底色图标 + 文字，与卡片页「…」菜单相同的操作 */
    private fun buildFocusActions(index: Int, card: UiCard): View {
        val ctx = requireContext()
        val fa = Typeface.createFromAsset(ctx.assets, "fonts/fa-solid-900.otf")
        val accent = Palette.accentFor(card.gradientStartColor)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun action(icon: String, label: String, color: Int, labelColor: Int, onClick: () -> Unit) {
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = label
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x38)),
                    null,
                    GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = (ACTION_BAR_DP / 2f).dpToPx()
                    }
                )
                setPadding(6.dpToPx(), 0, 12.dpToPx(), 0)
                setOnClickListener { onClick() }
            }
            // 按下反馈：图标圆略缩小、底色加深（配合整项水波纹）
            val iconBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(color, 0x24))
            }
            item.addView(TextView(ctx).apply {
                text = icon
                typeface = fa
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(color)
                background = iconBg
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }.also { iconView ->
                item.setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            iconBg.setColor(ColorUtils.setAlphaComponent(color, 0x40))
                            iconView.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            iconBg.setColor(ColorUtils.setAlphaComponent(color, 0x24))
                            iconView.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                        }
                    }
                    false
                }
            }, LinearLayout.LayoutParams(ACTION_ICON_DP.dpToPx(), ACTION_ICON_DP.dpToPx()))
            item.addView(TextView(ctx).apply {
                text = label
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(labelColor)
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 10.dpToPx()
            })
            row.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        action("", getString(R.string.card_info_title), accent, Palette.INK) {
            // 保持展开：返回首页时恢复聚焦状态
            if (findNavController().currentDestination?.id == R.id.homeFragment) {
                viewModel.selectCardByIndex(index)
                capturePredictiveBackSnapshot()
                findNavController().navigate(R.id.action_home_to_cardInfo)
            }
        }
        action("", getString(R.string.delete_card), Palette.DANGER, Palette.DANGER) {
            AppDialogs.confirm(
                context = ctx,
                title = getString(R.string.delete_card),
                message = getText(R.string.delete_card_message),
                confirmLabel = getString(R.string.action_delete)
            ) { collapseFocus { viewModel.deleteCard(index) } }
        }
        return row
    }

    private fun capturePredictiveBackSnapshot() {
        (activity as? MainActivity)?.capturePredictiveBackSnapshot()
    }

    /** 首页无数据时直接导入 TransitU / TripReader 数据库备份。 */
    private fun setupOldDataImport() {
        binding.btnImportOldData.setOnClickListener {
            if (viewModel.dbImporting.value == true) return@setOnClickListener
            importOldDataLauncher.launch(arrayOf("*/*"))
        }
        viewModel.dbImporting.observe(viewLifecycleOwner) { importing ->
            binding.btnImportOldData.isEnabled = !importing
            binding.btnImportOldData.setText(if (importing) R.string.importing else R.string.home_import_old)
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
                // 读到卡片：卡片顺序可能变化，不保留长按聚焦
                clearFocusState()
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
        // 总余额合计各卡上的人民币钱包（含澳门通/八达通联名卡上的 TU 钱包）；外币余额只在各自卡面显示
        val totalFen = cards.sumOf { card ->
            viewModel.walletBalances(card.id).filter { CardProfiles.currencySymbol(it.protocol) == "¥" }.sumOf { it.balanceFen }
        }
        binding.tvTotalBalance.text = "¥${String.format("%.2f", totalFen / 100.0)}"
        applyTotalBalanceVisibility()
        binding.tvCardCount.text = resources.getQuantityString(R.plurals.home_card_count, cards.size, cards.size)

        clearFocusState()
        val stack = binding.cardStack
        stack.removeAllViews()
        // 卡片阴影会超出叠卡区域左右边界：叠卡区与内容容器都不裁剪子视图，
        // 内容容器还要关闭 clipToPadding（否则仍按左右 20dp 内边距裁掉阴影）
        stack.clipChildren = false
        // 长按展开时叠卡区靠底部内边距增高：不能按内边距裁剪，否则操作栏与卡片两侧/底部阴影被裁掉
        stack.clipToPadding = false
        (stack.parent as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }
        val basePeek = PEEK_DP.dpToPx()
        // 标题条按卡面宽度测量（叠卡区尚未布局时按屏宽减去内容区左右 20dp 内边距估算）
        val faceWidth = stack.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - 40.dpToPx())
        // 首次进入从顶部开始（叠卡状态由 scrollY 决定，不能带着残留滚动位置）
        if (!walletRendered) {
            walletRendered = true
            binding.contentScroll.post { _binding?.contentScroll?.scrollTo(0, 0) }
        }
        // 按卡片顺序依次叠放：后加入的视图绘制在上层，压住前一张的下半部分。
        // 每张卡露出的高度随标题条（长卡名换行）增高，卡面同步加高，底部 logo/尾号位置不变
        val tops = IntArray(cards.size)
        val heights = IntArray(cards.size)
        val peekArr = IntArray(cards.size)
        val shadowArr = FloatArray(cards.size)
        var top = 0
        cards.forEachIndexed { index, card ->
            val (face, titleHeight) = buildStackCard(card, index, faceWidth)
            val peek = maxOf(basePeek, titleHeight + TITLE_GAP_DP.dpToPx())
            val cardHeight = CARD_HEIGHT_DP.dpToPx() + (peek - basePeek)
            tops[index] = top
            heights[index] = cardHeight
            peekArr[index] = peek
            // 被下一张卡压住的部分不投影：只在露出的标题条范围画阴影，避免两侧阴影层层叠加
            val shadowed = if (index == cards.lastIndex) cardHeight else peek + CARD_CORNER_DP.dpToPx()
            shadowArr[index] = shadowed.toFloat()
            stack.addView(
                withTopShadow(face, cardHeight, shadowedHeight = shadowed),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardHeight + SHADOW_DP.dpToPx()).apply {
                    topMargin = top
                }
            )
            top += peek
        }
        deckTops = tops
        cardHeights = heights
        peeks = peekArr
        baseShadowHeights = shadowArr
        val restore = savedFocusIndex
        savedFocusIndex = -1
        if (restore in cards.indices) restoreFocus(restore, cards[restore])
    }

    /** 无动画地恢复展开状态（滚动位置由 ScrollView 自身保存的状态恢复） */
    private fun restoreFocus(index: Int, card: UiCard) {
        val b = _binding ?: return
        focusIndex = index
        focusProgress = 1f
        focusExtraPad = savedFocusExtraPad
        focusBackCallback.isEnabled = true
        val bar = buildFocusActions(index, card)
        b.cardStack.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ACTION_BAR_DP.dpToPx()))
        focusActions = bar
        b.cardStack.setPadding(0, 0, 0, (focusShift(index) + focusExtraPad).toInt())
        updateDeck(b.contentScroll.scrollY)
    }

    /** 各卡在叠卡区内的顶边位置（px），供 [updateDeck] 计算吸附 */
    private var deckTops = IntArray(0)
    /** 各卡卡面高度、叠放时露出高度、未展开时的投影高度（px） */
    private var cardHeights = IntArray(0)
    private var peeks = IntArray(0)
    private var baseShadowHeights = FloatArray(0)

    /** 设置页「卡片管理 → 显示总余额」：关闭时隐藏总余额，卡片数左移顶替其位置 */
    private fun applyTotalBalanceVisibility() {
        val b = _binding ?: return
        val show = HomePrefs.showTotalBalance(requireContext())
        b.totalBalanceGroup.visibility = if (show) View.VISIBLE else View.GONE
        (b.cardCountGroup.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            val start = if (show) 32.dpToPx() else 0
            if (lp.marginStart != start) {
                lp.marginStart = start
                b.cardCountGroup.layoutParams = lp
            }
        }
    }

    /**
     * 一张卡面：标题行（卡名 + 应用标签 / 读取时间 + 余额）、左下卡面 logo、右下尾号。
     * 返回卡面与按 [faceWidth] 测得的标题行高度（决定叠放时露出多少）。
     */
    private fun buildStackCard(card: UiCard, index: Int, faceWidth: Int): Pair<View, Int> {
        val ctx = requireContext()
        val onCard = Palette.onCardFace(card.gradientStartColor)
        fun onCardAlpha(alpha: Int) = ColorUtils.setAlphaComponent(onCard, alpha)

        val face = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Palette.cardFace(card.gradientStartColor), Palette.cardFace(card.gradientEndColor, end = true))
            ).apply { cornerRadius = CARD_CORNER_DP.dpToPx().toFloat() }
            // 长按聚焦时其余卡片的蒙层（与卡面同圆角），平时透明
            foreground = GradientDrawable().apply {
                setColor(Palette.PAPER)
                cornerRadius = CARD_CORNER_DP.dpToPx().toFloat()
                alpha = 0
            }
            isClickable = true
            // 触屏模式下不抢焦点：避免 ScrollView 为「把焦点卡片滚进可视区」自动滚动，导致首屏位置偏移
            isFocusable = true
            isFocusableInTouchMode = false
            contentDescription = getString(R.string.card_cd, card.name, String.format("%.2f", card.balanceYuan))
            // 有卡片展开时：点展开卡进入该卡，点其他卡只收起
            setOnClickListener {
                when (focusIndex) {
                    -1 -> openCard(index)
                    index -> openCard(index)
                    else -> collapseFocus()
                }
            }
            // 长按：展开整张卡（其余卡片上下让开并退后），下方显示操作栏
            setOnLongClickListener {
                // 长按后手指松开前的轻微移动不算滚动（否则会立刻收起）
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                focusCard(index, card)
                true
            }
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22.dpToPx(), 18.dpToPx(), 22.dpToPx(), 0)
        }
        val titleCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // 卡名完整显示（长名换行，不省略不缩字），应用标签（如 LNP / TU）紧跟在卡名末尾、随文字一起换行
        titleCol.addView(TextView(ctx).apply {
            val density = resources.displayMetrics.density
            text = SpannableStringBuilder(card.name).apply {
                viewModel.appProtocols(card.id).forEach { protocol ->
                    append(" ")
                    val start = length
                    append(TransitLabels.protocol(protocol))
                    setSpan(
                        ChipSpan(
                            textSizePx = 10f * resources.displayMetrics.scaledDensity,
                            textColor = onCard,
                            background = onCardAlpha(0x2E),
                            padH = 6f * density,
                            padV = 3f * density,
                            radius = 7f * density,
                            marginStart = 2f * density
                        ),
                        start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            setTextColor(onCard)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(2f * density, 1f)
        })
        titleCol.addView(TextView(ctx).apply {
            text = getString(R.string.read_at, TimeLabels.relative(card.lastReadAt))
            setTextColor(onCardAlpha(0xBF))
            textSize = 12f
            maxLines = 1
            setPadding(0, 3.dpToPx(), 0, 0)
        })
        titleRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 12.dpToPx()  // 卡名与右侧余额留出间距
        })
        // 多币种联名卡：各钱包余额上下等大并列（币种符号区分），不分主次
        val wallets = viewModel.walletBalances(card.id)
        titleRow.addView(TextView(ctx).apply {
            text = when {
                wallets.size > 1 -> SpannableStringBuilder().apply {
                    wallets.forEachIndexed { i, wallet ->
                        if (i > 0) append("\n")
                        append(WalletBalanceViews.amountText(wallet, amountSp = 20, symbolSp = 12))
                    }
                }
                card.balanceFen == null -> "—"
                else -> balanceText(card.balanceYuan, CardProfiles.currencySymbol(card.protocolType))
            }
            gravity = Gravity.END
            setTextColor(onCard)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            if (wallets.size > 1) setLineSpacing(3f * resources.displayMetrics.density, 1f)
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
        titleRow.measure(
            View.MeasureSpec.makeMeasureSpec(faceWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return face to titleRow.measuredHeight
    }

    /** 卡名后的应用标签：圆角底色 + 小号粗体文字，作为一个整体随卡名换行 */
    private class ChipSpan(
        textSizePx: Float,
        private val textColor: Int,
        private val background: Int,
        private val padH: Float,
        private val padV: Float,
        private val radius: Float,
        private val marginStart: Float
    ) : android.text.style.ReplacementSpan() {
        private val chipPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun getSize(
            paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int,
            fm: android.graphics.Paint.FontMetricsInt?
        ): Int = (marginStart + chipPaint.measureText(text, start, end) + padH * 2).toInt() + 1

        override fun draw(
            canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint
        ) {
            // 以卡名文字的视觉中线为准垂直居中
            val center = y + (paint.ascent() + paint.descent()) / 2f
            val chipFm = chipPaint.fontMetrics
            val half = (chipFm.descent - chipFm.ascent) / 2f + padV
            val left = x + marginStart
            val width = chipPaint.measureText(text, start, end) + padH * 2
            chipPaint.color = background
            canvas.drawRoundRect(left, center - half, left + width, center + half, radius, radius, chipPaint)
            chipPaint.color = textColor
            val baseline = center - (chipFm.ascent + chipFm.descent) / 2f
            canvas.drawText(text, start, end, left + padH, baseline, chipPaint)
        }
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
        shadowedHeight: Float,
        private val corner: Float
    ) : View(context) {
        var shadowedHeight: Float = shadowedHeight
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

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

    /** 余额：币种符号小一号，金额大字 */
    private fun balanceText(yuan: Double, currency: String): CharSequence {
        val amount = String.format("%.2f", yuan)
        return SpannableStringBuilder("$currency$amount").apply {
            setSpan(AbsoluteSizeSpan(15, true), 0, currency.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(30, true), currency.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        // 离开首页（进入卡片 / 卡片信息）时记下展开的卡，返回时恢复
        savedFocusIndex = if (focusIndex >= 0 && focusBackCallback.isEnabled) focusIndex else -1
        savedFocusExtraPad = focusExtraPad
        focusAnimator?.cancel()
        focusAnimator = null
        focusActions = null
        focusIndex = -1
        focusProgress = 0f
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
        /** 标题行下方留白：露出高度 = max([PEEK_DP], 标题行高 + 留白)，长卡名换行时露出更多 */
        const val TITLE_GAP_DP = 12
        /** 长按展开：操作栏高度、图标圆直径、操作栏与卡片的间距；其余卡片退后的蒙层不透明度与缩放 */
        const val ACTION_BAR_DP = 52
        const val ACTION_ICON_DP = 40
        const val ACTION_GAP_DP = 14
        const val DIM_SCRIM = 0.55f
        const val DIM_SCALE = 0.96f
    }
}
