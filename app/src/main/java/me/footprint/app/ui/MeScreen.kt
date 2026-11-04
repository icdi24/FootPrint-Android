package me.footprint.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.footprint.app.net.Api
import me.footprint.app.net.ApiException
import me.footprint.app.net.BackupEntry
import me.footprint.app.net.Difficulty
import me.footprint.app.net.Freq
import me.footprint.app.net.LedgerItem
import me.footprint.app.net.PointsConfig
import me.footprint.app.net.Session
import me.footprint.app.net.StatsInner
import me.footprint.app.net.Window
import me.footprint.app.ui.theme.PixelColors
import me.footprint.app.ui.theme.PixelType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「我的」页：个人信息 + 统计卡片 + 积分流水 + 改口令 + 积分系数 + 备份 + 登出。
 *
 * 只用基础组件（Box / Row / Column / PixelTextField），直角、2px 描边、硬投影，
 * 颜色和字号只用 PixelColors / PixelType。
 */

private val SH_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
private val LEDGER_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** ISO 时间串转上海时区 "MM-dd HH:mm"；解析失败原样返回 */
private fun ledgerTime(iso: String): String {
  if (iso.isBlank()) return ""
  return try {
    Instant.parse(iso).atZone(SH_ZONE).format(LEDGER_FMT)
  } catch (e: Exception) {
    iso
  }
}

/** 分钟转 "X.X 小时"（保留一位小数） */
private fun hoursOf(min: Int): String {
  if (min <= 0) return "0"
  val h = min / 60.0
  return if (h >= 100) String.format("%.0f", h) else String.format("%.1f", h)
}

/** 字节转可读大小 */
private fun sizeOf(bytes: Long): String {
  if (bytes <= 0) return "0 B"
  val kb = bytes / 1024.0
  if (kb < 1024) return String.format("%.1f KB", kb)
  val mb = kb / 1024.0
  return String.format("%.1f MB", mb)
}

@Composable
fun MeScreen(vm: AppViewModel) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // ---- 页面状态 ----
  var stats by remember { mutableStateOf<StatsInner?>(null) }
  var ledger by remember { mutableStateOf<List<LedgerItem>>(emptyList()) }
  var settings by remember { mutableStateOf<PointsConfig?>(null) }
  var backups by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }

  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }
  var partnerName by remember { mutableStateOf<String?>(null) }
  var myAvatar by remember { mutableStateOf("") }

  // 提示消息（改口令/备份/系数保存等一次性反馈）
  var toast by remember { mutableStateOf<String?>(null) }

  // ---- 弹层开关 ----
  var showPassword by remember { mutableStateOf(false) }
  var showPoints by remember { mutableStateOf(false) }

  // 我的昵称（优先 vm.myName，兜底 Session.nickname）
  val myName by vm.myName.collectAsState()
  val nickname = myName.ifBlank { Session.nickname(context).orEmpty() }

  suspend fun loadAll() {
    val token = Session.token(context)
    if (token == null) {
      error = "未登录"
      loading = false
      return
    }
    loading = true
    error = null
    try {
      val s = Api.getStats(token)
      stats = s.stats
      myAvatar = s.user?.avatar.orEmpty()
    } catch (e: Exception) {
      error = e.message ?: "加载统计失败"
    }
    // 搭档昵称：getStats 不带参返回的是自己，必须走 /api/partner 拿搭档
    try {
      partnerName = Api.getPartnerPage(token).partner?.nickname
    } catch (e: Exception) {
      // 没搭档/失败都不阻塞其它模块
    }
    try {
      ledger = Api.getLedger(token).ledger
    } catch (e: Exception) {
      // 流水失败不阻塞其它模块
    }
    try {
      settings = Api.getSettings(token).points
    } catch (e: Exception) {
      // 系数失败不阻塞其它模块
    }
    try {
      backups = Api.listBackups(token).backups
    } catch (e: Exception) {
      // 备份失败不阻塞其它模块
    }
    loading = false
  }

  LaunchedEffect(Unit) { loadAll() }

  Screen {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
    ) {
      // ===== 顶部个人信息 =====
      ProfileHeader(
        nickname = nickname.ifBlank { "我" },
        partnerName = partnerName,
        avatar = myAvatar,
      )

      Spacer(Modifier.height(16.dp))

      if (error != null) {
        PixelPanel(background = PixelColors.OrangeSoft, borderColor = PixelColors.OrangeDeep) {
          Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error ?: "", style = PixelType.Small, color = PixelColors.OrangeDeep, modifier = Modifier.weight(1f))
            PixelSmallButton("重试", onClick = { scope.launch { loadAll() } }, filled = false)
          }
        }
        Spacer(Modifier.height(16.dp))
      }

      // ===== 统计卡片 =====
      StatsGrid(stats)

      Spacer(Modifier.height(16.dp))

      // ===== 积分流水 =====
      LedgerSection(ledger)

      Spacer(Modifier.height(16.dp))

      // ===== 改口令 / 积分系数 入口 =====
      SettingsActions(
        onPassword = { showPassword = true },
        onPoints = { showPoints = true },
      )

      Spacer(Modifier.height(16.dp))

      // ===== 备份 =====
      BackupSection(
        backups = backups,
        onBackup = {
          scope.launch {
            val token = Session.token(context) ?: return@launch
            try {
              Api.createBackup(token)
              backups = Api.listBackups(token).backups
              toast = "备份成功"
            } catch (e: Exception) {
              toast = e.message ?: "备份失败"
            }
          }
        },
      )

      Spacer(Modifier.height(24.dp))

      // ===== 登出 =====
      PixelButton(
        text = "登出",
        onClick = { vm.logout() },
        modifier = Modifier.fillMaxWidth(),
      )

      Spacer(Modifier.height(24.dp))
    }
  }

  // ---- 弹层 ----
  if (showPassword) {
    PasswordDialog(
      onDismiss = { showPassword = false },
      onSuccess = {
        showPassword = false
        toast = "口令已修改，可能需要重新登录"
      },
    )
  }

  if (showPoints) {
    val cfg = settings
    if (cfg != null) {
      PointsDialog(
        initial = cfg,
        onDismiss = { showPoints = false },
        onSave = { newCfg ->
          scope.launch {
            val token = Session.token(context) ?: return@launch
            try {
              Api.updateSettings(token, newCfg)
              settings = newCfg
              showPoints = false
              toast = "积分系数已保存"
            } catch (e: Exception) {
              toast = e.message ?: "保存失败"
            }
          }
        },
      )
    }
  }

  // ---- 提示 ----
  toast?.let { msg ->
    ToastBar(msg) { toast = null }
  }
}

// ---------------------------------------------------------------------------
// 各区块组件
// ---------------------------------------------------------------------------

/** 顶部个人信息：昵称 + 头像框 + 搭档监督文案 */
@Composable
private fun ProfileHeader(nickname: String, partnerName: String?, avatar: String) {
  PixelPanel(Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 头像：后端返回的 avatar emoji（与 Web 一致），空则首字
      Box(
        modifier = Modifier
          .width(56.dp)
          .height(56.dp)
          .background(PixelColors.Card)
          .pixelBorder(2.dp, PixelColors.Outline),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = avatar.ifBlank { nickname.take(1).ifBlank { "我" } },
          style = PixelType.Huge,
        )
      }
      Spacer(Modifier.width(12.dp))
      Column {
        Text(nickname, style = PixelType.Title, color = PixelColors.Ink)
        Spacer(Modifier.height(4.dp))
        Text(
          text = if (partnerName.isNullOrBlank()) "还没有搭档" else "和 $partnerName 互相监督中",
          style = PixelType.Small,
          color = PixelColors.Ink3,
        )
      }
    }
  }
}

/** 统计卡片：2 列网格 */
@Composable
private fun StatsGrid(stats: StatsInner?) {
  if (stats == null) {
    PixelPanel(Modifier.fillMaxWidth()) {
      Box(Modifier.padding(16.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text("统计加载中…", style = PixelType.Small, color = PixelColors.Ink3)
      }
    }
    return
  }
  val completion = if (stats.monthExpected > 0) {
    (stats.monthCheckinCount * 100 / stats.monthExpected)
  } else {
    0
  }
  val cells = listOf(
    StatSpec("本月得分", stats.monthPoints.toString(), PixelColors.GreenDeep, PixelColors.GreenSoft),
    StatSpec("当前余额", stats.balance.toString(), PixelColors.OrangeDeep, PixelColors.OrangeSoft),
    StatSpec("最长连续", stats.bestStreak.toString(), PixelColors.GreenDeep, PixelColors.GreenSoft),
    StatSpec("本月完成率", "$completion%", PixelColors.GreenDeep, PixelColors.GreenSoft),
    StatSpec("时间投入", "${hoursOf(stats.timeInvestMin)} 小时", PixelColors.Ink, PixelColors.Line),
    StatSpec("被质疑次数", stats.rejectedCount.toString(), PixelColors.RedDeep, PixelColors.OrangeSoft),
  )
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    cells.chunked(2).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { spec -> Box(Modifier.weight(1f)) { StatCell(spec) } }
        if (row.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}

/** 统计小卡的静态描述 */
private data class StatSpec(val label: String, val value: String, val valueColor: Color, val bg: Color)

/** 单个统计小卡 */
@Composable
private fun StatCell(spec: StatSpec) {
  val label = spec.label
  val value = spec.value
  val valueColor = spec.valueColor
  val bg = spec.bg
  PixelPanel(background = bg) {
    Column(Modifier.padding(12.dp)) {
      Text(label, style = PixelType.Small, color = PixelColors.Ink3)
      Spacer(Modifier.height(4.dp))
      Text(value, style = PixelType.Mono, color = valueColor)
    }
  }
}

/** 积分流水：最近流水列表 */
@Composable
private fun LedgerSection(ledger: List<LedgerItem>) {
  PixelPanel(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Text("积分流水", style = PixelType.Title, color = PixelColors.Ink)
      Spacer(Modifier.height(12.dp))
      if (ledger.isEmpty()) {
        Text("暂无流水", style = PixelType.Small, color = PixelColors.Ink3)
      } else {
        ledger.take(30).forEachIndexed { i, item ->
          LedgerRow(item)
          if (i != ledger.lastIndex) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(PixelColors.Line))
            Spacer(Modifier.height(10.dp))
          }
        }
      }
    }
  }
}

/** 单条流水 */
@Composable
private fun LedgerRow(item: LedgerItem) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PixelChip(
          text = item.typeLabel.ifBlank { item.type },
          bg = PixelColors.GreenSoft,
          fg = PixelColors.GreenDeep,
        )
        Spacer(Modifier.width(8.dp))
        Text(item.note, style = PixelType.Small, color = PixelColors.Ink2, maxLines = 1)
      }
      Spacer(Modifier.height(2.dp))
      Text(ledgerTime(item.created_at), style = PixelType.Small, color = PixelColors.Ink3)
    }
    Spacer(Modifier.width(8.dp))
    val positive = item.delta >= 0
    Text(
      text = if (positive) "+${item.delta}" else "${item.delta}",
      style = PixelType.Mono,
      color = if (positive) PixelColors.Green else PixelColors.Red,
    )
  }
}

/** 改口令 / 积分系数 两个入口按钮 */
@Composable
private fun SettingsActions(onPassword: () -> Unit, onPoints: () -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.weight(1f)) {
      PixelButton("改口令", onClick = onPassword, modifier = Modifier.fillMaxWidth())
    }
    Box(Modifier.weight(1f)) {
      PixelButton("积分系数", onClick = onPoints, modifier = Modifier.fillMaxWidth())
    }
  }
}

/** 备份列表 + 立即备份 */
@Composable
private fun BackupSection(backups: List<BackupEntry>, onBackup: () -> Unit) {
  PixelPanel(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("备份", style = PixelType.Title, color = PixelColors.Ink)
        Spacer(Modifier.weight(1f))
        PixelSmallButton("立即备份", onClick = onBackup)
      }
      Spacer(Modifier.height(12.dp))
      if (backups.isEmpty()) {
        Text("还没有备份", style = PixelType.Small, color = PixelColors.Ink3)
      } else {
        backups.take(10).forEachIndexed { i, b ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(b.name, style = PixelType.Body, color = PixelColors.Ink, maxLines = 1)
              Spacer(Modifier.height(2.dp))
              Text(
                "${ledgerTime(b.mtime)} · ${sizeOf(b.size)}",
                style = PixelType.Small,
                color = PixelColors.Ink3,
              )
            }
          }
          if (i != backups.lastIndex) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(PixelColors.Line))
            Spacer(Modifier.height(8.dp))
          }
        }
      }
    }
  }
}

/** 底部浮动的提示条 */
@Composable
private fun ToastBar(message: String, onDismiss: () -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(16.dp),
  ) {
    PixelPanel(background = PixelColors.Ink, borderColor = PixelColors.Ink) {
      Row(
        Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(message, style = PixelType.Body, color = PixelColors.White, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        PixelSmallButton("知道了", onClick = onDismiss, filled = true)
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 弹层
// ---------------------------------------------------------------------------

/** 改口令弹层 */
@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var oldPwd by remember { mutableStateOf("") }
  var newPwd by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var err by remember { mutableStateOf<String?>(null) }

  Overlay {
    PixelPanel(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
      Column(Modifier.padding(16.dp)) {
        Text("改口令", style = PixelType.Title, color = PixelColors.Ink)
        Spacer(Modifier.height(12.dp))
        PixelTextField(
          value = oldPwd,
          onValueChange = { oldPwd = it; err = null },
          placeholder = "旧口令",
          modifier = Modifier.fillMaxWidth(),
          isPassword = true,
        )
        Spacer(Modifier.height(8.dp))
        PixelTextField(
          value = newPwd,
          onValueChange = { newPwd = it; err = null },
          placeholder = "新口令（至少 6 位）",
          modifier = Modifier.fillMaxWidth(),
          isPassword = true,
        )
        err?.let {
          Spacer(Modifier.height(8.dp))
          Text(it, style = PixelType.Small, color = PixelColors.Red)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Box(Modifier.weight(1f)) {
            PixelButton("取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !busy)
          }
          Box(Modifier.weight(1f)) {
            PixelButton(
              "确定",
              onClick = {
                if (oldPwd.isBlank() || newPwd.isBlank()) {
                  err = "旧口令和新口令都要填"
                  return@PixelButton
                }
                if (newPwd.length < 6) {
                  err = "新口令至少 6 位"
                  return@PixelButton
                }
                busy = true
                scope.launch {
                  val token = Session.token(context)
                  if (token == null) {
                    busy = false
                    err = "未登录"
                    return@launch
                  }
                  try {
                    Api.changePassword(token, oldPwd, newPwd)
                    busy = false
                    onSuccess()
                  } catch (e: ApiException) {
                    busy = false
                    err = e.message ?: "修改失败"
                  } catch (e: Exception) {
                    busy = false
                    err = e.message ?: "修改失败"
                  }
                }
              },
              modifier = Modifier.fillMaxWidth(),
              enabled = !busy,
            )
          }
        }
      }
    }
  }
}

/** 积分系数弹层：键值列表 + 可编辑数字，一次保存 */
@Composable
private fun PointsDialog(
  initial: PointsConfig,
  onDismiss: () -> Unit,
  onSave: (PointsConfig) -> Unit,
) {
  // 本地可编辑副本（用字符串承载输入）
  var unit by remember { mutableStateOf(fmtNum(initial.unit)) }
  var cap by remember { mutableStateOf(initial.cap.toString()) }
  var maxPer by remember { mutableStateOf(initial.maxPer.toString()) }
  var d1 by remember { mutableStateOf(fmtNum(initial.difficulty.`1`)) }
  var d2 by remember { mutableStateOf(fmtNum(initial.difficulty.`2`)) }
  var d3 by remember { mutableStateOf(fmtNum(initial.difficulty.`3`)) }
  var d4 by remember { mutableStateOf(fmtNum(initial.difficulty.`4`)) }
  var d5 by remember { mutableStateOf(fmtNum(initial.difficulty.`5`)) }
  var fDaily by remember { mutableStateOf(fmtNum(initial.freq.daily)) }
  var f34 by remember { mutableStateOf(fmtNum(initial.freq.weekly34)) }
  var f12 by remember { mutableStateOf(fmtNum(initial.freq.weekly12)) }
  var fWd by remember { mutableStateOf(fmtNum(initial.freq.weekdays)) }
  var wIn by remember { mutableStateOf(fmtNum(initial.window.`in`)) }
  var wNone by remember { mutableStateOf(fmtNum(initial.window.none)) }
  var wOut by remember { mutableStateOf(fmtNum(initial.window.out)) }

  var err by remember { mutableStateOf<String?>(null) }

  Overlay {
    PixelPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
      Column(
        Modifier
          .padding(16.dp)
          .height(440.dp)
          .verticalScroll(rememberScrollState())
      ) {
        Text("积分系数", style = PixelType.Title, color = PixelColors.Ink)
        Text("数字直接改，改完点保存", style = PixelType.Small, color = PixelColors.Ink3)
        Spacer(Modifier.height(12.dp))

        NumField("每分钟积分 unit", unit) { unit = it }
        NumField("单次上限 cap", cap) { cap = it }
        NumField("每日上限 maxPer", maxPer) { maxPer = it }
        Spacer(Modifier.height(8.dp))
        Text("难度倍率", style = PixelType.Small, color = PixelColors.Ink3)
        NumField("难度 1", d1) { d1 = it }
        NumField("难度 2", d2) { d2 = it }
        NumField("难度 3", d3) { d3 = it }
        NumField("难度 4", d4) { d4 = it }
        NumField("难度 5", d5) { d5 = it }
        Spacer(Modifier.height(8.dp))
        Text("频率倍率", style = PixelType.Small, color = PixelColors.Ink3)
        NumField("每天 daily", fDaily) { fDaily = it }
        NumField("每周 3-4 天", f34) { f34 = it }
        NumField("每周 1-2 天", f12) { f12 = it }
        NumField("工作日", fWd) { fWd = it }
        Spacer(Modifier.height(8.dp))
        Text("窗口倍率", style = PixelType.Small, color = PixelColors.Ink3)
        NumField("窗口内 in", wIn) { wIn = it }
        NumField("无窗口 none", wNone) { wNone = it }
        NumField("窗口外 out", wOut) { wOut = it }

        err?.let {
          Spacer(Modifier.height(8.dp))
          Text(it, style = PixelType.Small, color = PixelColors.Red)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Box(Modifier.weight(1f)) {
            PixelButton("取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
          }
          Box(Modifier.weight(1f)) {
            PixelButton(
              "保存",
              onClick = {
                val parsed = parseCfg(
                  unit, cap, maxPer,
                  d1, d2, d3, d4, d5,
                  fDaily, f34, f12, fWd,
                  wIn, wNone, wOut,
                )
                if (parsed == null) {
                  err = "有数字格式不对，请检查"
                  return@PixelButton
                }
                onSave(parsed)
              },
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
    }
  }
}

/** 全屏半透明遮罩 + 居中内容 */
@Composable
private fun Overlay(content: @Composable () -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0x88000000)),
    contentAlignment = Alignment.Center,
  ) {
    Box(Modifier.fillMaxWidth()) {
      content()
    }
  }
}

/** 键值数字编辑行：label + 输入框 */
@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = PixelType.Small, color = PixelColors.Ink2, modifier = Modifier.width(130.dp))
    Spacer(Modifier.width(8.dp))
    PixelTextField(
      value = value,
      onValueChange = { onChange(it) },
      placeholder = "0",
      modifier = Modifier.weight(1f),
    )
  }
}

/** Double 转字符串：整数不带小数点，小数保留 2 位 */
private fun fmtNum(v: Double): String {
  return if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.2f", v)
}

/** 解析所有字段，构造 PointsConfig；任一解析失败返回 null */
private fun parseCfg(
  unit: String, cap: String, maxPer: String,
  d1: String, d2: String, d3: String, d4: String, d5: String,
  fDaily: String, f34: String, f12: String, fWd: String,
  wIn: String, wNone: String, wOut: String,
): PointsConfig? {
  return try {
    val diff = Difficulty(
      `1` = d1.toDouble(), `2` = d2.toDouble(), `3` = d3.toDouble(),
      `4` = d4.toDouble(), `5` = d5.toDouble(),
    )
    val freq = Freq(
      daily = fDaily.toDouble(), weekly34 = f34.toDouble(),
      weekly12 = f12.toDouble(), weekdays = fWd.toDouble(),
    )
    val win = Window(
      `in` = wIn.toDouble(), none = wNone.toDouble(), out = wOut.toDouble(),
    )
    PointsConfig(
      unit = unit.toDouble(),
      cap = cap.toInt(),
      maxPer = maxPer.toInt(),
      difficulty = diff,
      freq = freq,
      window = win,
      milestones = emptyList(),
    )
  } catch (e: Exception) {
    null
  }
}
