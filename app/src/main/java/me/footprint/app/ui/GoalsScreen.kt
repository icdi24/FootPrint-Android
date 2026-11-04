package me.footprint.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.footprint.app.net.Api
import me.footprint.app.net.ApiException
import me.footprint.app.net.CheckinResult
import me.footprint.app.net.GoalDetail
import me.footprint.app.net.GoalPayload
import me.footprint.app.net.MonthView
import me.footprint.app.net.Session
import me.footprint.app.net.WeekCell
import me.footprint.app.net.WeekGoal
import me.footprint.app.net.WeekView
import me.footprint.app.ui.theme.PixelColors
import me.footprint.app.ui.theme.PixelType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 目标页：周/月视图网格 + 新建/编辑目标 + 打卡 + 放弃。
 *
 * 状态自己用 remember 管，直接调 Api（token 从 Session 拿）。
 * 周视图 start 取「本周周一」，月视图 month 取 yyyy-MM，都用上海时区。
 * 小目标（stage）只展示，验收提交留给「我们」页对方那里处理。
 */

/** 周视图网格一格的边长，格子要能点得动又不能太挤 */
private val CELL = 32.dp

/** 上海时区，算周/月边界统一用它 */
private val ZONE = ZoneId.of("Asia/Shanghai")

/** 周一到周日的单字标题 */
private val WD_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 常用 emoji，新建目标时点选 */
private val EMOJI_OPTIONS = listOf("🎯", "📖", "🏃", "💪", "🧘", "💧", "🌙", "🎨", "🎹", "✍️")

/** 频率选项：mode / 文案 */
private val FREQ_OPTIONS = listOf(
  Triple("daily", "每天", null),
  Triple("weekly", "每周N次", 3),
  Triple("weekdays", "指定星期", 3),
)

/** 难度 1-5 的文案 */
private val DIFFICULTY_LABELS = listOf("1 轻松", "2 简单", "3 中等", "4 较难", "5 挑战")

@Composable
fun GoalsScreen(vm: AppViewModel) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // ---------------- 页面状态 ----------------

  // 0 = 日常目标，1 = 小目标（对齐 Web 版的「日常目标 / 小目标」切换）
  var mode by remember { mutableStateOf(0) }
  // 日常目标内：0 = 周视图，1 = 月视图
  var tab by remember { mutableStateOf(0) }
  // 周视图锚点（默认本周周一，可前后翻页）；月视图锚点（默认本月）
  var weekAnchor by remember { mutableStateOf(LocalDate.now(ZONE).with(DayOfWeek.MONDAY)) }
  var monthAnchor by remember { mutableStateOf(YearMonth.now(ZONE)) }
  var week by remember { mutableStateOf<WeekView?>(null) }
  var month by remember { mutableStateOf<MonthView?>(null) }
  // 小目标列表
  var stageGoals by remember { mutableStateOf<List<GoalDetail>?>(null) }
  var loading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  // 编辑弹层
  var editing by remember { mutableStateOf<GoalDetail?>(null) }
  var showEditor by remember { mutableStateOf(false) }

  // 打卡确认弹层
  var checkinGoal by remember { mutableStateOf<WeekGoal?>(null) }
  var checkinDate by remember { mutableStateOf<String?>(null) }

  // 放弃确认弹层（id + 标题即可）
  var abandonTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }

  // 一次性提示
  var toast by remember { mutableStateOf<String?>(null) }

  // ---------------- 加载数据 ----------------

  fun reload() {
    val token = Session.token(context) ?: return
    scope.launch {
      loading = true
      error = null
      try {
        if (mode == 1) {
          // 小目标：走 /api/goals?type=stage
          stageGoals = Api.listGoals(token, "stage")
        } else if (tab == 0) {
          week = Api.getWeek(token, weekAnchor.format(DateTimeFormatter.ISO_LOCAL_DATE))
        } else {
          month = Api.getMonth(token, monthAnchor.format(DateTimeFormatter.ofPattern("yyyy-MM")))
        }
      } catch (e: ApiException) {
        error = e.message ?: "加载失败"
      } catch (e: Exception) {
        error = e.message ?: "加载失败"
      } finally {
        loading = false
      }
    }
  }

  LaunchedEffect(mode, tab, weekAnchor, monthAnchor) {
    reload()
  }

  // ---------------- 动作 ----------------

  fun doCheckin(goalId: Long, date: String, durationMin: Int?, evidenceText: String?) {
    val token = Session.token(context) ?: return
    scope.launch {
      try {
        val r: CheckinResult = Api.checkin(token, goalId, durationMin, evidenceText)
        val bonus = if (r.bonuses.isNotEmpty()) "（${r.bonuses.joinToString("、")}）" else ""
        toast = "打卡成功 +${r.points} 分$bonus"
        checkinGoal = null
        checkinDate = null
        reload()
      } catch (e: ApiException) {
        toast = e.message ?: "打卡失败"
      } catch (e: Exception) {
        toast = e.message ?: "打卡失败"
      }
    }
  }

  fun doAbandon(goalId: Long) {
    val token = Session.token(context) ?: return
    scope.launch {
      try {
        Api.abandonGoal(token, goalId)
        toast = "已放弃该目标"
        abandonTarget = null
        reload()
      } catch (e: ApiException) {
        toast = e.message ?: "操作失败"
      } catch (e: Exception) {
        toast = e.message ?: "操作失败"
      }
    }
  }

  // 小目标：草稿 → 进行中
  fun doStartStage(goalId: Long) {
    val token = Session.token(context) ?: return
    scope.launch {
      try {
        Api.startGoal(token, goalId)
        toast = "已开始，加油 🌱"
        reload()
      } catch (e: ApiException) {
        toast = e.message ?: "操作失败"
      } catch (e: Exception) {
        toast = e.message ?: "操作失败"
      }
    }
  }

  // 小目标：提交验收
  fun doSubmitStage(goalId: Long) {
    val token = Session.token(context) ?: return
    scope.launch {
      try {
        Api.submitGoal(token, goalId, "我完成了，请验收")
        toast = "已提交验收，等对方处理"
        reload()
      } catch (e: ApiException) {
        toast = e.message ?: "提交失败"
      } catch (e: Exception) {
        toast = e.message ?: "提交失败"
      }
    }
  }

  // ---------------- 页面 ----------------

  Screen {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        // 标题
        Text("目标", style = PixelType.Big, color = PixelColors.Ink)

      Spacer(Modifier.height(12.dp))

      // 日常目标 / 小目标 切换（对齐 Web 版：居中窄条）
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
      ) {
        PixelSmallButton(
          text = "日常目标",
          onClick = { mode = 0 },
          filled = mode == 0,
        )
        Spacer(Modifier.width(8.dp))
        PixelSmallButton(
          text = "小目标",
          onClick = { mode = 1 },
          filled = mode == 1,
        )
      }

      // 日常目标内再有「周 / 月」切换
      if (mode == 0) {
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.Center,
        ) {
          PixelSmallButton(
            text = "周",
            onClick = { tab = 0 },
            filled = tab == 0,
          )
          Spacer(Modifier.width(8.dp))
          PixelSmallButton(
            text = "月",
            onClick = { tab = 1 },
            filled = tab == 1,
          )
        }

        // 翻页导航：‹ 上一段 / 下一段 ›
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          PixelSmallButton(
            text = "‹",
            filled = false,
            onClick = {
              if (tab == 0) weekAnchor = weekAnchor.minusWeeks(1)
              else monthAnchor = monthAnchor.minusMonths(1)
            },
          )
          Text(
            text = if (tab == 0) {
              val wk = weekAnchor.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
              "${weekAnchor.monthValue}月 第$wk 周"
            } else {
              "${monthAnchor.year}年${monthAnchor.monthValue}月"
            },
            style = PixelType.Mono,
            color = PixelColors.Ink,
          )
          PixelSmallButton(
            text = "›",
            filled = false,
            onClick = {
              if (tab == 0) weekAnchor = weekAnchor.plusWeeks(1)
              else monthAnchor = monthAnchor.plusMonths(1)
            },
          )
        }
      }

      Spacer(Modifier.height(12.dp))

      // 提示 / 错误
      if (toast != null) {
        PixelChip(text = toast.orEmpty(), bg = PixelColors.GreenSoft, fg = PixelColors.GreenDeep)
        Spacer(Modifier.height(8.dp))
      }
      if (error != null) {
        PixelChip(text = error.orEmpty(), bg = PixelColors.OrangeSoft, fg = PixelColors.OrangeDeep)
        Spacer(Modifier.height(8.dp))
      }

      // 主体
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState()),
      ) {
        if (mode == 1) {
          StageListView(
            goals = stageGoals,
            loading = loading,
            onNew = {
              editing = null
              showEditor = true
            },
            onEdit = { g ->
              editing = g
              showEditor = true
            },
            onStart = { g -> doStartStage(g.id) },
            onSubmit = { g -> doSubmitStage(g.id) },
            onAbandon = { g ->
              abandonTarget = g.id to g.title
            },
          )
        } else if (tab == 0) {
          WeekView(
            week = week,
            loading = loading,
            onCellClick = { goal, cell ->
              // 仅 today / todo 可打卡；today 由 date == week.today 判断（状态仍是 todo）
              if (cell.state == "todo") {
                checkinGoal = goal
                checkinDate = cell.date
              }
            },
          )
        } else {
          MonthView(month = month, loading = loading)
        }
        Spacer(Modifier.height(24.dp))
      }
      }

      // 右下角 FAB：新建目标（对齐 Web 的浮动 + 号）
      Box(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(20.dp),
      ) {
        Box(
          modifier = Modifier
            .size(64.dp)
            .pixelHardShadow(4.dp, PixelColors.Outline)
            .background(PixelColors.Green)
            .pixelBorder(2.dp, PixelColors.Outline)
            .clickable { editing = null; showEditor = true },
          contentAlignment = Alignment.Center,
        ) {
          Text("+", style = PixelType.Huge, color = PixelColors.White)
        }
      }
    }
  }

  // ---------------- 弹层 ----------------

  if (showEditor) {
    GoalEditorDialog(
      existing = editing,
      onDismiss = { showEditor = false },
      onSaved = {
        showEditor = false
        reload()
      },
      onAbandon = { goal ->
        showEditor = false
        abandonTarget = goal.id to goal.title
      },
      defaultType = if (mode == 1) "stage" else "daily",
    )
  }

  if (checkinGoal != null) {
    CheckinDialog(
      goal = checkinGoal!!,
      date = checkinDate,
      onDismiss = {
        checkinGoal = null
        checkinDate = null
      },
      onConfirm = { durationMin, evidence ->
        doCheckin(checkinGoal!!.id, checkinDate.orEmpty(), durationMin, evidence)
      },
    )
  }

  if (abandonTarget != null) {
    ConfirmDialog(
      title = "放弃目标",
      message = "确定要放弃「${abandonTarget!!.second}」吗？放弃后无法恢复打卡记录。",
      confirmText = "放弃",
      danger = true,
      onDismiss = { abandonTarget = null },
      onConfirm = { doAbandon(abandonTarget!!.first) },
    )
  }
}

// ==================== 周视图 ====================

@Composable
private fun WeekView(
  week: WeekView?,
  loading: Boolean,
  onCellClick: (WeekGoal, WeekCell) -> Unit,
) {
  if (loading && week == null) {
    Text("加载中…", style = PixelType.Body, color = PixelColors.Ink3)
    return
  }
  val w = week ?: run {
    Text("暂无周数据", style = PixelType.Body, color = PixelColors.Ink3)
    return
  }

  // 顶部统计三联卡（对齐 Web：周得分 / 打卡人次 / 进行中目标）
  PixelPanel(
    modifier = Modifier.fillMaxWidth(),
    background = PixelColors.Bg2,
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(
        text = "${w.weekStart} ~ ${w.weekEnd}",
        style = PixelType.Small,
        color = PixelColors.Ink3,
      )
      Spacer(Modifier.height(6.dp))
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Metric(text = "周得分", value = w.weekPoints.toString(), color = PixelColors.Gold)
        Metric(text = "打卡人次", value = "${w.weekDone}/${w.weekExpected}", color = PixelColors.Green)
        Metric(text = "进行中目标", value = w.goals.count { it.status == "active" }.toString(), color = PixelColors.Green)
      }
    }
  }

  Spacer(Modifier.height(12.dp))

  if (w.goals.isEmpty()) {
    PixelPanel(modifier = Modifier.fillMaxWidth()) {
      Text(
        text = "还没有目标，点右下角「+」创建一个吧",
        style = PixelType.Small,
        color = PixelColors.Ink3,
        modifier = Modifier.padding(12.dp),
      )
    }
    return
  }

  // 表格式周网格（对齐 Web）：一行一个目标 = 名称 | 分数 | 一~日
  PixelPanel(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(10.dp)) {
      // 表头
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("名称", style = PixelType.Small, color = PixelColors.Ink3, modifier = Modifier.weight(1f))
        Text("分数", style = PixelType.Small, color = PixelColors.Ink3, modifier = Modifier.width(40.dp))
        WD_LABELS.forEach { label ->
          Text(
            label,
            style = PixelType.Small,
            color = PixelColors.Ink3,
            modifier = Modifier.width(CELL),
            textAlign = TextAlign.Center,
          )
        }
      }
      Spacer(Modifier.height(6.dp))
      // 数据行
      w.goals.forEach { goal ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .dashedDivider()
            .padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(goal.emoji, style = PixelType.Body)
            Spacer(Modifier.width(4.dp))
            Text(
              goal.title,
              style = PixelType.Body,
              color = PixelColors.Ink,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier.weight(1f, fill = false),
            )
          }
          Text(
            goal.perCheckinPoints.toString(),
            style = PixelType.Mono,
            color = PixelColors.Ink2,
            modifier = Modifier.width(40.dp),
          )
          WD_LABELS.forEachIndexed { i, _ ->
            val cell = goal.cells.getOrNull(i)
            if (cell == null) {
              Box(
                modifier = Modifier
                  .size(CELL - 4.dp)
                  .background(PixelColors.Line)
                  .pixelBorder(1.dp, PixelColors.LineStrong),
              )
            } else {
              CellBox(cell = cell, today = w.today, clickable = goal.type != "stage", onClick = { onCellClick(goal, cell) })
            }
          }
        }
      }
      Spacer(Modifier.height(8.dp))
      // 图例（对齐 Web：✓已打 / □漏了 / □今天 三个图形图例，居中）
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // 已打
        Box(
          modifier = Modifier
            .size(16.dp)
            .background(PixelColors.Green)
            .pixelBorder(1.dp, PixelColors.GreenDeep),
          contentAlignment = Alignment.Center,
        ) {
          Text("✓", style = PixelType.Small, color = PixelColors.White)
        }
        Spacer(Modifier.width(4.dp))
        Text("已打", style = PixelType.Small, color = PixelColors.Ink2)
        Spacer(Modifier.width(10.dp))
        // 漏了
        Box(
          modifier = Modifier
            .size(16.dp)
            .background(PixelColors.Card)
            .pixelBorder(1.dp, PixelColors.LineStrong),
        )
        Spacer(Modifier.width(4.dp))
        Text("漏了", style = PixelType.Small, color = PixelColors.Ink2)
        Spacer(Modifier.width(10.dp))
        // 今天
        Box(
          modifier = Modifier
            .size(16.dp)
            .background(PixelColors.Card)
            .pixelBorder(2.dp, PixelColors.Orange),
        )
        Spacer(Modifier.width(4.dp))
        Text("今天", style = PixelType.Small, color = PixelColors.Ink2)
      }
    }
  }
}

@Composable
private fun Metric(text: String, value: String, color: Color) {
  Column {
    Text(text = text, style = PixelType.Small, color = PixelColors.Ink3)
    Text(text = value, style = PixelType.Mono, color = color)
  }
}

@Composable
private fun CellBox(
  cell: WeekCell,
  today: String,
  clickable: Boolean,
  onClick: () -> Unit,
) {
  // 状态 → 颜色（对齐 Web：已打=绿底白勾，漏了=白底空框，今天=白底橙框，未来=白底浅框）
  val bg: Color
  val border: Color
  when (cell.state) {
    "done" -> {
      bg = PixelColors.Green
      border = PixelColors.GreenDeep
    }
    "missed" -> {
      bg = PixelColors.Card
      border = PixelColors.LineStrong
    }
    "todo" -> {
      bg = PixelColors.Card
      border = PixelColors.Orange
    }
    else -> {
      bg = PixelColors.Card
      border = PixelColors.LineStrong
    }
  }

  val isToday = cell.date == today
  val canClick = clickable && cell.state == "todo"

  Box(
    modifier = Modifier
      .size(CELL)
      .background(bg)
      .pixelBorder(2.dp, border)
      .then(if (canClick) Modifier.clickable(onClick = onClick) else Modifier),
    contentAlignment = Alignment.Center,
  ) {
    // 已打 = 绿底白勾；漏了/未来/今天未打 = 空框（对齐 Web，不显示分数和 ✗）
    if (cell.state == "done") {
      Text("✓", style = PixelType.Body, color = PixelColors.White)
    }
  }
}

// ==================== 月视图 ====================

@Composable
private fun MonthView(month: MonthView?, loading: Boolean) {
  if (loading && month == null) {
    Text("加载中…", style = PixelType.Body, color = PixelColors.Ink3)
    return
  }
  val m = month ?: run {
    Text("暂无月数据", style = PixelType.Body, color = PixelColors.Ink3)
    return
  }

  // 顶部统计三联卡（对齐 Web：月得分 / 打卡人次 / 进行中目标）
  PixelPanel(
    modifier = Modifier.fillMaxWidth(),
    background = PixelColors.Bg2,
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(
        text = "${m.month}",
        style = PixelType.Small,
        color = PixelColors.Ink3,
      )
      Spacer(Modifier.height(6.dp))
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Metric(text = "月得分", value = m.monthPoints.toString(), color = PixelColors.Gold)
        Metric(text = "打卡人次", value = "${m.monthDone}/${m.monthExpected}", color = PixelColors.Green)
        Metric(text = "进行中目标", value = m.goals.count { it.id > 0 }.toString(), color = PixelColors.Green)
      }
    }
  }

  Spacer(Modifier.height(12.dp))

  if (m.goals.isEmpty()) {
    PixelPanel(modifier = Modifier.fillMaxWidth()) {
      Text(
        text = "还没有目标，点右下角「+」创建一个吧",
        style = PixelType.Small,
        color = PixelColors.Ink3,
        modifier = Modifier.padding(12.dp),
      )
    }
    return
  }

  // 表格式月视图（对齐 Web）：一行一个目标 = 标题 | N 次 · N 分 + 下方每天小方块
  PixelPanel(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(10.dp)) {
      m.goals.forEach { goal ->
        // 行1：emoji + 标题 …… 右侧 N 次 · N 分
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(goal.emoji, style = PixelType.Body)
          Spacer(Modifier.width(6.dp))
          Text(goal.title, style = PixelType.Body, color = PixelColors.Ink, modifier = Modifier.weight(1f))
          val doneCount = goal.days.count { it.done }
          val totalPoints = goal.days.sumOf { it.points }
          Text(
            text = "${doneCount} 次 · ${totalPoints} 分",
            style = PixelType.Small,
            color = PixelColors.Ink2,
          )
        }
        Spacer(Modifier.height(4.dp))
        // 行2：每天小方块（绿=已打，橙框=今天，白=未打/未来）
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
          goal.days.forEach { day ->
            val isToday = day.date == m.today
            val bg = if (day.done) PixelColors.Green else PixelColors.Card
            val border = when {
              isToday -> PixelColors.Orange
              day.done -> PixelColors.GreenDeep
              else -> PixelColors.LineStrong
            }
            Box(
              modifier = Modifier
                .size(14.dp)
                .background(bg)
                .pixelBorder(if (isToday) 2.dp else 1.dp, border),
            )
            Spacer(Modifier.width(3.dp))
          }
        }
        Spacer(Modifier.height(8.dp))
        if (goal !== m.goals.last()) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(2.dp)
              .dashedDivider(),
          )
        }
      }
    }
  }
}

@Composable
private fun GoalEditorDialog(
  existing: GoalDetail?,
  onDismiss: () -> Unit,
  onSaved: () -> Unit,
  onAbandon: (GoalDetail) -> Unit,
  defaultType: String = "daily",
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // 表单字段
  var title by remember { mutableStateOf(existing?.title.orEmpty()) }
  var emoji by remember { mutableStateOf(existing?.emoji ?: "🎯") }
  var type by remember { mutableStateOf(existing?.type ?: defaultType) }
  var difficulty by remember { mutableStateOf(existing?.difficulty ?: 3) }
  var durationMin by remember { mutableStateOf((existing?.duration_min ?: 30).toString()) }
  var freqMode by remember { mutableStateOf(existing?.freq_mode ?: "daily") }
  var freqN by remember { mutableStateOf((existing?.freq_n ?: 1).toString()) }
  var weekdays by remember { mutableStateOf(existing?.weekdays.orEmpty()) }
  var windowStart by remember { mutableStateOf(existing?.window_start.orEmpty()) }
  var windowEnd by remember { mutableStateOf(existing?.window_end.orEmpty()) }
  var acceptCriteria by remember { mutableStateOf(existing?.accept_criteria.orEmpty()) }
  var estTotalMin by remember { mutableStateOf(existing?.est_total_min?.toString().orEmpty()) }

  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  fun save() {
    if (title.isBlank()) {
      error = "标题不能为空"
      return
    }
    val token = Session.token(context) ?: return
    val payload = GoalPayload(
      title = title.trim(),
      type = type,
      emoji = emoji,
      description = null,
      freq_mode = freqMode,
      freq_n = freqN.toIntOrNull(),
      weekdays = weekdays.ifBlank { null },
      window_start = windowStart.ifBlank { null },
      window_end = windowEnd.ifBlank { null },
      duration_min = durationMin.toIntOrNull(),
      difficulty = difficulty,
      start_date = null,
      end_date = null,
      est_total_min = estTotalMin.toIntOrNull(),
      accept_criteria = if (type == "stage") acceptCriteria.ifBlank { null } else null,
      custom_points = null,
    )
    busy = true
    error = null
    scope.launch {
      try {
        if (existing == null) {
          Api.createGoal(token, payload)
        } else {
          Api.updateGoal(token, existing.id, payload)
        }
        onSaved()
      } catch (e: ApiException) {
        error = e.message ?: "保存失败"
      } catch (e: Exception) {
        error = e.message ?: "保存失败"
      } finally {
        busy = false
      }
    }
  }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
    ) {
      PixelPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(
            text = if (existing == null) "新建目标" else "编辑目标",
            style = PixelType.Title,
            color = PixelColors.Ink,
          )
          Spacer(Modifier.height(12.dp))

          FieldLabel("标题")
          PixelTextField(value = title, onValueChange = { title = it }, placeholder = "例如：每天读 30 分钟", modifier = Modifier.fillMaxWidth())

          Spacer(Modifier.height(12.dp))

          FieldLabel("表情")
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
          ) {
            EMOJI_OPTIONS.forEach { e ->
              Box(
                modifier = Modifier
                  .size(36.dp)
                  .background(if (emoji == e) PixelColors.GreenSoft else PixelColors.Card)
                  .pixelBorder(2.dp, if (emoji == e) PixelColors.Green else PixelColors.Outline)
                  .clickable { emoji = e },
                contentAlignment = Alignment.Center,
              ) {
                Text(e, style = PixelType.Title)
              }
              Spacer(Modifier.width(6.dp))
            }
          }

          Spacer(Modifier.height(12.dp))

          FieldLabel("类型")
          Row {
            TypeOption(text = "日常", selected = type == "daily", onClick = { type = "daily" })
            Spacer(Modifier.width(8.dp))
            TypeOption(text = "小目标", selected = type == "stage", onClick = { type = "stage" })
          }

          Spacer(Modifier.height(12.dp))

          FieldLabel("难度")
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
          ) {
            DIFFICULTY_LABELS.forEachIndexed { i, label ->
              val d = i + 1
              Box(
                modifier = Modifier
                  .background(if (difficulty == d) PixelColors.GreenSoft else PixelColors.Card)
                  .pixelBorder(2.dp, if (difficulty == d) PixelColors.Green else PixelColors.Outline)
                  .clickable { difficulty = d }
                  .padding(horizontal = 10.dp, vertical = 6.dp),
              ) {
                Text(label, style = PixelType.Small, color = PixelColors.Ink)
              }
              Spacer(Modifier.width(6.dp))
            }
          }

          Spacer(Modifier.height(12.dp))

          FieldLabel("时长（分钟）")
          PixelTextField(value = durationMin, onValueChange = { durationMin = it }, placeholder = "30", modifier = Modifier.fillMaxWidth())

          Spacer(Modifier.height(12.dp))

          FieldLabel("频率")
          Row {
            FREQ_OPTIONS.forEach { opt ->
              Box(
                modifier = Modifier
                  .background(if (freqMode == opt.first) PixelColors.GreenSoft else PixelColors.Card)
                  .pixelBorder(2.dp, if (freqMode == opt.first) PixelColors.Green else PixelColors.Outline)
                  .clickable { freqMode = opt.first }
                  .padding(horizontal = 10.dp, vertical = 6.dp),
              ) {
                Text(opt.second, style = PixelType.Small, color = PixelColors.Ink)
              }
              Spacer(Modifier.width(6.dp))
            }
          }

          if (freqMode == "weekly") {
            Spacer(Modifier.height(8.dp))
            FieldLabel("每周次数")
            PixelTextField(value = freqN, onValueChange = { freqN = it }, placeholder = "3", modifier = Modifier.fillMaxWidth())
          }

          if (freqMode == "weekdays") {
            Spacer(Modifier.height(8.dp))
            FieldLabel("指定星期（1-7，逗号分隔）")
            PixelTextField(value = weekdays, onValueChange = { weekdays = it }, placeholder = "1,3,5", modifier = Modifier.fillMaxWidth())
          }

          Spacer(Modifier.height(12.dp))

          FieldLabel("打卡窗口（起止，HH:mm）")
          Row(verticalAlignment = Alignment.CenterVertically) {
            PixelTextField(value = windowStart, onValueChange = { windowStart = it }, placeholder = "06:00", modifier = Modifier.weight(1f))
            Text(" ~ ", style = PixelType.Body, color = PixelColors.Ink3)
            PixelTextField(value = windowEnd, onValueChange = { windowEnd = it }, placeholder = "22:00", modifier = Modifier.weight(1f))
          }

          if (type == "stage") {
            Spacer(Modifier.height(12.dp))
            FieldLabel("验收标准")
            PixelTextField(value = acceptCriteria, onValueChange = { acceptCriteria = it }, placeholder = "例如：读完一整本书", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            FieldLabel("预估总时长（分钟）")
            PixelTextField(value = estTotalMin, onValueChange = { estTotalMin = it }, placeholder = "600", modifier = Modifier.fillMaxWidth())
          }

          if (error != null) {
            Spacer(Modifier.height(8.dp))
            PixelChip(text = error.orEmpty(), bg = PixelColors.OrangeSoft, fg = PixelColors.OrangeDeep)
          }

          Spacer(Modifier.height(16.dp))

          Row(modifier = Modifier.fillMaxWidth()) {
            PixelButton(
              text = "取消",
              onClick = onDismiss,
              enabled = !busy,
              modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            PixelButton(
              text = if (busy) "保存中…" else "保存",
              onClick = { save() },
              enabled = !busy,
              modifier = Modifier.weight(1f),
            )
          }

          // 编辑已有目标时提供「放弃」入口
          if (existing != null) {
            Spacer(Modifier.height(12.dp))
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .pixelHardShadow(4.dp, PixelColors.Outline)
                .background(PixelColors.Red)
                .pixelBorder(2.dp, PixelColors.Outline)
                .clickable(enabled = !busy) { onAbandon(existing) }
                .padding(horizontal = 20.dp, vertical = 12.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(text = "放弃此目标", style = PixelType.Body, color = PixelColors.White)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun FieldLabel(text: String) {
  Text(text = text, style = PixelType.Small, color = PixelColors.Ink3)
  Spacer(Modifier.height(4.dp))
}

@Composable
private fun TypeOption(text: String, selected: Boolean, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .background(if (selected) PixelColors.GreenSoft else PixelColors.Card)
      .pixelBorder(2.dp, if (selected) PixelColors.Green else PixelColors.Outline)
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  ) {
    Text(text, style = PixelType.Body, color = PixelColors.Ink)
  }
}

// ==================== 打卡确认弹层 ====================

@Composable
private fun CheckinDialog(
  goal: WeekGoal,
  date: String?,
  onDismiss: () -> Unit,
  onConfirm: (Int?, String?) -> Unit,
) {
  var duration by remember { mutableStateOf("") }
  var evidence by remember { mutableStateOf("") }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      PixelPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(
            text = "打卡 ${goal.emoji} ${goal.title}",
            style = PixelType.Title,
            color = PixelColors.Ink,
          )
          if (date != null) {
            Spacer(Modifier.height(4.dp))
            Text(text = date, style = PixelType.Small, color = PixelColors.Ink3)
          }
          Spacer(Modifier.height(12.dp))

          FieldLabel("时长（分钟，留空用默认）")
          PixelTextField(value = duration, onValueChange = { duration = it }, placeholder = "留空 = 默认", modifier = Modifier.fillMaxWidth())

          Spacer(Modifier.height(8.dp))
          FieldLabel("打卡说明（可选）")
          PixelTextField(value = evidence, onValueChange = { evidence = it }, placeholder = "例如：读完了第 3 章", modifier = Modifier.fillMaxWidth())

          Spacer(Modifier.height(16.dp))

          Row(modifier = Modifier.fillMaxWidth()) {
            PixelButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            PixelButton(
              text = "确认打卡",
              onClick = { onConfirm(duration.toIntOrNull(), evidence.ifBlank { null }) },
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }
  }
}

// ==================== 通用确认弹层 ====================

@Composable
private fun ConfirmDialog(
  title: String,
  message: String,
  confirmText: String,
  danger: Boolean,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      PixelPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(text = title, style = PixelType.Title, color = PixelColors.Ink)
          Spacer(Modifier.height(8.dp))
          Text(text = message, style = PixelType.Body, color = PixelColors.Ink2)
          Spacer(Modifier.height(16.dp))
          Row(modifier = Modifier.fillMaxWidth()) {
            PixelButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Box(
              modifier = Modifier
                .weight(1f)
                .pixelHardShadow(4.dp, PixelColors.Outline)
                .background(if (danger) PixelColors.Red else PixelColors.Green)
                .pixelBorder(2.dp, PixelColors.Outline)
                .clickable(onClick = onConfirm)
                .padding(horizontal = 20.dp, vertical = 12.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(text = confirmText, style = PixelType.Body, color = PixelColors.White)
            }
          }
        }
      }
    }
  }
}

// ==================== 小目标列表 ====================

/** 小目标状态徽章文案 + 颜色 */
private data class StageBadge(val label: String, val bg: Color, val fg: Color)

private fun stageBadge(status: String): StageBadge = when (status) {
  "active" -> StageBadge("进行中", PixelColors.GreenSoft, PixelColors.GreenDeep)
  "reviewing" -> StageBadge("等待验收", PixelColors.OrangeSoft, PixelColors.OrangeDeep)
  "achieved" -> StageBadge("已达成", PixelColors.GreenSoft, PixelColors.GreenDeep)
  "abandoned" -> StageBadge("已放弃", PixelColors.Bg2, PixelColors.Ink3)
  "draft" -> StageBadge("草稿", PixelColors.Bg2, PixelColors.Ink2)
  else -> StageBadge(status, PixelColors.Bg2, PixelColors.Ink2)
}

/**
 * 小目标列表：对齐 Web 版 drawStage。
 * 每个卡片：emoji + 标题 + 截止/难度 + 状态徽章 + 验收标准 + 达成分值 + 按状态的操作按钮。
 */
@Composable
private fun StageListView(
  goals: List<GoalDetail>?,
  loading: Boolean,
  onNew: () -> Unit,
  onEdit: (GoalDetail) -> Unit,
  onStart: (GoalDetail) -> Unit,
  onSubmit: (GoalDetail) -> Unit,
  onAbandon: (GoalDetail) -> Unit,
) {
  if (loading && goals == null) {
    Text("加载中…", style = PixelType.Small, color = PixelColors.Ink3)
    return
  }
  val list = goals.orEmpty()
  if (list.isEmpty()) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text("🌱", style = PixelType.Huge)
      Spacer(Modifier.height(8.dp))
      Text("还没有小目标", style = PixelType.Title, color = PixelColors.Ink)
      Spacer(Modifier.height(6.dp))
      Text(
        "定一个阶段要够到的东西，做完请对方验收，一次入账一大笔积分",
        style = PixelType.Small,
        color = PixelColors.Ink2,
      )
      Spacer(Modifier.height(16.dp))
      PixelSmallButton(text = "+ 新建小目标", onClick = onNew)
    }
    return
  }

  for (g in list) {
    Spacer(Modifier.height(12.dp))
    StageCard(g, onEdit, onStart, onSubmit, onAbandon)
  }
}

@Composable
private fun StageCard(
  g: GoalDetail,
  onEdit: (GoalDetail) -> Unit,
  onStart: (GoalDetail) -> Unit,
  onSubmit: (GoalDetail) -> Unit,
  onAbandon: (GoalDetail) -> Unit,
) {
  val badge = stageBadge(g.status)
  val points = g.total_points ?: 0
  val crit = g.accept_criteria.orEmpty()

  PixelPanel(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp)) {
      // 头部：emoji + 标题 + 状态徽章
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(g.emoji, style = PixelType.Title)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(g.title, style = PixelType.Body, color = PixelColors.Ink)
          Text(
            text = "${if (g.end_date != null) "截止 ${g.end_date}" else "长期"} · 难度 ${g.difficulty}",
            style = PixelType.Small,
            color = PixelColors.Ink3,
          )
        }
        PixelChip(text = badge.label, bg = badge.bg, fg = badge.fg)
      }

      // 验收标准
      if (crit.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text("验收标准：$crit", style = PixelType.Small, color = PixelColors.Ink2)
      }

      // 达成分值
      Spacer(Modifier.height(8.dp))
      Text("达成 +$points 分", style = PixelType.Mono, color = PixelColors.Gold)

      // 按状态的操作
      Spacer(Modifier.height(10.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        when (g.status) {
          "active" -> {
            PixelSmallButton(text = "提交验收", onClick = { onSubmit(g) }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PixelSmallButton(text = "编辑", filled = false, onClick = { onEdit(g) })
            Spacer(Modifier.width(8.dp))
            PixelSmallButton(text = "放弃", filled = false, onClick = { onAbandon(g) })
          }
          "draft" -> {
            PixelSmallButton(text = "开始", onClick = { onStart(g) }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PixelSmallButton(text = "编辑", filled = false, onClick = { onEdit(g) })
          }
          "reviewing" -> {
            Text("等待对方验收…", style = PixelType.Small, color = PixelColors.OrangeDeep)
          }
          "achieved" -> {
            Text("+$points 分已入账", style = PixelType.Small, color = PixelColors.GreenDeep)
          }
          "abandoned" -> {
            Text("已放弃", style = PixelType.Small, color = PixelColors.Ink3)
          }
        }
      }
    }
  }
}
