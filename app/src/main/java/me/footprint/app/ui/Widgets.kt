package me.footprint.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.footprint.app.ui.theme.PixelColors
import me.footprint.app.ui.theme.PixelType

/*
 * 像素风控件：按钮、输入框、进度条、徽章。
 * 都用基础组件（Box / BasicTextField）自己画，不用 Material 的 Button、TextField，
 * 这样才有直角、2px 描边、下沉式硬投影。
 *
 * 按压效果：靠 MutableInteractionSource 收 PressInteraction，按下时整体下沉、硬投影消失。
 */

/** 主按钮：绿底白字，2px 深棕描边 + 落地硬影；按下时整体下沉 4px */
@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink: Dp = if (pressed && enabled) 4.dp else 0.dp
    val shadow: Dp = if (enabled && !pressed) 4.dp else 0.dp

    Box(
        modifier = modifier
            .pixelHardShadow(shadow, PixelColors.Outline)
            .offset(y = sink)
            .background(if (enabled) PixelColors.Green else PixelColors.LineStrong)
            .pixelBorder(2.dp, PixelColors.Outline)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                // Compose 1.7+ 要求显式给 indication；传 null 表示不画默认水波纹（像素风自己画按压下沉）
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = PixelType.Body,
            color = if (enabled) PixelColors.White else PixelColors.Ink3,
        )
    }
}

/** 小号按钮：filled=true 绿底白字，false 白底棕字 */
@Composable
fun PixelSmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .pixelHardShadow(if (pressed) 0.dp else 3.dp, PixelColors.Outline)
            .offset(y = if (pressed) 3.dp else 0.dp)
            .background(if (filled) PixelColors.Green else PixelColors.Card)
            .pixelBorder(2.dp, PixelColors.Outline)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = PixelType.Body,
            color = if (filled) PixelColors.White else PixelColors.Ink,
        )
    }
}

/** 像素输入框：2px 描边、无圆角；占位符用弱化色，口令走 PasswordVisualTransformation */
@Composable
fun PixelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
) {
    Box(
        modifier = modifier
            .background(PixelColors.Card)
            .pixelBorder(2.dp, PixelColors.Outline)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, style = PixelType.Body, color = PixelColors.Ink3)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = PixelType.Body.copy(color = PixelColors.Ink),
            cursorBrush = SolidColor(PixelColors.Green),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 像素进度条：外壳 2px 描边，内层按比例填充的实心块 */
@Composable
fun PixelBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = PixelColors.Green,
) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(10.dp)
            .background(PixelColors.Bg2)
            .pixelBorder(2.dp, PixelColors.Outline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(f)
                .height(6.dp)
                .background(color)
        )
    }
}

/** 直角小徽章：用来标"已同步 / 未同步" */
@Composable
fun PixelChip(
    text: String,
    modifier: Modifier = Modifier,
    bg: Color = PixelColors.OrangeSoft,
    fg: Color = PixelColors.OrangeDeep,
) {
    Box(
        modifier = modifier
            .background(bg)
            .pixelBorder(2.dp, fg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = text, style = PixelType.Small, color = fg)
    }
}
