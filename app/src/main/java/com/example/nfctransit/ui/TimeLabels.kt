package com.example.nfctransit.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 读卡时间文案（首页卡包、单卡概览共用） */
object TimeLabels {

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun hm(cal: Calendar) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)

    /** 绝对时间：今天 23:37 / 昨天 23:37 / 8月29日 16:09 / 2025年8月29日 16:09；无记录返回 "—" */
    fun absolute(epochMs: Long): String {
        if (epochMs <= 0L) return "—"
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        val now = Calendar.getInstance()
        if (sameDay(cal, now)) return "今天 ${hm(cal)}"
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(cal, yesterday)) return "昨天 ${hm(cal)}"
        val pattern = if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "M月d日" else "yyyy年M月d日"
        return "${SimpleDateFormat(pattern, Locale.getDefault()).format(cal.time)} ${hm(cal)}"
    }

    /** 相对时间：今天/昨天带时刻，一周内「N 天前」，五周内「N 周前」，更早给日期 */
    fun relative(epochMs: Long): String {
        if (epochMs <= 0L) return "—"
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        val now = Calendar.getInstance()
        if (sameDay(cal, now)) return "今天 ${hm(cal)}"
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(cal, yesterday)) return "昨天 ${hm(cal)}"
        val days = ((now.timeInMillis - epochMs) / 86_400_000L).coerceAtLeast(2)
        return when {
            days < 7 -> "$days 天前"
            days < 35 -> "${days / 7} 周前"
            cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                SimpleDateFormat("M月d日", Locale.getDefault()).format(cal.time)
            else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(cal.time)
        }
    }
}
