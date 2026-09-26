package com.hnnujw.course.ui.system

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hnnujw.course.ykt.SceneOption

/**
 * 宿舍电费的**分级滚轮**选择器（支持任意级数）。
 *
 * ## 为什么是"一次一级"而不是多列通铺
 *
 * 电费链路是 校区 → 楼栋 → 楼层 → 房间 多级（**级数由服务端决定**，
 * 见 [YktSceneLevel]），把多列塞进一个弹窗，每列只剩很窄的宽度，
 * "7号学生公寓A区"这种名字必然被截断，用户根本分不清。
 * 真机上的官方页也是**逐级弹一个列表**，一次只让用户做一次决定。
 *
 * ## 选项数据由调用方提供
 *
 * 拉取级联数据要发网络请求，且**每一级都依赖上一级的结果**。为了不把
 * 网络逻辑塞进这个纯 UI 组件，约定：
 * - [options] 是**当前这一级**的候选项（调用方负责按当前选择拉好）
 * - [loading] 为 true 时显示加载态而不是空列表
 * - 选完由调用方决定"下一步弹哪一级"或"出读数"
 *
 * @param title 本级的标题（如"选择楼栋"），由调用方按服务端级名给出
 * @param emptyHint 本级无候选时的提示
 * @param options 当前级的候选项
 * @param loading 当前级是否正在加载
 * @param error 加载失败文案（非空时优先显示）
 * @param initialIndex 初始选中下标（用于回显上次选择）
 * @param onConfirm 确认，回传选中的选项（null = 用户没选）
 */
@Composable
fun SceneWheelPickerDialog(
    title: String,
    options: List<SceneOption>,
    loading: Boolean,
    onConfirm: (SceneOption?) -> Unit,
    onDismiss: () -> Unit,
    error: String = "",
    emptyHint: String = "暂无可选项。",
    initialIndex: Int = 0,
    modifier: Modifier = Modifier,
) {
    var index by remember(options, title) {
        mutableIntStateOf(initialIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0)))
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "确定",
                onClick = { onConfirm(options.getOrNull(index)) },
                enabled = !loading && error.isBlank() && options.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) { SystemLoadingState(text = "正在加载…") }

            error.isNotBlank() -> Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    // 不要把失败原因写死成"网络"：令牌失效、服务端故障都会走到这里，
                    // 写死网络会误导用户反复切换网络而放弃正确的处置（重新登录）。
                    text = "可取消后重试；若提示登录失效，请重新登录一卡通。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            options.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = emptyHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Box(modifier = modifier.fillMaxWidth()) {
                WheelCenterLens(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .align(Alignment.Center)
                )
                GlassWheelColumn(
                    items = options.map { it.display },
                    selectedIndex = index,
                    onSelect = { index = it },
                    modifier = Modifier.fillMaxWidth(),
                    showCenterIndicator = false,
                )
            }
        }
    }
}
