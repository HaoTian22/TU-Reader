package com.example.nfctransit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentSettingsBinding

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
            copyToClipboard(buildDataReport())
            showStatus("✓ 已复制读取数据")
        }

        binding.btnCopyLog.setOnClickListener {
            val log = viewModel.nfcLog.value?.joinToString("\n") ?: "暂无数据"
            copyToClipboard(log)
            showStatus("✓ 已复制 APDU 日志")
        }

        binding.root.findViewById<View>(R.id.rowDataExport)?.setOnClickListener {
            showExportDialog()
        }

        binding.root.findViewById<View>(R.id.rowClearData)?.setOnClickListener {
            showClearDialog()
        }
    }

    // ── 数据导出 ──

    private fun showExportDialog() {
        val options = arrayOf("CSV 文件", "JSON 文件")
        AlertDialog.Builder(requireContext())
            .setTitle("导出数据")
            .setItems(options) { _, which ->
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
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
        AlertDialog.Builder(requireContext())
            .setTitle("清除全部本地数据")
            .setMessage("将删除所有已保存的卡片和交易记录，此操作不可恢复。确定要清除吗？")
            .setPositiveButton("清除") { _, _ ->
                viewModel.clearAllData()
                showStatus("✓ 已清除全部本地数据")
                // 回到首页显示空状态
                findNavController().popBackStack()
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TU Reader Data", text))
    }

    private fun showStatus(text: String) {
        binding.tvCopyStatus.text = text
        binding.tvCopyStatus.visibility = View.VISIBLE
        binding.tvCopyStatus.postDelayed({ binding.tvCopyStatus.visibility = View.GONE }, 2500)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
