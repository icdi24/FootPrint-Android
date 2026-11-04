package me.footprint.app.usage

import android.content.Context
import android.util.Log
import me.footprint.app.net.Api
import me.footprint.app.net.ApiException
import me.footprint.app.net.Session

/**
 * 上报当日累计（文档 16.1 决策 4 + 16.4 第 6/7 条）：
 *  - 只在 App 进前台 / 切后台时各报一次，不做后台常驻
 *  - 距上次【成功】上报 < 60 秒就跳过（来回切 App 时不刷接口）
 *  - 失败不抛、不阻塞 UI，只记一个"未同步"标记，下次进前台重试
 */
object UsageReporter {

    private const val TAG = "UsageReporter"

    /** 节流窗口：60 秒 */
    const val THROTTLE_MS = 60_000L

    sealed class Outcome {
        /** 报上去了 */
        data class Reported(val count: Int, val totalSeconds: Long) : Outcome()

        /** 节流跳过（距上次成功上报还不够 60 秒） */
        data object Skipped : Outcome()

        /** 没权限 / 没登录，什么都不用做 */
        data class Ignored(val reason: String) : Outcome()

        /** 失败，已标记未同步，等下次进前台重试 */
        data class Failed(val message: String) : Outcome()
    }

    /**
     * 上报一次。调用方用 lifecycleScope 调它，不要 on Main 线程（Api 内部已经在 IO 里请求）。
     *
     * @param force true 时忽略 60 秒节流（手动下拉刷新用，目前 UI 没放这个入口，保留给调试）
     */
    suspend fun reportNow(context: Context, force: Boolean = false): Outcome {
        val appContext = context.applicationContext

        if (!Session.isLoggedIn(appContext)) return Outcome.Ignored("未登录")
        if (!UsageAccess.isGranted(appContext)) return Outcome.Ignored("未开启使用情况访问")

        val last = Session.lastReportMs(appContext)
        val now = System.currentTimeMillis()
        if (!force && last > 0L && now - last < THROTTLE_MS) {
            return Outcome.Skipped
        }

        val token = Session.token(appContext) ?: return Outcome.Ignored("未登录")

        return try {
            val collected = UsageCollector.collect(appContext)
            if (!collected.granted) return Outcome.Ignored("未开启使用情况访问")

            // 后端限制 apps 最多 200 条、单条 seconds <= 86400，采集层已经卡到 60 条以内
            Api.report(collected.date, collected.apps, token)

            Session.markReported(appContext, now)
            Outcome.Reported(collected.apps.size, collected.totalSeconds)
        } catch (e: ApiException) {
            // 401 = 会话过期，清掉本地状态让 UI 回到登录页
            if (e.code == 401) {
                Session.clear(appContext)
                Outcome.Failed("登录已过期，请重新登录")
            } else {
                Session.markPending(appContext)
                Log.w(TAG, "上报失败：${e.message}")
                Outcome.Failed(e.message ?: "上报失败")
            }
        } catch (e: Exception) {
            Session.markPending(appContext)
            Log.w(TAG, "上报异常", e)
            Outcome.Failed(e.message ?: "上报异常")
        }
    }
}
