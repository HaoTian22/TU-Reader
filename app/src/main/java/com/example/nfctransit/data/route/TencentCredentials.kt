package com.example.nfctransit.data.route

import android.content.Context
import com.example.nfctransit.BuildConfig

/**
 * 腾讯地图 WebService 凭据与路线开关（同步读取，路线请求在 IO 线程直接取用）。
 * 用户在设置页填入自己的 Key + SecretKey（BYOK）时优先使用，两项都填才生效；否则用内置凭据。
 */
object TencentCredentials {

    data class Pair(val key: String, val secretKey: String) {
        val isComplete: Boolean get() = key.isNotBlank() && secretKey.isNotBlank()
    }

    private const val PREFS_NAME = "route_settings"
    private const val KEY_USER_KEY = "user_web_service_key"
    private const val KEY_USER_SECRET = "user_secret_key"
    private const val KEY_REAL_ROUTES = "real_routes"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户自填的凭据（可能不完整） */
    fun userPair(context: Context): Pair = prefs(context).let {
        Pair(it.getString(KEY_USER_KEY, "").orEmpty(), it.getString(KEY_USER_SECRET, "").orEmpty())
    }

    fun hasUserPair(context: Context): Boolean = userPair(context).isComplete

    /** 实际请求用的凭据：用户凭据完整则用之，否则退回内置 */
    fun effective(context: Context): Pair =
        userPair(context).takeIf { it.isComplete }
            ?: Pair(BuildConfig.TENCENT_MAP_WEB_SERVICE_KEY, BuildConfig.TENCENT_MAP_SECRET_KEY)

    /** 保存用户凭据（两项都空 = 清除，回到内置）；换 Key 后重置服务熔断以便重新探测 */
    fun setUserPair(context: Context, key: String, secretKey: String) {
        prefs(context).edit()
            .putString(KEY_USER_KEY, key.trim())
            .putString(KEY_USER_SECRET, secretKey.trim())
            .apply()
        TransitRouteRepository.resetServiceBlock(context)
    }

    /** 轨迹地图是否加载真实线路；关闭则只画示意飞线，不调用路线服务 */
    fun isRealRoutes(context: Context): Boolean = prefs(context).getBoolean(KEY_REAL_ROUTES, true)

    fun setRealRoutes(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REAL_ROUTES, enabled).apply()
    }
}
