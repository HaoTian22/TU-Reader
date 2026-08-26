package com.example.nfctransit.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.example.nfctransit.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 自定义日期范围弹窗（浅色 sheet 风格）：
 * 头部日历图标 + 标题 + 关闭，两个日期输入盒（起/止）中间箭头连接，
 * 下方月历点选范围，选中端点填充主题色、区间用主题色淡色容器，取消/确定右对齐。
 */
object DateRangeDialog {

    fun show(
        context: Context,
        accentColor: Int,
        initialStart: String?,
        initialEnd: String?,
        onSelected: (start: String, end: String) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = fmt.format(Calendar.getInstance().time)

        // 浅色 sheet 配色（primary/确认等由卡片主题色注入）
        val primary = accentColor
        val surface = 0xFFFFFFFF.toInt()
        val fieldBg = 0xFFF1F2F6.toInt()
        val onSurface = 0xFF1A1A1A.toInt()
        val muted = 0xFF8E8E93.toInt()
        val primaryContainer = ColorUtils.blendARGB(surface, primary, 0.14f)
        val onPrimaryContainer = if (isDark(primary)) 0xFF1A1A1A.toInt()
            else ColorUtils.blendARGB(0xFF1A1A1A.toInt(), primary, 0.5f)

        // 起止选择状态（yyyy-MM-dd，区间内排序保证 start <= end）
        var start: String? = initialStart
        var end: String? = initialEnd
        if (start != null && end != null && start > end) {
            val t = start; start = end; end = t
        }

        // 当前展示的月份（初始定位到开始日期所在月）
        val display = Calendar.getInstance()
        if (initialStart != null) {
            try { display.time = fmt.parse(initialStart)!! } catch (_: Exception) {}
        }
        var displayYear = display.get(Calendar.YEAR)
        var displayMonth = display.get(Calendar.MONTH)

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)

        // FontAwesome 图标
        val fa = Typeface.createFromAsset(context.assets, "fonts/fa-solid-900.otf")
        fun icon(code: String, size: Int, color: Int) = TextView(context).apply {
            text = code
            typeface = fa
            textSize = (size * 0.85f).roundToInt().toFloat()
            setTextColor(color)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(surface)
            }
            elevation = dp(12).toFloat()
        }

        // ── 头部：日历图标 + 标题 + 关闭 ──
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(icon("", 22, primary))  // fa-calendar-days
        header.addView(TextView(context).apply {
            text = "选择日期范围"
            setTextColor(onSurface)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(icon("", 22, muted).apply {  // fa-xmark
            setOnClickListener { dialog.dismiss() }
            isClickable = true
        })
        root.addView(header)

        // 副标题
        root.addView(TextView(context).apply {
            text = "选择要查看统计的开始和结束日期"
            setTextColor(muted)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        })

        // ── 起止日期输入盒 ──
        fun dateField(isStart: Boolean, box: LinearLayout) {
            val label = TextView(context).apply {
                text = if (isStart) "开始日期" else "结束日期"
                setTextColor(muted)
                textSize = 11f
            }
            val value = TextView(context).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(2), 0, 0)
            }
            box.addView(label)
            box.addView(value)
            box.tag = value
        }
        fun fieldBox() = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(fieldBg)
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun renderField(box: LinearLayout, date: String?, placeholder: String) {
            val value = box.tag as TextView
            value.text = if (date != null) formatFieldDate(date) else placeholder
            value.setTextColor(if (date != null) onSurface else muted)
        }

        val startBox = fieldBox(); dateField(true, startBox)
        val endBox = fieldBox(); dateField(false, endBox)
        val rangeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rangeRow.addView(startBox)
        rangeRow.addView(icon("", 18, primary))  // fa-arrow-right
        rangeRow.addView(endBox)
        root.addView(rangeRow)

        // ── 日历 ──
        val monthLabel = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(onSurface)
        }
        fun renderMonthLabel() {
            monthLabel.text = "${displayYear}年${displayMonth + 1}月"
        }
        val prevBtn = icon("", 20, primary).apply {  // fa-chevron-left
            isClickable = true
        }
        val nextBtn = icon("", 20, primary).apply {  // fa-chevron-right
            isClickable = true
        }
        val monthHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(10))
        }
        monthHeader.addView(prevBtn)
        monthHeader.addView(monthLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        monthHeader.addView(nextBtn)
        root.addView(monthHeader)

        // 星期表头（周日开头，与设计一致）
        val weekdays = listOf("日", "一", "二", "三", "四", "五", "六")
        val weekdayRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        weekdays.forEach { w ->
            weekdayRow.addView(TextView(context).apply {
                text = w
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(muted)
                layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
            })
        }
        root.addView(weekdayRow)

        // 日网格 6x7
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val cells = Array(6) { row ->
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(rowView)
            Array(7) { col ->
                TextView(context).apply {
                    gravity = Gravity.CENTER
                    textSize = 16f
                    rowView.addView(this, LinearLayout.LayoutParams(0, dp(38), 1f))
                }
            }
        }
        root.addView(grid)

        // ── 底部按钮：取消 / 确定 ──
        val cancelBtn = TextView(context).apply {
            text = "取消"
            textSize = 16f
            setTextColor(onSurface)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        val confirmBtn = TextView(context).apply {
            text = "确定"
            textSize = 16f
            setTextColor(primary)
            setPadding(dp(16), dp(10), dp(8), dp(10))
            setOnClickListener {
                if (start != null && end != null) {
                    dialog.dismiss()
                    onSelected(start!!, end!!)
                }
            }
        }
        fun updateConfirm() {
            val ready = start != null && end != null
            confirmBtn.setTextColor(if (ready) primary else 0x4D8E8E93.toInt())
            confirmBtn.isClickable = ready
        }
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(confirmBtn)
        root.addView(btnRow)

        fun dateString(year: Int, month: Int, day: Int): String {
            val c = Calendar.getInstance().apply { set(year, month, day, 0, 0, 0) }
            return fmt.format(c.time)
        }

        fun renderFields() {
            renderField(startBox, start, "选择日期")
            renderField(endBox, end, "选择日期")
        }

        fun renderGrid() {
            val cal = Calendar.getInstance()
            cal.set(displayYear, displayMonth, 1)
            // 周日开头：DAY_OF_WEEK 周日=1 … 周六=7 → 偏移 = dow-1
            val firstOffset = cal.get(Calendar.DAY_OF_WEEK) - 1
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            for (i in 0 until 42) {
                val row = i / 7; val col = i % 7
                val cell = cells[row][col]
                val day = i - firstOffset + 1
                if (day < 1 || day > daysInMonth) {
                    cell.text = ""
                    cell.background = null
                    cell.isClickable = false
                    cell.setTextColor(onSurface)
                    continue
                }
                val dateStr = dateString(displayYear, displayMonth, day)
                val s = start; val e = end
                val isStart = dateStr == s
                val isEnd = dateStr == e
                val inRange = s != null && e != null && dateStr >= s && dateStr <= e
                val isToday = dateStr == today
                cell.text = day.toString()
                cell.isClickable = true
                cell.typeface = if (isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                cell.background = when {
                    isStart || isEnd -> GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(primary)
                    }
                    inRange -> GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(primaryContainer)
                    }
                    isToday -> GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setStroke(dp(1), primary)
                        setColor(Color.TRANSPARENT)
                    }
                    else -> null
                }
                cell.setTextColor(
                    when {
                        isStart || isEnd -> Color.WHITE
                        inRange -> onPrimaryContainer
                        isToday -> primary
                        else -> onSurface
                    }
                )
                cell.setOnClickListener {
                    when {
                        start == null -> start = dateStr
                        end != null -> { start = dateStr; end = null }
                        else -> {
                            end = dateStr
                            if (start!! > end!!) { val t = start; start = end; end = t }
                        }
                    }
                    renderFields(); renderGrid(); updateConfirm()
                }
            }
        }

        prevBtn.setOnClickListener {
            if (displayMonth == 0) { displayYear--; displayMonth = 11 } else displayMonth--
            renderMonthLabel(); renderGrid()
        }
        nextBtn.setOnClickListener {
            if (displayMonth == 11) { displayYear++; displayMonth = 0 } else displayMonth++
            renderMonthLabel(); renderGrid()
        }

        renderMonthLabel()
        renderFields()
        updateConfirm()
        renderGrid()

        dialog.setContentView(root)
        // 与其他弹窗一致：宽 320dp
        dialog.window?.setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun isDark(color: Int): Boolean {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) < 160
    }

    /** "2026-08-05" → "08月05日" */
    private fun formatFieldDate(date: String): String =
        "${date.substring(5, 7)}月${date.substring(8, 10)}日"
}
