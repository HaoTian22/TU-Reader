package com.example.nfctransit

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.ui.MainViewModel

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        TransitData.init(applicationContext)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, R.string.nfc_not_supported, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let { adapter ->
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntentFlags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_MUTABLE
                else 0
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, pendingIntentFlags
            )
            val techLists = arrayOf(arrayOf(IsoDep::class.java.name))
            adapter.enableForegroundDispatch(this, pendingIntent, null, techLists)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Toast.makeText(this, "该卡不支持 ISO-DEP", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, getString(R.string.nfc_detecting), Toast.LENGTH_SHORT).show()
        Thread {
            val result = TransitCardReader(isoDep).read()
            // 调试：把完整读卡 APDU 日志输出到 logcat，便于真机排查
            android.util.Log.d("TransitReader", result.rawLog.joinToString("\n"))
            runOnUiThread {
                viewModel.onNfcDataLoaded(result)
                if (result.matchedProfile != null && viewModel.lastReadCount > 0) {
                    Toast.makeText(
                        this,
                        "识别为：${result.matchedProfile.name}，读取到 ${viewModel.lastReadCount} 条记录",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (result.matchedProfile != null) {
                    Toast.makeText(
                        this,
                        "识别为：${result.matchedProfile.name}，但未读取到交易记录",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, "未识别出支持的卡种", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
