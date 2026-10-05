package com.hnnujw.course.review.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.ui.system.GlassLoadingIndicator
import com.hnnujw.course.ui.system.LiquidButton
import com.hnnujw.course.ui.system.LiquidButtonStyle
import com.kyant.shapes.Capsule

/**
 * 复习模块共用的小控件。
 *
 * 全部走项目的 [LiquidButton]（液态玻璃），不自己造 Material 按钮 ——
 * 否则复习页会在一屏玻璃里混进一个实心方块，视觉上很跳。
 */

/** 主操作按钮（「确认答案」「开始练习」「提交」）。 */
internal val ConfirmButtonHeight = 48.dp

/**
 * 次级操作按钮的默认高度，比主按钮矮一档 —— 主次靠**高度 + 样式**双重区分。
 *
 * ⚠️ 但**与主按钮并排时必须传 `height = ConfirmButtonHeight`**：`Row` 默认按
 * `Alignment.Top` 排列，两颗不等高会在底部露出参差的一截（练习页底栏、题库仓库页
 * 的「创建题库 / 合并题库」都踩过）。
 */
internal val SecondaryButtonHeight = 42.dp

@Composable
fun ConfirmButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LiquidButton(
        onClick = onClick,
        enabled = enabled,
        style = LiquidButtonStyle.SolidTinted,
        minHeight = ConfirmButtonHeight,
        modifier = modifier
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 次级操作（「再想一下」「问 AI」「合并题库」）。 */
@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    /**
     * 按钮高度，默认 [SecondaryButtonHeight]。
     *
     * 🔴 **与主按钮（[ConfirmButton]）并排时必须传 [ConfirmButtonHeight]**，否则两颗
     * 一高一低、底部参差 —— 见 [SecondaryButtonHeight] 的注释。
     */
    height: Dp = SecondaryButtonHeight,
    modifier: Modifier = Modifier
) {
    LiquidButton(
        onClick = onClick,
        enabled = enabled,
        style = LiquidButtonStyle.Tinted,
        shape = Capsule(),
        minHeight = height,
        modifier = modifier.height(height)
    ) {
        Text(text = text, fontSize = 14.sp, lineHeight = 18.sp)
    }
}

/** 加载中的行内指示（问 AI 等待时用）。 */
@Composable
fun InlineSpinner(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 用玻璃转圈而不是 Material 的裸 CircularProgressIndicator：
        // 否则一屏玻璃里会混进一个实心深色圆环，视觉上很跳。
        GlassLoadingIndicator(size = 18.dp)
        Text(
            text = label,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
