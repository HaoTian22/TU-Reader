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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nfctransit.R
import com.example.nfctransit.data.CityOption
import com.example.nfctransit.data.AppRelease
import com.example.nfctransit.data.AppReleaseNotes
import com.example.nfctransit.data.FeedbackLocationSource
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.TransitOverrideRow
import com.example.nfctransit.model.UiCard

/** 与应用整体风格一致的确认弹窗（白色圆角卡片 + 双按钮），替代系统 AlertDialog */
object AppDialogs {

    /**
     * 弹窗宽度：M3 基本对话框 312dp，窄屏两侧各留 24dp。
     * 根布局以 null 父级 inflate，XML 里的宽度不生效，必须在窗口上指定，否则弹窗会收缩到内容宽度。
     */
    /** 反馈表单的常用交通类型（取自 transit.db 中数量最多的类型）；其余经「其他」手动输入。 */
    private val FEEDBACK_TYPES = listOf("公交", "地铁", "有轨电车", "城际", "BRT", "自行车")
    private const val FEEDBACK_TYPE_OTHER = "其他"
    private const val FEEDBACK_HELP =
        "城市前缀：原始数据CITY的字段，与编号一起组成读卡器的完整编号写入数据库，通常保持预填值即可\n\n" +
            "设备编号：刷卡记录中的读卡器 Terminal 编号，或 Line & Station 的内容，是纠错映射的依据，请先判断填写的内容是否和线路/站名有关联\n\n" +
            "交通类型：该读卡器所属的交通方式。同一编号在不同类型下是不同的读卡器；" +
            "列表中没有时选「其他」并手动输入，如 轮渡、单轨、轻轨\n\n" +
            "线路：读卡器所在的线路，例如「1号线」或公交线路号，可留空\n\n" +
            "站名：车站或站点名称，不确定/公交可留空\n\n" +
            "所在城市：不参与站名映射，仅随公开上传提供给开发者排查问题（有时一个城市会用多个城市前缀，开发者需要知道实际所在城市）\n\n" +
            "公开上传纠错：开启后把这条纠错上传，用于改进内置站名数据；关闭则只保存在本机\n\n" +
            "提供更多信息：默认关闭。开启后随公开纠错上传该条交易的原始数据和匹配信息，可能包含余额、交易时间、金额等信息"

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
            // cancelLabel 为空 = 单按钮提示框
            visibility = if (cancelLabel.isEmpty()) View.GONE else View.VISIBLE
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
        view.applyTouchFeedback()
        dialog.show()
    }

    fun appUpdate(
        context: Context,
        currentVersion: String,
        release: AppRelease,
        testingBuild: Boolean,
        releaseNotes: List<AppReleaseNotes>,
        historyUnavailable: Boolean,
        onDownload: () -> Unit
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_app_update, null)
        dialog.setContentView(view)
        view.findViewById<TextView>(R.id.updateTitle).setText(
            if (testingBuild) R.string.official_release_available else R.string.app_update_available
        )
        view.findViewById<TextView>(R.id.updateVersions).text =
            context.getString(R.string.app_update_versions, currentVersion, release.version)
        view.findViewById<View>(R.id.updateTestingNotice).visibility =
            if (testingBuild) View.VISIBLE else View.GONE
        val notesText = SpannableStringBuilder()
        if (historyUnavailable) {
            notesText.append(context.getString(R.string.app_update_history_unavailable)).append("\n\n")
        }
        for (entry in releaseNotes) {
            val start = notesText.length
            notesText.append(entry.version)
            notesText.setSpan(StyleSpan(Typeface.BOLD), start, notesText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            notesText.append("\n")
                .append(entry.notes.ifBlank { context.getString(R.string.app_update_no_notes) })
                .append("\n\n")
        }
        view.findViewById<TextView>(R.id.updateNotes).text = notesText.trimEnd()
        val scroll = view.findViewById<android.widget.ScrollView>(R.id.updateNotesScroll)
        val dm = context.resources.displayMetrics
        scroll.getChildAt(0).measure(
            View.MeasureSpec.makeMeasureSpec(
                dialogWidth(context) - scroll.paddingLeft - scroll.paddingRight,
                View.MeasureSpec.EXACTLY
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        scroll.layoutParams = scroll.layoutParams.apply {
            height = minOf(scroll.getChildAt(0).measuredHeight + scroll.paddingBottom,
                (dm.heightPixels * 0.4f).toInt())
        }
        view.findViewById<View>(R.id.updateCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.updateDownload).apply {
            setText(if (release.apkUrl != null) R.string.download_apk else R.string.view_release)
            setOnClickListener {
                dialog.dismiss()
                onDownload()
            }
        }
        view.applyTouchFeedback()
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(dialogWidth(context), ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
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
        view.applyTouchFeedback()
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
        hasRawRecord: Boolean = false,
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
            locationSource: FeedbackLocationSource,
            publish: Boolean,
            includeRawRecord: Boolean
        ) -> Boolean
    ): Dialog {
        val dialog = Dialog(context)
        var clearImeInsets: (() -> Unit)? = null
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_feedback, null)
        dialog.setContentView(view)
        view.findViewById<View>(R.id.feedbackMoreInfoRow).visibility =
            if (hasRawRecord) View.VISIBLE else View.GONE
        val formScroll = view.findViewById<android.widget.ScrollView>(R.id.feedbackScroll)
        val dm = context.resources.displayMetrics
        // 表单区限高（默认屏高 45%），标题与按钮固定，中间滚动；内容不足上限时按内容高度
        formScroll?.let { scroll ->
            val cap = maxScrollHeightDp?.let { (it * dm.density).toInt() }
                ?: (dm.heightPixels * 0.45f).toInt()
            val innerWidth = dialogWidth(context) - scroll.paddingLeft - scroll.paddingRight
            scroll.getChildAt(0).measure(
                View.MeasureSpec.makeMeasureSpec(innerWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val natural = scroll.getChildAt(0).measuredHeight + scroll.paddingTop + scroll.paddingBottom
            scroll.layoutParams = scroll.layoutParams.apply { height = minOf(natural, cap) }
            val dividerTop = view.findViewById<View>(R.id.feedbackDividerTop)
            val dividerBottom = view.findViewById<View>(R.id.feedbackDividerBottom)
            val updateDividers = {
                dividerTop.visibility = if (scroll.canScrollVertically(-1)) View.VISIBLE else View.INVISIBLE
                dividerBottom.visibility = if (scroll.canScrollVertically(1)) View.VISIBLE else View.INVISIBLE
            }
            scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateDividers() }
            scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateDividers() }
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
        val typeInput = view.findViewById<LinearLayout>(R.id.feedbackType)
        val typeCustomInput = view.findViewById<EditText>(R.id.feedbackTypeCustom)
        val publishInput = view.findViewById<android.widget.CompoundButton>(R.id.feedbackPublish)
        view.findViewById<TextView>(R.id.feedbackTitle).text = title
        publishInput.isEnabled = showPublish
        if (!showPublish) publishInput.isChecked = false
        view.findViewById<View>(R.id.feedbackPublishRow).apply {
            alpha = if (showPublish) 1f else 0.5f
            // 整行可点：点说明文字也能切换开关
            if (showPublish) setOnClickListener { publishInput.toggle() }
        }
        (publishInput as? com.google.android.material.materialswitch.MaterialSwitch)?.tintAccent(accentColor)
        val moreInfoInput = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.feedbackMoreInfo)
        val moreInfoRow = view.findViewById<View>(R.id.feedbackMoreInfoRow)
        moreInfoInput.isChecked = false
        moreInfoInput.tintAccent(accentColor)
        fun updateMoreInfoAvailability() {
            val enabled = showPublish && publishInput.isChecked && hasRawRecord
            moreInfoInput.isEnabled = enabled
            moreInfoRow.isEnabled = enabled
            moreInfoRow.alpha = if (enabled) 1f else 0.5f
            if (!enabled) moreInfoInput.isChecked = false
        }
        moreInfoRow.setOnClickListener { if (moreInfoInput.isEnabled) moreInfoInput.toggle() }
        publishInput.setOnCheckedChangeListener { _, _ -> updateMoreInfoAvailability() }
        updateMoreInfoAvailability()
        view.findViewById<TextView>(R.id.feedbackHelp).apply {
            typeface = Typeface.createFromAsset(context.assets, "fonts/fa-solid-900.otf")
            setOnClickListener {
                confirm(
                    context,
                    title = "填写说明",
                    message = FEEDBACK_HELP,
                    confirmLabel = "知道了",
                    confirmColor = accentColor,
                    cancelLabel = ""
                ) {}
            }
        }
        // 交通类型：可换行胶囊单选，选中为主题色实心 + 白字（同统计页周期切换）；
        // 「其他」展开输入框，类型不在常用列表中时默认选中「其他」并带入原值
        val density = context.resources.displayMetrics.density
        val initialType = type.trim()
        var selectedTypeChip = when {
            initialType.isEmpty() -> "公交"
            initialType in FEEDBACK_TYPES -> initialType
            else -> FEEDBACK_TYPE_OTHER
        }
        if (selectedTypeChip == FEEDBACK_TYPE_OTHER) typeCustomInput.setText(initialType)
        // 每行最多 4 个芯片，铺满整行（末行芯片数少时同样铺满）
        val gap = (8 * density).toInt()
        val typeChips = (FEEDBACK_TYPES + FEEDBACK_TYPE_OTHER).chunked(4).flatMapIndexed { rowIndex, labels ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (rowIndex > 0) topMargin = gap }
            }
            typeInput.addView(row)
            labels.mapIndexed { i, label ->
                TextView(context).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                    isClickable = true
                    isFocusable = true
                    // 宽度 = 文字宽 + 剩余空间均分：长标签（有轨电车）不被挤压，整行仍铺满
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, (36 * density).toInt(), 1f
                    )
                        .apply { if (i > 0) marginStart = gap }
                    row.addView(this)
                }
            }
        }
        fun styleTypeChips() {
            typeChips.forEach { chip ->
                val checked = chip.text == selectedTypeChip
                chip.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 999 * density
                    setColor(if (checked) accentColor else Palette.LINE)
                }
                chip.setTextColor(if (checked) Color.WHITE else Palette.INK_2)
                chip.typeface = if (checked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            typeCustomInput.visibility =
                if (selectedTypeChip == FEEDBACK_TYPE_OTHER) View.VISIBLE else View.GONE
        }
        typeChips.forEach { chip ->
            chip.setOnClickListener {
                selectedTypeChip = chip.text.toString()
                styleTypeChips()
                if (selectedTypeChip == FEEDBACK_TYPE_OTHER) typeCustomInput.requestFocus()
            }
        }
        styleTypeChips()
        val cityOptions = TransitData.cityOptions()
        val cityLabels = cityOptions.map(CityOption::pickerLabel)
        cityInput.setAdapter(
            ArrayAdapter(context, R.layout.item_dropdown_option, cityLabels)
        )
        cityInput.dropDownHeight = (dm.heightPixels * 0.35f).toInt()
        // 下拉框外观像选择器：点按（含箭头）即展开候选，输入时按文字筛选
        // 已选城市时文字会把候选筛到只剩一项，点按展开时先清除筛选显示全部城市
        val showAllCities = {
            (cityInput.adapter as ArrayAdapter<*>).filter.filter(null) { cityInput.showDropDown() }
        }
        cityInput.setOnClickListener { showAllCities() }
        view.findViewById<TextView>(R.id.feedbackCityChevron).apply {
            typeface = Typeface.createFromAsset(context.assets, "fonts/fa-solid-900.otf")
            setOnClickListener {
                cityInput.requestFocus()
                showAllCities()
            }
        }
        var selectedCity: CityOption? = cityOptions.firstOrNull { it.code == actualCityCode }
        var citySource = FeedbackLocationSource.AUTO
        var applyingCity = false
        selectedCity?.let {
            applyingCity = true
            cityInput.setText(it.pickerLabel, false)
            applyingCity = false
        }
        cityInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!applyingCity) {
                    selectedCity = null
                    citySource = FeedbackLocationSource.MANUAL
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        cityInput.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position) as? String
            selectedCity = cityOptions.firstOrNull { it.pickerLabel == label }
            citySource = FeedbackLocationSource.MANUAL
        }
        if (selectedCity == null && actualCityName.isNotBlank()) {
            applyingCity = true
            cityInput.setText(actualCityName, false)
            applyingCity = false
        }

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
                val selectedType = if (selectedTypeChip == FEEDBACK_TYPE_OTHER) {
                    typeCustomInput.text.toString().trim()
                } else selectedTypeChip
                if (selectedType.isEmpty() || selectedType.length > 32 ||
                    selectedType.contains('\n') || selectedType.contains('\r')
                ) {
                    android.widget.Toast.makeText(
                        context, "请填写交通类型（最多 32 个字符）", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    typeCustomInput.requestFocus()
                    return@setOnClickListener
                }
                // 城市仅作补充信息：未从列表选中时把输入的文字原样作为城市名带给开发者
                val typedCity = cityInput.text.toString().trim()
                    .takeIf { it.length <= 128 }.orEmpty()
                val accepted = onConfirm(
                    prefixInput.text.toString(),
                    codeInput.text.toString(),
                    selectedType,
                    lineInput.text.toString(),
                    stationInput.text.toString(),
                    selectedCity?.code.orEmpty(),
                    selectedCity?.name ?: typedCity,
                    citySource,
                    publishInput.isChecked,
                    publishInput.isChecked && moreInfoInput.isEnabled && moreInfoInput.isChecked
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
        view.applyTouchFeedback()
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
            locationSource: FeedbackLocationSource,
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
        ) { prefix, code, type, line, station, cityCode, cityName, locationSource, publish, _ ->
            onSave(prefix, code, type, line, station, cityCode, cityName, locationSource, publish)
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
        view.applyTouchFeedback()
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
        view.applyTouchFeedback()
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
        view.applyTouchFeedback()
        dialog.show()
    }
}
