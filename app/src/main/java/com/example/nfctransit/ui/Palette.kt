package com.example.nfctransit.ui

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.nfctransit.R

/**
 * 界面色板（代码侧），取自 res/values/colors.xml（深色模式为 values-night/colors.xml）：中性灰 + 单一深蓝强调色。
 * 只用于界面框架；卡片主题色、线路色、图表系列色属数据色，不走这里。
 *
 * 随深浅色变化的颜色在 [refresh]（MainActivity.onCreate）时从当前主题读取；切换深浅色会重建 Activity，
 * 因此视图里直接读 Palette.X 即为当前主题色。下方初值是浅色兜底（JVM 单测 / 刷新前）。
 */
object Palette {
    var PAPER = 0xFFF3F4F6.toInt(); private set
    var SURFACE = 0xFFFFFFFF.toInt(); private set
    var FILL = 0xFFECEEF1.toInt(); private set
    var LINE = 0xFFE1E4E8.toInt(); private set
    var INK = 0xFF1B1C1E.toInt(); private set
    var INK_2 = 0xFF4A4D52.toInt(); private set
    var INK_3 = 0xFF6B6F76.toInt(); private set
    var INK_DISABLED = 0xFFC3C7CD.toInt(); private set
    var ACCENT = 0xFF1F4E9C.toInt(); private set
    var ACCENT_CONTAINER = 0xFFE7ECF5.toInt(); private set
    var DANGER = 0xFFB3261E.toInt(); private set
    var SUCCESS = 0xFF2E7D4F.toInt(); private set
    /** 交易金额专用：比 DANGER / SUCCESS 更鲜亮，让扣费与充值一眼可辨；危险操作仍用 DANGER */
    var AMOUNT_OUT = 0xFFE5383B.toInt(); private set
    var AMOUNT_IN = 0xFF12A150.toInt(); private set
    var WARNING = 0xFFB26A00.toInt(); private set
    /** 强调色填充上的文字/图标（浅色模式白字；深色模式强调色变浅，改用深字） */
    var ON_ACCENT = 0xFFFFFFFF.toInt(); private set

    /** 当前是否深色模式 */
    var isDark = false
        private set

    /** 卡面 / 线路色块上的深色字：底色是固定的数据色（马卡龙卡面、线路色），不随深浅模式变化 */
    const val CARD_INK = 0xFF1B1C1E.toInt()
    const val CARD_INK_2 = 0xFF4A4D52.toInt()

    /**
     * 卡面实际绘制色。深色模式下马卡龙卡面换成同色相的莫兰迪色：灰调低饱和（0.2）、中等亮度
     * （起点 0.60、终点 0.54 形成渐变），在深色界面上柔和不刺眼。
     */
    fun cardFace(color: Long, end: Boolean = false): Int {
        if (!isDark) return color.toInt()
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color.toInt(), hsl)
        hsl[1] = 0.20f
        hsl[2] = if (end) 0.54f else 0.60f
        return ColorUtils.HSLToColor(hsl)
    }

    /** 卡面上的主文字色：深色模式莫兰迪卡面统一深字；浅色模式按卡面深浅取白/深字 */
    fun onCardFace(color: Long): Int = when {
        isDark -> CARD_INK
        isDarkColor(color.toInt()) -> android.graphics.Color.WHITE
        else -> CARD_INK
    }

    // 深色面板（轨迹回放 / 原始数据十六进制）：本身即深色，深浅模式通用
    const val NIGHT = 0xFF18191B.toInt()
    const val NIGHT_2 = 0xFF2A2C30.toInt()
    const val NIGHT_INK = 0xFFD4D7DC.toInt()
    const val NIGHT_INK_3 = 0xFF979BA2.toInt()
    const val NIGHT_ACCENT = 0xFF8FB0E6.toInt()

    /** 按当前主题（含 AppCompat 深浅色设置）重读色板；在 Activity super.onCreate 之后调用 */
    fun refresh(context: Context) {
        isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        fun c(id: Int) = ContextCompat.getColor(context, id)
        PAPER = c(R.color.paper)
        SURFACE = c(R.color.surface)
        FILL = c(R.color.fill)
        LINE = c(R.color.line)
        INK = c(R.color.ink)
        INK_2 = c(R.color.ink_2)
        INK_3 = c(R.color.ink_3)
        INK_DISABLED = c(R.color.ink_disabled)
        ACCENT = c(R.color.accent)
        ACCENT_CONTAINER = c(R.color.accent_container)
        DANGER = c(R.color.danger)
        SUCCESS = c(R.color.success)
        AMOUNT_OUT = c(R.color.amount_out)
        AMOUNT_IN = c(R.color.amount_in)
        WARNING = c(R.color.warning)
        ON_ACCENT = c(R.color.on_accent)
    }

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
        if (isDark) {
            // 深色底上反过来：亮度抬到 0.72，保证在深灰背景上可读
            hsl[1] = hsl[1].coerceAtMost(0.6f)
            hsl[2] = hsl[2].coerceAtLeast(0.72f)
            return ColorUtils.HSLToColor(hsl)
        }
        if (hsl[2] <= 0.45f) return cardColor.toInt()
        hsl[1] = hsl[1].coerceAtMost(0.6f)
        hsl[2] = 0.38f
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * 交易类型图标配色（圆底浅色, 字形深色）：马卡龙色系，只用在类型图标上；类型胶囊保持中性灰。
     * 字形取同色系深色档，保证在浅色圆底上可读。
     */
    fun transitIconColors(type: String?): Pair<Int, Int> {
        val (bg, fg) = lightTransitIconColors(type)
        if (!isDark) return bg to fg
        // 深色：圆底取字形色的暗淡版，字形取浅色档
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(fg, hsl)
        hsl[2] = 0.74f
        return ColorUtils.blendARGB(SURFACE, fg, 0.30f) to ColorUtils.HSLToColor(hsl)
    }

    private fun lightTransitIconColors(type: String?): Pair<Int, Int> = when (type) {
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
    val ACCENT_ARGB: Long get() = ACCENT.toLong() and 0xFFFFFFFFL
}

