package com.example.nfctransit.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.widget.TextView

/**
 * 「图标 + 文字」按钮：只有图标字形用 Font Awesome，文字保持系统字体。
 * 整个控件设 FA 字体会让英文单词触发 FA 连字（如 "Copy" 被画成复制图标）。
 */
fun TextView.setIconLabel(iconFont: Typeface, glyph: String, label: CharSequence) {
    text = SpannableString("$glyph $label").apply {
        setSpan(IconFontSpan(iconFont), 0, glyph.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    isAllCaps = false
    // 原先整体用 FA 900 字重绘制，文字呈粗体；保持一致
    typeface = Typeface.DEFAULT_BOLD
}

private class IconFontSpan(private val font: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) = apply(paint)
    override fun updateMeasureState(paint: TextPaint) = apply(paint)
    private fun apply(paint: Paint) {
        paint.typeface = font
    }
}
