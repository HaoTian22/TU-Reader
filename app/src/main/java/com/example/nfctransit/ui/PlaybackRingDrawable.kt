package com.example.nfctransit.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** 播放按钮内的倒计时环：[remaining]（0..1）为当前行程距切换到下一段的剩余比例，<0 时不绘制 */
class PlaybackRingDrawable(density: Float) : Drawable() {
    var remaining: Float = -1f
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    private val strokePx = 2.5f * density
    private val insetPx = 4f * density
    private val rect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = Color.argb(70, 255, 255, 255)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    override fun draw(canvas: Canvas) {
        if (remaining < 0f) return
        val pad = insetPx + strokePx / 2f
        rect.set(bounds.left + pad, bounds.top + pad, bounds.right - pad, bounds.bottom - pad)
        canvas.drawOval(rect, trackPaint)
        canvas.drawArc(rect, -90f, 360f * remaining.coerceIn(0f, 1f), false, arcPaint)
    }

    override fun setAlpha(alpha: Int) {
        trackPaint.alpha = alpha * 70 / 255
        arcPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        trackPaint.colorFilter = colorFilter
        arcPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
