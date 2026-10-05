package com.example.nfctransit.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppUpdateControllerTest {
    private fun available(version: String = "0.1.6") = AppUpdateResult.Available(
        AppRelease(version, "说明", AppUpdateChecker.RELEASES_URL, null), false
    )

    @Test fun ignoredVersionPersistsAcrossLaunchesButDoesNotBlockManualChecksOrNewerVersions() = runBlocking {
        var ignored: String? = null
        var latest = available()
        var checks = 0
        fun controller() = AppUpdateController(
            checkForUpdate = { checks++; latest },
            readIgnoredVersion = { ignored },
            writeIgnoredVersion = { ignored = it }
        )
        val firstLaunch = controller()
        firstLaunch.checkOnStartup()
        assertEquals(latest, firstLaunch.startupUpdate.value)
        firstLaunch.ignoreVersion("0.1.6")
        assertNull(firstLaunch.startupUpdate.value)

        val secondLaunch = controller()
        secondLaunch.checkOnStartup()
        assertEquals(2, checks) // 忽略后仍查询最新版本。
        assertNull(secondLaunch.startupUpdate.value)
        assertEquals(latest, secondLaunch.checkManually())

        latest = available("0.1.7")
        val thirdLaunch = controller()
        thirdLaunch.checkOnStartup()
        assertEquals(latest, thirdLaunch.startupUpdate.value)
    }

    @Test fun testingBuildsNeverReadPreferencesOrSendStartupRequestsButCanCheckManually() = runBlocking {
        for ((version, testing) in listOf("0.1.6" to true, "1.0.0-beta.1" to false, "debug" to false)) {
            var checks = 0
            var preferenceReads = 0
            val controller = AppUpdateController(
                checkForUpdate = { checks++; available() },
                readIgnoredVersion = { preferenceReads++; null },
                writeIgnoredVersion = {},
                automaticChecksEnabled = AppUpdateChecker.shouldCheckAutomatically(version, testing)
            )
            controller.checkOnStartup()
            assertEquals(0, checks)
            assertEquals(0, preferenceReads)
            assertNull(controller.startupUpdate.value)
            assertTrue(controller.checkManually() is AppUpdateResult.Available)
            assertEquals(1, checks)
        }
        assertTrue(AppUpdateChecker.shouldCheckAutomatically("0.1.6"))
    }

    @Test fun manualCheckSuppressesAnAlreadyRunningStartupCheck() = runBlocking {
        val pendingStartup = CompletableDeferred<AppUpdateResult>()
        var checks = 0
        val controller = AppUpdateController(
            checkForUpdate = { if (++checks == 1) pendingStartup.await() else available() },
            readIgnoredVersion = { null },
            writeIgnoredVersion = {}
        )
        val startup = launch(start = CoroutineStart.UNDISPATCHED) { controller.checkOnStartup() }
        assertEquals(available(), controller.checkManually())
        pendingStartup.complete(available())
        startup.join()
        assertNull(controller.startupUpdate.value)
    }

    @Test fun dismissingWithoutIgnoringOnlySuppressesTheCurrentLaunch() = runBlocking {
        fun controller() = AppUpdateController({ available() }, { null }, {})
        val current = controller()
        current.checkOnStartup()
        current.dismissStartupPrompt()
        assertNull(current.startupUpdate.value)
        val next = controller()
        next.checkOnStartup()
        assertEquals(available(), next.startupUpdate.value)
    }

    @Test fun ignoredVersionComparisonUsesVersionSemantics() {
        assertTrue(AppUpdateChecker.isIgnoredVersion("v0.1.6", "0.1.6.0+build.1"))
        assertFalse(AppUpdateChecker.isIgnoredVersion("0.1.7", "0.1.6"))
        assertFalse(AppUpdateChecker.isIgnoredVersion("0.1.6", null))
        assertFalse(AppUpdateChecker.isIgnoredVersion("0.1.6", "invalid"))
    }

    @Test fun aFailedStartupCheckHasNoPromptAndManualRetryStillWorks() = runBlocking {
        var offline = true
        val controller = AppUpdateController(
            checkForUpdate = { if (offline) throw IOException("offline") else available() },
            readIgnoredVersion = { null },
            writeIgnoredVersion = {}
        )
        try {
            controller.checkOnStartup()
            fail("Expected network failure")
        } catch (_: IOException) {
            assertNull(controller.startupUpdate.value)
        }
        offline = false
        assertEquals(available(), controller.checkManually())
    }
}
