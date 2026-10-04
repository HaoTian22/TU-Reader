package com.example.nfctransit

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import android.view.View
import com.example.nfctransit.ui.keepTouchFeedback
import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.UiCache
import com.example.nfctransit.ui.MainViewModel
import com.example.nfctransit.ui.PredictiveBackFragmentAnimator
import com.example.nfctransit.ui.PredictiveBackLayout
import com.tencent.tencentmap.mapsdk.maps.TencentMapInitializer

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private val viewModel: MainViewModel by viewModels()
    private var predictiveBackAnimator: PredictiveBackFragmentAnimator? = null
    private var nfcToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 腾讯地图 SDK 合规：本应用无独立隐私弹窗，进入即视为同意地图组件隐私政策
        TencentMapInitializer.setAgreePrivacy(true)
        setContentView(R.layout.activity_main)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        // 所有页面的可点击视图统一加水波纹触摸反馈（含之后动态生成的行）
        navHost.childFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
                ) {
                    v.keepTouchFeedback()
                }
            },
            false
        )
        val predictiveBackLayout = findViewById<PredictiveBackLayout>(R.id.predictive_back_layout)
        navHost.viewLifecycleOwnerLiveData.observe(this) { owner ->
            if (owner != null && predictiveBackAnimator == null) {
                navHost.requireView().setBackgroundColor(Color.TRANSPARENT)
                predictiveBackAnimator = PredictiveBackFragmentAnimator(
                    navHost.navController,
                    navHost,
                    predictiveBackLayout,
                    navHost.requireView()
                )
                onBackPressedDispatcher.addCallback(this, predictiveBackAnimator!!)
            }
        }

        UiCache.clearOnPackageUpdate(applicationContext)
        TransitData.init(applicationContext)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, R.string.nfc_not_supported, Toast.LENGTH_LONG).show()
        }
        viewModel.nfcReadMessage.observe(this) { message ->
            if (message != null) {
                nfcToast?.cancel()
                nfcToast = Toast.makeText(this, message, Toast.LENGTH_LONG).also { it.show() }
                viewModel.consumeNfcReadMessage()
            }
        }
    }

    fun animatePredictiveBack() {
        predictiveBackAnimator?.startBackNavigation()
    }

    fun capturePredictiveBackSnapshot() {
        predictiveBackAnimator?.captureCurrentForNavigation()
    }

    override fun onDestroy() {
        predictiveBackAnimator?.dispose()
        predictiveBackAnimator = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let { adapter ->
            adapter.enableReaderMode(
                this,
                { tag -> viewModel.onNfcTagDiscovered(tag) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
            )
            android.util.Log.d("TransitReader", "Reader mode enabled")
        }
    }

    override fun onPause() {
        nfcToast?.cancel()
        nfcToast = null
        nfcAdapter?.disableReaderMode(this)
        android.util.Log.d("TransitReader", "Reader mode disabled")
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent) {
        if (intent.action !in setOf(NfcAdapter.ACTION_TECH_DISCOVERED,
                NfcAdapter.ACTION_TAG_DISCOVERED, NfcAdapter.ACTION_NDEF_DISCOVERED)) return
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        if (tag == null) {
            Toast.makeText(this, "未检测到卡片", Toast.LENGTH_SHORT).show()
            return
        }

        // 系统从 manifest 启动应用的旧 TECH_DISCOVERED 入口也使用同一互斥保护。
        viewModel.onNfcTagDiscovered(tag)
    }
}
