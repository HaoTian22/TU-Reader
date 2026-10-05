package com.example.nfctransit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CancellationSignal
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.TableRow
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.data.db.DatabaseQueryEngine
import com.example.nfctransit.data.db.DatabaseQueryResult
import com.example.nfctransit.data.db.DatabaseQuerySpec
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
        // 主题色跟随卡片：返回、复制/粘贴、运行按钮、SQL 弹窗确认与结果表头
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            accentColor = accent.toInt()
            binding.btnBack.setTextColor(accentColor)
            binding.btnCopyPrompt.setTextColor(accentColor)
            binding.btnPasteSql.setTextColor(accentColor)
            binding.btnRunSql.backgroundTintList = ColorStateList.valueOf(accentColor)
        }
        binding.tvDatabaseTitle.setText(spec.displayNameRes)
        binding.tvPrompt.text = spec.prompt
        binding.btnCopyPrompt.setOnClickListener { copyPrompt(spec.prompt) }
        binding.btnPasteSql.setOnClickListener { showSqlDialog() }
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
        showStatus(getString(R.string.db_prompt_copied))
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
            queryRevision++
            queryJob?.cancel()
            _binding?.btnRunSql?.isEnabled = true
            savedSql = dialogBinding.dialogSqlInput.text?.toString().orEmpty()
            sqlRevision++
            updateSqlPreview()
            persistSql(savedSql)
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
            if (preview.isEmpty()) Palette.INK_3 else Palette.INK_2
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
        binding.btnRunSql.isEnabled = false
        binding.tvResultsEmpty.visibility = View.GONE
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
                if (generation != queryRevision || _binding == null) return@launch
                renderResult(result)
                val suffix = if (result.truncated) getString(R.string.db_result_truncated) else ""
                showStatus(resources.getQuantityString(R.plurals.db_query_done, result.rows.size, result.rows.size) + suffix)
            } catch (e: CancellationException) {
                if (timedOut && generation == queryRevision && _binding != null) {
                    showEmptyResult(getString(R.string.db_query_timeout))
                    showStatus(getString(R.string.db_query_timeout_detail), error = true)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                if (timedOut && generation == queryRevision && _binding != null) {
                    showEmptyResult(getString(R.string.db_query_timeout))
                    showStatus(getString(R.string.db_query_timeout_detail), error = true)
                    return@launch
                }
                if (coroutineContext[Job]?.isActive != true ||
                    generation != queryRevision || _binding == null
                ) {
                    return@launch
                }
                showEmptyResult(getString(R.string.db_query_failed))
                showStatus(e.message ?: getString(R.string.db_query_failed), error = true)
            } finally {
                timeoutJob.cancel()
                cancellationHandle?.dispose()
                if (generation == queryRevision) {
                    _binding?.btnRunSql?.isEnabled = true
                }
            }
        }
    }

    private fun renderResult(result: DatabaseQueryResult) {
        val table = binding.resultsTable
        table.removeAllViews()
        if (result.columns.isEmpty()) {
            showEmptyResult(getString(R.string.db_no_columns))
            return
        }
        table.addView(buildTableRow(result.columns, header = true, rowIndex = 0))
        result.rows.forEachIndexed { index, row ->
            table.addView(buildTableRow(row, header = false, rowIndex = index + 1))
        }
        binding.tvResultsEmpty.visibility = if (result.rows.isEmpty()) View.VISIBLE else View.GONE
        if (result.rows.isEmpty()) binding.tvResultsEmpty.setText(R.string.db_result_empty)
    }

    private fun buildTableRow(
        values: List<String>,
        header: Boolean,
        rowIndex: Int
    ): TableRow {
        return TableRow(requireContext()).apply {
            gravity = Gravity.TOP
            values.forEach { value -> addView(buildTableCell(value, header, rowIndex)) }
        }
    }

    private fun buildTableCell(value: String, header: Boolean, rowIndex: Int): TextView {
        val density = resources.displayMetrics.density
        return TextView(requireContext()).apply {
            text = value
            textSize = if (header) 12f else 11f
            typeface = if (header) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
            setTextColor(if (header) Color.WHITE else Palette.INK_2)
            gravity = Gravity.TOP or Gravity.START
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            minWidth = (96 * density).toInt()
            setTextIsSelectable(true)
            background = GradientDrawable().apply {
                setColor(
                    when {
                        header -> accentColor
                        rowIndex % 2 == 0 -> Color.WHITE
                        else -> Palette.PAPER
                    }
                )
                setStroke((0.5f * density).toInt().coerceAtLeast(1), Palette.LINE)
            }
            layoutParams = TableRow.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun showEmptyResult(message: String) {
        binding.resultsTable.removeAllViews()
        binding.tvResultsEmpty.text = message
        binding.tvResultsEmpty.visibility = View.VISIBLE
    }

    private fun showStatus(message: String, error: Boolean = false) {
        _binding?.tvQueryStatus?.apply {
            text = message
            setTextColor(if (error) Palette.DANGER else Palette.INK_3)
        }
    }
}
