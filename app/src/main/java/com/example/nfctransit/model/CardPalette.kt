package com.example.nfctransit.model

import androidx.annotation.StringRes
import com.example.nfctransit.R

/**
 * 卡面马卡龙调色板（起色 → 止色，TL→BR 渐变）。新卡按顺序分配未被占用的颜色；卡片信息页可手动更换。
 * 卡面浅色配深色文字；界面强调色由 ui/Palette.accentFor 从卡面色压暗得到，保证文字可读。
 */
object CardPalette {

    data class Swatch(@StringRes val nameRes: Int, val start: Long, val end: Long) {
        val pair: Pair<Long, Long> get() = start to end
    }

    val swatches: List<Swatch> = listOf(
        Swatch(R.string.color_mint, 0xFFBDEBD5, 0xFF95DDBB),
        Swatch(R.string.color_peach, 0xFFFFD3BD, 0xFFFFB898),
        Swatch(R.string.color_lavender, 0xFFDCD3F7, 0xFFC2B4F0),
        Swatch(R.string.color_sky, 0xFFC6E2FA, 0xFFA3CEF5),
        Swatch(R.string.color_sakura, 0xFFFAD0DC, 0xFFF5B3C6),
        Swatch(R.string.color_lemon, 0xFFFCEBA8, 0xFFF7DC7E),
        Swatch(R.string.color_matcha, 0xFFD9EBB5, 0xFFC2DE8E),
        Swatch(R.string.color_coral, 0xFFFFC9C2, 0xFFFFA99F),
        Swatch(R.string.color_lake, 0xFFBFEDEA, 0xFF97E0DB),
        Swatch(R.string.color_lilac, 0xFFEBCDF2, 0xFFDCAEE8),
        Swatch(R.string.color_apricot, 0xFFFFE0B8, 0xFFFFCB8F),
        Swatch(R.string.color_haze, 0xFFCED6F5, 0xFFADB9EE)
    )

    val colors: List<Pair<Long, Long>> = swatches.map { it.pair }

    /** 旧版 20 色深色卡面（按顺序），用于把已有卡片迁移到马卡龙色 */
    private val legacy: List<Long> = listOf(
        0xFF1A73E8, 0xFF2E7D32, 0xFFE65100, 0xFF6A1B9A, 0xFFC62828,
        0xFF00838F, 0xFFF9A825, 0xFF5D4037, 0xFF455A64, 0xFFAD1457,
        0xFF00796B, 0xFF283593, 0xFFD81B60, 0xFFF4511E, 0xFF3949AB,
        0xFF43A047, 0xFF00ACC1, 0xFFE53935, 0xFF8E24AA, 0xFF00897B
    )

    /** 旧调色板颜色 → 同序号的马卡龙色；非旧调色板颜色返回 null（保持不动） */
    fun migrateLegacy(start: Long): Pair<Long, Long>? {
        val i = legacy.indexOf(start)
        return if (i < 0) null else colors[i % colors.size]
    }
}
