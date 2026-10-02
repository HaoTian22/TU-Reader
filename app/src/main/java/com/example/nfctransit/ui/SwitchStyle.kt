package com.example.nfctransit.ui

import android.content.res.ColorStateList
import android.graphics.Color
import com.google.android.material.materialswitch.MaterialSwitch

/** 开关统一配色：开启时轨道为主题色（跟随卡片）、白色旋钮；关闭时浅灰轨道 + 灰旋钮 */
fun MaterialSwitch.tintAccent(accent: Int) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    trackTintList = ColorStateList(states, intArrayOf(accent, Palette.LINE))
    thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Palette.INK_3))
    trackDecorationTintList = ColorStateList(states, intArrayOf(accent, Palette.INK_3))
}
