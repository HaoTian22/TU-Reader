package com.example.nfctransit.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle

/**
 * 次要信息排版：不用「·」做分隔符。并列的短信息以空格相连，
 * 主次之分靠不透明度（附加部分沿用当前文字色、降低 alpha），对任意文字色都成立。
 */
object MetaText {
    private const val DIM_ALPHA = 0.6f

    /** 并列短信息（城市 类型 线路）：去空后以空格相连 */
    fun join(parts: Iterable<String?>): String =
        parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" ")

    /** 主文本 + 弱化的附加文本；任一为空时只返回另一个 */
    fun withDim(primary: String, secondary: String): CharSequence {
        if (secondary.isBlank()) return primary
        if (primary.isBlank()) return dim(secondary)
        return SpannableStringBuilder(primary).append("  ").append(dim(secondary))
    }

    /** 卡名 + 尾号（徽标 / 卡片主页胶囊） */
    fun cardBadge(name: String, lastFour: String): CharSequence = withDim(name, lastFour)

    private fun dim(text: String): CharSequence = SpannableStringBuilder(text).apply {
        setSpan(object : CharacterStyle() {
            override fun updateDrawState(tp: TextPaint) {
                tp.alpha = (tp.alpha * DIM_ALPHA).toInt()
            }
        }, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
