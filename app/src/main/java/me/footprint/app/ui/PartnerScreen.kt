package me.footprint.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.footprint.app.net.Api
import me.footprint.app.net.ApiException
import me.footprint.app.net.AppUsage
import me.footprint.app.net.ApproveResult
import me.footprint.app.net.PartnerCheckin
import me.footprint.app.net.PartnerGoalItem
import me.footprint.app.net.PartnerPageResponse
import me.footprint.app.net.PartnerResponse
import me.footprint.app.net.PendingVerification
import me.footprint.app.net.Redemption
import me.footprint.app.net.TodaySummary
import me.footprint.app.net.UsageListResponse
import me.footprint.app.net.UserDto
import me.footprint.app.net.UserStats
import me.footprint.app.net.Session
import me.footprint.app.usage.UsageAccess
import me.footprint.app.ui.theme.PixelColors
import me.footprint.app.ui.theme.PixelType

/*
 * 「我们」页：对方卡片、并排对比、待我验收、对方今日打卡、ta 的目标、手机时长对比。
 * 对齐 Web 版 partner 页。数据自己拉、状态自己管，不依赖其它页面。
 *
 * 网络层只复用 Api.json（解码器）与 Session（token / baseUrl），
 * partner 相关请求与响应数据类都定义在本文件里，避免改动 Api.kt。
 */

// ---------------- 页面状态 ----------------

/** 一次加载的完整状态 */
private class PartnerState {
  var page by mutableStateOf<PartnerPageResponse?>(null)
  var loading by mutableStateOf(true)
  var error by mutableStateOf<String?>(null)
  // 正在处理中的验收 / 打卡 id，防止连点
  var busyVerify by mutableStateOf<Long?>(null)
  var busyCheckin by mutableStateOf<Long?>(null)
  // 驳回弹层：区分「验收」与「打卡」两种
  var dialog by mutableStateOf<RejectTarget?>(null)
  // 一次性提示
  var toast by mutableStateOf<String?>(null)
}

/** 驳回弹层要处理的目标类型 */
private sealed class RejectTarget {
  data class Verify(val id: Long) : RejectTarget()
  data class Checkin(val id: Long) : RejectTarget()
}

// ---------------- 页面 ----------------

@Composable
fun PartnerScreen(vm: AppViewModel, onOpenUsageSettings: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val state = remember { PartnerState() }
  val home by vm.home.collectAsStateWithLifecycle()
  val myAvatar = home?.me?.avatar.orEmpty()

  // 拉数据：成功后写进 state；失败写 error
  fun load() {
    state.loading = true
    state.error = null
    scope.launch {
      val token = Session.token(context)
      if (token == null) {
        state.loading = false
        state.error = "未登录"
        return@launch
      }
      try {
        state.page = Api.getPartnerPage(token)
        state.error = null
      } catch (e: Exception) {
        if (e is ApiException && e.code == 401) {
          state.error = "登录已过期，请重新登录"
        } else if (e is ApiException && e.code == 404) {
          state.error = "还没有搭档"
        } else {
          state.error = e.message ?: "加载失败"
        }
      } finally {
        state.loading = false
      }
    }
  }

  LaunchedEffect(Unit) { load() }

  Screen {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState()),
    ) {
      // 顶栏
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column {
          Text("我们", style = PixelType.Big, color = PixelColors.Ink)
          Text("互相看得见，才坚持得住", style = PixelType.Small, color = PixelColors.Ink3)
        }
        if (state.loading) {
          Text("加载中…", style = PixelType.Small, color = PixelColors.Ink3)
        } else {
          PixelSmallButton(text = "刷新", onClick = { load() }, filled = false)
        }
      }

      Spacer(Modifier.height(12.dp))

      val err = state.error
      if (err != null) {
        PixelPanel(modifier = Modifier.fillMaxWidth(), background = PixelColors.OrangeSoft) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text("加载出错", style = PixelType.Title, color = PixelColors.Ink)
            Spacer(Modifier.height(6.dp))
            Text(err, style = PixelType.Small, color = PixelColors.OrangeDeep)
            Spacer(Modifier.height(12.dp))
            PixelSmallButton(text = "重试", onClick = { load() })
          }
        }
        Spacer(Modifier.height(12.dp))
      }

      val page = state.page
      if (page == null) {
        if (!state.loading && err == null) {
          Text("还没有内容", style = PixelType.Small, color = PixelColors.Ink3)
        }
        Spacer(Modifier.height(24.dp))
        return@Column
      }

      // 1. 顶部对方卡片
      PartnerHeaderCard(page)
      Spacer(Modifier.height(16.dp))

      // 2. 并排对比
      ComparePanel(page, myAvatar)
      Spacer(Modifier.height(16.dp))

      // 3. 待我验收
      if (page.pendingVerifications.isNotEmpty()) {
        VerificationPanel(
          items = page.pendingVerifications,
          busyId = state.busyVerify,
          onApprove = { v ->
            state.busyVerify = v.id
            scope.launch {
              val token = Session.token(context)
              try {
                val r = Api.approveVerification(token ?: throw ApiException("未登录"), v.id)
                val note = if (r.overdue) "（逾期，按 90% 计）" else ""
                state.toast = "已通过「${v.goal_title}」+${r.points} 分$note"
              } catch (e: Exception) {
                state.toast = e.message ?: "通过失败"
              } finally {
                state.busyVerify = null
              }
              load()
            }
          },
          onReject = { v -> state.dialog = RejectTarget.Verify(v.id) },
        )
        Spacer(Modifier.height(16.dp))
      }

      // 3.5 手机时长对比（对齐 Web 顺序：并排看看之后）
      UsageComparePanel(
        context = context,
        granted = UsageAccess.isGranted(context),
        onOpenUsageSettings = onOpenUsageSettings,
      )
      Spacer(Modifier.height(16.dp))

      // 4. 对方今天的打卡
      PartnerTodayPanel(
        items = page.partnerToday,
        busyId = state.busyCheckin,
        onReject = { c -> state.dialog = RejectTarget.Checkin(c.id) },
      )
      Spacer(Modifier.height(16.dp))

      // 5. ta 的目标
      PartnerGoalsPanel(page.partnerGoals)
      Spacer(Modifier.height(16.dp))

      // 5.5 ta 最近的兑换（对齐 Web 版）
      if (page.partnerRecentRedemptions.isNotEmpty()) {
        PartnerRedemptionsPanel(page.partnerRecentRedemptions)
        Spacer(Modifier.height(16.dp))
      }

      Spacer(Modifier.height(24.dp))
    }
  }

  // 驳回理由弹层
  val target = state.dialog
  if (target != null) {
    RejectDialog(
      target = target,
      busy = state.busyCheckin != null || state.busyVerify != null,
      onDismiss = { state.dialog = null },
      onSubmit = { reason ->
        scope.launch {
          val token = Session.token(context)
          try {
            if (token == null) throw ApiException("未登录")
            when (target) {
              is RejectTarget.Verify -> {
                state.busyVerify = target.id
                Api.rejectVerification(token, target.id, reason)
                state.toast = "已驳回验收"
              }
              is RejectTarget.Checkin -> {
                state.busyCheckin = target.id
                Api.rejectCheckin(token, target.id, reason)
                state.toast = "已驳回打卡"
              }
            }
          } catch (e: Exception) {
            state.toast = e.message ?: "驳回失败"
          } finally {
            state.busyVerify = null
            state.busyCheckin = null
            state.dialog = null
          }
          load()
        }
      },
    )
  }

  // 一次性提示
  val toast = state.toast
  if (toast != null) {
    LaunchedEffect(toast) {
      Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
      state.toast = null
    }
  }
}

// ---------------- 子卡片 ----------------

/** 顶部对方卡片：昵称 + 头像 + 连续天数 + 本月得分 + 今日打卡进度 */
@Composable
private fun PartnerHeaderCard(page: PartnerPageResponse) {
  val p = page.partner
  val stats = page.partnerStats
  val today = page.today
  val name = p?.nickname ?: "ta"
  val initial = name.take(1)

  PixelPanel(modifier = Modifier.fillMaxWidth(), background = PixelColors.GreenSoft) {
    Column(modifier = Modifier.padding(16.dp)) {
      val checked = today?.checked ?: 0
      val expected = today?.expected ?: 0
      Row(verticalAlignment = Alignment.CenterVertically) {
        // 头像：后端返回的 avatar emoji（与 Web 一致），空则兜底首字
        val avatarEmoji = p?.avatar.orEmpty()
        Box(
          modifier = Modifier
            .size(48.dp)
            .background(PixelColors.Card)
            .pixelBorder(2.dp, PixelColors.Outline),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            avatarEmoji.ifBlank { initial },
            style = PixelType.Title,
          )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(name, style = PixelType.Title, color = PixelColors.Ink)
          Text(
            text = "连续 ${stats?.currentStreak ?: 0} 天 · 本月 ${stats?.monthPoints ?: 0} 分",
            style = PixelType.Small,
            color = PixelColors.Ink2,
          )
        }
        // 右侧：今日打卡 N/₂ 大数字（对齐 Web 版）
        Column(horizontalAlignment = Alignment.End) {
          Row(verticalAlignment = Alignment.Bottom) {
            Text(checked.toString(), style = PixelType.Big, color = PixelColors.Ink)
            Text("/$expected", style = PixelType.Small, color = PixelColors.Ink3)
          }
          Text("今日打卡", style = PixelType.Small, color = PixelColors.Ink3)
        }
      }

      Spacer(Modifier.height(12.dp))

      if (expected > 0) {
        PixelBar(
          fraction = checked.toFloat() / expected.toFloat(),
          modifier = Modifier.fillMaxWidth(),
          color = PixelColors.Green,
        )
      }
    }
  }
}

/** 并排对比：我 vs 对方，各显示本月得分、连续、完成率、时间投入、余额 */
@Composable
private fun ComparePanel(page: PartnerPageResponse, myAvatar: String) {
  val my = page.myStats
  val ta = page.partnerStats
  val taName = page.partner?.nickname ?: "ta"
  val taAvatar = page.partner?.avatar.orEmpty()

  Column {
    SectionHeadPs("并排看看", "不比输赢")
    Spacer(Modifier.height(8.dp))
    PixelPanel(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = (myAvatar.ifBlank { "" } + " 我").trim(),
              style = PixelType.Body,
              color = PixelColors.GreenDeep,
            )
            StatsColumn(my)
          }
          Spacer(Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = (taAvatar.ifBlank { "" } + " " + taName).trim(),
              style = PixelType.Body,
              color = PixelColors.OrangeDeep,
            )
            StatsColumn(ta)
          }
        }
      }
    }
  }
}

/** 「我们」页的板块标题：■ 前缀 + 右侧说明（与 Screens.kt 的 SectionHead 同款） */
@Composable
private fun SectionHeadPs(title: String, note: String) {
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
    if (note.isNotBlank()) {
      Text(note, style = PixelType.Small, color = PixelColors.Ink2)
    }
  }
}

@Composable
private fun StatsColumn(s: UserStats?) {
  val completion = s?.completion
  val completionText = if (completion == null) "—" else "$completion%"
  StatLine("本月得分", s?.monthPoints?.let { "+" + fmtThousand(it) } ?: "—")
  StatLine("连续天数", s?.currentStreak?.let { "$it 天" } ?: "—")
  StatLine("完成率", completionText)
  StatLine("时间投入", humanMinutes(s?.timeInvestMin ?: 0))
  StatLine("当前余额", s?.balance?.let { fmtThousand(it) } ?: "—")
}

/** 千分位数字（对齐 Web 的 1,028 样式） */
private fun fmtThousand(n: Int): String {
  val neg = n < 0
  val digits = kotlin.math.abs(n).toString()
  val sb = StringBuilder()
  for ((i, ch) in digits.withIndex()) {
    if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
    sb.append(ch)
  }
  return (if (neg) "-" else "") + sb.toString()
}

@Composable
private fun StatLine(label: String, value: String) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = PixelType.Small, color = PixelColors.Ink3)
    // maxLines=1 + softWrap=false：值再长也不换行挤爆两列布局（截图里"11 小时 45 分"换行成 3 行）
    Text(
      value,
      style = PixelType.Mono,
      color = PixelColors.Ink,
      maxLines = 1,
      softWrap = false,
    )
  }
}

/** 待我验收：对方提交的小目标验收 */
@Composable
private fun VerificationPanel(
  items: List<PendingVerification>,
  busyId: Long?,
  onApprove: (PendingVerification) -> Unit,
  onReject: (PendingVerification) -> Unit,
) {
  PixelPanel(modifier = Modifier.fillMaxWidth(), background = PixelColors.OrangeSoft) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text("待我验收", style = PixelType.Title, color = PixelColors.Ink)
      Spacer(Modifier.height(10.dp))

      for (v in items) {
        Spacer(Modifier.height(8.dp))
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(PixelColors.Card)
            .pixelBorder(2.dp, PixelColors.Outline)
            .padding(12.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v.goal_emoji, style = PixelType.Title)
            Spacer(Modifier.width(8.dp))
            Text(
              text = v.goal_title,
              style = PixelType.Body,
              color = PixelColors.Ink,
              modifier = Modifier.weight(1f),
            )
          }
          Spacer(Modifier.height(6.dp))
          val criteria = v.accept_criteria
          if (!criteria.isNullOrBlank()) {
            Text("验收标准：$criteria", style = PixelType.Small, color = PixelColors.Ink2)
          }
          Text(
            text = "提交人：${v.submitter_nickname} · +${v.total_points ?: 0} 分",
            style = PixelType.Small,
            color = PixelColors.Ink3,
          )
          Spacer(Modifier.height(10.dp))
          val busy = busyId == v.id
          Row {
            PixelSmallButton(
              text = if (busy) "处理中…" else "通过",
              filled = true,
              onClick = { if (!busy) onApprove(v) },
            )
            Spacer(Modifier.width(8.dp))
            PixelSmallButton(text = "驳回", filled = false, onClick = { onReject(v) })
          }
        }
      }
    }
  }
}

/** 对方今天的打卡：emoji + 目标名 + 分值 + 时长 + 驳回按钮 */
@Composable
private fun PartnerTodayPanel(
  items: List<PartnerCheckin>,
  busyId: Long?,
  onReject: (PartnerCheckin) -> Unit,
) {
  PixelPanel(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("ta 今天的打卡", style = PixelType.Title, color = PixelColors.Ink)
        Text("${items.size} 条", style = PixelType.Mono, color = PixelColors.Ink3)
      }
      Spacer(Modifier.height(10.dp))

      if (items.isEmpty()) {
        Text("ta 今天还没有打卡", style = PixelType.Small, color = PixelColors.Ink3)
        return@Column
      }

      for (c in items) {
        Spacer(Modifier.height(8.dp))
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .dashedDivider()
            .padding(vertical = 6.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.goalEmoji, style = PixelType.Title)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(c.goalTitle, style = PixelType.Body, color = PixelColors.Ink)
              Text(
                text = "+${c.points} 分 · ${c.durationMin} 分钟",
                style = PixelType.Small,
                color = PixelColors.Ink2,
              )
            }
            when (c.status) {
              "credited" -> {
                val busy = busyId == c.id
                PixelSmallButton(
                  text = if (busy) "…" else "驳回",
                  filled = false,
                  onClick = { if (!busy) onReject(c) },
                )
              }
              "rejected" -> PixelChip(text = "已驳回", bg = PixelColors.OrangeSoft, fg = PixelColors.OrangeDeep)
              "revoked" -> PixelChip(text = "已撤销", bg = PixelColors.OrangeSoft, fg = PixelColors.OrangeDeep)
              else -> PixelChip(text = c.status, bg = PixelColors.OrangeSoft, fg = PixelColors.OrangeDeep)
            }
          }
        }
      }
    }
  }
}

/** ta 的目标：进行中目标，emoji + 标题 + 频率 + 每次分值 */
@Composable
private fun PartnerGoalsPanel(goals: List<PartnerGoalItem>) {
  val active = goals.filter { it.status == "active" || it.status == "reviewing" }

  PixelPanel(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text("ta 的目标", style = PixelType.Title, color = PixelColors.Ink)
      Spacer(Modifier.height(10.dp))

      if (active.isEmpty()) {
        Text("ta 还没有进行中的目标", style = PixelType.Small, color = PixelColors.Ink3)
        return@Column
      }

      for (g in active) {
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .dashedDivider()
            .padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(g.emoji, style = PixelType.Title)
          Spacer(Modifier.width(8.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(g.title, style = PixelType.Body, color = PixelColors.Ink)
            Text(
              text = "${freqLabel(g.freq_mode, g.freq_n)} · 每次 +${g.perCheckinPoints} 分",
              style = PixelType.Small,
              color = PixelColors.Ink2,
            )
          }
          if (g.streak > 0) {
            Text("🔥${g.streak}", style = PixelType.Mono, color = PixelColors.OrangeDeep)
          }
        }
      }
    }
  }
}

/** 频率文案：daily / weekly / weekdays */
private fun freqLabel(mode: String, n: Int): String = when (mode) {
  "daily" -> "每天"
  "weekly" -> "每周 $n 次"
  "weekdays" -> "工作日"
  else -> mode
}

/** 手机时长对比：ta 今日总时长 vs 我今日总时长 */
@Composable
private fun UsageComparePanel(
  context: Context,
  granted: Boolean,
  onOpenUsageSettings: () -> Unit,
) {
  var my by remember { mutableStateOf<UsageListResponse?>(null) }
  var ta by remember { mutableStateOf<PartnerResponse?>(null) }

  LaunchedEffect(Unit) {
    val token = Session.token(context)
    if (token == null) return@LaunchedEffect
    try {
      my = Api.getMyToday(token)
    } catch (e: Exception) {
      // 拿不到就留空
    }
    try {
      ta = Api.getPartner(token)
    } catch (e: Exception) {
      // 留空
    }
  }

  Column {
    SectionHeadPs("手机在谁手里", "今日")
    Spacer(Modifier.height(8.dp))
    PixelPanel(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp)) {
        // 上半：双方总时长 大数字 + 进度条（中间竖虚线）
        Row(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.weight(1f)) {
            Text("我", style = PixelType.Small, color = PixelColors.Ink3)
            Spacer(Modifier.height(2.dp))
            if (!granted) {
              // 未授权：在我的半列里给引导（不开权限 ta 就看不到我的时长）
              Text("还没开启使用情况访问", style = PixelType.Small, color = PixelColors.OrangeDeep)
              Spacer(Modifier.height(4.dp))
              Text("开启后 ta 才能看到你的手机时长", style = PixelType.Small, color = PixelColors.Ink2)
              Spacer(Modifier.height(8.dp))
              PixelSmallButton(text = "去开启", onClick = onOpenUsageSettings)
            } else {
              Text(
                text = my?.let { AppViewModel.humanDuration(it.totalSeconds) } ?: "—",
                style = PixelType.Big,
                color = PixelColors.GreenDeep,
              )
              Spacer(Modifier.height(6.dp))
              val maxSec = maxOf(my?.totalSeconds ?: 0L, ta?.totalSeconds ?: 0L, 1L)
              PixelBar(
                fraction = (my?.totalSeconds ?: 0L).toFloat() / maxSec,
                modifier = Modifier.fillMaxWidth(),
                color = PixelColors.Green,
              )
              Spacer(Modifier.height(4.dp))
              Text("${(my?.totalSeconds ?: 0L) / 60} 分钟", style = PixelType.Small, color = PixelColors.Ink2)
            }
          }
          Spacer(Modifier.width(12.dp))
          Box(modifier = Modifier.width(2.dp).height(80.dp).dashedDividerV())
          Spacer(Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(ta?.partner?.nickname ?: "ta", style = PixelType.Small, color = PixelColors.Ink3)
            Spacer(Modifier.height(2.dp))
            Text(
              text = ta?.let { AppViewModel.humanDuration(it.totalSeconds) } ?: "—",
              style = PixelType.Big,
              color = PixelColors.OrangeDeep,
            )
            Spacer(Modifier.height(6.dp))
            val maxSec = maxOf(my?.totalSeconds ?: 0L, ta?.totalSeconds ?: 0L, 1L)
            PixelBar(
              fraction = (ta?.totalSeconds ?: 0L).toFloat() / maxSec,
              modifier = Modifier.fillMaxWidth(),
              color = PixelColors.Orange,
            )
            Spacer(Modifier.height(4.dp))
            Text("${(ta?.totalSeconds ?: 0L) / 60} 分钟", style = PixelType.Small, color = PixelColors.Ink2)
          }
        }

        Spacer(Modifier.height(12.dp))

        // 下半：双方 Top5 App 并排（我绿条 / ta 橙条）
        Row(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.weight(1f)) {
            Text("我", style = PixelType.Small, color = PixelColors.Ink3)
            Spacer(Modifier.height(6.dp))
            val myApps = (my?.apps ?: emptyList()).sortedByDescending { it.seconds }.take(5)
            if (myApps.isEmpty()) {
              Text("暂无数据", style = PixelType.Small, color = PixelColors.Ink3)
            }
            val maxMy = myApps.firstOrNull()?.seconds ?: 1L
            for (a in myApps) {
              AppRowMini(a, maxMy, PixelColors.Green)
              Spacer(Modifier.height(6.dp))
            }
          }
          Spacer(Modifier.width(12.dp))
          Box(modifier = Modifier.width(2.dp).height(200.dp).dashedDividerV())
          Spacer(Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(ta?.partner?.nickname ?: "ta", style = PixelType.Small, color = PixelColors.Ink3)
            Spacer(Modifier.height(6.dp))
            val taApps = (ta?.apps ?: emptyList()).sortedByDescending { it.seconds }.take(5)
            if (taApps.isEmpty()) {
              Text("暂无数据", style = PixelType.Small, color = PixelColors.Ink3)
            }
            val maxTa = taApps.firstOrNull()?.seconds ?: 1L
            for (a in taApps) {
              AppRowMini(a, maxTa, PixelColors.Orange)
              Spacer(Modifier.height(6.dp))
            }
          }
        }

        Spacer(Modifier.height(8.dp))

        // 底部：ta 最近同步时间
        val sync = ta?.lastReportAt
        if (sync != null) {
          val stale = ta?.staleMinutes ?: 0
          val staleTxt = when {
            stale <= 0 -> "刚刚"
            stale < 60 -> "$stale 分钟前"
            else -> "${stale / 60} 小时前"
          }
          Text(
            text = "🕐 ta 最近一次同步于 ${AppViewModel.clockOf(sync)} · $staleTxt",
            style = PixelType.Small,
            color = PixelColors.Ink2,
          )
        }
      }
    }
  }
}

/** 迷你 App 行：名字 + 秒数 + 色条（「我们」页手机对比用） */
@Composable
private fun AppRowMini(a: AppUsage, maxSeconds: Long, color: Color) {
  Column {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        a.appName.ifBlank { a.packageName },
        style = PixelType.Small,
        color = PixelColors.Ink,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.weight(1f, fill = false),
      )
      Spacer(Modifier.width(6.dp))
      Text(
        text = AppViewModel.humanDuration(a.seconds),
        style = PixelType.Small,
        color = PixelColors.Ink2,
      )
    }
    Spacer(Modifier.height(2.dp))
    PixelBar(
      fraction = a.seconds.toFloat() / maxSeconds.coerceAtLeast(1L),
      modifier = Modifier.fillMaxWidth(),
      color = color,
    )
  }
}

/** 竖向虚线分隔（「我们」页手机对比的中间分隔） */
fun Modifier.dashedDividerV(color: Color = PixelColors.LineStrong): Modifier = drawBehind {
  val dash = 6.dp.toPx()
  val gap = 4.dp.toPx()
  val w = 2.dp.toPx()
  var y = 0f
  while (y < size.height) {
    drawRect(
      color = color,
      topLeft = Offset(0f, y),
      size = Size(w, minOf(dash, size.height - y)),
    )
    y += dash + gap
  }
}

/** 分钟 → 可读时长（时间投入用） */
private fun humanMinutes(min: Int): String {
  if (min <= 0) return "0 小时"
  // Web 版格式："4.3 小时"（一位小数小时）
  return String.format("%.1f 小时", min / 60.0)
}

// ---------------- 驳回理由弹层 ----------------

@Composable
private fun RejectDialog(
  target: RejectTarget,
  busy: Boolean,
  onDismiss: () -> Unit,
  onSubmit: (String) -> Unit,
) {
  val title = when (target) {
    is RejectTarget.Verify -> "驳回验收"
    is RejectTarget.Checkin -> "驳回打卡"
  }
  var reason by remember { mutableStateOf("") }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp)
        .background(PixelColors.Card)
        .pixelBorder(2.dp, PixelColors.Outline)
        .pixelHardShadow(4.dp, PixelColors.Outline),
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(title, style = PixelType.Title, color = PixelColors.Ink)
        Spacer(Modifier.height(8.dp))
        Text("说说驳回的理由（1–200 字）", style = PixelType.Small, color = PixelColors.Ink3)
        Spacer(Modifier.height(10.dp))
        PixelTextField(
          value = reason,
          onValueChange = { reason = it },
          placeholder = "比如：证据不足 / 时长不对",
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
          PixelSmallButton(text = "取消", filled = false, onClick = onDismiss)
          Spacer(Modifier.width(8.dp))
          PixelSmallButton(
            text = if (busy) "提交中…" else "提交",
            filled = true,
            onClick = { onSubmit(reason.trim()) },
          )
        }
      }
    }
  }
}

/** ta 最近的兑换（对齐 Web 版 partner 页的「ta 最近的兑换」板块） */
@Composable
private fun PartnerRedemptionsPanel(reds: List<Redemption>) {
  PixelPanel(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text("ta 最近的兑换", style = PixelType.Title, color = PixelColors.Ink)
      Spacer(Modifier.height(10.dp))
      for (r in reds.take(5)) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .dashedDivider()
            .padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(r.item_emoji, style = PixelType.Title)
          Spacer(Modifier.width(8.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(r.item_title, style = PixelType.Body, color = PixelColors.Ink)
            Text(
              text = "${r.points_locked} 分",
              style = PixelType.Small,
              color = PixelColors.Ink2,
            )
          }
        }
      }
    }
  }
}
