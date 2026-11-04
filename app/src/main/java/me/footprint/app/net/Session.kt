package me.footprint.app.net

import android.content.Context
import me.footprint.app.BuildConfig

/**
 * 本地一点点持久化：只存会话 token 和"最后一次成功上报时间"。
 * 文档 16.2 明确说不引数据库，一个 SharedPreferences 就够。
 */
object Session {

    private const val PREFS = "footprint_session"
    private const val K_TOKEN = "fp_token"
    private const val K_NICKNAME = "nickname"
    private const val K_LAST_REPORT_MS = "last_report_ms"
    private const val K_PENDING = "report_pending"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 会话 token（fp_token 的值，不含 "fp_token=" 前缀） */
    fun token(context: Context): String? =
        prefs(context).getString(K_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun saveToken(context: Context, token: String) {
        prefs(context).edit().putString(K_TOKEN, token).apply()
    }

    fun nickname(context: Context): String? = prefs(context).getString(K_NICKNAME, null)

    fun saveNickname(context: Context, nickname: String) {
        prefs(context).edit().putString(K_NICKNAME, nickname).apply()
    }

    fun isLoggedIn(context: Context): Boolean = token(context) != null

    /** 最后一次【成功】上报的时间戳（节流用） */
    fun lastReportMs(context: Context): Long = prefs(context).getLong(K_LAST_REPORT_MS, 0L)

    fun markReported(context: Context, ms: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(K_LAST_REPORT_MS, ms).putBoolean(K_PENDING, false).apply()
    }

    /** 上次上报失败、还没补上 —— UI 上显示一个"未同步"标记 */
    fun isPending(context: Context): Boolean = prefs(context).getBoolean(K_PENDING, false)

    fun markPending(context: Context) {
        prefs(context).edit().putBoolean(K_PENDING, true).apply()
    }

    /** 退出登录：清 token 与上报状态（节流时间留着也无所谓，一起清掉更干净） */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** 供网络层/日志显示当前后端地址 */
    fun baseUrl(): String = BuildConfig.BASE_URL
}
