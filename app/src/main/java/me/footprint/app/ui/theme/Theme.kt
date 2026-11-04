package me.footprint.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import me.footprint.app.R

/**
 * 像素风配色，取自 DESIGN.md 的令牌表。
 *
 * 字体：Fusion Pixel 12px（与 Web 版同一套字体，woff2 转 ttf 打进 res/font）。
 * zh_hans 子集已包含全部拉丁字符，所以只需两个字重文件。
 */
val PixelFont: FontFamily = FontFamily(Font(R.font.fusion_pixel_zh))
val PixelFontMono: FontFamily = FontFamily(Font(R.font.fusion_pixel_mono_zh))

object PixelColors {
    val Bg = Color(0xFFFAF3E3)          // 羊皮纸地面
    val Bg2 = Color(0xFFF3E9D2)         // 次级面板
    val Card = Color(0xFFFFFDF6)        // 卡片
    val Ink = Color(0xFF46382B)         // 正文深棕
    val Ink2 = Color(0xFF6E5C46)        // 次级文字
    val Ink3 = Color(0xFF7D6C56)        // 弱化文字（比 DESIGN 的 #8A7A62 略深，保住对比度）
    val Line = Color(0xFFE9DFC7)
    val LineStrong = Color(0xFFD8C9A6)
    val Outline = Color(0xFF3B2E22)     // 2px 像素描边
    val Green = Color(0xFF3F7C37)       // 主色
    val GreenDeep = Color(0xFF2E6228)
    val GreenSoft = Color(0xFFE2EFDA)
    val Orange = Color(0xFFE8953C)
    val OrangeDeep = Color(0xFF8A4E0F)
    val OrangeSoft = Color(0xFFFBEDD5)
    val Red = Color(0xFFB44A40)
    val RedDeep = Color(0xFF93342B)
    val Gold = Color(0xFFF2B84B)
    val White = Color(0xFFFFFFFF)
}

/** 字号只用 12 的整数倍（12 / 24 / 36），全部用 Fusion Pixel 像素字体（与 Web 版同款） */
object PixelType {
    val Body: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, fontFamily = PixelFont)
    val Small: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontFamily = PixelFont)
    val Title: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, fontFamily = PixelFont)
    val Big: TextStyle = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, fontFamily = PixelFont)
    val Huge: TextStyle = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, fontFamily = PixelFont)
    /** 数字/计数用的等宽样式（积分、完成数、时间），对应 Web 版的 FusionPixelMono */
    val Mono: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontFamily = PixelFontMono)
}

private val colorScheme = lightColorScheme(
    primary = PixelColors.Green,
    onPrimary = PixelColors.White,
    primaryContainer = PixelColors.Green,
    onPrimaryContainer = PixelColors.White,
    secondary = PixelColors.Orange,
    secondaryContainer = PixelColors.Orange,
    onSecondaryContainer = PixelColors.White,
    background = PixelColors.Bg,
    onBackground = PixelColors.Ink,
    surface = PixelColors.Card,
    onSurface = PixelColors.Ink,
    onSurfaceVariant = PixelColors.Ink3,
    outline = PixelColors.Outline,
    error = PixelColors.Red,
)

@Composable
fun FootprintTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(bodyLarge = PixelType.Body, bodyMedium = PixelType.Small),
        content = content,
    )
}
