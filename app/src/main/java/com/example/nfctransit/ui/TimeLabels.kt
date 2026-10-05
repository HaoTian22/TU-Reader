package com.example.nfctransit.ui

import android.text.format.DateFormat
import com.example.nfctransit.R
import com.example.nfctransit.util.AppLanguage
import com.example.nfctransit.util.L10n
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/** 读卡时间 / 日期分组文案（首页卡包、单卡概览、交易列表共用），按界面语言格式化 */
object TimeLabels {

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun hm(cal: Calendar) = format("HH:mm", cal.time)

    /** 按语言取最佳日期格式：中文 "M月d日" / 英文 "MMM d" 等 */
    private fun format(skeleton: String, date: Date): String {
        val locale = AppLanguage.locale()
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
    }

    /** 日期（无时刻）：今年省略年份 */
    fun date(cal: Calendar, withWeekday: Boolean = false): String {
        val sameYear = cal.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
        val skeleton = (if (sameYear) "MMMd" else "yyyyMMMd") + if (withWeekday) "EEE" else ""
        return format(skeleton, cal.time)
    }

    private fun isToday(cal: Calendar) = sameDay(cal, Calendar.getInstance())

    private fun isYesterday(cal: Calendar) =
        sameDay(cal, Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) })

    /** 绝对时间：今天 23:37 / 昨天 23:37 / 8月29日 16:09 / 2025年8月29日 16:09；无记录返回 "—" */
    fun absolute(epochMs: Long): String {
        if (epochMs <= 0L) return "—"
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        if (isToday(cal)) return L10n.str(R.string.time_today_at, hm(cal))
        if (isYesterday(cal)) return L10n.str(R.string.time_yesterday_at, hm(cal))
        return "${date(cal)} ${hm(cal)}"
    }

    /** 相对时间：今天/昨天带时刻，一周内「N 天前」，五周内「N 周前」，更早给日期 */
    fun relative(epochMs: Long): String {
        if (epochMs <= 0L) return "—"
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        if (isToday(cal)) return L10n.str(R.string.time_today_at, hm(cal))
        if (isYesterday(cal)) return L10n.str(R.string.time_yesterday_at, hm(cal))
        val days = ((System.currentTimeMillis() - epochMs) / 86_400_000L).coerceAtLeast(2).toInt()
        return when {
            days < 7 -> L10n.plural(R.plurals.time_days_ago, days, days)
            days < 35 -> L10n.plural(R.plurals.time_weeks_ago, days / 7, days / 7)
            else -> date(cal)
        }
    }

    /** 交易列表分组标题：今天 / 昨天 / 10月1日 周四 */
    fun dayTitle(cal: Calendar): String = when {
        isToday(cal) -> L10n.str(R.string.time_today)
        isYesterday(cal) -> L10n.str(R.string.time_yesterday)
        else -> date(cal, withWeekday = true)
    }
}
