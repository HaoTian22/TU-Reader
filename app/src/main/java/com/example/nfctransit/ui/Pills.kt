package com.example.nfctransit.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.nfctransit.R

/**
 * 线路药丸：背景 = 线路颜色，文字颜色按背景亮度自动调整（深底白字 / 浅底深字）。
 * 地图轨迹、交易列表、统计页共用，保证全应用线路药丸一致。
 */

/** 解析 "#RRGGBB" 线路颜色；空/非法返回 null */
fun parseLineColor(color: String?): Int? {
    val trimmed = color?.trim()
    if (trimmed.isNullOrEmpty()) return null
    return try {
        android.graphics.Color.parseColor(trimmed)
    } catch (e: RuntimeException) {
        null
    }
}

/** 感知亮度需要白字（与交易列表一致） */
fun isDarkColor(color: Int): Boolean {
    val r = android.graphics.Color.red(color)
    val g = android.graphics.Color.green(color)
    val b = android.graphics.Color.blue(color)
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) < 160
}

fun Context.dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

/** 给已存在的 TextView 应用线路药丸（背景=线路色，文字色按亮度调整）；无颜色时灰底 */
fun TextView.applyLinePill(color: String?) {
    val parsed = parseLineColor(color)
    if (parsed == null) {
        background = ContextCompat.getDrawable(context, R.drawable.bg_chip_default)
        setTextColor(0xFF555555.toInt())
        return
    }
    background = GradientDrawable().apply {
        cornerRadius = 20f * context.resources.displayMetrics.density
        setColor(parsed)
    }
    setTextColor(if (isDarkColor(parsed)) 0xFFFFFFFF.toInt() else 0xFF555555.toInt())
}

/** 创建一颗线路药丸 TextView（统计页/地图轨迹用） */
fun Context.linePill(text: String, color: String?): TextView = TextView(this).apply {
    this.text = text
    textSize = 10f
    setSingleLine(true)
    setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
    applyLinePill(color)
}
