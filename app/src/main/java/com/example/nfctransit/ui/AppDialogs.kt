package com.example.nfctransit.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.nfctransit.R
import com.example.nfctransit.data.CityOption
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.TransitOverrideRow
import com.example.nfctransit.model.UiCard

/** 与应用整体风格一致的确认弹窗（白色圆角卡片 + 双按钮），替代系统 AlertDialog */
object AppDialogs {

    fun confirm(
        context: Context,
        title: String,
        message: String,
        confirmLabel: String,
        confirmColor: Int = 0xFFFF3B30.toInt(),
        cancelLabel: String = "取消",
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCancelable(true)

        view.findViewById<TextView>(R.id.dialogTitle)?.text = title
        view.findViewById<TextView>(R.id.dialogMessage)?.text = message
        view.findViewById<TextView>(R.id.dialogCancel)?.apply {
            text = cancelLabel
            setOnClickListener { dialog.dismiss() }
        }
        view.findViewById<TextView>(R.id.dialogConfirm)?.apply {
            text = confirmLabel
            setTextColor(confirmColor)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }
        dialog.show()
    }

    fun textInput(
        context: Context,
        title: String,
        initialValue: String = "",
        hint: String = "",
        maxLength: Int? = null,
        accentColor: Int = 0xFF0066FF.toInt(),
        onConfirm: (String) -> Unit
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_text_input, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCancelable(true)

        view.findViewById<TextView>(R.id.dialogInputTitle)?.text = title
        view.findViewById<EditText>(R.id.dialogInput)?.apply {
            setText(initialValue)
            setSelection(text.length)
            if (hint.isNotEmpty()) this.hint = hint
            maxLength?.let { filters = arrayOf(android.text.InputFilter.LengthFilter(it)) }
        }
        view.findViewById<TextView>(R.id.dialogInputCancel)?.setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.dialogInputConfirm)?.apply {
            setTextColor(accentColor)
            setOnClickListener {
                val value = view.findViewById<EditText>(R.id.dialogInput)?.text?.toString().orEmpty()
                dialog.dismiss()
                onConfirm(value)
            }
        }
        dialog.setOnShowListener {
            view.findViewById<EditText>(R.id.dialogInput)?.apply {
                requestFocus()
                dialog.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                )
            }
        }
        dialog.show()
        return dialog
    }

    fun feedback(
        context: Context,
        prefix: String,
        code: String,
        line: String,
        station: String,
        type: String,
        actualCityCode: String?,
        actualCityName: String,
        title: String = "反馈站名纠错",
        showPublish: Boolean = true,
        accentColor: Int = 0xFF0066FF.toInt(),
        onConfirm: (
            prefix: String,
            code: String,
            type: String,
            line: String,
            station: String,
            cityCode: String,
            cityName: String,
            publish: Boolean
        ) -> Boolean
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_feedback, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCancelable(true)
        val prefixInput = view.findViewById<EditText>(R.id.feedbackPrefix)
        val codeInput = view.findViewById<EditText>(R.id.feedbackCode)
        val lineInput = view.findViewById<EditText>(R.id.feedbackLine)
        val stationInput = view.findViewById<EditText>(R.id.feedbackStation)
        val cityInput = view.findViewById<AutoCompleteTextView>(R.id.feedbackCity)
        val typeInput = view.findViewById<android.widget.RadioGroup>(R.id.feedbackType)
        val publishInput = view.findViewById<android.widget.CheckBox>(R.id.feedbackPublish)
        view.findViewById<TextView>(R.id.feedbackTitle).text = title
        publishInput.visibility = View.VISIBLE
        publishInput.isEnabled = showPublish
        if (!showPublish) publishInput.isChecked = false
        publishInput.buttonTintList = ColorStateList.valueOf(accentColor)
        listOf(
            view.findViewById<android.widget.RadioButton>(R.id.feedbackTypeBus),
            view.findViewById<android.widget.RadioButton>(R.id.feedbackTypeMetro),
            view.findViewById<android.widget.RadioButton>(R.id.feedbackTypeIntercity)
        ).forEach { it.buttonTintList = ColorStateList.valueOf(accentColor) }
        typeInput.check(
            when (type) {
                "地铁" -> R.id.feedbackTypeMetro
                "城际" -> R.id.feedbackTypeIntercity
                else -> R.id.feedbackTypeBus
            }
        )
        val cityOptions = TransitData.cityOptions()
        val cityLabels = cityOptions.map(CityOption::displayName)
        cityInput.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, cityLabels)
        )
        var selectedCity: CityOption? = cityOptions.firstOrNull { it.code == actualCityCode }
        var applyingCity = false
        selectedCity?.let {
            applyingCity = true
            cityInput.setText(it.displayName, false)
            applyingCity = false
        }
        cityInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!applyingCity) selectedCity = null
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        cityInput.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position) as? String
            selectedCity = cityOptions.firstOrNull { it.displayName == label }
        }
        if (selectedCity == null && actualCityName.isNotBlank()) cityInput.setText(actualCityName, false)

        prefixInput.setText(prefix)
        codeInput.setText(code)
        lineInput.setText(line)
        stationInput.setText(station)
        listOf(prefixInput, codeInput, lineInput, stationInput).forEach { input ->
            input.setSelection(input.text.length)
        }
        view.findViewById<TextView>(R.id.feedbackConfirm).apply {
            setTextColor(accentColor)
            setOnClickListener {
                val selectedType = when (typeInput.checkedRadioButtonId) {
                    R.id.feedbackTypeMetro -> "地铁"
                    R.id.feedbackTypeIntercity -> "城际"
                    else -> "公交"
                }
                val accepted = onConfirm(
                    prefixInput.text.toString(),
                    codeInput.text.toString(),
                    selectedType,
                    lineInput.text.toString(),
                    stationInput.text.toString(),
                    selectedCity?.code.orEmpty(),
                    selectedCity?.name.orEmpty(),
                    publishInput.isChecked
                )
                if (accepted) dialog.dismiss()
            }
        }
        view.findViewById<TextView>(R.id.feedbackCancel).setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            prefixInput.requestFocus()
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        dialog.show()
        return dialog
    }

    fun overrideEditor(
        context: Context,
        row: TransitOverrideRow,
        accentColor: Int = 0xFF0066FF.toInt(),
        onSave: (
            prefix: String,
            code: String,
            type: String,
            line: String,
            station: String,
            cityCode: String,
            cityName: String
        ) -> Unit
    ): Dialog {
        val city = row.locationCityCode?.let { code ->
            TransitData.cityOptions().firstOrNull { it.code == code }
        } ?: TransitData.cityOptions().firstOrNull { it.code == row.prefix }
        return feedback(
            context = context,
            prefix = row.prefix,
            code = row.code,
            line = row.line,
            station = row.station,
            type = row.type,
            actualCityCode = city?.code,
            actualCityName = city?.name.orEmpty(),
            title = "编辑本地映射表",
            showPublish = false,
            accentColor = accentColor
        ) { prefix, code, type, line, station, cityCode, cityName, _ ->
            onSave(prefix, code, type, line, station, cityCode, cityName)
            true
        }
    }

    fun options(
        context: Context,
        title: String,
        options: List<String>,
        selectedIndex: Int = -1,
        accentColor: Int = 0xFF0066FF.toInt(),
        maxHeightDp: Int? = null,
        cancelLabel: String = "取消",
        onSelect: (Int) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_options, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCancelable(true)

        view.findViewById<TextView>(R.id.dialogOptionsTitle)?.text = title
        val container = view.findViewById<LinearLayout>(R.id.dialogOptionsContainer)
            ?: return
        val density = context.resources.displayMetrics.density
        view.findViewById<android.widget.ScrollView>(R.id.dialogOptionsScroll)?.let { scroll ->
            val params = scroll.layoutParams
            params.height = maxHeightDp?.let { (it * density).toInt() }
                ?: ViewGroup.LayoutParams.WRAP_CONTENT
            scroll.layoutParams = params
        }

        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (48 * density).toInt()
                )
                setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(i)
                }
            }
            row.addView(
                TextView(context).apply {
                    text = label
                    setTextColor(if (selected) accentColor else 0xFF1A1A1A.toInt())
                    textSize = 15f
                    typeface =
                        if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                    )
                    gravity = Gravity.CENTER_VERTICAL
                }
            )
            if (selected) {
                row.addView(
                    TextView(context).apply {
                        text = "✓"
                        setTextColor(accentColor)
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                    }
                )
            }
            container.addView(row)
            if (i < options.lastIndex) {
                container.addView(
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (0.5 * density).toInt()
                        )
                        setBackgroundColor(0xFFE5E5EA.toInt())
                    }
                )
            }
        }

        view.findViewById<TextView>(R.id.dialogOptionsCancel)?.apply {
            text = cancelLabel
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }

    /** 与应用风格一致的多选弹窗：每项一行 + 勾选标记，底部「清除 / 确定」 */
    fun multiSelect(
        context: Context,
        title: String,
        options: List<String>,
        selected: Set<String>,
        accentColor: Int = 0xFF0066FF.toInt(),
        onClear: () -> Unit,
        onDone: (Set<String>) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_filter, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCancelable(true)

        view.findViewById<TextView>(R.id.dialogFilterTitle)?.text = title
        val container = view.findViewById<LinearLayout>(R.id.dialogFilterContainer) ?: return
        val density = context.resources.displayMetrics.density
        val current = selected.toMutableSet()

        fun renderRow(row: LinearLayout, check: TextView, checked: Boolean) {
            check.text = if (checked) "✓" else "○"
            check.setTextColor(if (checked) accentColor else 0xFFC7C7CC.toInt())
            check.typeface = if (checked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        options.forEachIndexed { i, label ->
            val check = TextView(context).apply {
                textSize = 18f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt())
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt()
                )
                setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!current.remove(label)) current.add(label)
                    renderRow(this, check, label in current)
                }
            }
            row.addView(
                TextView(context).apply {
                    text = label
                    setTextColor(0xFF1A1A1A.toInt())
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                    )
                }
            )
            row.addView(check)
            renderRow(row, check, label in current)
            container.addView(row)
            if (i < options.lastIndex) {
                container.addView(
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, (0.5 * density).toInt()
                        )
                        setBackgroundColor(0xFFE5E5EA.toInt())
                    }
                )
            }
        }

        view.findViewById<TextView>(R.id.dialogFilterClear)?.setOnClickListener {
            dialog.dismiss()
            onClear()
        }
        view.findViewById<TextView>(R.id.dialogFilterConfirm)?.setOnClickListener {
            dialog.dismiss()
            onDone(current.toSet())
        }
        dialog.show()
    }

    /** 卡片排序弹窗：每张卡一行（行名前置主题色圆点，行名与操作按钮黑色），置顶/↑↓ 调整顺序，完成后回调新顺序的 cardId 列表 */
    fun reorder(
        context: Context,
        cards: List<UiCard>,
        accentColor: Int = 0xFF0066FF.toInt(),
        onDone: (List<String>) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_reorder, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCancelable(true)

        view.findViewById<TextView>(R.id.dialogReorderTitle)?.text = "卡片排序"
        val container = view.findViewById<LinearLayout>(R.id.dialogReorderContainer)
            ?: return
        val reorderScroll = view.findViewById<android.widget.ScrollView>(R.id.dialogReorderScroll)
            ?: return
        val density = context.resources.displayMetrics.density
        val maxScrollHeightPx = (300 * density).toInt()
        val fa = Typeface.createFromAsset(context.assets, "fonts/fa-solid-900.otf")
        val order = cards.toMutableList()
        val rowViews = mutableMapOf<String, LinearLayout>()  // card.id -> 行 View
        var animating = false
        val rowHeightPx = 52f * density
        val divHeightPx = 0.5f * density

        fun divider(): View = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, divHeightPx.toInt()
            )
            setBackgroundColor(0xFFE5E5EA.toInt())
        }

        /** 构建一行：主题色圆点 + 黑色行名 + ↑↓ 箭头；箭头启用态颜色在 render 里按索引更新 */
        fun buildRow(card: UiCard): LinearLayout {
            val theme = card.gradientStartColor.toInt()
            val label = if (card.lastFour.isBlank() || card.lastFour == "----") {
                card.name
            } else {
                "${card.name} (${card.lastFour})"
            }
            fun actionButton(label: String, contentDescription: String) =
                TextView(context).apply {
                    text = label
                    typeface = fa
                    this.contentDescription = contentDescription
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        (36 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    gravity = Gravity.CENTER
                }
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx.toInt()
                )
                setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
                // 主题色圆点
                addView(View(context).apply {
                    val d = (10 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(d, d).apply {
                        marginEnd = (8 * density).toInt()
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(theme)
                    }
                })
                addView(
                    TextView(context).apply {
                        text = label
                        setTextColor(0xFF1A1A1A.toInt())
                        textSize = 15f
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                        )
                        gravity = Gravity.CENTER_VERTICAL
                    }
                )
                addView(actionButton("", "置顶"))
                addView(actionButton("", "上移"))
                addView(actionButton("", "下移"))
            }
        }

        lateinit var swap: (Int, Int) -> Unit

        fun render() {
            container.removeAllViews()
            rowViews.clear()
            order.forEachIndexed { i, card ->
                val row = buildRow(card)
                rowViews[card.id] = row
                container.addView(row)
                if (i < order.lastIndex) container.addView(divider())
            }
            // 按当前索引挂操作事件与启用态颜色；动画期间禁用点击
            order.forEachIndexed { i, card ->
                val row = rowViews[card.id]!!
                val top = row.getChildAt(row.childCount - 3) as TextView
                val up = row.getChildAt(row.childCount - 2) as TextView
                val down = row.getChildAt(row.childCount - 1) as TextView
                top.setTextColor(if (i > 0) 0xFF1A1A1A.toInt() else 0xFFD1D1D6.toInt())
                up.setTextColor(if (i > 0) 0xFF1A1A1A.toInt() else 0xFFD1D1D6.toInt())
                down.setTextColor(if (i < order.lastIndex) 0xFF1A1A1A.toInt() else 0xFFD1D1D6.toInt())
                top.isClickable = i > 0 && !animating
                up.isClickable = i > 0 && !animating
                down.isClickable = i < order.lastIndex && !animating
                top.isFocusable = top.isClickable
                up.isFocusable = up.isClickable
                down.isFocusable = down.isClickable
                top.setOnClickListener {
                    if (i > 0 && !animating) {
                        animating = true
                        val movedRow = rowViews[order[i].id]!!
                        val step = rowHeightPx + divHeightPx
                        movedRow.animate().translationYBy(-step * i)
                            .setDuration(180)
                            .withEndAction {
                                order.add(0, order.removeAt(i))
                                animating = false
                                render()
                                reorderScroll.post { reorderScroll.fullScroll(View.FOCUS_UP) }
                            }
                            .start()
                        for (j in 0 until i) {
                            rowViews[order[j].id]?.animate()
                                ?.translationYBy(step)
                                ?.setDuration(180)
                                ?.start()
                        }
                    }
                }
                up.setOnClickListener {
                    if (i > 0 && !animating) swap(i, i - 1)
                }
                down.setOnClickListener {
                    if (i < order.lastIndex && !animating) swap(i, i + 1)
                }
            }
            reorderScroll.post {
                val contentHeight = container.measuredHeight
                if (contentHeight > 0) {
                    reorderScroll.layoutParams = reorderScroll.layoutParams.apply {
                        height = contentHeight.coerceAtMost(maxScrollHeightPx)
                    }
                }
            }
        }

        /** 相邻两行滑动交换：先平移动画，结束后按新顺序重建列表 */
        swap = { from, to ->
            val movedRow = rowViews[order[from].id]!!
            val targetRow = rowViews[order[to].id]!!
            animating = true
            val step = rowHeightPx + divHeightPx
            // from 在下方（上移）时上移一步，目标行下移一步；下移时反之
            val movedOffset = if (to < from) -step else step
            movedRow.animate().translationYBy(movedOffset)
                .setDuration(180)
                .withEndAction {
                    val item = order.removeAt(from)
                    order.add(to, item)
                    animating = false
                    render()
                }
                .start()
            targetRow.animate().translationYBy(-movedOffset)
                .setDuration(180)
                .start()
        }

        render()

        view.findViewById<TextView>(R.id.dialogReorderCancel)?.setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.dialogReorderDone)?.apply {
            setTextColor(accentColor)
            setOnClickListener {
                dialog.dismiss()
                onDone(order.map { it.id })
            }
        }
        dialog.show()
    }
}
