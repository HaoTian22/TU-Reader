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
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nfctransit.R
import com.example.nfctransit.data.CityOption
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.TransitOverrideRow
import com.example.nfctransit.model.UiCard

/** 与应用整体风格一致的确认弹窗（白色圆角卡片 + 双按钮），替代系统 AlertDialog */
object AppDialogs {

    /**
     * 弹窗宽度：M3 基本对话框 312dp，窄屏两侧各留 24dp。
     * 根布局以 null 父级 inflate，XML 里的宽度不生效，必须在窗口上指定，否则弹窗会收缩到内容宽度。
     */
    private fun dialogWidth(context: Context): Int {
        val dm = context.resources.displayMetrics
        return minOf((312 * dm.density).toInt(), dm.widthPixels - (48 * dm.density).toInt())
    }

    fun confirm(
        context: Context,
        title: String,
        message: String,
        confirmLabel: String,
        confirmColor: Int = Palette.DANGER,
        cancelLabel: String = "取消",
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(context), ViewGroup.LayoutParams.WRAP_CONTENT)
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
        accentColor: Int = Palette.ACCENT,
        onConfirm: (String) -> Unit
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_text_input, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(context), ViewGroup.LayoutParams.WRAP_CONTENT)
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
        accentColor: Int = Palette.ACCENT,
        maxScrollHeightDp: Int? = null,
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
        var clearImeInsets: (() -> Unit)? = null
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_feedback, null)
        dialog.setContentView(view)
        val formScroll = view.findViewById<android.widget.ScrollView>(R.id.feedbackScroll)
        maxScrollHeightDp?.let { heightDp ->
            formScroll?.let { scroll ->
                scroll.layoutParams = scroll.layoutParams.apply {
                    height = (heightDp * context.resources.displayMetrics.density).toInt()
                }
            }
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(context), ViewGroup.LayoutParams.WRAP_CONTENT)
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
        dialog.setOnDismissListener {
            clearImeInsets?.invoke()
            clearImeInsets = null
        }
        dialog.setOnShowListener {
            prefixInput.requestFocus()
            dialog.window?.let { window ->
                clearImeInsets = DialogImeInsets.install(window, view, formScroll ?: view)
            }
        }
        dialog.show()
        return dialog
    }

    fun overrideEditor(
        context: Context,
        row: TransitOverrideRow,
        accentColor: Int = Palette.ACCENT,
        onSave: (
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
            showPublish = true,
            accentColor = accentColor,
            maxScrollHeightDp = 480
        ) { prefix, code, type, line, station, cityCode, cityName, publish ->
            onSave(prefix, code, type, line, station, cityCode, cityName, publish)
        }
    }

    fun options(
        context: Context,
        title: String,
        options: List<String>,
        selectedIndex: Int = -1,
        accentColor: Int = Palette.ACCENT,
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
            setLayout(dialogWidth(context), ViewGroup.LayoutParams.WRAP_CONTENT)
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
                    setTextColor(if (selected) accentColor else Palette.INK)
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
                        setBackgroundColor(Palette.LINE)
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
        accentColor: Int = Palette.ACCENT,
        onClear: () -> Unit,
        onDone: (Set<String>) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_filter, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(context), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCancelable(true)

        view.findViewById<TextView>(R.id.dialogFilterTitle)?.text = title
        val container = view.findViewById<LinearLayout>(R.id.dialogFilterContainer) ?: return
        val density = context.resources.displayMetrics.density
        val current = selected.toMutableSet()

        fun renderRow(row: LinearLayout, check: TextView, checked: Boolean) {
            check.text = if (checked) "✓" else "○"
            check.setTextColor(if (checked) accentColor else Palette.INK_DISABLED)
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
                    setTextColor(Palette.INK)
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
                        setBackgroundColor(Palette.LINE)
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

    /** 卡片排序弹窗：每张卡一行（主题色圆点 + 行名 + 拖动手柄），拖动手柄或长按整行调整顺序，完成后回调新顺序的 cardId 列表 */
    fun reorder(
        context: Context,
        cards: List<UiCard>,
        accentColor: Int = Palette.ACCENT,
        onDone: (List<String>) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_reorder, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth(context), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCancelable(true)

        val list = view.findViewById<RecyclerView>(R.id.dialogReorderList) ?: return
        val density = context.resources.displayMetrics.density
        val fa = Typeface.createFromAsset(context.assets, "fonts/fa-solid-900.otf")
        val order = cards.toMutableList()
        lateinit var touchHelper: ItemTouchHelper

        class RowHolder(val row: LinearLayout, val dot: View, val label: TextView, val handle: TextView) :
            RecyclerView.ViewHolder(row)

        val adapter = object : RecyclerView.Adapter<RowHolder>() {
            override fun getItemCount() = order.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
                val dot = View(context).apply {
                    val d = (10 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(d, d).apply {
                        marginEnd = (12 * density).toInt()
                    }
                }
                val label = TextView(context).apply {
                    setTextColor(Palette.INK)
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                }
                val handle = TextView(context).apply {
                    text = ""  // fa-grip-vertical
                    typeface = fa
                    textSize = 14f
                    setTextColor(Palette.INK_3)
                    gravity = Gravity.CENTER
                    contentDescription = "拖动排序"
                    layoutParams = LinearLayout.LayoutParams(
                        (40 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (52 * density).toInt()
                    )
                    setPadding((24 * density).toInt(), 0, (12 * density).toInt(), 0)
                    setBackgroundColor(Palette.SURFACE)
                    addView(dot)
                    addView(label)
                    addView(handle)
                }
                return RowHolder(row, dot, label, handle).also { holder ->
                    @Suppress("ClickableViewAccessibility")
                    handle.setOnTouchListener { _, event ->
                        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                            touchHelper.startDrag(holder)
                        }
                        false
                    }
                }
            }

            override fun onBindViewHolder(holder: RowHolder, position: Int) {
                val card = order[position]
                holder.label.text = if (card.lastFour.isBlank() || card.lastFour == "----") {
                    card.name
                } else {
                    "${card.name} (${card.lastFour})"
                }
                holder.dot.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(card.gradientStartColor.toInt())
                }
                // 读屏用户无法拖动：提供上移 / 下移无障碍操作
                (holder.row.getTag(R.id.dialogReorderList) as? IntArray)?.forEach {
                    ViewCompat.removeAccessibilityAction(holder.row, it)
                }
                val actionIds = mutableListOf<Int>()
                if (position > 0) {
                    actionIds += ViewCompat.addAccessibilityAction(holder.row, "上移") { _, _ ->
                        move(holder.bindingAdapterPosition, holder.bindingAdapterPosition - 1); true
                    }
                }
                if (position < order.lastIndex) {
                    actionIds += ViewCompat.addAccessibilityAction(holder.row, "下移") { _, _ ->
                        move(holder.bindingAdapterPosition, holder.bindingAdapterPosition + 1); true
                    }
                }
                holder.row.setTag(R.id.dialogReorderList, actionIds.toIntArray())
            }

            fun move(from: Int, to: Int) {
                if (from !in order.indices || to !in order.indices) return
                order.add(to, order.removeAt(from))
                notifyItemMoved(from, to)
                notifyItemChanged(from)
                notifyItemChanged(to)
            }
        }
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                order.add(to, order.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.setBackgroundColor(Palette.FILL)
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.setBackgroundColor(Palette.SURFACE)
                // 拖动结束后刷新无障碍上移 / 下移操作的可用性
                adapter.notifyItemRangeChanged(0, order.size)
            }
        })
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        touchHelper.attachToRecyclerView(list)
        // 卡片多时限高 300dp 滚动
        val maxHeightPx = (300 * density).toInt()
        if (order.size * 52 * density > maxHeightPx) {
            list.layoutParams = list.layoutParams.apply { height = maxHeightPx }
        }

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
