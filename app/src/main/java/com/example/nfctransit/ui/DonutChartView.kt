package com.example.nfctransit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.example.nfctransit.model.CategorySpending
import kotlin.math.min

/** 环形饼图（空心圆）：按分类画圆弧，中心显示总开销 */
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
        color = 0xFF8E8E93.toInt()
        textSize = labelBaseSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val amountBaseSize = 84f
    private val amountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
        textSize = amountBaseSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private var segments: List<CategorySpending> = emptyList()
    private var totalText = "¥0.00"

    fun setSegments(segments: List<CategorySpending>, totalText: String) {
        this.segments = segments
        this.totalText = totalText
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
                ringPaint.color = seg.color
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
        val labelW = labelPaint.measureText("总开销")
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
        canvas.drawText("总开销", centerX, labelBaseline, labelPaint)
        canvas.drawText(totalText, centerX, amountBaseline, amountPaint)
    }

    private fun dpToPx(dp: Int): Float = dp * resources.displayMetrics.density
}
