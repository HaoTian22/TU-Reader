package com.example.nfctransit.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 每次启动独立检查，手动检查优先；忽略的版本跨启动保存。 */
class AppUpdateController(
    private val checkForUpdate: suspend () -> AppUpdateResult,
    private val readIgnoredVersion: suspend () -> String?,
    private val writeIgnoredVersion: suspend (String) -> Unit,
    private val automaticChecksEnabled: Boolean = true
) {
    private val _startupUpdate = MutableStateFlow<AppUpdateResult.Available?>(null)
    val startupUpdate = _startupUpdate.asStateFlow()
    private var startupPromptAllowed = true

    suspend fun checkOnStartup() {
        if (!automaticChecksEnabled) return
        val ignoredVersion = readIgnoredVersion()
        val result = checkForUpdate()
        if (startupPromptAllowed && result is AppUpdateResult.Available &&
            !AppUpdateChecker.isIgnoredVersion(result.release.version, ignoredVersion)) {
            _startupUpdate.value = result
        }
    }

    suspend fun checkManually(): AppUpdateResult {
        dismissStartupPrompt()
        return checkForUpdate()
    }

    fun dismissStartupPrompt() {
        startupPromptAllowed = false
        _startupUpdate.value = null
    }

    suspend fun ignoreVersion(version: String) {
        dismissStartupPrompt()
        writeIgnoredVersion(version)
    }
}
