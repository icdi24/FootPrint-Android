package me.footprint.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.LocalDateTime
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.footprint.app.net.AppUsage
import me.footprint.app.net.StageGoal
import me.footprint.app.net.Todo
import me.footprint.app.ui.theme.PixelColors
import me.footprint.app.ui.theme.PixelType
import android.widget.Toast

/*
 * M1 的两个界面：登录页、主页（今日时长）。
 * 打卡 / 积分 / 愿望单那些继续用 Web 版，App 里不做（文档 16.10）。
 */

// ==================== 登录页 ====================

@Composable
fun LoginScreen(vm: AppViewModel) {
    val login by vm.login.collectAsStateWithLifecycle()

    // 口令只放在本地 state 里，不塞进 ViewModel 长期持有
    var password: String by rememberSaveable { mutableStateOf("") }

    // 登录失败时把错误清掉的简单做法：用户一改动就重置
    Screen {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            // logo：绿底像素面板里放一个爪印
            PixelPanel(background = PixelColors.Green) {
                Text(
                    text = "🐾",
                    style = PixelType.Huge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("脚 印", style = PixelType.Huge, color = PixelColors.Ink)
            Spacer(Modifier.height(8.dp))
            Text("只给你们两个人看的手机时长", style = PixelType.Small, color = PixelColors.Ink2)

            Spacer(Modifier.height(32.dp))

            Text("昵称", style = PixelType.Small, color = PixelColors.Ink2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            PixelTextField(
                value = login.nickname,
                onValueChange = vm::onNicknameChange,
                placeholder = "比如：栗子",
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Text("口令", style = PixelType.Small, color = PixelColors.Ink2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            PixelTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "和网页版一样",
                modifier = Modifier.fillMaxWidth(),
                isPassword = true,
            )

            Spacer(Modifier.height(12.dp))

            val error = login.error
            if (error != null) {
                Text(
                    text = error,
                    style = PixelType.Small,
                    color = PixelColors.RedDeep,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            PixelButton(
                text = if (login.busy) "登录中…" else "登 录",
                onClick = { vm.login(login.nickname, password) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !login.busy,
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = "用网页版同一套账号登录。\n时长只在你和 ta 之间可见。",
                style = PixelType.Small,
                color = PixelColors.Ink3,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ==================== 主页 ====================

@Composable
fun HomeScreen(vm: AppViewModel, onOpenUsageSettings: () -> Unit) {
    val granted by vm.granted.collectAsStateWithLifecycle()
    val mine by vm.mine.collectAsStateWithLifecycle()
    val partner by vm.partner.collectAsStateWithLifecycle()
    val syncNote by vm.syncNote.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val busy by vm.checkinBusy.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val myName by vm.myName.collectAsStateWithLifecycle()
    val pendingCount = (home?.todos ?: emptyList<Todo>()).count { it.dueToday && it.checked == null }

    val context = LocalContext.current
    // 一次性提示：显示完立刻清掉，避免重组时反复弹
    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    Screen {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // 顶栏：问候 + 日期 + 退出
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = greetingText() + "，" + (home?.me?.nickname ?: myName.ifBlank { "你好" }) + " " + (home?.me?.avatar ?: ""),
                        style = PixelType.Big,
                        color = PixelColors.Ink,
                    )
                    Text(
                        text = todayLabel(),
                        style = PixelType.Small,
                        color = PixelColors.Ink3,
                    )
                }
                PixelSmallButton(text = "退出", onClick = vm::logout, filled = false)
            }

            Spacer(Modifier.height(16.dp))

            // ---- 积分横幅（对齐 Web 首页 balance-banner）----
            if (home != null) {
                BalanceBanner(
                    balance = home!!.balance,
                    currentStreak = home!!.currentStreak,
                    monthPoints = home!!.monthPoints,
                )
                Spacer(Modifier.height(16.dp))
            }

            // ---- 今日打卡（与 Web 首页一致：先打卡，再看时长）----
            val todos = home?.todos ?: emptyList<Todo>()
            SectionHead("今日待办", if (pendingCount > 0) "剩 $pendingCount 项" else "")
            for (t in todos.filter { it.dueToday && it.checked == null }) {
                TodoCard(t, busy) { id, dur -> vm.checkin(id, dur, null) }
                Spacer(Modifier.height(10.dp))
            }
            val doneList = todos.filter { it.dueToday && it.checked != null }
            if (doneList.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SectionHead("已完成", "${doneList.size} 项 ✓")
                for (t in doneList) {
                    DoneCard(t)
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (pendingCount == 0 && doneList.isEmpty()) {
                PixelPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("🌱", style = PixelType.Huge)
                        Spacer(Modifier.height(8.dp))
                        Text("今天还没有目标", style = PixelType.Title, color = PixelColors.Ink)
                        Text(
                            "去「目标」页建第一个打卡目标，两个人就都看得见了",
                            style = PixelType.Small,
                            color = PixelColors.Ink2,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---- 进行中的小目标（对齐 Web 首页）----
            val stageList = home?.stageGoals ?: emptyList()
            if (stageList.isNotEmpty()) {
                StageMiniList(stageList)
                Spacer(Modifier.height(20.dp))
            }

            // ---- 我 ----
            if (!granted) {
                // 没授权时绝不显示 0 分钟（会被读成"今天一点没玩"）
                NoPermissionCard(onOpenUsageSettings)
            } else {
                MyUsageCard(
                    totalSeconds = mine?.totalSeconds ?: 0L,
                    apps = mine?.apps ?: emptyList(),
                    hasData = mine != null,
                    syncNote = syncNote,
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---- ta ----
            PartnerCard(
                nickname = partner?.partner?.nickname ?: "ta",
                totalSeconds = partner?.totalSeconds ?: 0L,
                apps = partner?.apps ?: emptyList(),
                lastReportAt = partner?.lastReportAt,
                hasPartner = partner != null,
            )

            val err = loadError
            if (err != null) {
                Spacer(Modifier.height(12.dp))
                Text("加载出错：$err", style = PixelType.Small, color = PixelColors.RedDeep)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 单张待办卡（对齐 Web 版 todoCard）：emoji 方块 + 标题/元信息/连续 + 右侧分值 + 完成按钮。
 */
@Composable
private fun TodoCard(
    t: Todo,
    busyGoalId: Long?,
    onCheckin: (Long, Int?) -> Unit,
) {
    val g = t.goal
    val meta = buildList {
        add(if (g.window_start != null) "${g.window_start}–${g.window_end}" else "全天")
        add("难度 ${g.difficulty}")
        if (t.goal.freq_mode == "weekly") add("本周 ${t.goal.freq_n}/3")
    }
    PixelPanel(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // emoji 绿色方块
            Box(
                modifier = Modifier
                    .background(PixelColors.GreenSoft)
                    .pixelBorder(2.dp, PixelColors.Outline)
                    .padding(8.dp),
            ) {
                Text(g.emoji, style = PixelType.Title)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(g.title, style = PixelType.Body, color = PixelColors.Ink)
                Text(meta.joinToString(" · "), style = PixelType.Small, color = PixelColors.Ink2)
                Spacer(Modifier.height(2.dp))
                Text(
                    "🔥 连续 ${t.streak} 天",
                    style = PixelType.Small,
                    color = PixelColors.OrangeDeep,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "+${t.pointsNow}",
                    style = PixelType.Big,
                    color = PixelColors.Ink,
                )
                Spacer(Modifier.height(4.dp))
                val isBusy = busyGoalId == g.id
                PixelSmallButton(
                    text = if (isBusy) "…" else "✓ 完成",
                    filled = true,
                    onClick = { if (!isBusy) onCheckin(g.id, g.duration_min) },
                )
            }
        }
    }
}

/** 已完成卡（对齐 Web 版 doneCard） */
@Composable
private fun DoneCard(t: Todo) {
    val g = t.goal
    PixelPanel(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .background(PixelColors.GreenSoft)
                    .pixelBorder(2.dp, PixelColors.Outline)
                    .padding(8.dp),
            ) {
                Text(g.emoji, style = PixelType.Title)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(g.title, style = PixelType.Body, color = PixelColors.Ink)
                Text(
                    text = "${t.checked?.durationMin ?: 0} 分钟",
                    style = PixelType.Small,
                    color = PixelColors.Ink2,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "+${t.checked?.points ?: 0}",
                    style = PixelType.Big,
                    color = PixelColors.GreenDeep,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .background(PixelColors.Green)
                        .pixelBorder(2.dp, PixelColors.Outline)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("✓", style = PixelType.Small, color = PixelColors.White)
                }
            }
        }
    }
}

/** 板块标题：■ 前缀 + 右侧计数（对齐 Web sec-head） */
@Composable
private fun SectionHead(title: String, count: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(10.dp).background(PixelColors.Ink))
            Spacer(Modifier.width(8.dp))
            Text(title, style = PixelType.Title, color = PixelColors.Ink)
        }
        if (count.isNotBlank()) {
            Text(count, style = PixelType.Small, color = PixelColors.Ink2)
        }
    }
}

@Composable
private fun NoPermissionCard(onOpenUsageSettings: () -> Unit) {
    PixelPanel(modifier = Modifier.fillMaxWidth(), background = PixelColors.OrangeSoft) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("还没开启使用情况访问", style = PixelType.Title, color = PixelColors.Ink)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "开启后脚印才能知道你今天在手机上花了多久，好让 ta 看见。" +
                    "只给 ta 看，不发给任何第三方。",
                style = PixelType.Small,
                color = PixelColors.Ink2,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "没开权限时我们不显示 0 分钟 —— 那不代表你今天没玩手机。",
                style = PixelType.Small,
                color = PixelColors.OrangeDeep,
            )
            Spacer(Modifier.height(16.dp))
            PixelSmallButton(text = "去开启", onClick = onOpenUsageSettings)
        }
    }
}

@Composable
private fun MyUsageCard(
    totalSeconds: Long,
    apps: List<AppUsage>,
    hasData: Boolean,
    syncNote: String?,
) {
    PixelPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("我 · 今天", style = PixelType.Title, color = PixelColors.Ink)
                if (syncNote != null) {
                    PixelChip(text = "未同步")
                } else if (hasData) {
                    PixelChip(text = "已同步", bg = PixelColors.GreenSoft, fg = PixelColors.GreenDeep)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = AppViewModel.humanDuration(totalSeconds),
                style = PixelType.Huge,
                color = PixelColors.GreenDeep,
            )

            if (syncNote != null) {
                Spacer(Modifier.height(4.dp))
                Text(syncNote, style = PixelType.Small, color = PixelColors.OrangeDeep)
            }

            if (apps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                val max = apps.first().seconds.coerceAtLeast(1L)
                for (a in AppViewModel.top(apps, 5)) {
                    AppRow(a, max, PixelColors.Green)
                }
            } else if (hasData) {
                Spacer(Modifier.height(8.dp))
                Text("今天还没有应用使用记录", style = PixelType.Small, color = PixelColors.Ink3)
            }
        }
    }
}

@Composable
private fun PartnerCard(
    nickname: String,
    totalSeconds: Long,
    apps: List<AppUsage>,
    lastReportAt: String?,
    hasPartner: Boolean,
) {
    PixelPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("$nickname · 今天", style = PixelType.Title, color = PixelColors.Ink)
            Spacer(Modifier.height(8.dp))

            if (!hasPartner) {
                Text(
                    text = "还没拿到 ta 的数据。如果有搭档，多半是网络或还没同步，回到 App 会自动重试。",
                    style = PixelType.Small,
                    color = PixelColors.Ink3,
                )
                return@Column
            }

            // 没数据时绝不显示 "0 分钟" —— 那和"今天真的没玩手机"分不出来（文档 16.8 第 1 条的同一条原则）
            val noData = totalSeconds <= 0L && apps.isEmpty()
            if (noData) {
                Text(
                    text = "今天还没有 ta 的数据",
                    style = PixelType.Title,
                    color = PixelColors.Ink3,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "ta 打开脚印手机版并同步后，这里就会出现",
                    style = PixelType.Small,
                    color = PixelColors.Ink2,
                )
                return@Column
            }

            Text(
                text = AppViewModel.humanDuration(totalSeconds),
                style = PixelType.Huge,
                color = PixelColors.OrangeDeep,
            )
            Spacer(Modifier.height(4.dp))

            val clock = AppViewModel.clockOf(lastReportAt)
            Text(
                text = if (clock != null) "ta 最近一次同步于 $clock" else "ta 今天还没同步过",
                style = PixelType.Small,
                color = PixelColors.Ink2,
            )

            if (apps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                val max = apps.first().seconds.coerceAtLeast(1L)
                for (a in AppViewModel.top(apps, 5)) {
                    AppRow(a, max, PixelColors.Orange)
                }
            }
        }
    }
}

/** 一行应用：名字 + 条形 + 时长 */
@Composable
private fun AppRow(a: AppUsage, maxSeconds: Long, color: Color) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = a.appName.ifBlank { a.packageName },
                style = PixelType.Body,
                color = PixelColors.Ink,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = AppViewModel.humanDuration(a.seconds),
                style = PixelType.Small,
                color = PixelColors.Ink2,
            )
        }
        Spacer(Modifier.height(4.dp))
        PixelBar(
            fraction = a.seconds.toFloat() / maxSeconds.toFloat(),
            modifier = Modifier.fillMaxWidth(),
            color = color,
        )
    }
}

// ==================== 首页辅助组件 ====================

/** Web 版 UI.greeting() 的等价实现：按小时问候 */
private fun greetingText(): String {
    val h = LocalDateTime.now().hour
    return when {
        h < 6 -> "夜深了"
        h < 11 -> "早上好"
        h < 14 -> "中午好"
        h < 18 -> "下午好"
        h < 23 -> "晚上好"
        else -> "夜深了"
    }
}

/** "X月X日 周X"，对齐 Web 版 date-line */
private fun todayLabel(): String {
    val d = LocalDate.now()
    val wd = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[d.dayOfWeek.value - 1]
    return "${d.monthValue}月${d.dayOfMonth}日 $wd"
}

/** 积分横幅：深绿底白字 + 火焰连续天数 + 本月得分（对齐 Web balance-banner） */
@Composable
private fun BalanceBanner(balance: Int, currentStreak: Int, monthPoints: Int) {
    PixelPanel(modifier = Modifier.fillMaxWidth(), background = PixelColors.Green) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("✏ 可用积分", style = PixelType.Small, color = PixelColors.White)
                Text(
                    text = balance.toString(),
                    style = PixelType.Huge,
                    color = PixelColors.White,
                )
                Text(
                    text = "≈ ¥" + (balance / 10f).toString(),
                    style = PixelType.Small,
                    color = PixelColors.GreenSoft,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                PixelChip(
                    text = "🔥 $currentStreak 天连续",
                    bg = PixelColors.OrangeSoft,
                    fg = PixelColors.OrangeDeep,
                )
                Spacer(Modifier.height(8.dp))
                PixelChip(
                    text = "本月 +$monthPoints",
                    bg = PixelColors.Card,
                    fg = PixelColors.Ink2,
                )
            }
        }
    }
}

/** 进行中的小目标（首页迷你卡，对齐 Web stageMiniCard） */
@Composable
private fun StageMiniList(goals: List<StageGoal>) {
    PixelPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("进行中的小目标", style = PixelType.Title, color = PixelColors.Ink)
                Text("${goals.size} 个", style = PixelType.Mono, color = PixelColors.Ink3)
            }
            Spacer(Modifier.height(10.dp))
            for (g in goals) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dashedDivider()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(g.emoji, style = PixelType.Title)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(g.title, style = PixelType.Body, color = PixelColors.Ink)
                        Text(
                            text = "达成 +" + (g.total_points ?: 0).toString() + " 分",
                            style = PixelType.Small,
                            color = PixelColors.Ink2,
                        )
                    }
                    if (g.status == "reviewing") {
                        PixelChip(text = "待验收", bg = PixelColors.OrangeSoft, fg = PixelColors.OrangeDeep)
                    }
                }
            }
        }
    }
}
