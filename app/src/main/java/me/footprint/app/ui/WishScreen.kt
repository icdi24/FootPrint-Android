package me.footprint.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.footprint.app.net.Api
import me.footprint.app.net.ApiException
import me.footprint.app.net.Redemption
import me.footprint.app.net.Session
import me.footprint.app.net.WishItem
import me.footprint.app.net.WishListResponse
import me.footprint.app.net.WishPayload
import me.footprint.app.ui.theme.PixelColors
import me.footprint.app.ui.theme.PixelType

/**
 * 愿望单页：余额横幅 + 愿望列表 + 新建/编辑 + 兑换 + 撤销 + 标记兑现。
 *
 * 与 Web 版 wish 页对齐。页面自己用 remember 管理状态、直接调 Api，
 * token 走 Session.token(context)，context 用 LocalContext.current。
 *
 * 积分与价格换算：1 元 = 10 分（后端默认 price_cny × 10 = points_cost）。
 * 兑换有冷却期，后端返回 redeemable=false + cooldownMsg 时如实展示。
 */

/** 常用 emoji 选项，新建/编辑时点选 */
private val WISH_EMOJIS = listOf("🎁", "🎮", "📱", "💻", "⌚", "🎧", "📷", "🚲", "✈️", "🏠", "🎨", "🍰")

/** 价格格式化：去掉无意义的小数（10.0 → 10），最多保留两位 */
private fun fmtPrice(p: Double): String {
  val rounded = Math.round(p * 100.0) / 100.0
  return if (rounded == Math.floor(rounded) && !rounded.isInfinite()) {
    rounded.toLong().toString()
  } else {
    rounded.toString()
  }
}

/** 弹层表单里暂存的数据（新建和编辑共用一套） */
private data class WishForm(
  val title: String = "",
  val emoji: String = "🎁",
  val description: String = "",
  val priceText: String = "",
  val pointsText: String = "",
  val cooldownDays: Int = 0,
)

@Composable
fun WishScreen(vm: AppViewModel) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // 列表数据与加载状态
  var data by remember { mutableStateOf<WishListResponse?>(null) }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  // 弹层状态：null = 关闭；否则携带要编辑的愿望（null 表示新建）
  var editing: WishItem? by remember { mutableStateOf<WishItem?>(null) }
  var showEditor by remember { mutableStateOf(false) }

  // 正在操作的愿望 id / 兑换 id，用于防连点
  var busyWishId by remember { mutableStateOf<Long?>(null) }
  var busyRedemptionId by remember { mutableStateOf<Long?>(null) }

  // 拉取列表
  suspend fun load() {
    val token = Session.token(context) ?: return
    loading = true
    error = null
    try {
      data = Api.listWish(token)
    } catch (e: Exception) {
      error = (e as? ApiException)?.message ?: e.message ?: "加载失败"
    } finally {
      loading = false
    }
  }

  LaunchedEffect(Unit) {
    load()
  }

  Screen {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState()),
    ) {
      // ---- 顶栏 ----
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column {
          Text("愿望单", style = PixelType.Big, color = PixelColors.Ink)
          Text("攒够分，换点真的", style = PixelType.Small, color = PixelColors.Ink3)
        }
        PixelSmallButton(
          text = "+ 新建",
          filled = true,
          onClick = { editing = null; showEditor = true },
        )
      }

      Spacer(Modifier.height(12.dp))

      // ---- 余额横幅 ----
      BalanceBanner(balance = data?.balance ?: 0)

      Spacer(Modifier.height(16.dp))

      if (loading) {
        Text("加载中…", style = PixelType.Body, color = PixelColors.Ink3)
        Spacer(Modifier.height(24.dp))
        return@Column
      }

      if (error != null) {
        Text("加载出错：$error", style = PixelType.Small, color = PixelColors.RedDeep)
        Spacer(Modifier.height(8.dp))
        PixelSmallButton(text = "重试", onClick = { scope.launch { load() } }, filled = false)
        Spacer(Modifier.height(24.dp))
        return@Column
      }

      val items = data?.items ?: emptyList()
      val redemptions = data?.redemptions ?: emptyList()
      val balance = data?.balance ?: 0

      if (items.isEmpty()) {
        Text("还没有愿望，点右上角「+ 新建」加一个吧", style = PixelType.Small, color = PixelColors.Ink3)
        Spacer(Modifier.height(24.dp))
        return@Column
      }

      // ---- 想要的东西 标题 ----
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(modifier = Modifier.size(10.dp).background(PixelColors.Ink))
          Spacer(Modifier.width(8.dp))
          Text("想要的东西", style = PixelType.Title, color = PixelColors.Ink)
        }
        Text("${items.size} 件", style = PixelType.Small, color = PixelColors.Ink2)
      }
      Spacer(Modifier.height(8.dp))

      // ---- 愿望列表 ----
      for (item in items) {
        // 找这条愿望对应的、已兑换未兑现的记录
        val applied = redemptions.firstOrNull { it.wish_item_id == item.id && it.status == "applied" }
        WishCard(
          item = item,
          balance = balance,
          redemption = applied,
          busy = busyWishId == item.id || busyRedemptionId == item.id,
          onEdit = { editing = item; showEditor = true },
          onRedeem = {
            scope.launch {
              val token = Session.token(context) ?: return@launch
              busyWishId = item.id
              try {
                Api.redeemWish(token, item.id)
                Toast.makeText(context, "兑换成功，已锁定 ${item.points_cost} 分", Toast.LENGTH_SHORT).show()
                load()
              } catch (e: Exception) {
                Toast.makeText(context, (e as? ApiException)?.message ?: "兑换失败", Toast.LENGTH_SHORT).show()
              } finally {
                busyWishId = null
              }
            }
          },
          onCancel = {
            scope.launch {
              val token = Session.token(context) ?: return@launch
              busyRedemptionId = applied?.id
              try {
                Api.cancelRedemption(token, applied!!.id)
                Toast.makeText(context, "已撤销兑换", Toast.LENGTH_SHORT).show()
                load()
              } catch (e: Exception) {
                Toast.makeText(context, (e as? ApiException)?.message ?: "撤销失败", Toast.LENGTH_SHORT).show()
              } finally {
                busyRedemptionId = null
              }
            }
          },
          onDone = {
            scope.launch {
              val token = Session.token(context) ?: return@launch
              busyRedemptionId = applied?.id
              try {
                Api.doneRedemption(token, applied!!.id)
                Toast.makeText(context, "已标记兑现 🎉", Toast.LENGTH_SHORT).show()
                load()
              } catch (e: Exception) {
                Toast.makeText(context, (e as? ApiException)?.message ?: "操作失败", Toast.LENGTH_SHORT).show()
              } finally {
                busyRedemptionId = null
              }
            }
          },
        )
        Spacer(Modifier.height(12.dp))
      }

      // ---- 兑换记录（对齐 Web 版「兑换记录」板块）----
      if (redemptions.isNotEmpty()) {
        RedemptionList(redemptions)
        Spacer(Modifier.height(12.dp))
      }

      Spacer(Modifier.height(12.dp))
    }
  }

  // ---- 新建 / 编辑弹层 ----
  if (showEditor) {
    WishEditor(
      editing = editing,
      onDismiss = { showEditor = false },
      onSave = { form ->
        scope.launch {
          val token = Session.token(context) ?: return@launch
          val price = form.priceText.trim().toDoubleOrNull() ?: 0.0
          // 积分价：手动覆盖优先，否则价格 × 10
          val points = form.pointsText.trim().toIntOrNull()
            ?: Math.round(price * 10).toInt()
          val payload = WishPayload(
            title = form.title.trim(),
            category = "goods",
            description = form.description.trim().ifBlank { null },
            emoji = form.emoji,
            price_cny = price,
            points_cost = points,
            cooldown_days = form.cooldownDays.takeIf { it > 0 },
          )
          try {
            if (editing == null) {
              Api.createWish(token, payload)
              Toast.makeText(context, "已创建", Toast.LENGTH_SHORT).show()
            } else {
              Api.updateWish(token, editing!!.id, payload)
              Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            }
            showEditor = false
            load()
          } catch (e: Exception) {
            Toast.makeText(context, (e as? ApiException)?.message ?: "保存失败", Toast.LENGTH_SHORT).show()
          }
        }
      },
    )
  }
}

/** 顶部余额横幅：金色底（对齐 Web），当前积分 + 「1 元 = 10 分」参考 */
@Composable
private fun BalanceBanner(balance: Int) {
  PixelPanel(
    modifier = Modifier.fillMaxWidth(),
    background = PixelColors.Gold,
    borderColor = PixelColors.OrangeDeep,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column {
        Text("可用积分", style = PixelType.Small, color = PixelColors.OrangeDeep)
        Text(
          text = balance.toString(),
          style = PixelType.Huge,
          color = PixelColors.Ink,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        Text("参考价", style = PixelType.Small, color = PixelColors.Ink2)
        Text("1 元 = 10 分", style = PixelType.Body, color = PixelColors.Ink2)
      }
    }
  }
}

/** 单个愿望卡片：emoji + 标题 + 描述 + 积分价 + 参考价 + 进度条 + 操作按钮 */
@Composable
private fun WishCard(
  item: WishItem,
  balance: Int,
  redemption: Redemption?,
  busy: Boolean,
  onEdit: () -> Unit,
  onRedeem: () -> Unit,
  onCancel: () -> Unit,
  onDone: () -> Unit,
) {
  PixelPanel(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
      // 标题行
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(item.emoji, style = PixelType.Big)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(item.title, style = PixelType.Title, color = PixelColors.Ink)
          if (item.description.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(item.description, style = PixelType.Small, color = PixelColors.Ink2)
          }
        }
        PixelSmallButton(text = "编辑", onClick = onEdit, filled = false)
      }

      Spacer(Modifier.height(10.dp))

      // 价格行：积分价 + 参考价
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("积分", style = PixelType.Small, color = PixelColors.Ink3)
        Spacer(Modifier.width(6.dp))
        Text(
          text = item.points_cost.toString(),
          style = PixelType.Mono,
          color = PixelColors.GreenDeep,
        )
        Spacer(Modifier.width(12.dp))
        Text("≈", style = PixelType.Small, color = PixelColors.Ink3)
        Spacer(Modifier.width(6.dp))
        Text(
          text = "¥${fmtPrice(item.price_cny)}",
          style = PixelType.Mono,
          color = PixelColors.OrangeDeep,
        )
      }

      Spacer(Modifier.height(10.dp))

      // 进度条：积分 / 目标
      val cost = item.points_cost
      val fraction = if (cost > 0) balance.toFloat() / cost.toFloat() else 1f
      PixelBar(
        fraction = fraction,
        modifier = Modifier.fillMaxWidth(),
        color = if (fraction >= 1f) PixelColors.Green else PixelColors.Orange,
      )
      Spacer(Modifier.height(4.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = if (cost > 0) "$balance / $cost 分" else "目标积分未设置",
          style = PixelType.Small,
          color = PixelColors.Ink3,
        )
        if (cost > 0) {
          Text(
            text = "${(fraction * 100).toInt().coerceAtMost(999)}%",
            style = PixelType.Small,
            color = PixelColors.Ink3,
          )
        }
      }

      Spacer(Modifier.height(12.dp))

      // 操作区：已兑换未兑现 / 可兑换 / 不可兑换
      if (redemption != null) {
        // 已兑换未兑现：显示「已兑换」状态 + 撤销 + 标记兑现
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          PixelChip(
            text = "已兑换 · 锁定 ${redemption.points_locked} 分",
            bg = PixelColors.OrangeSoft,
            fg = PixelColors.OrangeDeep,
          )
          Spacer(Modifier.weight(1f))
          PixelSmallButton(text = "撤销", onClick = onCancel, filled = false)
          Spacer(Modifier.width(8.dp))
          PixelSmallButton(text = "已兑现", onClick = onDone, filled = true)
        }
      } else if (item.redeemable) {
        PixelButton(
          text = "兑 换",
          onClick = onRedeem,
          modifier = Modifier.fillMaxWidth(),
          enabled = !busy,
        )
      } else {
        // 不可兑换：显示冷却提示或积分不足
        val reason = item.cooldownMsg?.takeIf { it.isNotBlank() }
          ?: if (balance < item.points_cost) "积分不足" else "暂不可兑换"
        PixelButton(
          text = reason,
          onClick = {},
          modifier = Modifier.fillMaxWidth(),
          enabled = false,
        )
      }
    }
  }
}

/** 新建 / 编辑弹层：标题、emoji 点选、描述、参考价、冷却天数 */
@Composable
private fun WishEditor(
  editing: WishItem?,
  onDismiss: () -> Unit,
  onSave: (WishForm) -> Unit,
) {
  // 用 editing 初始化表单；editing 变化时重置
  var form by remember {
    mutableStateOf(
      if (editing == null) {
        WishForm()
      } else {
        WishForm(
          title = editing.title,
          emoji = editing.emoji,
          description = editing.description,
          priceText = fmtPrice(editing.price_cny),
          pointsText = editing.points_cost.toString(),
          cooldownDays = editing.cooldown_days,
        )
      }
    )
  }

  // 覆盖全屏的遮罩 + 居中弹层（像素风自己画，不用 Material Dialog）
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0x993B2E22))
      .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    contentAlignment = Alignment.Center,
  ) {
    PixelPanel(modifier = Modifier.fillMaxWidth(0.9f)) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
          text = if (editing == null) "新建愿望" else "编辑愿望",
          style = PixelType.Title,
          color = PixelColors.Ink,
        )
        Spacer(Modifier.height(14.dp))

        // 标题（≤30 字）
        Text("标题（30 字内）", style = PixelType.Small, color = PixelColors.Ink2)
        Spacer(Modifier.height(6.dp))
        PixelTextField(
          value = form.title,
          onValueChange = { v -> if (v.length <= 30) form = form.copy(title = v) },
          placeholder = "比如：Switch 2",
          modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // emoji 点选
        Text("emoji", style = PixelType.Small, color = PixelColors.Ink2)
        Spacer(Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          for (e in WISH_EMOJIS) {
            val selected = form.emoji == e
            Box(
              modifier = Modifier
                .background(if (selected) PixelColors.GreenSoft else PixelColors.Card)
                .pixelBorder(2.dp, if (selected) PixelColors.GreenDeep else PixelColors.Outline)
                .clickable(interactionSource = null, indication = null, onClick = { form = form.copy(emoji = e) })
                .padding(4.dp),
            ) {
              Text(e, style = PixelType.Body)
            }
          }
        }

        Spacer(Modifier.height(12.dp))

        // 描述（可选）
        Text("描述（可选）", style = PixelType.Small, color = PixelColors.Ink2)
        Spacer(Modifier.height(6.dp))
        PixelTextField(
          value = form.description,
          onValueChange = { form = form.copy(description = it) },
          placeholder = "一句话说说想要什么",
          modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // 参考价 + 积分价（价格驱动积分，允许手动覆盖）
        Row(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.weight(1f)) {
            Text("参考价（元）", style = PixelType.Small, color = PixelColors.Ink2)
            Spacer(Modifier.height(6.dp))
            PixelTextField(
              value = form.priceText,
              onValueChange = { form = form.copy(priceText = it) },
              placeholder = "0",
              modifier = Modifier.fillMaxWidth(),
            )
          }
          Spacer(Modifier.width(10.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text("积分价", style = PixelType.Small, color = PixelColors.Ink2)
            Spacer(Modifier.height(6.dp))
            PixelTextField(
              value = form.pointsText,
              onValueChange = { form = form.copy(pointsText = it) },
              placeholder = "自动 = 价×10",
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }

        Spacer(Modifier.height(12.dp))

        // 冷却天数
        Text("冷却天数", style = PixelType.Small, color = PixelColors.Ink2)
        Spacer(Modifier.height(6.dp))
        PixelTextField(
          value = if (form.cooldownDays > 0) form.cooldownDays.toString() else "",
          onValueChange = { v ->
            val n = v.trim().toIntOrNull() ?: 0
            form = form.copy(cooldownDays = n.coerceAtLeast(0))
          },
          placeholder = "0 = 无冷却",
          modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          PixelSmallButton(text = "取消", onClick = onDismiss, filled = false)
          val canSave = form.title.isNotBlank() && form.priceText.isNotBlank()
          PixelSmallButton(
            text = "保存",
            filled = true,
            onClick = { if (canSave) onSave(form) },
          )
        }
      }
    }
  }
}

// ==================== 兑换记录 ====================

/** 兑换记录列表（对齐 Web 版 redRow）：emoji + 商品名 + 时间 + 锁定分 + 状态徽章 */
@Composable
private fun RedemptionList(redemptions: List<Redemption>) {
  PixelPanel(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text("兑换记录", style = PixelType.Title, color = PixelColors.Ink)
      Spacer(Modifier.height(10.dp))
      for (r in redemptions) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .dashedDivider()
            .padding(vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(r.item_emoji, style = PixelType.Title)
          Spacer(Modifier.width(8.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(r.item_title, style = PixelType.Body, color = PixelColors.Ink)
            Text(
              text = "${redTime(r.applied_at)} · ${r.points_locked} 分",
              style = PixelType.Small,
              color = PixelColors.Ink2,
            )
          }
          PixelChip(
            text = redStatus(r.status),
            bg = when (r.status) {
              "done" -> PixelColors.GreenSoft
              "cancelled", "expired" -> PixelColors.Bg2
              "confirmed" -> PixelColors.GreenSoft
              else -> PixelColors.OrangeSoft
            },
            fg = when (r.status) {
              "done" -> PixelColors.GreenDeep
              "cancelled", "expired" -> PixelColors.Ink3
              "confirmed" -> PixelColors.GreenDeep
              else -> PixelColors.OrangeDeep
            },
          )
        }
      }
    }
  }
}

/** 兑换状态 → 中文 */
private fun redStatus(status: String): String = when (status) {
  "applied" -> "待确认"
  "confirmed" -> "已扣分"
  "done" -> "已完成"
  "cancelled" -> "已取消"
  "expired" -> "已过期"
  else -> status
}

/** ISO 时间 → "MM-dd HH:mm"（上海时区） */
private fun redTime(iso: String): String {
  return try {
    val t = java.time.Instant.parse(iso).atZone(java.time.ZoneId.of("Asia/Shanghai"))
    String.format("%02d-%02d %02d:%02d", t.monthValue, t.dayOfMonth, t.hour, t.minute)
  } catch (e: Exception) {
    ""
  }
}
