package me.footprint.app.usage

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.footprint.app.net.AppUsage
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 用 UsageStatsManager 读今天各 App 的前台使用时长。
 *
 * 严格按文档 16.4：
 *  - queryUsageStats(INTERVAL_DAILY, 今天0点, now)
 *  - "今天 0 点" 按 **Asia/Shanghai** 算（设备时区可能不是 +8，绝不能用设备时区）
 *  - 丢掉 totalTimeInForeground <= 0、自家包名、系统 UI / 桌面
 *  - 按前台时长降序，最多 60 个
 *  - appName 取本地应用名，取不到就用包名
 */
object UsageCollector {

    const val ZONE = "Asia/Shanghai"
    private const val TAG = "UsageCollector"
    const val SELF_PACKAGE = "me.footprint.app"
    const val MAX_APPS = 60

    val shanghai: ZoneId = ZoneId.of(ZONE)
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 上海时区的"今天"日期串 YYYY-MM-DD（后端只接受上海时区的今天/昨天） */
    fun todayString(): String = LocalDate.now(shanghai).format(dateFmt)

    /** 上海时区今天 00:00:00 的毫秒时间戳 */
    fun startOfTodayMillis(): Long =
        LocalDate.now(shanghai).atStartOfDay(shanghai).toInstant().toEpochMilli()

    /**
     * 采集结果。
     * @param granted 有没有使用情况访问权限；false 时 [apps] 一定是空的，UI 必须显示空态而不是 0
     */
    data class UsageSnapshot(
        val date: String,
        val granted: Boolean,
        val totalSeconds: Long,
        val apps: List<AppUsage>,
    ) {
        companion object {
            fun notGranted(date: String = todayString()) = UsageSnapshot(date, false, 0L, emptyList())
        }
    }

    /** 是否有权限（转发，方便 UI 只依赖这一个类） */
    fun isGranted(context: Context): Boolean = UsageAccess.isGranted(context)

    /**
     * 采集今天（上海时区）的数据。调用方应在 IO 线程调用（这里自己也切了一次，双保险）。
     */
    suspend fun collect(context: Context): UsageSnapshot = withContext(Dispatchers.IO) {
        val date = todayString()
        if (!UsageAccess.isGranted(context)) return@withContext UsageSnapshot.notGranted(date)

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext UsageSnapshot.notGranted(date)

        val start = startOfTodayMillis()
        val now = System.currentTimeMillis()

        // 第三个参数是 beginTime，第二个是 endTime：只要今天 0 点到现在这一段
        val raw = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
        } catch (e: Exception) {
            Log.w(TAG, "queryUsageStats 失败", e)
            null
        } ?: return@withContext UsageSnapshot(date, true, 0L, emptyList())

        val pm = context.packageManager

        // 同一个包在同一天可能返回多条（跨天切分等），先按包名合并，取最大值更稳妥
        val merged = HashMap<String, Long>()
        for (s in raw) {
            val pkg = s.packageName ?: continue
            val ms = s.totalTimeInForeground
            if (ms <= 0L) continue
            if (shouldSkip(pkg)) continue
            val prev = merged[pkg] ?: 0L
            if (ms > prev) merged[pkg] = ms
        }

        val apps = merged.entries
            .sortedByDescending { it.value }
            .take(MAX_APPS)
            .map { (pkg, ms) ->
                val seconds = ms / 1000L
                AppUsage(
                    packageName = pkg,
                    appName = labelOf(pm, pkg),
                    seconds = if (seconds > 0) seconds else 0L,
                )
            }
            // 秒数取整后可能变成 0，丢掉没意义的条目
            .filter { it.seconds > 0L }

        return@withContext UsageSnapshot(
            date = date,
            granted = true,
            totalSeconds = apps.sumOf { it.seconds },
            apps = apps,
        )
    }

    /**
     * 过滤规则（文档 16.4 第 4 条）：
     * 自家包名、系统 UI、桌面。文档原话是"以 com.android.systemui / launcher 开头"，
     * 这里放宽成"包含 launcher"，因为不少 ROM 的桌面包名不是以 launcher 开头/结尾，
     * 而包含 launcher 的包基本都不是用户在用的 App（miui.home.launcher 之类会被这条兜住一部分）。
     */
    private fun shouldSkip(pkg: String): Boolean {
        if (pkg == SELF_PACKAGE) return true
        if (pkg.startsWith("com.android.systemui")) return true
        val lower = pkg.lowercase()
        if (lower.startsWith("launcher") || lower.contains("launcher")) return true
        if (lower.contains("systemui")) return true
        return false
    }

    /** 本地应用名，取不到（比如卸载了只剩残留记录）就用包名兜底 */
    private fun labelOf(pm: PackageManager, pkg: String): String {
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString().ifBlank { pkg }
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        } catch (e: Exception) {
            pkg
        }
    }
}
