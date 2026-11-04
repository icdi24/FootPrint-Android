package me.footprint.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.footprint.app.ui.theme.PixelColors

/**
 * 像素风基础外观：
 *  Compose 的 Modifier.shadow 是柔和阴影，像素风要的是**偏移的实心矩形**，
 *  所以用 drawBehind 先画一块偏移 (dx, dy) 的深色底，再把内容盖在上面。
 */

/** 硬投影：在内容后面画一块偏移的深棕矩形。用法：Modifier.pixelHardShadow(2.dp).background(...) */
fun Modifier.pixelHardShadow(
    offset: Dp = 3.dp,
    color: Color = PixelColors.Outline,
): Modifier = drawBehind {
    val o = offset.toPx()
    drawRect(
        color = color,
        topLeft = Offset(o, o),
        size = Size(size.width, size.height),
    )
}

/** 2px 深棕描边（像素世界没有圆角） */
fun Modifier.pixelBorder(
    width: Dp = 2.dp,
    color: Color = PixelColors.Outline,
): Modifier = border(width, color)

/** 一块像素面板：羊皮纸卡片 + 2px 描边 + 硬投影 */
@Composable
fun PixelPanel(
    modifier: Modifier = Modifier,
    background: Color = PixelColors.Card,
    borderColor: Color = PixelColors.Outline,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .pixelHardShadow(3.dp, PixelColors.Outline)
            .background(background)
            .pixelBorder(2.dp, borderColor)
    ) {
        content()
    }
}

/**
 * 页面地面：羊皮纸底 + 9px 点阵纹理（对应 DESIGN.md 的"点阵纹理"）。
 * 点阵只画一层很淡的圆点，性能上没问题（一次性绘制）。
 */
fun Modifier.parchmentGround(): Modifier = drawBehind {
    drawRect(PixelColors.Bg)
    val step = 9.dp.toPx()
    val r = 1.dp.toPx()
    val dot = Color(0x143B2E22) // 很淡的深棕
    var y = 0f
    while (y < size.height) {
        var x = 0f
        while (x < size.width) {
            drawCircle(color = dot, radius = r, center = Offset(x, y))
            x += step
        }
        y += step
    }
}

/** 虚线分隔（2px 暖棕），列表用 */
fun Modifier.dashedDivider(color: Color = PixelColors.LineStrong): Modifier = drawBehind {
    val dash = 6.dp.toPx()
    val gap = 4.dp.toPx()
    val h = 2.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawRect(
            color = color,
            topLeft = Offset(x, size.height - h),
            size = Size(minOf(dash, size.width - x), h),
        )
        x += dash + gap
    }
}

@Composable
fun Screen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .parchmentGround()
            .padding(16.dp)
    ) {
        content()
    }
}
