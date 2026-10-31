package com.example.nfctransit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * 自定义图表的坐标轴图（Google Analytics 风格）：浅色虚线网格 + 右侧刻度，柱状或折线（带渐变面积）。
 * 轻点/横向拖动选中某一格：竖向参考线 + 顶部气泡显示标签与数值。
 */
class AxisChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Style { COLUMN, LINE }

    data class Point(val value: Double, val label: String, val detail: String)

    private var points: List<Point> = emptyList()
    private var style = Style.COLUMN
    private var accent = Palette.ACCENT
    private var axisFormat: (Double) -> String = { it.toString() }
    private var niceMax = 1.0
    private var selected = -1

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.LINE
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.LINE
        strokeWidth = dp(1f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK_3
        textSize = sp(10f)
        textAlign = Paint.Align.LEFT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK_3
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.INK }
    private val bubbleTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        textSize = sp(10f)
    }
    private val bubbleValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = sp(12f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val path = Path()
    private val rect = RectF()

    fun setData(points: List<Point>, style: Style, accent: Int, axisFormat: (Double) -> String) {
        this.points = points
        this.style = style
        this.accent = accent
        this.axisFormat = axisFormat
        niceMax = niceCeil(points.maxOfOrNull { it.value } ?: 0.0)
        selected = -1
        invalidate()
    }

    /** 刻度上限取 1/2/5×10ⁿ 的整齐值，网格 4 等分 */
    private fun niceCeil(v: Double): Double {
        if (v <= 0) return 1.0
        val mag = 10.0.pow(floor(log10(v)))
        val n = v / mag
        val nice = when {
            n <= 1 -> 1.0
            n <= 2 -> 2.0
            n <= 4 -> 4.0
            n <= 5 -> 5.0
            n <= 8 -> 8.0
            else -> 10.0
        }
        return nice * mag
    }

    private val topPad get() = dp(44f)       // 留给选中气泡
    private val labelArea get() = dp(20f)
    private val axisGutter get() = dp(40f)    // 右侧刻度
    private val plotRight get() = width - axisGutter
    private val slot get() = plotRight / points.size.coerceAtLeast(1)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val bottom = height - labelArea
        val plotH = bottom - topPad
        fun yOf(v: Double) = bottom - (v / niceMax).toFloat().coerceIn(0f, 1f) * plotH

        // 网格 + 右侧刻度
        for (i in 1..4) {
            val y = bottom - plotH * i / 4f
            path.reset(); path.moveTo(0f, y); path.lineTo(plotRight, y)
            canvas.drawPath(path, gridPaint)
            canvas.drawText(axisFormat(niceMax * i / 4), plotRight + dp(6f), y + axisPaint.textSize / 3f, axisPaint)
        }
        canvas.drawLine(0f, bottom, plotRight, bottom, basePaint)
        canvas.drawText(axisFormat(0.0), plotRight + dp(6f), bottom + axisPaint.textSize / 3f, axisPaint)

        val n = points.size
        val s = slot
        fun xOf(i: Int) = s * i + s / 2f

        when (style) {
            Style.COLUMN -> {
                val barW = (s * 0.6f).coerceAtMost(dp(32f))
                val r = (barW / 2f).coerceAtMost(dp(3f))
                points.forEachIndexed { i, p ->
                    if (p.value <= 0) return@forEachIndexed
                    val top = yOf(p.value).coerceAtMost(bottom - dp(2f))
                    barPaint.color = if (selected < 0 || selected == i) accent
                        else ColorUtils.blendARGB(Palette.SURFACE, accent, 0.35f)
                    rect.set(xOf(i) - barW / 2f, top, xOf(i) + barW / 2f, bottom)
                    path.reset()
                    path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
                    canvas.drawPath(path, barPaint)
                }
            }
            Style.LINE -> {
                path.reset()
                points.forEachIndexed { i, p -> if (i == 0) path.moveTo(xOf(i), yOf(p.value)) else path.lineTo(xOf(i), yOf(p.value)) }
                val area = Path(path).apply {
                    lineTo(xOf(n - 1), bottom); lineTo(xOf(0), bottom); close()
                }
                areaPaint.shader = LinearGradient(
                    0f, topPad, 0f, bottom,
                    ColorUtils.setAlphaComponent(accent, 0x40), ColorUtils.setAlphaComponent(accent, 0x00),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(area, areaPaint)
                linePaint.color = accent
                canvas.drawPath(path, linePaint)
                // 点少时画出每个数据点
                if (n <= 31) {
                    dotPaint.color = accent
                    points.forEachIndexed { i, p -> canvas.drawCircle(xOf(i), yOf(p.value), dp(2.5f), dotPaint) }
                }
            }
        }

        // X 轴标签：至少约 36dp 一个，放不下时等距抽稀
        val step = ceil(dp(36f) / s).toInt().coerceAtLeast(1)
        points.forEachIndexed { i, p ->
            if (i % step == 0) canvas.drawText(fit(p.label, labelPaint, s * step - dp(2f)), xOf(i), height - dp(5f), labelPaint)
        }

        // 选中：参考线 + 气泡
        points.getOrNull(selected)?.let { p ->
            val x = xOf(selected)
            guidePaint.color = ColorUtils.setAlphaComponent(accent, 0x80)
            canvas.drawLine(x, topPad - dp(4f), x, bottom, guidePaint)
            if (style == Style.LINE) {
                dotPaint.color = Palette.SURFACE
                canvas.drawCircle(x, yOf(p.value), dp(5f), dotPaint)
                dotPaint.color = accent
                canvas.drawCircle(x, yOf(p.value), dp(3.5f), dotPaint)
            }
            val padH = dp(8f)
            val w = maxOf(bubbleTitlePaint.measureText(p.label), bubbleValuePaint.measureText(p.detail)) + padH * 2
            val h = dp(38f)
            val left = (x - w / 2f).coerceIn(0f, (width - w).coerceAtLeast(0f))
            rect.set(left, 0f, left + w, h)
            canvas.drawRoundRect(rect, dp(6f), dp(6f), bubblePaint)
            canvas.drawText(p.label, left + padH, dp(15f), bubbleTitlePaint)
            canvas.drawText(p.detail, left + padH, dp(31f), bubbleValuePaint)
        }
    }

    private fun fit(text: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(text) <= maxW) return text
        val count = paint.breakText(text, true, (maxW - paint.measureText("…")).coerceAtLeast(0f), null)
        return if (count <= 0) "" else text.take(count) + "…"
    }

    // 横向拖动时禁止父级 ScrollView 拦截；纵向拖动交给页面滚动
    private var downX = 0f
    private var downY = 0f
    private var scrubbing = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) return false
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; scrubbing = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                if (!scrubbing && dx > touchSlop && dx > abs(event.y - downY)) {
                    scrubbing = true
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                if (scrubbing) select(indexAt(event.x), toggle = false)
            }
            MotionEvent.ACTION_UP -> {
                if (!scrubbing) select(indexAt(event.x), toggle = true)
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun indexAt(x: Float): Int = (x / slot).toInt().coerceIn(0, points.size - 1)

    private fun select(index: Int, toggle: Boolean) {
        val next = if (toggle && index == selected) -1 else index
        if (next != selected) { selected = next; invalidate() }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
