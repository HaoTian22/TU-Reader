package com.example.nfctransit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * 柱状图背景网格：按"整数刻度"画水平虚线，左侧留出标签栏写金额。
 * 纵向几何与柱子一致：基线在视图底部上方 [baselineOffsetPx]，满高 [plotHeightPx] 对应 [maxValue]。
 */
class ChartGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Palette.LINE
        pathEffect = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK_3
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 7f, resources.displayMetrics)
        textAlign = Paint.Align.RIGHT
    }
    private val path = Path()

    private var maxValue = 0.0
    private var currency = ""
    private var plotHeightPx = 0f
    private var baselineOffsetPx = 0f
    private var ticks: List<Double> = emptyList()

    /** 标签栏宽度（px），图表容器据此设置 paddingStart，让柱子不压住标签 */
    var gutterWidth = 0
        private set

    fun setScale(maxValue: Double, currency: String, plotHeightPx: Int, baselineOffsetPx: Int) {
        this.maxValue = maxValue
        this.currency = currency
        this.plotHeightPx = plotHeightPx.toFloat()
        this.baselineOffsetPx = baselineOffsetPx.toFloat()
        ticks = niceTicks(maxValue)
        val widest = ticks.maxOfOrNull { labelPaint.measureText(format(it)) } ?: 0f
        gutterWidth = if (ticks.isEmpty()) 0 else (widest + 4 * density).toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (maxValue <= 0 || ticks.isEmpty()) return
        val baseline = height - baselineOffsetPx
        val textCenter = (labelPaint.descent() + labelPaint.ascent()) / 2
        for (v in ticks) {
            val y = baseline - (v / maxValue * plotHeightPx).toFloat()
            path.reset()
            path.moveTo(gutterWidth.toFloat(), y)
            path.lineTo(width.toFloat(), y)
            canvas.drawPath(path, linePaint)
            canvas.drawText(format(v), gutterWidth - 3 * density, y - textCenter, labelPaint)
        }
    }

    private fun format(v: Double): String =
        currency + String.format("%.2f", v).trimEnd('0').trimEnd('.')

    /** 1/2/5×10ⁿ 步长，约 2~4 条线，不超过最大值；0 刻度不画（基线已由柱底体现） */
    private fun niceTicks(max: Double): List<Double> {
        if (max <= 0) return emptyList()
        val raw = max / 4
        val mag = 10.0.pow(floor(log10(raw)))
        val n = raw / mag
        val step = when {
            n <= 1.0 -> 1.0
            n <= 2.0 -> 2.0
            n <= 5.0 -> 5.0
            else -> 10.0
        } * mag
        return generateSequence(step) { it + step }
            .takeWhile { it <= max * 1.0001 }
            .toList()
    }
}
