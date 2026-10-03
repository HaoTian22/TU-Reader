package com.example.nfctransit.ui

import android.content.Context
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Toast
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.nfctransit.MainActivity
import com.example.nfctransit.BuildConfig
import com.example.nfctransit.R
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.AppUpdateChecker
import com.example.nfctransit.data.AppUpdateResult
import com.example.nfctransit.data.db.DatabaseQuerySpec
import com.example.nfctransit.data.prefs.CurrentTripRouteDisplayMode
import com.example.nfctransit.databinding.FragmentSettingsBinding
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var updateCheckJob: Job? = null
    private var updateDialog: Dialog? = null

    private fun capturePredictiveBackSnapshot() {
        (activity as? MainActivity)?.capturePredictiveBackSnapshot()
    }

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
                val chevron = _binding?.root?.findViewById<TextView>(R.id.chevronImportData)
                val statusText = _binding?.root?.findViewById<TextView>(R.id.tvImportDataStatus)
                if (chevron == null || statusText == null) {
                    // 视图不可用：仅兜底执行导入并提示
                    val fallback = try {
                        viewModel.importDatabase(uri)
                    } catch (e: Exception) {
                        "导入失败: ${e.message}"
                    }
                    showStatus(fallback)
                    return@launch
                }
                // 导入反馈与「站名映射表更新」一致：加载转圈 → 成功✓ / 失败✗，5 秒恢复箭头
                val spinner = ObjectAnimator.ofFloat(chevron, "rotation", 0f, 360f).apply {
                    duration = 800
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                }
                var pendingRevert: Runnable? = null
                fun feedback(state: String, msg: String) {
                    spinner.cancel()
                    chevron.rotation = 0f
                    when (state) {
                        "loading" -> {
                            chevron.text = ""                  // fa-spinner
                            chevron.setTextColor(Palette.INK_3)
                            spinner.start()
                        }
                        "success" -> {
                            chevron.text = ""                  // fa-circle-check
                            chevron.setTextColor(Palette.SUCCESS)
                        }
                        "error" -> {
                            chevron.text = ""                  // fa-xmark
                            chevron.setTextColor(Palette.DANGER)
                        }
                    }
                    statusText.text = msg
                    statusText.setTextColor(
                        when (state) {
                            "success" -> Palette.SUCCESS
                            "error" -> Palette.DANGER
                            else -> Palette.INK_3
                        }
                    )
                    statusText.visibility = View.VISIBLE
                    pendingRevert?.let { chevron.removeCallbacks(it) }
                    pendingRevert = Runnable {
                        spinner.cancel()
                        chevron.rotation = 0f
                        chevron.text = ""                      // fa-chevron-right
                        chevron.setTextColor(Palette.INK_3)
                        statusText.visibility = View.GONE
                    }
                    chevron.postDelayed(pendingRevert!!, 5000)
                }
                feedback("loading", "正在导入…")
                val (state, msg) = try {
                    "success" to viewModel.importDatabase(uri)
                } catch (e: Exception) {
                    "error" to "导入失败: ${e.message}"
                }
                feedback(state, msg)
                if (state == "success") updateLocalStorageSize()
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

        binding.btnBack.setOnClickListener { (activity as? MainActivity)?.animatePredictiveBack() }

        // FontAwesome 字体从 assets 加载，应用到所有带 fa 标记的图标/箭头
        val fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.otf")
        fun applyFaFont(view: View) {
            if (view.tag == "fa" && view is TextView) view.typeface = fa
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) applyFaFont(view.getChildAt(i))
            }
        }
        applyFaFont(binding.root)

        // 主题色跟随卡片的只有返回按钮；选项行首图标统一中性色，仅危险操作（清除全部数据）用红色，
        // 颜色只在有含义时出现
        val optionIcons = intArrayOf(
            R.id.iconCardCount, R.id.iconUpdateStationMap, R.id.iconCardSort,
            R.id.iconLocalStorage, R.id.iconDatabaseViewer, R.id.iconTransitOverrides,
            R.id.iconDataExport, R.id.iconImportData,
            R.id.iconClearCache,
            R.id.iconDarkMode, R.id.iconCurrentTripRoute,
            R.id.iconMapSpeed, R.id.iconLanguage,
            R.id.iconExportData, R.id.iconExportLog, R.id.iconDebugLog,
            R.id.iconVersion, R.id.iconCheckUpdate,
            R.id.iconOpenSource, R.id.iconFeedback
        )
        optionIcons.forEach { id ->
            binding.root.findViewById<TextView>(id)?.setTextColor(Palette.INK_3)
        }
        binding.root.findViewById<TextView>(R.id.iconClearData)?.setTextColor(Palette.DANGER)
        binding.root.findViewById<TextView>(R.id.iconClearData)?.background =
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(androidx.core.graphics.ColorUtils.blendARGB(Palette.SURFACE, Palette.DANGER, 0.1f))
            }
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            binding.btnBack.setTextColor(accent.toInt())
        }

        // 导出读取数据
        binding.rowExportData.setOnClickListener {
            pendingExportContent = buildDataReport()
            pendingExportName = "transitu-data.txt"
            exportLauncher.launch("transitu-data.txt")
        }

        // 导出 APDU 日志
        binding.rowExportLog.setOnClickListener {
            val log = viewModel.nfcLog.value?.joinToString("\n") ?: "暂无数据"
            pendingExportContent = log
            pendingExportName = "transitu-apdu-log.txt"
            exportLauncher.launch("transitu-apdu-log.txt")
        }

        // 已绑定卡片数量
        binding.root.findViewById<TextView>(R.id.tvCardCount)?.let { tv ->
            viewModel.cards.observe(viewLifecycleOwner) { list ->
                tv.text = "${list.size} 张"
            }
        }

        // 卡片排序：弹窗用 ↑↓ 调整顺序
        binding.root.findViewById<View>(R.id.rowCardSort)?.setOnClickListener {
            val cards = viewModel.cards.value.orEmpty()
            if (cards.size < 2) {
                showStatus("至少需要 2 张卡片才能排序")
                return@setOnClickListener
            }
            AppDialogs.reorder(
                context = requireContext(),
                cards = cards,
                accentColor = viewModel.mainAccent.value?.toInt() ?: Palette.ACCENT,
                onDone = { orderedIds -> viewModel.applyCardOrder(orderedIds) }
            )
        }

        binding.root.findViewById<View>(R.id.rowDatabaseViewer)?.setOnClickListener {
            val options = DatabaseQuerySpec.values().map { it.displayName }
            AppDialogs.options(
                context = requireContext(),
                title = "选择数据库",
                options = options,
                accentColor = viewModel.mainAccent.value?.toInt() ?: Palette.ACCENT,
                onSelect = { index ->
                    val spec = DatabaseQuerySpec.values()[index]
                    capturePredictiveBackSnapshot()
                    findNavController().navigate(
                        R.id.action_settings_to_databaseQuery,
                        Bundle().apply { putString("databaseKey", spec.key) }
                    )
                }
            )
        }

        binding.root.findViewById<View>(R.id.rowTransitOverrides)?.setOnClickListener {
            capturePredictiveBackSnapshot()
            findNavController().navigate(R.id.action_settings_to_transitOverrides)
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

        val currentTripRouteValue =
            binding.root.findViewById<TextView>(R.id.tvCurrentTripRouteValue)
        viewModel.currentTripRouteDisplayMode.observe(viewLifecycleOwner) { mode ->
            currentTripRouteValue.text = when (mode) {
                CurrentTripRouteDisplayMode.ENDPOINTS_ONLY -> "仅起终点"
                CurrentTripRouteDisplayMode.FULL_TRANSFERS -> "完整换乘"
            }
        }
        binding.root.findViewById<View>(R.id.rowCurrentTripRoute)?.setOnClickListener {
            val current = viewModel.currentTripRouteDisplayMode.value
                ?: CurrentTripRouteDisplayMode.ENDPOINTS_ONLY
            AppDialogs.options(
                context = requireContext(),
                title = "当前行程路线",
                options = listOf("仅起终点", "完整换乘过程"),
                selectedIndex = if (current == CurrentTripRouteDisplayMode.ENDPOINTS_ONLY) 0 else 1,
                accentColor = viewModel.mainAccent.value?.toInt() ?: Palette.ACCENT,
                onSelect = { which ->
                    viewModel.setCurrentTripRouteDisplayMode(
                        if (which == 0) CurrentTripRouteDisplayMode.ENDPOINTS_ONLY
                        else CurrentTripRouteDisplayMode.FULL_TRANSFERS
                    )
                }
            )
        }

        // 语言切换：跟随系统 / 中文 / English
        binding.root.findViewById<View>(R.id.rowLanguage)?.setOnClickListener {
            showLanguageDialog()
        }
        updateLanguageRow()

        binding.root.findViewById<View>(R.id.rowCheckUpdate).setOnClickListener {
            checkAppUpdate()
        }

        // 开源致谢 → GitHub 仓库
        binding.root.findViewById<View>(R.id.rowOpenSource)?.setOnClickListener {
            openInBrowser("https://github.com/HaoTian22/TU-Reader")
        }

        // 反馈入口 → GitHub Issues
        binding.root.findViewById<View>(R.id.rowFeedback)?.setOnClickListener {
            openInBrowser("https://github.com/HaoTian22/TU-Reader/issues")
        }

        // 站名映射表在线更新：行右侧同一位置切换 箭头 → 下载进度环（已知大小时按比例填充，
        // 未知或校验替换阶段为不定进度）→ 成功 ✓ / 失败 ⚠，3 秒后恢复为箭头。失败原因用 Toast 提示。
        val chevronMap = binding.root.findViewById<TextView>(R.id.chevronUpdateStationMap)!!
        val progressMap = binding.root
            .findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(
                R.id.progressUpdateStationMap
            )!!
        var pendingRevert: Runnable? = null

        fun showChevron(glyph: String, color: Int) {
            progressMap.visibility = View.GONE
            chevronMap.text = glyph
            chevronMap.setTextColor(color)
            chevronMap.visibility = View.VISIBLE
        }

        fun showProgress(fraction: Float?) {
            chevronMap.visibility = View.GONE
            progressMap.setIndicatorColor(viewModel.mainAccent.value?.toInt() ?: Palette.ACCENT)
            if (fraction == null) {
                if (!progressMap.isIndeterminate) {
                    // 切换不定状态前需先隐藏，否则 Material 会抛异常/闪烁
                    progressMap.visibility = View.INVISIBLE
                    progressMap.isIndeterminate = true
                }
            } else {
                if (progressMap.isIndeterminate) {
                    progressMap.visibility = View.INVISIBLE
                    progressMap.isIndeterminate = false
                }
                progressMap.setProgressCompat((fraction * 100).toInt(), true)
            }
            progressMap.visibility = View.VISIBLE
        }

        binding.root.findViewById<View>(R.id.rowUpdateStationMap)?.setOnClickListener {
            pendingRevert?.let { chevronMap.removeCallbacks(it) }
            viewModel.updateStationDatabase()
        }
        viewModel.stationDbUpdating.observe(viewLifecycleOwner) { updating ->
            if (updating) showProgress(viewModel.stationDbProgress.value)
        }
        viewModel.stationDbProgress.observe(viewLifecycleOwner) { fraction ->
            if (viewModel.stationDbUpdating.value == true) showProgress(fraction)
        }
        viewModel.stationDbUpdateStatus.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                val success = msg.startsWith("✓")
                if (success) {
                    showChevron("", Palette.SUCCESS)               // fa-circle-check
                } else {
                    showChevron("", Palette.DANGER)                // fa-triangle-exclamation
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
                pendingRevert = Runnable { showChevron("", Palette.INK_3) }  // fa-chevron-right
                chevronMap.postDelayed(pendingRevert!!, 3000)
                // 一次性结果：处理后清空，重新进入设置页不会重放旧的 ✓/⚠ 与 Toast
                viewModel.consumeStationDbUpdateStatus()
            }
        }

        // 清理缓存：确认弹窗 → viewModel.clearCache()。反馈与「站名映射表更新」一致：
        // 行右侧 箭头 → 不定进度环 → 成功 ✓ / 失败 ⚠，3 秒后恢复为箭头；失败原因用 Toast 提示。
        val chevronCache = binding.root.findViewById<TextView>(R.id.chevronClearCache)!!
        val progressCache = binding.root
            .findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(
                R.id.progressClearCache
            )!!
        var pendingCacheRevert: Runnable? = null

        fun showCacheChevron(glyph: String, color: Int) {
            progressCache.visibility = View.GONE
            chevronCache.text = glyph
            chevronCache.setTextColor(color)
            chevronCache.visibility = View.VISIBLE
        }

        binding.root.findViewById<View>(R.id.rowClearCache)?.setOnClickListener {
            pendingCacheRevert?.let { chevronCache.removeCallbacks(it) }
            showClearCacheDialog()
        }
        viewModel.cacheClearing.observe(viewLifecycleOwner) { clearing ->
            if (clearing) {
                chevronCache.visibility = View.GONE
                progressCache.setIndicatorColor(viewModel.mainAccent.value?.toInt() ?: Palette.ACCENT)
                progressCache.visibility = View.VISIBLE
            }
        }
        viewModel.cacheClearStatus.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                if (msg.startsWith("✓")) {
                    showCacheChevron("", Palette.SUCCESS)          // fa-circle-check
                    Toast.makeText(requireContext(), msg.removePrefix("✓").trim(), Toast.LENGTH_SHORT).show()
                } else {
                    showCacheChevron("", Palette.DANGER)           // fa-triangle-exclamation
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
                pendingCacheRevert = Runnable { showCacheChevron("", Palette.INK_3) }  // fa-chevron-right
                chevronCache.postDelayed(pendingCacheRevert!!, 3000)
                updateLocalStorageSize()  // 清理后占用变小，刷新本地存储大小显示
                // 一次性结果：处理后清空，重新进入设置页不会重放旧的 ✓/⚠ 与 Toast
                viewModel.consumeCacheClearStatus()
            }
        }

        // 保留调试日志开关：MaterialSwitch，开启时轨道跟随卡片主题色
        binding.switchKeepDebugLogs.let { toggle ->
            toggle.isChecked = viewModel.keepDebugLogs.value ?: true
            toggle.tintAccent(viewModel.mainAccent.value?.toInt() ?: Palette.ACCENT)
            viewModel.keepDebugLogs.observe(viewLifecycleOwner) { v ->
                if (v != toggle.isChecked) toggle.isChecked = v
            }
            viewModel.mainAccent.observe(viewLifecycleOwner) { c -> toggle.tintAccent(c.toInt()) }
            toggle.setOnCheckedChangeListener { _, checked ->
                if (checked != viewModel.keepDebugLogs.value) viewModel.setKeepDebugLogs(checked)
            }
        }
        binding.root.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchDarkMode)
            ?.tintAccent(Palette.ACCENT)
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
            accentColor = viewModel.mainAccent.value?.toInt() ?: Palette.ACCENT,
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
                    2 -> dbExportLauncher.launch("transitu-data.db")
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

    private fun showClearCacheDialog() {
        AppDialogs.confirm(
            context = requireContext(),
            title = "清理缓存",
            message = "将清除界面构建缓存和地图路线缓存；站名映射表仅在内置版本更新时才重置为内置版本，否则保留当前版本（如需最新站名请联网更新）。\n已保存的卡片与交易不受影响。确定要清理吗？",
            confirmLabel = "清理",
            confirmColor = Palette.ACCENT,  // 不删用户数据，用主题蓝而非警示红
            onConfirm = { viewModel.clearCache() }
        )
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            showStatus("无法打开浏览器")
        }
    }

    // ── 复制工具 ──

    private fun buildDataReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== TransitU 读取数据 ===")

        val card = viewModel.selectedCard.value
        if (card != null) {
            sb.appendLine("卡片: ${card.name} (${card.lastFour})")
            sb.appendLine("余额: ¥${String.format("%.2f", card.balanceYuan)}")
        }

        val txns = viewModel.allTransactions.value ?: emptyList()
        sb.appendLine("交易记录: ${txns.size} 条")
        txns.forEach { txn ->
            sb.appendLine("#${txn.seq} ${txn.displayDateTime}")
            sb.appendLine("  类型: ${txn.transitType}  金额: ${txn.amountText}")
            sb.appendLine("  站点: ${txn.stationName}  线路: ${txn.lineName}")
            sb.appendLine("  终端: ${txn.terminal}  TypeHex: ${txn.typeHex}")
            sb.appendLine("  余额: ${txn.balanceAfterText ?: "-"}")
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

    /** 本地数据存储大小 = 应用私有数据目录（databases/shared_prefs/files/cache）实际占用 */
    private fun updateLocalStorageSize() {
        val tv = _binding?.root?.findViewById<TextView>(R.id.tvLocalStorageValue) ?: return
        val bytes = requireContext().dataDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        tv.text = formatBytes(bytes)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.2f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format("%.1f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    private fun checkAppUpdate() {
        if (updateCheckJob?.isActive == true) return
        val context = requireContext()
        val root = binding.root
        val row = root.findViewById<View>(R.id.rowCheckUpdate)
        val label = root.findViewById<TextView>(R.id.tvCheckUpdate)
        val chevron = root.findViewById<View>(R.id.chevronCheckUpdate)
        val progress = root.findViewById<View>(R.id.progressCheckUpdate)
        row.isEnabled = false
        label.setText(R.string.checking_for_updates)
        chevron.visibility = View.GONE
        progress.visibility = View.VISIBLE
        updateCheckJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    AppUpdateChecker().check(BuildConfig.VERSION_NAME,
                        BuildConfig.DEBUG || BuildConfig.IS_PRERELEASE_BUILD)
                }
                when (result) {
                    AppUpdateResult.UpToDate -> Toast.makeText(context, R.string.app_up_to_date, Toast.LENGTH_SHORT).show()
                    AppUpdateResult.NoRelease -> Toast.makeText(context, R.string.app_no_release, Toast.LENGTH_SHORT).show()
                    is AppUpdateResult.Available -> {
                        updateDialog?.dismiss()
                        updateDialog = AppDialogs.appUpdate(context, BuildConfig.VERSION_NAME,
                            result.release, result.testingBuild, result.releaseNotes, result.historyUnavailable) {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse(result.release.apkUrl ?: result.release.pageUrl)))
                            } catch (e: Exception) {
                                Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.app_update_failed,
                    e.localizedMessage ?: "网络错误"), Toast.LENGTH_LONG).show()
            } finally {
                row.isEnabled = true
                label.setText(R.string.check_for_updates)
                chevron.visibility = View.VISIBLE
                progress.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到设置页时刷新本地数据大小，反映新增读卡/导入等变化
        updateLocalStorageSize()
    }

    override fun onDestroyView() {
        updateCheckJob?.cancel()
        updateDialog?.dismiss()
        updateDialog = null
        super.onDestroyView()
        _binding = null
    }
}
