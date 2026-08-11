package com.example.nfctransit.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.R
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    private var pendingExportContent: String? = null
    private var pendingExportName: String = "transactions"

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/*")) { uri ->
            if (uri != null) {
                pendingExportContent?.let { writeToUri(uri, it) }
                showStatus("✓ 已导出 ${pendingExportName}")
            }
        }

    private val dbExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    viewModel.exportDatabase(uri)
                    showStatus("✓ 已导出数据库")
                } catch (e: Exception) {
                    showStatus("导出失败: ${e.message}")
                }
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                showStatus("正在导入…")
                val msg = try {
                    viewModel.importDatabase(uri)
                } catch (e: Exception) {
                    "导入失败: ${e.message}"
                }
                showStatus(msg)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        // 主题色跟随卡片：返回按钮、主操作按钮一起变
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            val color = accent.toInt()
            binding.btnBack.setTextColor(color)
            binding.btnCopyData.setTextColor(0xFFFFFFFF.toInt())
            // 用圆角 GradientDrawable 填充主题色，保留按钮圆角
            binding.btnCopyData.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20f * resources.displayMetrics.density
                setColor(color)
            }
        }

        binding.btnCopyData.setOnClickListener {
            pendingExportContent = buildDataReport()
            pendingExportName = "tu-reader-data.txt"
            exportLauncher.launch("tu-reader-data.txt")
        }

        binding.btnCopyLog.setOnClickListener {
            val log = viewModel.nfcLog.value?.joinToString("\n") ?: "暂无数据"
            pendingExportContent = log
            pendingExportName = "tu-reader-apdu-log.txt"
            exportLauncher.launch("tu-reader-apdu-log.txt")
        }

        binding.root.findViewById<View>(R.id.rowDataExport)?.setOnClickListener {
            showExportDialog()
        }

        binding.root.findViewById<View>(R.id.rowClearData)?.setOnClickListener {
            showClearDialog()
        }

        binding.root.findViewById<View>(R.id.rowImportData)?.setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }

        // 语言切换：跟随系统 / 中文 / English
        binding.root.findViewById<View>(R.id.rowLanguage)?.setOnClickListener {
            showLanguageDialog()
        }
        updateLanguageRow()

        // 站名映射表在线更新：下载最新 transit.db 并替换本地库
        binding.root.findViewById<View>(R.id.rowUpdateStationMap)?.setOnClickListener {
            viewModel.updateStationDatabase()
        }
        viewModel.stationDbUpdating.observe(viewLifecycleOwner) { updating ->
            if (updating) showStatus("正在下载并更新站名映射表…")
        }
        viewModel.stationDbUpdateStatus.observe(viewLifecycleOwner) { msg ->
            msg?.let { showStatus(it) }
        }

        // 保留调试日志开关：自绘开关，样式与深色模式一致，开启时轨道跟随卡片主题色
        binding.root.findViewById<View>(R.id.switchKeepDebugLogs)?.let { toggle ->
            val knob = binding.root.findViewById<View>(R.id.knobKeepDebugLogs)
            var checked = viewModel.keepDebugLogs.value ?: true
            var accent = 0xFF0066FF.toInt()

            fun renderToggle() {
                toggle.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14f * resources.displayMetrics.density
                    setColor(if (checked) accent else 0xFFE5E5EA.toInt())
                }
                knob?.let {
                    val lp = it.layoutParams as FrameLayout.LayoutParams
                    lp.gravity =
                        (if (checked) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                    it.layoutParams = lp
                }
            }

            viewModel.mainAccent.observe(viewLifecycleOwner) { accent = it.toInt(); renderToggle() }
            viewModel.keepDebugLogs.observe(viewLifecycleOwner) { checked = it; renderToggle() }
            toggle.setOnClickListener {
                checked = !checked
                viewModel.setKeepDebugLogs(checked)
                renderToggle()
            }
        }
    }

    // ── 显示语言 ──

    private fun updateLanguageRow() {
        val tv = binding.root.findViewById<TextView>(R.id.tvLanguageValue) ?: return
        tv.text = when (TransitData.getDisplayLanguage()) {
            "zh" -> "中文"
            "en" -> "English"
            else -> "跟随系统"
        }
    }

    private fun showLanguageDialog() {
        val options = arrayOf("跟随系统", "中文", "English")
        val current = TransitData.getDisplayLanguage()
        val checked = when (current) {
            "zh" -> 1
            "en" -> 2
            else -> 0
        }
        AppDialogs.options(
            context = requireContext(),
            title = "语言切换",
            options = options.toList(),
            selectedIndex = checked,
            accentColor = viewModel.mainAccent.value?.toInt() ?: 0xFF0066FF.toInt(),
            onSelect = { which ->
                when (which) {
                    0 -> TransitData.setDisplayLanguage("system")
                    1 -> TransitData.setDisplayLanguage("zh")
                    2 -> TransitData.setDisplayLanguage("en")
                }
                updateLanguageRow()
                // 语言已切换：按 ID 重新解析全部站点/线路名并刷新界面
                viewModel.reloadDisplayLanguage()
                showStatus("✓ 语言已切换")
            }
        )
    }

    // ── 数据导出 ──

    private fun showExportDialog() {
        val options = arrayOf("CSV 文件", "JSON 文件", "SQLite 数据库 (.db)")
        AppDialogs.options(
            context = requireContext(),
            title = "导出数据",
            options = options.toList(),
            onSelect = { which ->
                when (which) {
                    0 -> {
                        pendingExportContent = viewModel.exportCsv()
                        pendingExportName = "transactions.csv"
                        exportLauncher.launch("transactions.csv")
                    }
                    1 -> {
                        pendingExportContent = viewModel.exportJson()
                        pendingExportName = "transactions.json"
                        exportLauncher.launch("transactions.json")
                    }
                    2 -> dbExportLauncher.launch("tu-reader-data.db")
                }
            }
        )
    }

    private fun writeToUri(uri: Uri, content: String) {
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            showStatus("导出失败: ${e.message}")
        }
    }

    // ── 清除数据 ──

    private fun showClearDialog() {
        AppDialogs.confirm(
            context = requireContext(),
            title = "清除全部本地数据",
            message = "将删除所有已保存的卡片和交易记录，此操作不可恢复。确定要清除吗？",
            confirmLabel = "清除",
            onConfirm = {
                viewModel.clearAllData()
                showStatus("✓ 已清除全部本地数据")
                // 回到首页显示空状态
                findNavController().popBackStack()
            }
        )
    }

    // ── 复制工具 ──

    private fun buildDataReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== TU Reader 读取数据 ===")

        val card = viewModel.selectedCard.value
        if (card != null) {
            sb.appendLine("卡片: ${card.cardType} (${card.lastFour})")
            sb.appendLine("余额: ¥${String.format("%.2f", card.balanceYuan)}")
        }

        val txns = viewModel.allTransactions.value ?: emptyList()
        sb.appendLine("交易记录: ${txns.size} 条")
        txns.forEach { txn ->
            sb.appendLine("#${txn.seq} ${txn.displayDateTime}")
            sb.appendLine("  类型: ${txn.transitType}  金额: ${txn.amountText}")
            sb.appendLine("  站点: ${txn.stationName}  线路: ${txn.lineName}")
            sb.appendLine("  终端: ${txn.terminal}  TypeHex: ${txn.typeHex}")
            sb.appendLine("  余额: ${txn.balanceAfterText}")
        }

        sb.appendLine()
        sb.appendLine("=== APDU 原始日志 ===")
        (viewModel.nfcLog.value ?: emptyList()).forEach { sb.appendLine(it) }

        return sb.toString()
    }

    private fun showStatus(text: String) {
        val tv = _binding?.tvCopyStatus ?: return
        tv.text = text
        tv.visibility = View.VISIBLE
        // 只引用捕获的 View，避免 fragment 视图销毁后 _binding 为 null 时回调触发 NPE
        tv.postDelayed({ tv.visibility = View.GONE }, 2500)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
