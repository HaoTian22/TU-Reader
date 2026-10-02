package com.example.nfctransit.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView

/**
 * 全局触摸反馈：应用里的按钮/行大多是自绘背景的 TextView/LinearLayout，没有系统水波纹。
 * 这里给可点击视图补一个前景 RippleDrawable，蒙版沿用背景形状（圆角卡片、圆形按钮），
 * 无背景时按视图矩形。灰色波纹在浅色页面和深色地图页上都可见。
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
    if (!isClickable || foreground != null) return false
    if (background is RippleDrawable) return false          // 已自带水波纹（如弹窗文字按钮）
    if (this is EditText || this is CompoundButton) return false
    if (this is TextView && isTextSelectable) return false  // 可选中文本（原始数据面板）
    return true
}

/** 蒙版：复制背景形状并填实（虚线框等透明填充的形状也要整块响应）；无背景用矩形 */
private fun View.rippleMask(): Drawable {
    val bg = background?.constantState?.newDrawable()?.mutate()
    return when (bg) {
        is GradientDrawable -> bg.apply { setColor(Color.WHITE) }
        null, is ColorDrawable -> ColorDrawable(Color.WHITE)
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
