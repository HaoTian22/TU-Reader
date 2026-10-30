package com.example.nfctransit.ui

import android.content.Context
import android.graphics.Typeface

/**
 * 原始数据面板的等宽字体：随应用打包的 Hermit Regular（Pablo Caro，OFL 1.1，见 assets/fonts/Hermit-OFL.txt）。
 * 系统字体主题（如 ColorOS）会替换 Typeface.MONOSPACE，hex 列因此对不齐；从 assets 加载不受影响。
 */
object RawFont {
    @Volatile
    private var cached: Typeface? = null

    fun get(context: Context): Typeface = cached ?: synchronized(this) {
        cached ?: runCatching {
            Typeface.createFromAsset(context.applicationContext.assets, "fonts/Hermit-Regular.otf")
        }.getOrDefault(Typeface.MONOSPACE).also { cached = it }
    }
}
