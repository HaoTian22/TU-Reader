package com.example.nfctransit.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.nfctransit.R
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 应用内提示条：白色圆角卡片自顶部滑入，右侧关闭按钮外圈的圆环显示剩余时间，到时自动收起。
 * 点关闭或上滑收起，按住暂停倒计时。切换页面不影响提示。
 *
 * 同一时间只有一张卡片：新提示在原卡片上形变过去（如「保存中」的转圈变成 ✓ 并展开成结果）。
 * 进行中提示几秒后缩成右侧只有转圈的小圆，点按展开。
 *
 * 以独立的应用窗口挂在 Activity 上（而不是 Activity 视图树里），这样弹窗打开时也显示在弹窗之上；
 * 放在顶栏下方（不挡返回键），避免被弹窗表单的输入法遮住。拿不到 Activity 时退回系统 Toast。
 */
object AppToast {
    enum class Kind { INFO, SUCCESS, ERROR, PROGRESS }

    const val SHORT = 2_500L
    const val LONG = 4_000L

    /** 状态栏下方让出的顶栏高度（dp）：nav_header_padding_top 10dp + 40dp 返回按钮 */
    private const val HEADER_CLEARANCE = 58f
    /** 进行中提示展开多久后缩成小圆 */
    private const val MINIMIZE_DELAY = 3_000L
    /** 兜底：调用方漏掉结束时，进行中提示最多挂这么久 */
    private const val PROGRESS_TIMEOUT = 5 * 60_000L
    private const val MORPH_DURATION = 300L

    /** 提示句柄：只关闭自己，不影响之后显示（或在同一卡片上形变出来）的提示 */
    class Handle internal constructor(private val host: Host?, private val generation: Int) {
        fun dismiss() {
            host?.takeIf { it === current && it.generation == generation }?.hide()
        }
    }

    private var resumedActivity: WeakReference<Activity>? = null
    private var current: Host? = null

    /** 在 Activity.onCreate 调用：记录前台 Activity（供 ViewModel 等无 Activity 的调用方使用），销毁时移除窗口 */
    fun bind(activity: Activity) {
        val owner = activity as? LifecycleOwner ?: return
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                resumedActivity = WeakReference(activity)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                current?.takeIf { it.activity === activity }?.removeNow()
                if (resumedActivity?.get() === activity) resumedActivity = null
            }
        })
    }

    fun show(context: Context, @StringRes text: Int, kind: Kind = Kind.INFO, duration: Long = SHORT): Handle =
        show(context, context.getString(text), kind, duration)

    fun show(context: Context, text: CharSequence, kind: Kind = Kind.INFO, duration: Long = SHORT): Handle {
        // 文案里自带的 ✓ / ⚠ 前缀改由图标表达
        var resolvedKind = kind
        var message = text.toString().trim()
        if (message.startsWith("✓")) {
            message = message.removePrefix("✓").trim()
            if (resolvedKind == Kind.INFO) resolvedKind = Kind.SUCCESS
        } else if (message.startsWith("⚠")) {
            message = message.removePrefix("⚠").trim()
            if (resolvedKind == Kind.INFO) resolvedKind = Kind.ERROR
        }
        val resolvedDuration = if (resolvedKind == Kind.PROGRESS) 0L else duration
        val activity = context.findActivity()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: resumedActivity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (activity == null) {
            Toast.makeText(context.applicationContext, message,
                if (duration > SHORT) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            return Handle(null, 0)
        }
        val previous = current
        // Activity 窗口失焦说明上面压着弹窗：重新添加窗口才能盖过它；否则在原卡片上形变
        if (previous != null && previous.activity === activity && previous.isLive && activity.hasWindowFocus()) {
            previous.update(message, resolvedKind, resolvedDuration)
            return Handle(previous, previous.generation)
        }
        previous?.removeNow()
        val host = Host(activity, message, resolvedKind, resolvedDuration)
        current = host
        host.attach(animateIn = previous == null)
        return Handle(host, host.generation)
    }

    /** 按 [StatusMessage] 的样式显示：进行中常驻，失败停留更久 */
    fun show(context: Context, status: StatusMessage): Handle = show(
        context, status.text, status.kind,
        when (status.kind) {
            Kind.PROGRESS -> 0L
            Kind.ERROR -> LONG
            else -> SHORT
        }
    )

    private fun Context.findActivity(): Activity? {
        var context: Context? = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    /**
     * Android 13 及以下没有 setCanPlayMoveAnimation：它在 34+ 只是设置隐藏的
     * PRIVATE_FLAG_NO_MOVE_ANIMATION（0x40，系统窗口策略早就支持），这里直接写 privateFlags。
     * 不设置的话（如 Android 8）小圆缩完后窗口左边界移动，会从旧位置滑回来。反射失败时忽略。
     */
    @SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    private fun disableMoveAnimationLegacy(params: WindowManager.LayoutParams) {
        try {
            val field = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
            field.isAccessible = true
            field.setInt(params, field.getInt(params) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        } catch (_: Throwable) {
        }
    }

    private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040

    private fun tintFor(kind: Kind): Int = when (kind) {
        Kind.SUCCESS -> Palette.SUCCESS
        Kind.ERROR -> Palette.DANGER
        Kind.INFO, Kind.PROGRESS -> Palette.ACCENT
    }

    internal class Host(
        val activity: Activity,
        private var message: String,
        private var kind: Kind,
        private var duration: Long
    ) {
        private val windowManager = activity.windowManager
        private val density = activity.resources.displayMetrics.density
        private val root = FrameLayout(activity)
        private val card = LinearLayout(activity)
        private val cardBackground = GradientDrawable()
        private val iconBox = FrameLayout(activity)
        private val messageView = TextView(activity)
        private val close = CountdownCloseButton(activity)
        private var iconView: View? = null
        private var timer: ValueAnimator? = null
        private var morph: ValueAnimator? = null
        private var attached = false
        private var hiding = false
        private val progressTimeout = Runnable { hide() }
        private val minimizeLater = Runnable { setMinimized(true) }

        /** 每次 [update] 递增：旧句柄关不掉形变后的新提示 */
        var generation = 0
            private set
        var minimized = false
            private set
        val isLive: Boolean get() = attached && !hiding

        private val shadowPad = dp(8f)
        private val iconSize = dp(28f)
        private val pillSize = dp(44f)
        private val expandedRadius = dp(16f).toFloat()
        private val expandedWidth =
            min(activity.resources.displayMetrics.widthPixels - dp(32f), dp(480f))  // 平板/横屏不拉满整行

        private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

        fun attach(animateIn: Boolean) {
            buildViews()
            // 窗口只包住卡片（卡片两侧不拦截下层页面的点按），靠右放：展开时居中，缩成小圆后贴右
            val screenWidth = activity.resources.displayMetrics.widthPixels
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = max(0, (screenWidth - expandedWidth - 2 * shadowPad) / 2)
                windowAnimations = 0
                title = "AppToast"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
                // 缩成小圆/展开后窗口左边界会移动：关掉系统的窗口移动动画，否则小圆会从旧位置再滑一次
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setCanPlayMoveAnimation(false)
                else disableMoveAnimationLegacy(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            // 窗口自行铺到状态栏下，按 Activity 的状态栏/刘海高度下移，再让出页面顶栏；四周留出阴影位
            val topInset = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
                ?.top ?: 0
            root.setPadding(shadowPad, topInset + dp(HEADER_CLEARANCE), shadowPad, dp(10f))
            try {
                windowManager.addView(root, params)
                attached = true
            } catch (e: Exception) {
                // Activity 窗口令牌已失效（正在销毁等）
                if (current === this) current = null
                Toast.makeText(activity.applicationContext, message, Toast.LENGTH_SHORT).show()
                return
            }
            if (animateIn) {
                card.alpha = 0f
                card.post {
                    card.translationY = -(card.height + root.paddingTop).toFloat()
                    card.alpha = 1f
                    card.animate().translationY(0f).setDuration(260)
                        .setInterpolator(DecelerateInterpolator(2f))
                        .withEndAction { startTimer() }
                        .start()
                }
            } else {
                startTimer()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun buildViews() {
            card.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBackground.apply { setColor(Palette.SURFACE) }
                elevation = dp(6f).toFloat()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    outlineAmbientShadowColor = 0x26000000
                    outlineSpotShadowColor = 0x33000000
                }
                // 形变过程中内容超出的部分裁在圆角轮廓内
                clipToOutline = true
            }
            applyShape(expandedProgress = 1f)
            card.addView(iconBox, LinearLayout.LayoutParams(iconSize, iconSize))
            messageView.apply {
                setTextColor(Palette.INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(0f, 1.15f)
                maxLines = 6
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            card.addView(messageView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12f)
                marginEnd = dp(8f)
            })
            close.contentDescription = activity.getString(R.string.action_close)
            close.setOnClickListener { hide() }
            card.addView(close, LinearLayout.LayoutParams(dp(36f), dp(36f)))
            applyContent(animate = false)
            root.clipToPadding = false
            root.clipChildren = false
            root.addView(card, FrameLayout.LayoutParams(
                expandedWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
            ))
            card.setOnTouchListener(SwipeTouch())
        }

        /** 按当前 kind / message / duration 更新图标、文案与关闭按钮；animate 时交叉淡化 */
        private fun applyContent(animate: Boolean) {
            val tint = tintFor(kind)
            val newIcon = makeIcon(kind, tint)
            val oldIcon = iconView
            iconView = newIcon
            iconBox.addView(newIcon, FrameLayout.LayoutParams(
                if (kind == Kind.PROGRESS) ViewGroup.LayoutParams.WRAP_CONTENT else iconSize,
                if (kind == Kind.PROGRESS) ViewGroup.LayoutParams.WRAP_CONTENT else iconSize,
                Gravity.CENTER
            ))
            if (animate && oldIcon != null) {
                // 旧图标缩小淡出，新图标弹出（转圈 → ✓）
                oldIcon.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(140)
                    .withEndAction { iconBox.removeView(oldIcon) }.start()
                newIcon.alpha = 0f
                newIcon.scaleX = 0.4f
                newIcon.scaleY = 0.4f
                newIcon.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(90).setDuration(260)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
            } else if (oldIcon != null) {
                iconBox.removeView(oldIcon)
            }
            if (animate && messageView.text.toString() != message) {
                messageView.alpha = 0f
                messageView.animate().alpha(1f).setStartDelay(120).setDuration(180).start()
            }
            messageView.text = message
            close.tint = tint
            close.showRing = duration > 0
            close.remaining = 1f
            // 进行中提示由结果提示替换，不给关闭按钮
            close.visibility = if (kind == Kind.PROGRESS) View.GONE else View.VISIBLE
            if (animate && kind != Kind.PROGRESS) {
                close.alpha = 0f
                close.animate().alpha(1f).setStartDelay(150).setDuration(180).start()
            }
        }

        private fun makeIcon(kind: Kind, tint: Int): View = if (kind == Kind.PROGRESS) {
            CircularProgressIndicator(activity).apply {
                isIndeterminate = true
                indicatorSize = dp(18f)
                trackThickness = dp(2.5f)
                setIndicatorColor(tint)
                trackColor = ColorUtils.setAlphaComponent(tint, 0x24)
            }
        } else {
            TextView(activity).apply {
                typeface = iconFont(activity)
                text = when (kind) {
                    Kind.SUCCESS -> ""   // fa-check
                    Kind.ERROR -> ""     // fa-exclamation
                    else -> ""           // fa-info
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(tint)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ColorUtils.setAlphaComponent(tint, 0x1F))
                }
            }
        }

        /** 0 = 小圆，1 = 展开卡片：内边距与圆角随之插值 */
        private fun applyShape(expandedProgress: Float) {
            val pillPad = (pillSize - iconSize) / 2f
            fun lerp(a: Float, b: Float) = a + (b - a) * expandedProgress
            card.setPadding(
                lerp(pillPad, dp(12f).toFloat()).toInt(),
                lerp(pillPad, dp(10f).toFloat()).toInt(),
                lerp(pillPad, dp(8f).toFloat()).toInt(),
                lerp(pillPad, dp(10f).toFloat()).toInt()
            )
            cardBackground.cornerRadius = lerp(pillSize / 2f, expandedRadius)
        }

        /** 展开状态下文案的宽度：卡片宽减去内边距、图标、间距和（可见时的）关闭按钮 */
        private fun expandedTextWidth(): Int {
            val closeWidth = if (close.visibility == View.VISIBLE) dp(36f) else 0
            return expandedWidth - dp(12f) - dp(8f) - iconSize - dp(12f) - dp(8f) - closeWidth
        }

        /** 展开状态下卡片的自然高度（按展开宽度测量） */
        private fun expandedHeight(): Int {
            applyShape(1f)
            card.measure(
                View.MeasureSpec.makeMeasureSpec(expandedWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return max(card.measuredHeight, dp(56f))
        }

        /**
         * 卡片尺寸从当前形变到目标尺寸。动画期间把窗口撑到两者中较大的那个，避免逐帧重排窗口；
         * 卡片贴窗口右上角，缩小时自然收向右侧。
         */
        private fun morphTo(toExpanded: Boolean, onEnd: () -> Unit) {
            morph?.cancel()
            val fromW = card.width
            val fromH = card.height
            val fromP = if (minimized) 0f else 1f
            val toP = if (toExpanded) 1f else 0f
            val toW = if (toExpanded) expandedWidth else pillSize
            val toH = if (toExpanded) expandedHeight() else pillSize
            applyShape(fromP)
            root.minimumWidth = max(fromW, toW) + root.paddingLeft + root.paddingRight
            root.minimumHeight = max(fromH, toH) + root.paddingTop + root.paddingBottom
            // 文案固定为展开时的宽度：形变中由卡片边缘裁切/露出，不随宽度逐帧重新换行（否则缩小时闪动）
            val textLp = messageView.layoutParams as LinearLayout.LayoutParams
            textLp.width = expandedTextWidth()
            textLp.weight = 0f
            messageView.layoutParams = textLp
            val lp = card.layoutParams
            morph = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = MORPH_DURATION
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    lp.width = (fromW + (toW - fromW) * t).toInt()
                    lp.height = (fromH + (toH - fromH) * t).toInt()
                    applyShape(fromP + (toP - fromP) * t)
                    card.layoutParams = lp
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled || !attached) return
                        lp.width = toW
                        // 展开后高度跟随内容（文案换行等）
                        lp.height = if (toExpanded) ViewGroup.LayoutParams.WRAP_CONTENT else pillSize
                        card.layoutParams = lp
                        textLp.width = 0
                        textLp.weight = 1f
                        messageView.layoutParams = textLp
                        root.minimumWidth = 0
                        root.minimumHeight = 0
                        onEnd()
                    }
                })
                start()
            }
        }

        /** 在原卡片上形变成新提示（如转圈 → ✓ 结果），缩小状态下同时展开 */
        fun update(newMessage: String, newKind: Kind, newDuration: Long) {
            stopTimers()
            generation++
            message = newMessage
            kind = newKind
            duration = newDuration
            card.animate().cancel()
            card.translationY = 0f
            card.alpha = 1f
            if (card.width == 0) {
                // 尚未完成首次布局（刚滑入）：直接换内容
                applyContent(animate = false)
                startTimer()
                return
            }
            val wasMinimized = minimized
            messageView.visibility = View.VISIBLE
            applyContent(animate = true)
            if (wasMinimized) {
                messageView.alpha = 0f
                messageView.animate().alpha(1f).setStartDelay(MORPH_DURATION / 2).setDuration(160).start()
            }
            morphTo(toExpanded = true) { startTimer() }
            minimized = false
        }

        /** 缩成右侧只有转圈的小圆 / 展开回完整卡片 */
        private fun setMinimized(value: Boolean) {
            if (value == minimized || !isLive) return
            root.removeCallbacks(minimizeLater)
            if (value) {
                messageView.animate().alpha(0f).setStartDelay(0).setDuration(100).start()
                close.animate().alpha(0f).setStartDelay(0).setDuration(100).start()
                morphTo(toExpanded = false) {
                    messageView.visibility = View.GONE
                    close.visibility = View.GONE
                }
                minimized = true
            } else {
                messageView.visibility = View.VISIBLE
                messageView.alpha = 0f
                morphTo(toExpanded = true) {
                    if (kind == Kind.PROGRESS) root.postDelayed(minimizeLater, MINIMIZE_DELAY)
                }
                minimized = false
                messageView.animate().alpha(1f).setStartDelay(MORPH_DURATION / 2).setDuration(160).start()
                if (kind != Kind.PROGRESS) {
                    close.visibility = View.VISIBLE
                    close.animate().alpha(1f).setStartDelay(MORPH_DURATION / 2).setDuration(160).start()
                }
            }
        }

        private fun startTimer() {
            if (!isLive) return
            if (duration <= 0) {
                // 常驻提示正常会被结果提示替换；先缩成小圆，兜底超时后收起
                root.postDelayed(minimizeLater, MINIMIZE_DELAY)
                root.postDelayed(progressTimeout, PROGRESS_TIMEOUT)
                return
            }
            timer = ValueAnimator.ofFloat(1f, 0f).apply {
                this.duration = this@Host.duration
                interpolator = LinearInterpolator()
                addUpdateListener { close.remaining = it.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: Animator) { if (!cancelled) hide() }
                })
                start()
            }
        }

        private fun stopTimers() {
            timer?.cancel()
            timer = null
            root.removeCallbacks(progressTimeout)
            root.removeCallbacks(minimizeLater)
        }

        fun hide() {
            if (!isLive) return
            hiding = true
            stopTimers()
            morph?.cancel()
            if (current === this) current = null
            card.animate().translationY(-(card.height + root.paddingTop).toFloat()).alpha(0f)
                .setStartDelay(0).setDuration(200).setInterpolator(DecelerateInterpolator())
                .withEndAction { removeNow() }
                .start()
        }

        fun removeNow() {
            stopTimers()
            morph?.cancel()
            card.animate().cancel()
            if (current === this) current = null
            if (!attached) return
            attached = false
            try {
                windowManager.removeViewImmediate(root)
            } catch (_: Exception) {
            }
        }

        /**
         * 按住暂停倒计时；上滑超过阈值收起（进行中提示改为缩小），否则回弹。
         * 点按小圆展开；关闭按钮自身处理点按。
         */
        private inner class SwipeTouch : View.OnTouchListener {
            private val slop = ViewConfiguration.get(activity).scaledTouchSlop
            private var downY = 0f
            private var dragging = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                if (hiding) return true
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = event.rawY
                        dragging = false
                        timer?.pause()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - downY
                        if (!dragging && abs(dy) > slop) dragging = true
                        // 只能往上拖，往下给一点阻尼
                        if (dragging) card.translationY = if (dy < 0) dy else dy / 4f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val swipedAway = dragging && card.translationY < -card.height / 3f
                        if (swipedAway && kind != Kind.PROGRESS) {
                            hide()
                        } else {
                            card.animate().translationY(0f).setStartDelay(0).setDuration(150).start()
                            timer?.resume()
                            if (kind == Kind.PROGRESS && event.actionMasked == MotionEvent.ACTION_UP) {
                                if (swipedAway) setMinimized(true)
                                else if (!dragging && minimized) setMinimized(false)
                            }
                        }
                    }
                }
                return true
            }
        }
    }

    /** 关闭按钮：中间 × 图标，外圈圆环随剩余时间从满圈缩到零 */
    private class CountdownCloseButton(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val stroke = 2.5f * density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        private val icon = ContextCompat.getDrawable(context, R.drawable.ic_close_24)!!.mutate().apply {
            setTint(Palette.INK_3)
        }
        private val oval = RectF()

        var tint: Int = Palette.ACCENT
            set(value) {
                field = value
                trackPaint.color = ColorUtils.setAlphaComponent(value, 0x2E)
                ringPaint.color = value
                invalidate()
            }

        var showRing = true
            set(value) {
                field = value
                invalidate()
            }

        /** 剩余时间比例 1 → 0 */
        var remaining = 1f
            set(value) {
                field = value
                invalidate()
            }

        init {
            tint = Palette.ACCENT
            isClickable = true
            isFocusable = true
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f - stroke / 2f - 3f * density
            if (showRing) {
                oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
                canvas.drawCircle(cx, cy, radius, trackPaint)
                // 12 点钟方向起，顺时针剩余部分
                canvas.drawArc(oval, -90f, 360f * remaining, false, ringPaint)
            }
            val half = 8f * density
            icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            icon.draw(canvas)
        }
    }

    private var cachedIconFont: Typeface? = null

    private fun iconFont(context: Context): Typeface =
        cachedIconFont ?: Typeface.createFromAsset(context.assets, "fonts/fa-solid-900.otf")
            .also { cachedIconFont = it }
}
