package com.example.nfctransit.ui

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nfctransit.BuildConfig
import com.example.nfctransit.R
import com.example.nfctransit.data.AppUpdateChecker
import com.example.nfctransit.data.AppUpdateController
import com.example.nfctransit.data.prefs.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity 级状态：旋转屏幕沿用检查结果，重新启动应用会再次检查。 */
class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = AppUpdateController(
        checkForUpdate = {
            withContext(Dispatchers.IO) {
                AppUpdateChecker().check(BuildConfig.VERSION_NAME,
                    BuildConfig.DEBUG || BuildConfig.IS_PRERELEASE_BUILD)
            }
        },
        readIgnoredVersion = { AppPreferences.getIgnoredAppVersion(application) },
        writeIgnoredVersion = { AppPreferences.setIgnoredAppVersion(application, it) },
        automaticChecksEnabled = AppUpdateChecker.shouldCheckAutomatically(
            BuildConfig.VERSION_NAME, BuildConfig.DEBUG || BuildConfig.IS_PRERELEASE_BUILD
        )
    )
    val startupUpdate = controller.startupUpdate

    init {
        viewModelScope.launch {
            try {
                controller.checkOnStartup()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 自动检查失败保持安静；手动检查仍会向用户报告错误。
                Log.w("AppUpdate", "Startup update check failed", error)
            }
        }
    }

    suspend fun checkManually() = controller.checkManually()

    fun dismissStartupPrompt() = controller.dismissStartupPrompt()

    fun ignoreVersion(version: String) {
        controller.dismissStartupPrompt()
        viewModelScope.launch {
            try {
                controller.ignoreVersion(version)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("AppUpdate", "Failed to save ignored version", error)
                Toast.makeText(getApplication(), R.string.app_update_ignore_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
