package com.example.nfctransit.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import com.example.nfctransit.R
import com.example.nfctransit.util.L10n

data class AppRelease(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?
)

data class AppReleaseNotes(val version: String, val notes: String)

sealed class AppUpdateResult {
    object UpToDate : AppUpdateResult()
    object NoRelease : AppUpdateResult()
    data class Available(
        val release: AppRelease,
        val testingBuild: Boolean,
        val releaseNotes: List<AppReleaseNotes> = listOf(AppReleaseNotes(release.version, release.notes)),
        val historyUnavailable: Boolean = false
    ) : AppUpdateResult()
}

/** 检查 GitHub 最新正式 Release；仅查询元数据，APK 交给浏览器下载。 */
class AppUpdateChecker(private val fetchRelease: (String) -> String? = ::fetchReleaseJson) {
    fun check(currentVersion: String, isTestingBuild: Boolean = false): AppUpdateResult {
        val json = fetchRelease(API_URL) ?: return AppUpdateResult.NoRelease
        val root = JsonParser.parseString(json).asJsonObject
        if (root.get("draft")?.asBoolean == true || root.get("prerelease")?.asBoolean == true) {
            return AppUpdateResult.NoRelease
        }
        val tag = root.string("tag_name").orEmpty()
        val latest = AppVersion.parse(tag) ?: throw IOException(L10n.str(R.string.update_err_release_version))
        if (latest.prerelease.isNotEmpty()) return AppUpdateResult.NoRelease
        val current = AppVersion.parse(currentVersion)
        val testingBuild = isTestingBuild || current?.prerelease?.isNotEmpty() == true
        // 测试版始终提供正式版入口，不以测试版的版本号阻止切换。
        if (!testingBuild) {
            if (current == null) throw IOException(L10n.str(R.string.update_err_current_version))
            if (latest <= current) return AppUpdateResult.UpToDate
        }
        val apkUrl = root.getAsJsonArray("assets")?.firstNotNullOfOrNull { element ->
            val asset = element.asJsonObject
            if (asset.string("name")?.endsWith(".apk", ignoreCase = true) == true &&
                asset.string("state").let { it == null || it == "uploaded" }) {
                asset.string("browser_download_url")?.takeIf { isReleaseUrl(it, "download/") }
            } else null
        }
        val release = AppRelease(
            version = tag,
            notes = root.string("body").orEmpty().trim(),
            pageUrl = root.string("html_url")?.takeIf { isReleaseUrl(it, "") } ?: RELEASES_URL,
            apkUrl = apkUrl
        )
        // 测试版版本号高于正式版或无法解析时，仅展示最新正式版，不拼接无关历史。
        if (current == null || current >= latest) return AppUpdateResult.Available(release, testingBuild)
        return try {
            AppUpdateResult.Available(release, testingBuild, collectReleaseNotes(current, latest, release))
        } catch (e: Exception) {
            // 历史查询失败不影响已确认的可用更新，但在弹窗明确说明日志不完整。
            AppUpdateResult.Available(release, testingBuild, historyUnavailable = true)
        }
    }

    private fun collectReleaseNotes(
        current: AppVersion,
        latest: AppVersion,
        release: AppRelease
    ): List<AppReleaseNotes> {
        // /latest 为本次更新目标的权威来源，分页列表中同版本不重复或覆盖它。
        val notes = sortedMapOf(latest to AppReleaseNotes(release.version, release.notes))
        var page = 1
        while (true) {
            val json = fetchRelease("$RELEASES_API_URL?per_page=$PAGE_SIZE&page=$page")
                ?: throw IOException(L10n.str(R.string.update_err_history))
            val releases = JsonParser.parseString(json).asJsonArray
            for (element in releases) {
                val root = element.asJsonObject
                if (root.get("draft")?.asBoolean == true || root.get("prerelease")?.asBoolean == true) continue
                val tag = root.string("tag_name") ?: continue
                val version = AppVersion.parse(tag) ?: continue
                if (version.prerelease.isNotEmpty() || version <= current || version > latest) continue
                notes.putIfAbsent(version, AppReleaseNotes(tag, root.string("body").orEmpty().trim()))
            }
            // 发布时间不保证与版本号顺序一致，必须扫完分页，避免漏掉中间正式版。
            if (releases.size() < PAGE_SIZE) break
            page++
        }
        return notes.values.reversed()
    }

    companion object {
        const val RELEASES_URL = "https://github.com/HaoTian22/TU-Reader/releases"
        const val RELEASES_API_URL = "https://api.github.com/repos/HaoTian22/TU-Reader/releases"
        const val API_URL = "$RELEASES_API_URL/latest"
        private const val PAGE_SIZE = 100

        fun shouldCheckAutomatically(currentVersion: String, isTestingBuild: Boolean = false): Boolean =
            !isTestingBuild && AppVersion.parse(currentVersion)?.prerelease?.isEmpty() == true

        /** 忽略仅针对同一正式版本；兼容 v 前缀、补零和构建元数据。 */
        fun isIgnoredVersion(version: String, ignoredVersion: String?): Boolean {
            val release = AppVersion.parse(version) ?: return false
            val ignored = ignoredVersion?.let(AppVersion::parse) ?: return false
            return release.compareTo(ignored) == 0
        }

        private fun fetchReleaseJson(url: String): String? {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "TU-Reader")
            return try {
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    HttpURLConnection.HTTP_NOT_FOUND -> null
                    HttpURLConnection.HTTP_FORBIDDEN, 429 -> throw IOException(L10n.str(R.string.update_err_rate_limited))
                    else -> throw IOException(L10n.str(R.string.update_err_http, status))
                }
            } finally {
                connection.disconnect()
            }
        }

        private fun isReleaseUrl(value: String, suffix: String): Boolean = runCatching {
            val url = URL(value)
            url.protocol == "https" && url.host.equals("github.com", ignoreCase = true) &&
                url.path.startsWith("/HaoTian22/TU-Reader/releases/$suffix")
        }.getOrDefault(false)

        private fun JsonObject.string(name: String): String? = get(name)
            ?.takeUnless { it.isJsonNull }?.asString
    }
}

/** 数字版本按段比较；支持 v 前缀、预发布标识和不参与排序的构建元数据。 */
private data class AppVersion(val numbers: List<BigInteger>, val prerelease: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (index in 0 until maxOf(numbers.size, other.numbers.size)) {
            val result = (numbers.getOrNull(index) ?: BigInteger.ZERO)
                .compareTo(other.numbers.getOrNull(index) ?: BigInteger.ZERO)
            if (result != 0) return result
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumber = left.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val rightNumber = right.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val result = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (result != 0) return result
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex("^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?$")
        fun parse(value: String): AppVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            return AppVersion(match.groupValues[1].split('.').map(String::toBigInteger),
                match.groupValues[2].takeIf(String::isNotEmpty)?.split('.').orEmpty())
        }
    }
}
