package com.example.nfctransit.util

import android.content.Context

/** 首页显示偏好（设置页「卡片管理」）。 */
object HomePrefs {
    private const val PREFS_NAME = "home_prefs"
    private const val KEY_SHOW_TOTAL_BALANCE = "show_total_balance"

    fun showTotalBalance(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_TOTAL_BALANCE, true)

    fun setShowTotalBalance(context: Context, show: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHOW_TOTAL_BALANCE, show)
            .apply()
    }
}
