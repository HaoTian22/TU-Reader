package com.example.nfctransit.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AppUpdateCheckerTest {
    private fun checker(version: String = "0.1.1", body: String = "更新说明", assets: String = "[]",
                        flags: String = "\"draft\": false, \"prerelease\": false") = AppUpdateChecker { url ->
        if (url == AppUpdateChecker.API_URL) {
            """{"tag_name":"$version","body":"$body","html_url":"${AppUpdateChecker.RELEASES_URL}/tag/$version","assets":$assets,$flags}"""
        } else "[]"
    }

    @Test fun stableBuildOnlyOffersNewerVersion() {
        assertEquals(AppUpdateResult.UpToDate, checker().check("0.1.1"))
        assertEquals(AppUpdateResult.UpToDate, checker().check("1.0.0"))
        val result = checker().check("0.1.0") as AppUpdateResult.Available
        assertFalse(result.testingBuild)
    }

    @Test fun versionComparisonUsesNumericSegmentsAndIgnoresMetadata() {
        assertTrue(checker("v0.1.10").check("0.1.9") is AppUpdateResult.Available)
        assertEquals(AppUpdateResult.UpToDate, checker("v1.0.0").check("1.0+build.2"))
    }

    @Test fun debugAndExplicitTestingBuildAlwaysOfferOfficialRelease() {
        for (current in listOf("0.1.0", "0.1.1", "1.0.0", "debug")) {
            val result = checker().check(current, isTestingBuild = true) as AppUpdateResult.Available
            assertTrue(result.testingBuild)
            assertEquals("0.1.1", result.release.version)
        }
    }

    @Test fun prereleaseVersionNameAlsoOffersOlderOfficialRelease() {
        for (current in listOf("1.0.0-beta.1", "v2.0.0-rc.2", "0.1.1-debug")) {
            assertTrue((checker().check(current) as AppUpdateResult.Available).testingBuild)
        }
    }

    @Test fun noReleaseAndDraftOrPrereleaseAreNeverOffered() {
        assertEquals(AppUpdateResult.NoRelease, AppUpdateChecker { null }.check("1.0", true))
        for (flags in listOf("\"draft\":true", "\"prerelease\":true")) {
            assertEquals(AppUpdateResult.NoRelease, checker(flags = flags).check("0.1.0", true))
        }
    }

    @Test fun notesAndUploadedApkComeFromRelease() {
        val url = "${AppUpdateChecker.RELEASES_URL}/download/0.1.1/TU-Reader.apk"
        val assets = """[
            {"name":"source.zip","browser_download_url":"$url"},
            {"name":"unfinished.apk","state":"new","browser_download_url":"$url"},
            {"name":"TU-Reader.apk","state":"uploaded","browser_download_url":"$url"}
        ]"""
        val release = (checker(body = "  ## 更新内容\\n修复问题  ", assets = assets)
            .check("0.1.0") as AppUpdateResult.Available).release
        assertEquals("## 更新内容\n修复问题", release.notes)
        assertEquals(url, release.apkUrl)
        assertEquals("${AppUpdateChecker.RELEASES_URL}/tag/0.1.1", release.pageUrl)
    }

    @Test fun missingNotesAndApkFallBackToReleasePage() {
        val checker = AppUpdateChecker { url ->
            if (url == AppUpdateChecker.API_URL) {
                """{"tag_name":"0.1.1","body":null,"html_url":null,"assets":[]}"""
            } else "[]"
        }
        val release = (checker.check("0.1.0") as AppUpdateResult.Available).release
        assertEquals("", release.notes)
        assertNull(release.apkUrl)
        assertEquals(AppUpdateChecker.RELEASES_URL, release.pageUrl)
    }

    @Test fun unrelatedDownloadUrlIsNotUsed() {
        val assets = """[{"name":"app.apk","browser_download_url":"https://example.com/app.apk"}]"""
        assertNull((checker(assets = assets).check("0.1.0") as AppUpdateResult.Available).release.apkUrl)
    }

    @Test(expected = IOException::class) fun malformedOfficialVersionIsAnError() {
        checker("unknown").check("1.0", true)
    }

    @Test(expected = IOException::class) fun malformedStableCurrentVersionIsAnError() {
        checker().check("unknown")
    }

    @Test(expected = IOException::class) fun networkFailureIsNotReportedAsUpToDate() {
        AppUpdateChecker { throw IOException("网络错误") }.check("1.0", true)
    }

    private fun release(version: String, notes: String = "说明 $version", flags: String = "") =
        """{"tag_name":"$version","body":"$notes","assets":[]$flags}"""

    @Test fun combinesEveryInterveningOfficialReleaseInDescendingVersionOrder() {
        val latest = release("v0.1.10", "最新说明")
        val history = listOf(
            release("0.1.2"), release("0.1.0"), release("v0.1.10", "列表说明"),
            release("0.1.9"), release("0.1.1"), release("0.1.11"),
            release("0.1.8", flags = ",\"draft\":true"),
            release("0.1.7", flags = ",\"prerelease\":true"),
            release("0.1.6-beta.1"), release("prerelease"),
            release("v0.1.9", "重复说明"), release("0.1.9.0", "重复说明")
        ).joinToString(prefix = "[", postfix = "]")
        val result = AppUpdateChecker { url ->
            if (url == AppUpdateChecker.API_URL) latest else history
        }.check("0.1.1") as AppUpdateResult.Available
        assertEquals(listOf("v0.1.10", "0.1.9", "0.1.2"), result.releaseNotes.map { it.version })
        assertEquals(listOf("最新说明", "说明 0.1.9", "说明 0.1.2"), result.releaseNotes.map { it.notes })
        assertEquals("v0.1.10", result.release.version)
        assertFalse(result.historyUnavailable)
    }

    @Test fun releaseHistoryContinuesAcrossPagesEvenWithOldVersionsOnFirstPage() {
        val requested = mutableListOf<String>()
        val firstPage = List(100) { release("0.1.0") }.joinToString(prefix = "[", postfix = "]")
        val result = AppUpdateChecker { url ->
            requested.add(url)
            when {
                url == AppUpdateChecker.API_URL -> release("0.4.0")
                url.endsWith("page=1") -> firstPage
                url.endsWith("page=2") -> "[${release("0.3.0")},${release("0.2.0")}]"
                else -> error("Unexpected URL $url")
            }
        }.check("0.1.0") as AppUpdateResult.Available
        assertEquals(listOf("0.4.0", "0.3.0", "0.2.0"), result.releaseNotes.map { it.version })
        assertEquals(listOf(AppUpdateChecker.API_URL,
            "${AppUpdateChecker.RELEASES_API_URL}?per_page=100&page=1",
            "${AppUpdateChecker.RELEASES_API_URL}?per_page=100&page=2"), requested)
    }

    @Test fun equalOrOlderOfficialReleaseForTestingBuildNeedsNoHistoryRequest() {
        for (current in listOf("0.4.0", "1.0.0", "debug")) {
            val result = AppUpdateChecker { url ->
                assertEquals(AppUpdateChecker.API_URL, url)
                release("0.4.0")
            }.check(current, true) as AppUpdateResult.Available
            assertEquals(listOf("0.4.0"), result.releaseNotes.map { it.version })
            assertFalse(result.historyUnavailable)
        }
    }

    @Test fun lowerTestingVersionIncludesInterveningOfficialVersions() {
        val result = AppUpdateChecker { url ->
            if (url == AppUpdateChecker.API_URL) release("0.4.0")
            else "[${release("0.3.0")},${release("0.2.0")},${release("0.1.0")}]"
        }.check("0.2.0-beta.1") as AppUpdateResult.Available
        assertTrue(result.testingBuild)
        assertEquals(listOf("0.4.0", "0.3.0", "0.2.0"), result.releaseNotes.map { it.version })
    }

    @Test fun historyFailureKeepsLatestUpdateWithAnExplicitIncompleteFlag() {
        for (response in listOf(null, "invalid json", "{}")) {
            val result = AppUpdateChecker { url ->
                if (url == AppUpdateChecker.API_URL) release("0.4.0") else response
            }.check("0.1.0") as AppUpdateResult.Available
            assertEquals(listOf("0.4.0"), result.releaseNotes.map { it.version })
            assertTrue(result.historyUnavailable)
        }
        val result = AppUpdateChecker { url ->
            if (url == AppUpdateChecker.API_URL) release("0.4.0") else throw IOException("HTTP 429")
        }.check("0.1.0") as AppUpdateResult.Available
        assertTrue(result.historyUnavailable)
    }

    @Test fun upToDateStableBuildDoesNotFetchHistory() {
        val result = AppUpdateChecker { url ->
            assertEquals(AppUpdateChecker.API_URL, url)
            release("0.4.0")
        }.check("0.4.0")
        assertEquals(AppUpdateResult.UpToDate, result)
    }
}
