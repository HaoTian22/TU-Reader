package com.example.nfctransit.ui

import androidx.core.graphics.ColorUtils

/**
 * 界面色板（代码侧），与 res/values/colors.xml 同值：中性灰 + 单一深蓝强调色。
 * 只用于界面框架；卡片主题色、线路色、图表系列色属数据色，不走这里。
 */
object Palette {
    const val PAPER = 0xFFF3F4F6.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val FILL = 0xFFECEEF1.toInt()
    const val LINE = 0xFFE1E4E8.toInt()
    const val INK = 0xFF1B1C1E.toInt()
    const val INK_2 = 0xFF4A4D52.toInt()
    const val INK_3 = 0xFF6B6F76.toInt()
    const val INK_DISABLED = 0xFFC3C7CD.toInt()
    const val ACCENT = 0xFF1F4E9C.toInt()
    const val ACCENT_CONTAINER = 0xFFE7ECF5.toInt()
    const val DANGER = 0xFFB3261E.toInt()
    const val SUCCESS = 0xFF2E7D4F.toInt()
    /** 交易金额专用：比 DANGER / SUCCESS 更鲜亮，让扣费与充值一眼可辨；危险操作仍用 DANGER */
    const val AMOUNT_OUT = 0xFFE5383B.toInt()
    const val AMOUNT_IN = 0xFF12A150.toInt()
    const val WARNING = 0xFFB26A00.toInt()

    const val NIGHT = 0xFF18191B.toInt()
    const val NIGHT_2 = 0xFF2A2C30.toInt()
    const val NIGHT_INK = 0xFFD4D7DC.toInt()
    const val NIGHT_INK_3 = 0xFF979BA2.toInt()
    const val NIGHT_ACCENT = 0xFF8FB0E6.toInt()

    /** 交易金额文字色：入账绿、扣款红；无金额的状态文字（如「进站」）用中性灰，避免误读为扣费 */
    fun amountColor(amountText: String): Int = when {
        amountText.startsWith("+") -> AMOUNT_IN
        amountText.startsWith("-") -> AMOUNT_OUT
        else -> INK_3
    }

    /**
     * 由卡面色得到界面强调色：卡面是浅色马卡龙，直接用作文字/图标不可读，
     * 故保持色相、把亮度压到 0.38（饱和度封顶 0.6）；本来就够深的颜色原样返回。
     */
    fun accentFor(cardColor: Long): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(cardColor.toInt(), hsl)
        if (hsl[2] <= 0.45f) return cardColor.toInt()
        hsl[1] = hsl[1].coerceAtMost(0.6f)
        hsl[2] = 0.38f
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * 交易类型图标配色（圆底浅色, 字形深色）：马卡龙色系，只用在类型图标上；类型胶囊保持中性灰。
     * 字形取同色系深色档，保证在浅色圆底上可读。
     */
    fun transitIconColors(type: String?): Pair<Int, Int> = when (type) {
        "地铁" -> 0xFFD8F3E6.toInt() to 0xFF1F7A55.toInt()            // 薄荷
        "公交", "BRT" -> 0xFFFFE3D3.toInt() to 0xFFB4532A.toInt()     // 蜜桃
        "有轨电车", "城际" -> 0xFFE6E0F8.toInt() to 0xFF5B45A8.toInt()  // 薰衣草
        "充值" -> 0xFFFFF1C2.toInt() to 0xFF8A6400.toInt()            // 奶油黄
        "消费", "便利店" -> 0xFFFBDDE5.toInt() to 0xFFA23A5A.toInt()    // 玫瑰
        else -> 0xFFDCEBFA.toInt() to 0xFF2F5F96.toInt()              // 天蓝（轮渡/出租车/铁路……）
    }

    /** 给类型图标 TextView 的圆底（自身或父容器的 GradientDrawable）和字形着色 */
    fun applyTransitIcon(icon: android.widget.TextView, circle: android.view.View, type: String?) {
        val (bg, fg) = transitIconColors(type)
        icon.setTextColor(fg)
        // 圆底 drawable 来自共享资源，先 mutate 再改色，避免所有行串成同一颜色
        (circle.background?.mutate() as? android.graphics.drawable.GradientDrawable)?.setColor(bg)
    }

    /** 主强调色的 ARGB Long 形式（卡片主题色以 Long 存储） */
    const val ACCENT_ARGB = 0xFF1F4E9CL
}

