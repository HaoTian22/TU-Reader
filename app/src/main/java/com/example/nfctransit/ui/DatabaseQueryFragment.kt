package com.example.nfctransit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
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
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.tvDatabaseTitle.text = spec.displayName
        binding.tvPrompt.text = spec.prompt
        binding.btnCopyPrompt.setOnClickListener { copyPrompt(spec.prompt) }
        binding.btnPasteSql.setOnClickListener { showSqlDialog() }
        binding.btnRunSql.setOnClickListener { runQuery() }
        showEmptyResult("运行 SQL 后在此显示结果")

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
        clipboard.setPrimaryClip(ClipData.newPlainText("数据库查询 prompt", prompt))
        showStatus("Prompt 已复制，可粘贴给 AI")
    }

    private fun showSqlDialog() {
        val dialog = android.app.Dialog(requireContext())
        sqlDialog = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogBinding = DialogSqlInputBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            if (sqlDialog === dialog) sqlDialog = null
        }
        dialogBinding.dialogSqlInput.setText(savedSql)
        dialogBinding.dialogSqlInput.setSelection(dialogBinding.dialogSqlInput.text.length)
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
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
        }
        dialogBinding.dialogSqlInput.requestFocus()
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
        binding.tvSqlPreview.text = if (preview.isEmpty()) "尚未粘贴 SQL" else preview
        binding.tvSqlPreview.setTextColor(
            if (preview.isEmpty()) 0xFF8E8E93.toInt() else 0xFF3A3A3C.toInt()
        )
    }

    private fun runQuery() {
        val sql = savedSql.trim()
        if (sql.isEmpty()) {
            showStatus("请先点击“粘贴”输入 SQL", error = true)
            return
        }
        queryRevision++
        val generation = queryRevision
        queryJob?.cancel()
        val appContext = requireContext().applicationContext
        val spec = databaseSpec
        binding.btnRunSql.isEnabled = false
        binding.tvResultsEmpty.visibility = View.GONE
        showStatus("正在查询…")
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
                val suffix = if (result.truncated) "（结果较大，仅显示部分内容）" else ""
                showStatus("查询完成：${result.rows.size} 行$suffix")
            } catch (e: CancellationException) {
                if (timedOut && generation == queryRevision && _binding != null) {
                    showEmptyResult("查询超时")
                    showStatus("查询超过 15 秒，已停止", error = true)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                if (timedOut && generation == queryRevision && _binding != null) {
                    showEmptyResult("查询超时")
                    showStatus("查询超过 15 秒，已停止", error = true)
                    return@launch
                }
                if (coroutineContext[Job]?.isActive != true ||
                    generation != queryRevision || _binding == null
                ) {
                    return@launch
                }
                showEmptyResult("查询失败")
                showStatus(e.message ?: "查询失败", error = true)
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
            showEmptyResult("没有可显示的列")
            return
        }
        table.addView(buildTableRow(result.columns, header = true, rowIndex = 0))
        result.rows.forEachIndexed { index, row ->
            table.addView(buildTableRow(row, header = false, rowIndex = index + 1))
        }
        binding.tvResultsEmpty.visibility = if (result.rows.isEmpty()) View.VISIBLE else View.GONE
        if (result.rows.isEmpty()) binding.tvResultsEmpty.text = "查询结果为空（0 行）"
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
            setTextColor(if (header) Color.WHITE else 0xFF3A3A3C.toInt())
            gravity = Gravity.TOP or Gravity.START
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            minWidth = (96 * density).toInt()
            setTextIsSelectable(true)
            background = GradientDrawable().apply {
                setColor(
                    when {
                        header -> 0xFF0066FF.toInt()
                        rowIndex % 2 == 0 -> Color.WHITE
                        else -> 0xFFF4F7FB.toInt()
                    }
                )
                setStroke((0.5f * density).toInt().coerceAtLeast(1), 0xFFD9DDE5.toInt())
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
            setTextColor(if (error) 0xFFFF3B30.toInt() else 0xFF8E8E93.toInt())
        }
    }
}
