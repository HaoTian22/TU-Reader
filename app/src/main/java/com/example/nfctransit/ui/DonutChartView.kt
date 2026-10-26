package com.example.nfctransit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.example.nfctransit.model.CategorySpending
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import com.example.nfctransit.R

/**
 * 环形饼图（空心圆）：按分类画圆弧，中心显示总开销。
 * 传入 details 时可点选扇区（与自定义图表 AxisChartView 的选中样式一致）：选中扇区保持原色、其余淡化，
 * 扇区中点旁浮出深色气泡显示名称与数值；再点同一扇区或点圆心取消。
 * 视图宽于圆环时（宽度 match_parent），气泡可伸到圆环两侧而不被裁切。
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(24)
    }
    private val labelBaseSize = 45f
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK_3
        textSize = labelBaseSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val amountBaseSize = 84f
    private val amountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK
        textSize = amountBaseSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.INK }
    private val bubbleTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        textSize = sp(10f)
    }
    private val bubbleValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = sp(12f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bubbleRect = RectF()

    private var segments: List<CategorySpending> = emptyList()
    private var totalText = "¥0.00"
    private var centerLabel: String? = null
    private var details: List<Pair<String, String>>? = null
    private var selected = -1

    /**
     * centerLabel 为中心小标题，null = 默认"总开销"；
     * details 与 segments 一一对应（名称, 数值文字），非 null 时可点选扇区
     */
    fun setSegments(
        segments: List<CategorySpending>,
        totalText: String,
        centerLabel: String? = null,
        details: List<Pair<String, String>>? = null
    ) {
        this.segments = segments
        this.totalText = totalText
        this.centerLabel = centerLabel
        this.details = details?.takeIf { it.size == segments.size }
        selected = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        val radius = size / 2f - ringPaint.strokeWidth / 2f - dpToPx(4)
        val centerX = width / 2f
        val centerY = height / 2f
        val rect = RectF(
            centerX - radius, centerY - radius,
            centerX + radius, centerY + radius
        )

        if (segments.isNotEmpty()) {
            val gapDegrees = 2f
            var start = -90f   // 从 12 点方向起画
            for (seg in segments) {
                val sweep = seg.percent * 360f
                val drawSweep = (sweep - gapDegrees).coerceAtLeast(0.5f)
                ringPaint.color = if (selected < 0 || segments[selected] === seg) seg.color
                    else ColorUtils.blendARGB(0xFFFFFFFF.toInt(), seg.color, 0.35f)
                canvas.drawArc(rect, start + gapDegrees / 2f, drawSweep, false, ringPaint)
                start += sweep
            }
        }

        // 中心：总开销金额 + 标签。统一缩放适配视图（不裁切），整体再缩 0.8×0.8；
        // 金额按字形实际边界贴紧标签，避免数字行顶部留白造成两行间距过大
        val contentScale = 0.64f
        val maxTextW = (size - dpToPx(4)) * contentScale
        val maxBlockH = (size - dpToPx(8)) * contentScale
        val gap = dpToPx(8)
        amountPaint.textSize = amountBaseSize
        labelPaint.textSize = labelBaseSize
        val labelFm = labelPaint.fontMetrics
        val labelLineH = labelFm.bottom - labelFm.top

        var scale = 1f
        val amountW = amountPaint.measureText(totalText)
        if (amountW > maxTextW) scale = min(scale, maxTextW / amountW)
        val totalLabel = centerLabel ?: context.getString(R.string.stats_donut_total)
        val labelW = labelPaint.measureText(totalLabel)
        if (labelW > maxTextW) scale = min(scale, maxTextW / labelW)
        val baseGlyphH = Rect().also { amountPaint.getTextBounds(totalText, 0, totalText.length, it) }.height().toFloat()
        val baseBlockH = labelLineH + gap + baseGlyphH
        if (baseBlockH > maxBlockH) scale = min(scale, maxBlockH / baseBlockH)

        amountPaint.textSize = amountBaseSize * scale * 0.7f
        labelPaint.textSize = labelBaseSize * scale
        val lFm = labelPaint.fontMetrics
        val lH = lFm.bottom - lFm.top
        val aBounds = Rect().also { amountPaint.getTextBounds(totalText, 0, totalText.length, it) }
        val aTop = aBounds.top.toFloat()
        val aH = aBounds.height().toFloat()
        val blockH = lH + gap + aH
        // 整体略微上移，视觉上更贴近圆环中心
        val blockTop = centerY - blockH / 2f - dpToPx(4)
        val labelBaseline = blockTop - lFm.top
        // 金额字形顶部贴到标签基线，去掉标签 descender 与数字行顶部留白，两行间距尽量小
        val amountBaseline = labelBaseline + gap - aTop
        canvas.drawText(totalLabel, centerX, labelBaseline, labelPaint)
        canvas.drawText(totalText, centerX, amountBaseline, amountPaint)

        details?.getOrNull(selected)?.let { (title, value) -> drawBubble(canvas, title, value, centerX, centerY, radius) }
    }

    /** 选中气泡：锚在扇区弧线中点，上半环放在锚点上方、下半环放在下方，左右不超出视图 */
    private fun drawBubble(canvas: Canvas, title: String, value: String, cx: Float, cy: Float, radius: Float) {
        val startFraction = segments.take(selected).sumOf { it.percent.toDouble() }
        val mid = Math.toRadians((startFraction + segments[selected].percent / 2.0) * 360.0 - 90.0)
        val ax = cx + (radius * cos(mid)).toFloat()
        val ay = cy + (radius * sin(mid)).toFloat()

        val padH = dpToPx(8)
        val maxW = width - padH * 2
        val name = TextUtils.ellipsize(title, bubbleTitlePaint, maxW, TextUtils.TruncateAt.END).toString()
        val w = (maxOf(bubbleTitlePaint.measureText(name), bubbleValuePaint.measureText(value)) + padH * 2).coerceAtMost(width.toFloat())
        val h = dpToPx(38)
        val offset = ringPaint.strokeWidth / 2f + dpToPx(4)
        val top = (if (ay <= cy) ay - offset - h else ay + offset).coerceIn(0f, (height - h).coerceAtLeast(0f))
        val left = (ax - w / 2f).coerceIn(0f, (width - w).coerceAtLeast(0f))
        bubbleRect.set(left, top, left + w, top + h)
        canvas.drawRoundRect(bubbleRect, dpToPx(6), dpToPx(6), bubblePaint)
        canvas.drawText(name, left + padH, top + dpToPx(15), bubbleTitlePaint)
        canvas.drawText(value, left + padH, top + dpToPx(31), bubbleValuePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (details == null || segments.isEmpty()) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val index = segmentAt(event.x, event.y)
            val next = if (index == selected) -1 else index
            if (next != selected) { selected = next; invalidate() }
        }
        return true
    }

    /** 触点所在扇区：圆环内外各放宽一些便于点中；圆心或环外返回 -1 */
    private fun segmentAt(x: Float, y: Float): Int {
        val size = min(width, height).toFloat()
        val radius = size / 2f - ringPaint.strokeWidth / 2f - dpToPx(4)
        val dist = hypot(x - width / 2f, y - height / 2f)
        if (dist < radius - ringPaint.strokeWidth || dist > radius + ringPaint.strokeWidth) return -1
        // 从 12 点方向顺时针的角度（0..360）
        val angle = (Math.toDegrees(atan2((y - height / 2f).toDouble(), (x - width / 2f).toDouble())) + 90 + 360) % 360
        var start = 0.0
        segments.forEachIndexed { i, seg ->
            start += seg.percent * 360.0
            if (angle < start) return i
        }
        return segments.lastIndex
    }

    private fun dpToPx(dp: Int): Float = dp * resources.displayMetrics.density
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
