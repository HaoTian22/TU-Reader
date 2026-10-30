package com.example.nfctransit.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 界面深浅色：跟随系统 / 浅色 / 深色（AppCompat DayNight，配色见 values-night/colors.xml）。
 * 同步读写 SharedPreferences：须在 Activity super.onCreate 之前生效，避免先画一帧浅色。
 */
object AppTheme {

    enum class Mode(val nightMode: Int) {
        SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
        DARK(AppCompatDelegate.MODE_NIGHT_YES)
    }

    private const val PREFS_NAME = "app_theme"
    private const val KEY_MODE = "mode"

    fun mode(context: Context): Mode {
        val stored = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        return Mode.entries.firstOrNull { it.name == stored } ?: Mode.SYSTEM
    }

    /** 在 Activity.onCreate（super 之前）调用 */
    fun apply(context: Context) {
        val night = mode(context).nightMode
        if (AppCompatDelegate.getDefaultNightMode() != night) AppCompatDelegate.setDefaultNightMode(night)
    }

    /** 切换并立即生效（AppCompat 会重建 Activity） */
    fun select(context: Context, mode: Mode) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode.name)
            .apply()
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }
}
