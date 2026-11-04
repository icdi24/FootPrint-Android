package me.footprint.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import me.footprint.app.ui.theme.PixelColors
import me.footprint.app.ui.theme.PixelType

/** 底部 5 个 Tab，与 Web 版底部导航一一对应 */
enum class MainTab(val label: String, val icon: String) {
    Home("今天", "🏠"),
    Goals("目标", "◎"),
    Wish("愿望单", "🎁"),
    Partner("我们", "👫"),
    Me("我的", "👤"),
}

/**
 * 主脚手架：底部 Tab 导航 + 5 个页面切换。
 * 各页面是独立的 Composable（GoalsScreen / WishScreen / PartnerScreen / MeScreen），
 * 分别写在各自的文件里，由这里统一组装。
 */
@Composable
fun MainScaffold(
    vm: AppViewModel,
    onOpenUsageSettings: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Home) }

    Box(modifier = Modifier.fillMaxSize().background(PixelColors.Bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    MainTab.Home -> HomeScreen(vm = vm, onOpenUsageSettings = onOpenUsageSettings)
                    MainTab.Goals -> GoalsScreen(vm = vm)
                    MainTab.Wish -> WishScreen(vm = vm)
                    MainTab.Partner -> PartnerScreen(vm = vm, onOpenUsageSettings = onOpenUsageSettings)
                    MainTab.Me -> MeScreen(vm = vm)
                }
            }
            BottomTabBar(current = tab, onSelect = { tab = it })
        }
    }
}

@Composable
private fun BottomTabBar(current: MainTab, onSelect: (MainTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PixelColors.Card)
            .pixelBorderTop(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (t in MainTab.entries) {
            val selected = t == current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(t) },
                    )
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 激活态：绿色小方块指示（像素风，不用圆点）
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .fillMaxWidth(0.4f)
                        .background(if (selected) PixelColors.Green else PixelColors.Card),
                )
                androidx.compose.material3.Text(
                    text = t.icon,
                    style = PixelType.Body,
                    color = if (selected) PixelColors.GreenDeep else PixelColors.Ink2,
                )
                androidx.compose.material3.Text(
                    text = t.label,
                    style = PixelType.Small,
                    color = if (selected) PixelColors.GreenDeep else PixelColors.Ink2,
                )
            }
        }
    }
}

/** 底部 Tab 顶部一条 2px 深棕描边（像素风的分隔） */
private fun Modifier.pixelBorderTop(): Modifier = this.then(
    Modifier.drawBehind {
        drawRect(
            color = PixelColors.Outline,
            size = Size(size.width, 2.dp.toPx()),
        )
    }
)
