package com.example.nfctransit.ui

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView

/**
 * 全局触摸反馈：应用里的按钮/行大多是自绘背景的 TextView/LinearLayout，没有系统水波纹。
 * 这里给可点击视图补一个前景 RippleDrawable，蒙版沿用背景形状（圆角卡片、圆形按钮），
 * 无背景时按视图矩形，但贴着圆角卡片上/下沿的行会跟随卡片圆角（见 [EdgeAwareMask]）。
 * 灰色波纹在浅色页面和深色地图页上都可见。
 */
private const val RIPPLE_COLOR = 0x33808080

/** 遍历视图树，给尚未处理过的可点击视图加水波纹 */
fun View.applyTouchFeedback() {
    if (wantsRipple()) {
        foreground = RippleDrawable(ColorStateList.valueOf(RIPPLE_COLOR), null, rippleMask())
    }
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).applyTouchFeedback()
}

private fun View.wantsRipple(): Boolean {
    if (this is TextureView) return false                   // 不支持前景（setForeground 直接抛异常），如地图 TextureMapView
    if (!isClickable || foreground != null) return false
    if (background is RippleDrawable) return false          // 已自带水波纹（如弹窗文字按钮）
    if (this is EditText || this is CompoundButton) return false
    if (this is TextView && isTextSelectable) return false  // 可选中文本（原始数据面板）
    return true
}

/** 蒙版：复制背景形状并填实（虚线框等透明填充的形状也要整块响应）；无背景用矩形（贴卡片边沿处圆角） */
private fun View.rippleMask(): Drawable {
    val bg = background?.constantState?.newDrawable()?.mutate()
    return when (bg) {
        is GradientDrawable -> bg.apply { setColor(Color.WHITE) }
        null, is ColorDrawable -> EdgeAwareMask(this)
        else -> bg
    }
}

/**
 * 让一棵视图树持续获得触摸反馈：立即处理一次，之后每次布局（列表行绑定、动态生成的行）再补处理新视图。
 * 已处理的视图有前景，会被跳过，重复遍历的开销很小。
 */
fun View.keepTouchFeedback() {
    applyTouchFeedback()
    val listener = ViewTreeObserver.OnGlobalLayoutListener { applyTouchFeedback() }
    // 监听挂在窗口级 ViewTreeObserver 上：随视图挂载/卸载增删，页面销毁后不再遍历旧视图
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }

        override fun onViewDetachedFromWindow(v: View) {
            v.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    })
    if (isAttachedToWindow) viewTreeObserver.addOnGlobalLayoutListener(listener)
}

/**
 * 无背景行的水波纹蒙版：矩形，但若该行贴着祖先圆角卡片（GradientDrawable 背景）的上沿/下沿，
 * 对应两角取卡片圆角，避免按压高亮在卡片圆角处露出直角。
 * 每次绘制时按当前位置判断，行的显隐变化（首/末行改变）后仍正确。
 */
private class EdgeAwareMask(private val view: View) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        var top = 0
        var radius = 0f
        var atTop = false
        var atBottom = false
        var child: View = view
        // 最多向上找 3 层：行可能包在一个无背景的容器里（如行 + 状态文字）
        for (level in 0 until 3) {
            val parent = child.parent as? ViewGroup ?: break
            top += child.top
            val bg = parent.background
            if (bg is GradientDrawable && bg.cornerRadius > 0f) {
                radius = bg.cornerRadius
                atTop = top <= 0
                atBottom = top + view.height >= parent.height
                break
            }
            child = parent
        }
        rect.set(bounds)
        if (radius == 0f || (!atTop && !atBottom)) {
            canvas.drawRect(rect, paint)
            return
        }
        val t = if (atTop) radius else 0f
        val b = if (atBottom) radius else 0f
        path.reset()
        path.addRoundRect(rect, floatArrayOf(t, t, t, t, b, b, b, b), Path.Direction.CW)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
