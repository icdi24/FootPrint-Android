package me.footprint.app.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ---------------- 请求 / 响应数据类 ----------------

@Serializable
data class LoginRequest(val nickname: String, val password: String)

@Serializable
data class LoginResponse(val user: UserDto? = null)

@Serializable
data class UserDto(val id: Long, val nickname: String, val avatar: String? = null)

@Serializable
data class AppUsage(
    val packageName: String,
    val appName: String,
    val seconds: Long,
)

@Serializable
data class ReportRequest(val date: String, val apps: List<AppUsage>)

@Serializable
data class ReportResponse(val ok: Boolean = false, val serverTime: String? = null, val date: String? = null)

@Serializable
data class UsageListResponse(
    val date: String = "",
    val totalSeconds: Long = 0,
    val apps: List<AppUsage> = emptyList(),
    val lastReportAt: String? = null,
)

@Serializable
data class PartnerResponse(
    val partner: UserDto? = null,
    val date: String = "",
    val totalSeconds: Long = 0,
    val apps: List<AppUsage> = emptyList(),
    val lastReportAt: String? = null,
    val staleMinutes: Long? = null,
)

/** 后端统一用 { error: "..." } 返回错误信息 */
@Serializable
data class ApiErrorBody(val error: String? = null)

/** 把后端的 HTTP 错误变成可读异常，UI 直接展示 message */
class ApiException(message: String, val code: Int = 0) : IOException(message)

// ---------------- 客户端 ----------------

/**
 * OkHttp 单例 + 手写 Cookie 处理。
 *
 * 为什么不用 OkHttp 自带的持久化 CookieJar：
 * 文档 16.5 说得很清楚 —— Cookie 不会自己跨进程可靠地持久化，手动塞最省心。
 * 这里的做法是：
 *   1. 登录响应里的 Set-Cookie 由 [MemoryCookieJar] 在内存里接住（只管当前进程）；
 *   2. 真正落盘走 [Session.saveToken]，值取自 Set-Cookie 的 fp_token；
 *   3. 之后每个请求手动加 `Cookie: fp_token=xxx` 头。
 * 即内存 CookieJar 只做兜底（防止个别逻辑漏加头），落盘和回填都是显式的。
 */
object Api {

    private const val TAG = "FootprintApi"
    private const val COOKIE_NAME = "fp_token"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** kotlinx.serialization 的配置：后端有时会多给字段，忽略未知字段更稳 */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** 内存 CookieJar：只用来接住登录时 Set-Cookie 的 fp_token */
    private class MemoryCookieJar : CookieJar {
        @Volatile
        var session: String? = null

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.firstOrNull { it.name == COOKIE_NAME }?.let { session = it.value }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }

    private val cookieJar = MemoryCookieJar()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun baseUrl(): String = Session.baseUrl()

    /** 拼完整地址；BASE_URL 必须带结尾 "/"，这里再兜一层 */
    private fun url(path: String): String {
        val base = if (baseUrl().endsWith("/")) baseUrl() else baseUrl() + "/"
        return base + path.removePrefix("/")
    }

    // ---------------- 底层请求 ----------------

    private suspend fun send(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
        onSetCookie: ((Map<String, String>) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url(path))
        if (token != null) {
            // 显式带上会话 Cookie（文档 16.5：不要指望 OkHttp 自动跨进程持久化）
            builder.header("Cookie", "$COOKIE_NAME=$token")
        }
        val reqBody = body?.toRequestBody(JSON_MEDIA_TYPE)
        builder.method(method, reqBody)
        builder.header("Accept", "application/json")

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            // 网络不通（没连 Wi-Fi / 地址填错 / 后端没起）统一成一句话
            Log.w(TAG, "$method $path 网络失败", e)
            throw ApiException("连不上后端：${e.message ?: "网络错误"}")
        }

        response.use { resp ->
            // 直接从响应头解析 Set-Cookie，不依赖 CookieJar 回调是否触发
            if (onSetCookie != null) {
                val pairs = mutableMapOf<String, String>()
                resp.headers.values("Set-Cookie").forEach { raw ->
                    val first = raw.substringBefore(';')
                    val i = first.indexOf('=')
                    if (i > 0) pairs[first.substring(0, i).trim()] = first.substring(i + 1).trim()
                }
                onSetCookie(pairs)
            }
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    json.decodeFromString(ApiErrorBody.serializer(), text).error
                }.getOrNull()
                throw ApiException(msg?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}", resp.code)
            }
            text
        }
    }

    // ---------------- 登录 ----------------

    /**
     * 登录：POST /api/login
     * 成功后 token 同时进内存 CookieJar 和 SharedPreferences，调用方拿到的就是 token。
     */
    suspend fun login(
        nickname: String,
        password: String,
        onToken: (String) -> Unit,
    ): LoginResponse {
        val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(nickname.trim(), password))
        // 从响应头的 Set-Cookie 里直接取 token（后端 Cookie 名是 fp_token）
        var fromHeader: String? = null
        val text = send("POST", "api/login", body) { cookies ->
            fromHeader = cookies[COOKIE_NAME]
        }

        val resp = runCatching {
            json.decodeFromString(LoginResponse.serializer(), text)
        }.getOrNull()

        // 优先用响应头里的；兜底再用 CookieJar 接到的
        val token = (fromHeader ?: cookieJar.session)?.takeIf { it.isNotBlank() }
            ?: throw ApiException("登录成功但没拿到会话 Cookie，检查是否连到了正确的后端")
        onToken(token)
        return resp ?: LoginResponse()
    }

    // ---------------- 使用时长 ----------------

    /** PUT /api/usage/report —— 当日累计覆盖写，后端保证幂等 */
    suspend fun report(date: String, apps: List<AppUsage>, token: String): ReportResponse {
        val body = json.encodeToString(ReportRequest.serializer(), ReportRequest(date, apps))
        val text = send("PUT", "api/usage/report", body, token)
        return json.decodeFromString(ReportResponse.serializer(), text)
    }

    /** GET /api/usage/today */
    suspend fun getMyToday(token: String): UsageListResponse {
        val text = send("GET", "api/usage/today", null, token)
        return json.decodeFromString(UsageListResponse.serializer(), text)
    }

    /** GET /api/usage/partner */
    suspend fun getPartner(token: String): PartnerResponse {
        val text = send("GET", "api/usage/partner", null, token)
        return json.decodeFromString(PartnerResponse.serializer(), text)
    }

    // ---------------- 打卡 / 积分（与 Web 版共用同一套后端） ----------------

    /** GET /api/home —— 首页聚合：余额、连续、今日待办、小目标、对方今日 */
    suspend fun getHome(token: String): HomeResponse {
        val text = send("GET", "api/home", null, token)
        return json.decodeFromString(HomeResponse.serializer(), text)
    }

    /** POST /api/goals/{id}/checkin —— 打卡；duration 不传就用目标的默认时长 */
    suspend fun checkin(token: String, goalId: Long, durationMin: Int?, evidenceText: String?): CheckinResult {
        val body = json.encodeToString(
            CheckinRequest.serializer(),
            CheckinRequest(durationMin, evidenceText?.takeIf { it.isNotBlank() }),
        )
        val text = send("POST", "api/goals/$goalId/checkin", body, token)
        return json.decodeFromString(CheckinResult.serializer(), text)
    }

    /** POST /api/checkins/{id}/revoke —— 24 小时内撤销本人打卡 */
    suspend fun revoke(token: String, checkinId: Long): Boolean {
        send("POST", "api/checkins/$checkinId/revoke", "{}", token)
        return true
    }

    /** GET /api/ledger —— 积分流水（最近 300 条） */
    suspend fun getLedger(token: String): LedgerResponse {
        val text = send("GET", "api/ledger", null, token)
        return json.decodeFromString(LedgerResponse.serializer(), text)
    }

    /** GET /api/partner —— 「我们」页：对方打卡、待验收、并排对比 */
    suspend fun getPartnerPage(token: String): PartnerPageResponse {
        val text = send("GET", "api/partner", null, token)
        return json.decodeFromString(PartnerPageResponse.serializer(), text)
    }

    /** POST /api/checkins/{id}/reject —— 驳回对方今天的打卡 */
    suspend fun rejectCheckin(token: String, checkinId: Long, reason: String) {
        send(
            "POST",
            "api/checkins/$checkinId/reject",
            json.encodeToString(RejectRequest.serializer(), RejectRequest(reason)),
            token,
        )
    }

    // ---------------- 目标（goals） ----------------

    /** GET /api/goals —— 全部目标列表（type 可选 "daily" / "stage"，不传返回全部） */
    suspend fun listGoals(token: String, type: String? = null): List<GoalDetail> {
        val q = type?.let { "?type=$it" } ?: ""
        val text = send("GET", "api/goals$q", null, token)
        return json.decodeFromString(GoalsResponse.serializer(), text).goals
    }

    /** GET /api/goals/week?start=YYYY-MM-DD —— 周视图 */
    suspend fun getWeek(token: String, start: String): WeekView {
        val text = send("GET", "api/goals/week?start=$start", null, token)
        return json.decodeFromString(WeekView.serializer(), text)
    }

    /** GET /api/goals/month?month=YYYY-MM —— 月视图 */
    suspend fun getMonth(token: String, month: String): MonthView {
        val text = send("GET", "api/goals/month?month=$month", null, token)
        return json.decodeFromString(MonthView.serializer(), text)
    }

    /** POST /api/goals —— 新建目标 */
    suspend fun createGoal(token: String, body: GoalPayload): GoalDetail {
        val text = send("POST", "api/goals", json.encodeToString(GoalPayload.serializer(), body), token)
        return json.decodeFromString(GoalCreated.serializer(), text).goal
    }

    /** PUT /api/goals/{id} —— 编辑目标 */
    suspend fun updateGoal(token: String, goalId: Long, body: GoalPayload): GoalDetail {
        val text = send("PUT", "api/goals/$goalId", json.encodeToString(GoalPayload.serializer(), body), token)
        return json.decodeFromString(GoalCreated.serializer(), text).goal
    }

    /** POST /api/goals/{id}/start —— 开始（草稿 → 进行中） */
    suspend fun startGoal(token: String, goalId: Long) {
        send("POST", "api/goals/$goalId/start", "{}", token)
    }

    /** POST /api/goals/{id}/abandon —— 放弃 */
    suspend fun abandonGoal(token: String, goalId: Long) {
        send("POST", "api/goals/$goalId/abandon", "{}", token)
    }

    /** POST /api/goals/{id}/submit —— 小目标提交验收 */
    suspend fun submitGoal(token: String, goalId: Long, selfStatement: String) {
        send(
            "POST",
            "api/goals/$goalId/submit",
            json.encodeToString(SubmitRequest.serializer(), SubmitRequest(selfStatement)),
            token,
        )
    }

    // ---------------- 愿望单（wish） ----------------

    /** GET /api/wish —— 愿望列表 */
    suspend fun listWish(token: String): WishListResponse {
        val text = send("GET", "api/wish", null, token)
        return json.decodeFromString(WishListResponse.serializer(), text)
    }

    /** POST /api/wish —— 新建愿望 */
    suspend fun createWish(token: String, body: WishPayload): WishItem {
        val text = send("POST", "api/wish", json.encodeToString(WishPayload.serializer(), body), token)
        return json.decodeFromString(WishCreated.serializer(), text).item
    }

    /** PUT /api/wish/{id} —— 编辑愿望 */
    suspend fun updateWish(token: String, wishId: Long, body: WishPayload): WishItem {
        val text = send("PUT", "api/wish/$wishId", json.encodeToString(WishPayload.serializer(), body), token)
        return json.decodeFromString(WishCreated.serializer(), text).item
    }

    /** POST /api/wish/{id}/redeem —— 兑换（锁分） */
    suspend fun redeemWish(token: String, wishId: Long): RedeemResult {
        val text = send("POST", "api/wish/$wishId/redeem", "{}", token)
        return json.decodeFromString(RedeemResult.serializer(), text)
    }

    /** POST /api/redemptions/{id}/cancel —— 撤销兑换（2 小时内） */
    suspend fun cancelRedemption(token: String, redemptionId: Long) {
        send("POST", "api/redemptions/$redemptionId/cancel", "{}", token)
    }

    /** POST /api/redemptions/{id}/done —— 标记已兑现 */
    suspend fun doneRedemption(token: String, redemptionId: Long) {
        send("POST", "api/redemptions/$redemptionId/done", "{}", token)
    }

    // ---------------- 验收（小目标） ----------------

    /** POST /api/verifications/{id}/approve —— 通过对方提交的验收 */
    suspend fun approveVerification(token: String, verificationId: Long): ApproveResult {
        val text = send("POST", "api/verifications/$verificationId/approve", "{}", token)
        return json.decodeFromString(ApproveResult.serializer(), text)
    }

    /** POST /api/verifications/{id}/reject —— 驳回验收 */
    suspend fun rejectVerification(token: String, verificationId: Long, reason: String) {
        send(
            "POST",
            "api/verifications/$verificationId/reject",
            json.encodeToString(RejectRequest.serializer(), RejectRequest(reason)),
            token,
        )
    }

    // ---------------- 我的（me） ----------------

    /** GET /api/stats —— 本人或搭档的统计 */
    suspend fun getStats(token: String, userId: Long? = null): StatsResponse {
        val q = userId?.let { "?user=$it" } ?: ""
        val text = send("GET", "api/stats$q", null, token)
        return json.decodeFromString(StatsResponse.serializer(), text)
    }

    /** POST /api/me/password —— 改口令 */
    suspend fun changePassword(token: String, oldPassword: String, newPassword: String) {
        send(
            "POST",
            "api/me/password",
            json.encodeToString(PasswordChange.serializer(), PasswordChange(oldPassword, newPassword)),
            token,
        )
    }

    /** GET /api/settings —— 积分系数 */
    suspend fun getSettings(token: String): SettingsResponse {
        val text = send("GET", "api/settings", null, token)
        return json.decodeFromString(SettingsResponse.serializer(), text)
    }

    /** PUT /api/settings —— 改积分系数 */
    suspend fun updateSettings(token: String, points: PointsConfig) {
        send(
            "PUT",
            "api/settings",
            json.encodeToString(SettingsRequest.serializer(), SettingsRequest(points)),
            token,
        )
    }

    /** GET /api/backups —— 备份列表 */
    suspend fun listBackups(token: String): BackupsResponse {
        val text = send("GET", "api/backups", null, token)
        return json.decodeFromString(BackupsResponse.serializer(), text)
    }

    /** POST /api/backups —— 手动备份 */
    suspend fun createBackup(token: String) {
        send("POST", "api/backups", "{}", token)
    }
}

// ---------------- 打卡 / 积分相关数据类 ----------------

@Serializable
data class GoalRef(
    val id: Long,
    val title: String = "",
    val emoji: String = "🎯",
    val duration_min: Int = 30,
    val difficulty: Int = 3,
    val freq_mode: String = "daily",
    val freq_n: Int = 1,
    val weekdays: String = "",
    val window_start: String? = null,
    val window_end: String? = null,
    val custom_points: Int? = null,
)

@Serializable
data class CheckedInfo(
    val id: Long = 0,
    val points: Int = 0,
    val durationMin: Int = 0,
    val evidenceText: String = "",
)

@Serializable
data class Todo(
    val goal: GoalRef,
    val dueToday: Boolean = false,
    val checked: CheckedInfo? = null,
    val pointsNow: Int = 0,
    val streak: Int = 0,
)

@Serializable
data class PartnerTodayItem(
    val id: Long = 0,
    val goalTitle: String = "",
    val goalEmoji: String = "🎯",
    val points: Int = 0,
    val durationMin: Int = 0,
    val evidenceText: String = "",
)

@Serializable
data class PartnerToday(
    val checked: Int = 0,
    val expected: Int = 0,
    val list: List<PartnerTodayItem> = emptyList(),
)

@Serializable
data class StageGoal(
    val id: Long,
    val title: String = "",
    val emoji: String = "🏁",
    val status: String = "",
    val total_points: Int? = null,
    val accept_criteria: String? = null,
    val est_total_min: Int? = null,
)

@Serializable
data class HomeResponse(
    val me: UserDto? = null,
    val partner: UserDto? = null,
    val balance: Int = 0,
    val monthPoints: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val todos: List<Todo> = emptyList(),
    val stageGoals: List<StageGoal> = emptyList(),
    val pendingCount: Int = 0,
    val partnerToday: PartnerToday? = null,
    val today: String = "",
)

@Serializable
data class CheckinRequest(
    val duration_min: Int? = null,
    val evidence_text: String? = null,
)

@Serializable
data class CheckinResult(
    val points: Int = 0,
    val streak: Int = 0,
    val balance: Int = 0,
    val bonuses: List<String> = emptyList(),
)

@Serializable
data class LedgerItem(
    val id: Long = 0,
    val delta: Int = 0,
    val balance_after: Int = 0,
    val type: String = "",
    val typeLabel: String = "",
    val note: String = "",
    val created_at: String = "",
)

@Serializable
data class LedgerResponse(val ledger: List<LedgerItem> = emptyList())

// ---------------- 「我们」页 ----------------

@Serializable
data class UserStats(
    val balance: Int = 0,
    val monthPoints: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val completion: Int? = null,
    val timeInvestMin: Int = 0,
)

@Serializable
data class PartnerCheckin(
    val id: Long = 0,
    val goalId: Long = 0,
    val goalTitle: String = "",
    val goalEmoji: String = "🎯",
    val points: Int = 0,
    val durationMin: Int = 0,
    val evidenceText: String = "",
    val evidenceUrl: String? = null,
    val status: String = "credited",
    val createdAt: String = "",
)

@Serializable
data class PartnerGoalItem(
    val id: Long = 0,
    val title: String = "",
    val emoji: String = "🎯",
    val freq_mode: String = "daily",
    val freq_n: Int = 1,
    val duration_min: Int = 30,
    val status: String = "",
    val streak: Int = 0,
    val perCheckinPoints: Int = 0,
)

@Serializable
data class TodaySummary(
    val dueGoals: Int = 0,
    val expected: Int = 0,
    val checked: Int = 0,
)

@Serializable
data class PartnerPageResponse(
    val partner: UserDto? = null,
    val partnerStats: UserStats? = null,
    val myStats: UserStats? = null,
    val partnerGoals: List<PartnerGoalItem> = emptyList(),
    val partnerToday: List<PartnerCheckin> = emptyList(),
    val pendingVerifications: List<PendingVerification> = emptyList(),
    val today: TodaySummary? = null,
    val partnerRecentRedemptions: List<Redemption> = emptyList(),
)

@Serializable
data class PendingVerification(
    val id: Long = 0,
    val goal_title: String = "",
    val goal_emoji: String = "🏁",
    val total_points: Int = 0,
    val accept_criteria: String? = null,
    val submitter_nickname: String = "",
)

@Serializable
data class RejectRequest(val reason: String)

// ---------------- 目标（goals） ----------------

@Serializable
data class Milestone(val n: Int = 0, val p: Int = 0)

@Serializable
data class GoalDetail(
    val id: Long = 0,
    val owner_id: Long = 0,
    val type: String = "daily",
    val title: String = "",
    val emoji: String = "🎯",
    val description: String = "",
    val freq_mode: String = "daily",
    val freq_n: Int = 1,
    val weekdays: String = "",
    val window_start: String? = null,
    val window_end: String? = null,
    val duration_min: Int = 30,
    val difficulty: Int = 3,
    val start_date: String = "",
    val end_date: String? = null,
    val status: String = "draft",
    val streak_count: Int = 0,
    val streak_best: Int = 0,
    val est_total_min: Int? = null,
    val accept_criteria: String? = null,
    val total_points: Int? = null,
    val custom_points: Int? = null,
    val perCheckinPoints: Int = 0,
)

@Serializable
data class GoalsResponse(val goals: List<GoalDetail> = emptyList())

@Serializable
data class GoalCreated(val goal: GoalDetail)

@Serializable
data class WeekCell(
    val date: String = "",
    val wd: Int = 0,
    val state: String = "future", // done / missed / future / todo
    val points: Int = 0,
    val makeup: Boolean = false,
)

@Serializable
data class WeekGoal(
    val id: Long = 0,
    val title: String = "",
    val emoji: String = "🎯",
    val status: String = "",
    val type: String = "daily",
    val freq_mode: String = "daily",
    val freq_n: Int = 1,
    val weekdays: String = "",
    val perCheckinPoints: Int = 0,
    val streak: Int = 0,
    val cells: List<WeekCell> = emptyList(),
)

@Serializable
data class WeekView(
    val weekStart: String = "",
    val weekEnd: String = "",
    val today: String = "",
    val weekPoints: Int = 0,
    val weekDone: Int = 0,
    val weekExpected: Int = 0,
    val goals: List<WeekGoal> = emptyList(),
)

@Serializable
data class MonthDay(
    val date: String = "",
    val done: Boolean = false,
    val points: Int = 0,
)

@Serializable
data class MonthGoal(
    val id: Long = 0,
    val title: String = "",
    val emoji: String = "🎯",
    val days: List<MonthDay> = emptyList(),
)

@Serializable
data class MonthView(
    val month: String = "",
    val today: String = "",
    val monthPoints: Int = 0,
    val monthDone: Int = 0,
    val monthExpected: Int = 0,
    val goals: List<MonthGoal> = emptyList(),
)

@Serializable
data class GoalPayload(
    val title: String,
    val type: String = "daily",
    val emoji: String? = null,
    val description: String? = null,
    val freq_mode: String? = null,
    val freq_n: Int? = null,
    val weekdays: String? = null,
    val window_start: String? = null,
    val window_end: String? = null,
    val duration_min: Int? = null,
    val difficulty: Int? = null,
    val start_date: String? = null,
    val end_date: String? = null,
    val est_total_min: Int? = null,
    val accept_criteria: String? = null,
    val custom_points: Int? = null,
)

// ---------------- 愿望单（wish） ----------------

@Serializable
data class WishItem(
    val id: Long = 0,
    val owner_id: Long = 0,
    val category: String = "goods",
    val title: String = "",
    val description: String = "",
    val emoji: String = "🎁",
    val price_cny: Double = 0.0,
    val points_cost: Int = 0,
    val image_id: Long? = null,
    val cooldown_days: Int = 0,
    val status: String = "active",
    val redeemable: Boolean = false,
    val cooldownMsg: String? = null,
)

@Serializable
data class Redemption(
    val id: Long = 0,
    val wish_item_id: Long = 0,
    val points_locked: Int = 0,
    val points_charged: Int = 0,
    val status: String = "applied",
    val applied_at: String = "",
    val item_title: String = "",
    val item_emoji: String = "🎁",
    val item_category: String = "goods",
)

@Serializable
data class WishListResponse(
    val items: List<WishItem> = emptyList(),
    val redemptions: List<Redemption> = emptyList(),
    val balance: Int = 0,
)

@Serializable
data class WishCreated(val item: WishItem)

@Serializable
data class WishPayload(
    val title: String,
    val category: String = "goods",
    val description: String? = null,
    val emoji: String? = null,
    val price_cny: Double,
    val points_cost: Int? = null,
    val cooldown_days: Int? = null,
)

@Serializable
data class RedeemResult(
    val redemption: Redemption? = null,
    val balance: Int = 0,
    val cooldownDays: Int = 0,
)

// ---------------- 验收 ----------------

@Serializable
data class ApproveResult(
    val points: Int = 0,
    val overdue: Boolean = false,
)

// ---------------- 我的（me） ----------------

@Serializable
data class StatsInner(
    val balance: Int = 0,
    val monthPoints: Int = 0,
    val bestStreak: Int = 0,
    val currentStreak: Int = 0,
    val completion: Int? = null,
    val timeInvestMin: Int = 0,
    val monthCheckinCount: Int = 0,
    val monthExpected: Int = 0,
    val rejectedCount: Int = 0,
)

@Serializable
data class StatsResponse(
    val user: UserDto? = null,
    val stats: StatsInner? = null,
)

@Serializable
data class PasswordChange(val oldPassword: String, val newPassword: String)

@Serializable
data class Difficulty(val `1`: Double = 1.0, val `2`: Double = 1.2, val `3`: Double = 1.5, val `4`: Double = 1.8, val `5`: Double = 2.2)

@Serializable
data class Freq(val daily: Double = 1.0, val weekly34: Double = 1.2, val weekly12: Double = 1.5, val weekdays: Double = 1.0)

@Serializable
data class Window(val `in`: Double = 1.1, val none: Double = 1.0, val out: Double = 0.8)

@Serializable
data class PointsConfig(
    val unit: Double = 0.5,
    val cap: Int = 60,
    val maxPer: Int = 300,
    val difficulty: Difficulty = Difficulty(),
    val freq: Freq = Freq(),
    val window: Window = Window(),
    val milestones: List<Milestone> = emptyList(),
)

@Serializable
data class SettingsResponse(val points: PointsConfig)

@Serializable
data class SettingsRequest(val points: PointsConfig)

@Serializable
data class BackupEntry(
    val name: String = "",
    val size: Long = 0,
    val mtime: String = "",
)

@Serializable
data class BackupsResponse(val backups: List<BackupEntry> = emptyList())

@Serializable
data class SubmitRequest(val self_statement: String)
