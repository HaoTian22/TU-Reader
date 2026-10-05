package com.example.nfctransit.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 应用界面语言（AndroidX per-app locale）。
 *
 * 新增语言：加 res/values-xx/strings.xml、res/xml/locales_config.xml 条目，并在 [SUPPORTED] 登记。
 * 卡名、交通类型等数据键保持中文，只在显示时翻译。
 */
object AppLanguage {

    /** 可选语言：语言标签 -> 该语言的自称（语言列表始终用各自语言显示） */
    val SUPPORTED: Map<String, String> = linkedMapOf(
        "zh" to "中文",
        "en" to "English"
    )

    private const val LEGACY_PREFS = "transit_prefs"
    private const val LEGACY_KEY_LANG = "display_lang"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var localized: Pair<Locale, Context>? = null

    /** 在 AppCompatActivity.onCreate（super 之后）调用：此时 AppCompat 已同步持久化的语言 */
    fun init(context: Context) {
        appContext = context.applicationContext
        migrateLegacyPreference(context.applicationContext)
    }

    /** 用户选择的语言标签；空串 = 跟随系统 */
    fun selectedTag(): String =
        AppCompatDelegate.getApplicationLocales()[0]?.language.orEmpty()

    /** 切换语言（空串 = 跟随系统）；AppCompat 会重建 Activity 并持久化选择 */
    fun select(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
    }

    /** 当前生效的界面语言 */
    fun locale(): Locale {
        if (appContext == null) return Locale.getDefault()   // JVM 单测 / 未初始化
        return AppCompatDelegate.getApplicationLocales()[0]
            ?: Resources.getSystem().configuration.locales[0]
            ?: Locale.getDefault()
    }

    /** 中文界面显示中文站名/线路名；其他语言优先英文名（缺失时回退中文） */
    fun isChinese(): Boolean = locale().language == "zh"

    /**
     * 按当前界面语言取资源的 Context。Android 13 以下 AppCompat 只更新 Activity 的语言，
     * ViewModel / 数据层通过这里取字符串。
     */
    fun context(): Context? {
        val base = appContext ?: return null
        val locale = locale()
        localized?.let { (cachedLocale, ctx) -> if (cachedLocale == locale) return ctx }
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config).also { localized = locale to it }
    }

    /** 旧版「语言切换」只作用于站名，存于 transit_prefs；迁移为整应用语言后删除 */
    private fun migrateLegacyPreference(context: Context) {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val legacy = prefs.getString(LEGACY_KEY_LANG, null) ?: return
        prefs.edit().remove(LEGACY_KEY_LANG).apply()
        if (legacy in SUPPORTED && selectedTag().isEmpty()) select(legacy)
    }
}

/** ViewModel / 数据层取本地化字符串；未初始化（JVM 单测）时返回资源 ID 占位，避免数据层逻辑依赖 Android */
object L10n {
    fun str(@StringRes id: Int, vararg args: Any): String =
        AppLanguage.context()?.getString(id, *args) ?: placeholder(id, args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        AppLanguage.context()?.resources?.getQuantityString(id, count, *args) ?: placeholder(id, args)

    private fun placeholder(id: Int, args: Array<out Any>) = "#$id" + args.joinToString(",", "(", ")")
}
