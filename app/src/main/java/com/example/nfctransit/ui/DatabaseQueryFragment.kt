package com.example.nfctransit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.CancellationSignal
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.data.db.DatabaseQueryEngine
import com.example.nfctransit.data.db.DatabaseQueryResult
import com.example.nfctransit.data.db.DatabaseQuerySpec
import com.example.nfctransit.data.db.SqlTextExtractor
import com.example.nfctransit.data.prefs.AppPreferences
import com.example.nfctransit.databinding.DialogSqlInputBinding
import com.example.nfctransit.databinding.FragmentDatabaseQueryBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class DatabaseQueryFragment : Fragment(R.layout.fragment_database_query) {

    private var _binding: FragmentDatabaseQueryBinding? = null
    private val binding get() = _binding!!
    private var savedSql = ""
    private var sqlRevision = 0L
    private var queryRevision = 0L
    private var queryJob: Job? = null
    private var sqlWriteJob: Job? = null
    private var sqlDialog: android.app.Dialog? = null
    private lateinit var databaseSpec: DatabaseQuerySpec
    private val viewModel: MainViewModel by viewModels({ requireActivity() })
    private var accentColor = Palette.ACCENT
    private var faTypeface: Typeface? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDatabaseQueryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val spec = DatabaseQuerySpec.fromKey(requireArguments().getString("databaseKey"))
        if (spec == null) {
            findNavController().popBackStack()
            return
        }
        databaseSpec = spec
        binding.btnBack.setOnClickListener { (activity as? MainActivity)?.animatePredictiveBack() }
        val fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.otf")
        faTypeface = fa
        binding.btnCopyPrompt.setIconLabel(fa, "\uF0C5", getString(R.string.action_copy)) // fa-copy
        binding.btnRunSql.setIconLabel(fa, "\uF04B", getString(R.string.action_run))      // fa-play
        binding.btnPasteSql.typeface = fa
        binding.btnPasteSql.text = "" // fa-paste
        binding.iconPrompt.typeface = fa
        binding.iconPrompt.text = "\uF544" // fa-robot
        binding.tvResultsEmptyIcon.typeface = fa
        // 圆角卡片裁切结果表格，表头底色不越过圆角
        binding.resultsCard.clipToOutline = true
        binding.resultsList.layoutManager = LinearLayoutManager(requireContext())
        binding.resultsList.setHasFixedSize(true)
        // 主题色跟随卡片：返回、提示词图标、复制、运行按钮、SQL 弹窗确认
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            accentColor = accent.toInt()
            binding.btnBack.setTextColor(accentColor)
            binding.iconPrompt.setTextColor(accentColor)
            binding.btnCopyPrompt.setTextColor(accentColor)
            binding.btnPasteSql.setTextColor(accentColor)
            binding.btnRunSql.backgroundTintList = ColorStateList.valueOf(accentColor)
        }
        binding.tvDatabaseTitle.setText(spec.displayNameRes)
        binding.tvPrompt.text = spec.prompt
        // 完整提示词默认收起，点提示词行展开，把屏幕留给结果表格
        updatePromptExpanded(false)
        binding.rowPrompt.setOnClickListener {
            updatePromptExpanded(binding.promptPanel.visibility != View.VISIBLE)
        }
        binding.btnCopyPrompt.setOnClickListener { copyPrompt(spec.prompt) }
        binding.tvSqlPreview.setOnClickListener { showSqlDialog() }
        binding.btnPasteSql.setOnClickListener { pasteSqlFromClipboard() }
        binding.btnRunSql.setOnClickListener { runQuery() }
        showEmptyResult(getString(R.string.db_query_results_placeholder))

        val loadRevision = sqlRevision
        viewLifecycleOwner.lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val sql = withContext(Dispatchers.IO) {
                AppPreferences.getLastDatabaseSql(appContext, spec.key)
            }
            if (loadRevision != sqlRevision || _binding == null) return@launch
            savedSql = sql
            updateSqlPreview()
        }
    }

    override fun onDestroyView() {
        sqlRevision++
        queryRevision++
        queryJob?.cancel()
        queryJob = null
        sqlDialog?.dismiss()
        sqlDialog = null
        _binding = null
        super.onDestroyView()
    }

    private fun copyPrompt(prompt: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("prompt", prompt))
        AppToast.show(requireContext(), R.string.db_prompt_copied, AppToast.Kind.SUCCESS)
    }

    private fun showSqlDialog() {
        val dialog = android.app.Dialog(requireContext())
        var clearImeInsets: (() -> Unit)? = null
        sqlDialog = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogBinding = DialogSqlInputBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            clearImeInsets?.invoke()
            clearImeInsets = null
            if (sqlDialog === dialog) sqlDialog = null
        }
        dialogBinding.dialogSqlInput.setText(savedSql)
        dialogBinding.dialogSqlInput.setSelection(dialogBinding.dialogSqlInput.text.length)
        dialogBinding.dialogSqlConfirm.setTextColor(accentColor)
        dialogBinding.dialogSqlCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.dialogSqlConfirm.setOnClickListener {
            if (_binding == null) {
                dialog.dismiss()
                return@setOnClickListener
            }
            replaceSql(dialogBinding.dialogSqlInput.text?.toString().orEmpty())
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
            }
            dialogBinding.dialogSqlInput.requestFocus()
            dialog.window?.let { window ->
                clearImeInsets = DialogImeInsets.install(
                    window,
                    dialogBinding.dialogSqlContent,
                    dialogBinding.dialogSqlInput
                )
            }
        }
        dialog.show()
        dialog.window?.decorView?.keepTouchFeedback()
    }

    // 一键用剪贴板文本替换当前 SQL，免去打开弹窗、全选、粘贴、确认
    private fun pasteSqlFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            // AI 回复常带说明/备注/代码块标记，只保留 SQL 语句
            SqlTextExtractor.extract(clip.getItemAt(0).coerceToText(requireContext())?.toString().orEmpty())
        } else {
            ""
        }
        if (text.isEmpty()) {
            AppToast.show(requireContext(), R.string.db_clipboard_empty, AppToast.Kind.ERROR)
            return
        }
        replaceSql(text)
        showStatus("")
        AppToast.show(requireContext(), R.string.db_sql_pasted, AppToast.Kind.SUCCESS)
    }

    private fun replaceSql(sql: String) {
        queryRevision++
        queryJob?.cancel()
        setRunning(false)
        savedSql = sql
        sqlRevision++
        updateSqlPreview()
        persistSql(savedSql)
    }

    private fun persistSql(sql: String) {
        val previous = sqlWriteJob
        val appContext = requireContext().applicationContext
        val databaseKey = databaseSpec.key
        sqlWriteJob = requireActivity().lifecycleScope.launch(Dispatchers.IO) {
            previous?.join()
            AppPreferences.setLastDatabaseSql(appContext, databaseKey, sql)
        }
    }

    private fun updateSqlPreview() {
        val preview = savedSql.trim().replace(Regex("\\s+"), " ")
        binding.tvSqlPreview.text = if (preview.isEmpty()) getString(R.string.db_query_no_sql) else preview
        binding.tvSqlPreview.setTextColor(
            if (preview.isEmpty()) Palette.INK_3 else Palette.INK
        )
    }

    private fun runQuery() {
        val sql = savedSql.trim()
        if (sql.isEmpty()) {
            showStatus(getString(R.string.db_paste_first), error = true)
            return
        }
        queryRevision++
        val generation = queryRevision
        queryJob?.cancel()
        val appContext = requireContext().applicationContext
        val spec = databaseSpec
        val metrics = resources.displayMetrics
        setRunning(true)
        binding.resultsEmpty.visibility = View.GONE
        showStatus(getString(R.string.db_querying))
        queryJob = viewLifecycleOwner.lifecycleScope.launch {
            val cancellationSignal = CancellationSignal()
            val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion {
                cancellationSignal.cancel()
            }
            var timedOut = false
            val timeoutJob = launch {
                delay(15_000L)
                timedOut = true
                cancellationSignal.cancel()
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    DatabaseQueryEngine.execute(appContext, spec, sql, cancellationSignal)
                }
                val widths = withContext(Dispatchers.Default) { measureColumnWidths(result, metrics) }
                if (generation != queryRevision || _binding == null) return@launch
                renderResult(result, widths)
                val suffix = if (result.truncated) getString(R.string.db_result_truncated) else ""
                showStatus(resources.getQuantityString(R.plurals.db_query_done, result.rows.size, result.rows.size) + suffix)
            } catch (e: CancellationException) {
                if (timedOut && generation == queryRevision && _binding != null) {
                    showEmptyResult(
                        getString(R.string.db_query_timeout),
                        getString(R.string.db_query_timeout_detail),
                        error = true
                    )
                    showStatus(getString(R.string.db_query_timeout), error = true)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                if (timedOut && generation == queryRevision && _binding != null) {
                    showEmptyResult(
                        getString(R.string.db_query_timeout),
                        getString(R.string.db_query_timeout_detail),
                        error = true
                    )
                    showStatus(getString(R.string.db_query_timeout), error = true)
                    return@launch
                }
                if (coroutineContext[Job]?.isActive != true ||
                    generation != queryRevision || _binding == null
                ) {
                    return@launch
                }
                showEmptyResult(getString(R.string.db_query_failed), e.message, error = true)
                showStatus(getString(R.string.db_query_failed), error = true)
            } finally {
                timeoutJob.cancel()
                cancellationHandle?.dispose()
                if (generation == queryRevision) setRunning(false)
            }
        }
    }

    private fun renderResult(result: DatabaseQueryResult, widths: IntArray) {
        if (result.columns.isEmpty()) {
            showEmptyResult(getString(R.string.db_no_columns))
            return
        }
        // 网格总宽 = 各列宽之和（至少铺满卡片），给 RecyclerView 精确宽度，避免在横向滚动容器里不定宽测量
        val gridWidth = maxOf(widths.sum(), binding.resultsHScroll.width)
        binding.resultsGrid.layoutParams = binding.resultsGrid.layoutParams.apply { width = gridWidth }
        binding.resultsHeader.removeAllViews()
        binding.resultsHeader.background = rowBackground(header = true)
        result.columns.forEachIndexed { index, name ->
            binding.resultsHeader.addView(buildCell(widths[index], header = true).apply { text = name })
        }
        binding.resultsHScroll.scrollTo(0, 0)
        binding.resultsList.adapter = ResultAdapter(result, widths)
        if (result.rows.isEmpty()) {
            showEmptyState(getString(R.string.db_result_empty), null, error = false)
        } else {
            binding.resultsEmpty.visibility = View.GONE
        }
    }

    /** 列宽：表头与各行取最宽者，限制在 88–280dp；在后台线程测量，不创建任何 View。 */
    private fun measureColumnWidths(result: DatabaseQueryResult, metrics: DisplayMetrics): IntArray {
        val textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CELL_TEXT_SP, metrics)
        val headerPaint = Paint().apply { this.textSize = textSize; typeface = HEADER_TYPEFACE }
        val cellPaint = Paint().apply { this.textSize = textSize; typeface = Typeface.MONOSPACE }
        val padding = 2 * CELL_PADDING_H_DP * metrics.density
        val minWidth = CELL_MIN_WIDTH_DP * metrics.density
        val maxWidth = CELL_MAX_WIDTH_DP * metrics.density
        return IntArray(result.columns.size) { column ->
            var widest = headerPaint.measureText(result.columns[column])
            // 只取前若干行估算列宽，更宽的值在单元格内换行/截断
            for (row in result.rows.asSequence().take(MEASURE_ROWS)) {
                if (widest >= maxWidth) break
                widest = maxOf(widest, cellPaint.measureText(row[column].longestLine()))
            }
            (widest + padding).coerceIn(minWidth, maxWidth).toInt()
        }
    }

    // 超出最大列宽的部分不影响测量结果，只取前若干字符
    private fun String.longestLine(): String =
        take(MEASURE_CHARS * 4).lineSequence().maxByOrNull { it.length }.orEmpty().take(MEASURE_CHARS)

    private fun buildCell(width: Int, header: Boolean): TextView {
        val density = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            textSize = CELL_TEXT_SP
            typeface = if (header) HEADER_TYPEFACE else Typeface.MONOSPACE
            setTextColor(if (header) Palette.INK_3 else Palette.INK)
            gravity = Gravity.TOP or Gravity.START
            val horizontal = (CELL_PADDING_H_DP * density).toInt()
            val vertical = (10 * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            // 长值截断显示，点按单元格查看完整内容（代替逐格可选中文本，后者开销大且与行复用冲突）
            maxLines = if (header) 2 else CELL_MAX_LINES
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    // 表头浅灰底、数据行白底；整行一个背景，只画行底细分隔线，与设置/详情卡片的行分隔一致
    private fun rowBackground(header: Boolean): LayerDrawable {
        val density = resources.displayMetrics.density
        return LayerDrawable(
            arrayOf(
                ColorDrawable(if (header) Palette.FILL else Palette.SURFACE),
                ColorDrawable(Palette.LINE)
            )
        ).apply {
            setLayerGravity(1, Gravity.BOTTOM)
            setLayerHeight(1, (0.5f * density).toInt().coerceAtLeast(1))
        }
    }

    private fun showCellValue(column: String, value: String) {
        val context = context ?: return
        AppDialogs.confirm(
            context,
            title = column,
            message = value,
            confirmLabel = getString(R.string.action_copy),
            confirmColor = accentColor,
            cancelLabel = getString(R.string.action_close)
        ) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(column, value))
            AppToast.show(context, R.string.copied, AppToast.Kind.SUCCESS)
        }
    }

    private inner class ResultAdapter(
        private val result: DatabaseQueryResult,
        private val widths: IntArray
    ) : RecyclerView.Adapter<ResultAdapter.RowHolder>() {

        inner class RowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            val cells: List<TextView> = List(widths.size) { column ->
                buildCell(widths[column], header = false).also { cell ->
                    cell.background = cellRipple()
                    cell.setOnClickListener {
                        val position = bindingAdapterPosition
                        if (position != RecyclerView.NO_POSITION) {
                            showCellValue(result.columns[column], result.rows[position][column])
                        }
                    }
                    row.addView(cell)
                }
            }
        }

        override fun getItemCount() = result.rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rowBackground(header = false)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return RowHolder(row)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val values = result.rows[position]
            holder.cells.forEachIndexed { column, cell -> cell.text = values[column] }
        }
    }

    private fun cellRipple(): android.graphics.drawable.Drawable? {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return requireContext().getDrawable(value.resourceId)
    }

    private fun showEmptyResult(message: String, detail: String? = null, error: Boolean = false) {
        binding.resultsHeader.removeAllViews()
        binding.resultsHeader.background = null
        binding.resultsList.adapter = null
        showEmptyState(message, detail, error)
    }

    private fun showEmptyState(message: String, detail: String?, error: Boolean) {
        val color = if (error) Palette.DANGER else Palette.INK_3
        binding.resultsEmpty.visibility = View.VISIBLE
        binding.tvResultsEmpty.text = message
        binding.tvResultsEmptyIcon.text = if (error) "\uF071" else "\uF0CE" // fa-triangle-exclamation / fa-table
        binding.tvResultsEmptyIcon.setTextColor(color)
        binding.tvResultsEmptyDetail.text = detail
        binding.tvResultsEmptyDetail.setTextColor(color)
        binding.tvResultsEmptyDetail.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun updatePromptExpanded(expanded: Boolean) {
        binding.promptPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        val chevron = if (expanded) "" else "" // fa-chevron-up / fa-chevron-down
        val title = getString(R.string.db_section_prompt)
        binding.tvPromptTitle.text = SpannableString("$title  $chevron").apply {
            val start = length - chevron.length
            faTypeface?.let { setSpan(IconFontSpan(it), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            setSpan(RelativeSizeSpan(0.7f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Palette.INK_3), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun setRunning(running: Boolean) {
        _binding?.btnRunSql?.apply {
            isEnabled = !running
            alpha = if (running) 0.5f else 1f
        }
    }

    private fun showStatus(message: String, error: Boolean = false) {
        _binding?.tvQueryStatus?.apply {
            text = message
            setTextColor(if (error) Palette.DANGER else Palette.INK_3)
        }
    }

    private companion object {
        const val CELL_TEXT_SP = 12f
        const val CELL_PADDING_H_DP = 12
        const val CELL_MIN_WIDTH_DP = 88
        const val CELL_MAX_WIDTH_DP = 280
        const val CELL_MAX_LINES = 4
        const val MEASURE_CHARS = 64
        const val MEASURE_ROWS = 1000
        val HEADER_TYPEFACE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
