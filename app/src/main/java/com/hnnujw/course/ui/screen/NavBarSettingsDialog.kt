package com.hnnujw.course.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.manager.NavBarLayout
import com.hnnujw.course.manager.StartupPage
import com.hnnujw.course.ui.system.AppSymbol
import com.hnnujw.course.ui.system.AppSymbolSpec
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemIconButton
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import com.hnnujw.course.ui.system.SystemStatusBadge
import com.hnnujw.course.ui.system.SystemTone

/**
 * 「底部导航」编辑弹窗。
 *
 * 交互形态：每行一项，行尾三个图标按钮 —— 上移 / 下移 / 移除。
 * 底部一个「恢复默认导航」。
 *
 * 页面不能新增，只能重排 + 显隐：可选项就是现有这 5 个页面
 * （见 [NavBarLayout] 的注释）。
 *
 * 所有判断都走后端的纯函数（[NavBarLayout.moveUp] / [moveDown] / [canRemove]），
 * 本层只做「渲染 + 把结果回传」，不放任何业务判断 —— 项目红线。
 */
@Composable
fun NavBarSettingsDialog(
    pages: List<StartupPage>,
    onPagesChange: (List<StartupPage>) -> Unit,
    onDismiss: () -> Unit
) {
    // 以调用方传入的实测布局为初值；本弹窗内所有编辑都先落在这份局部状态上，
    // 每次变更立刻回调出去（调用方负责持久化 + 让底栏重排）。
    var items by remember(pages) { mutableStateOf(pages) }

    fun apply(next: List<StartupPage>) {
        items = next
        onPagesChange(next)
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "底部导航",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "调整顺序、收起不常用的页面，「我的」始终保留",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEachIndexed { index, page ->
                NavBarEditRow(
                    page = page,
                    index = index,
                    lastIndex = items.lastIndex,
                    onMoveUp = {
                        val next = NavBarLayout.moveUp(items, index)
                        if (next == null) GlassToaster.show("已经是第一位了") else apply(next)
                    },
                    onMoveDown = {
                        val next = NavBarLayout.moveDown(items, index)
                        if (next == null) GlassToaster.show("已经是最后一位了") else apply(next)
                    },
                    onRemove = {
                        when (NavBarLayout.canRemove(items, page)) {
                            is NavBarLayout.RemoveCheck.Ok -> apply(items - page)
                            is NavBarLayout.RemoveCheck.CannotRemoveSettings ->
                                GlassToaster.show("「我的」是进入设置的入口，不能移除")
                            is NavBarLayout.RemoveCheck.TooFewItems ->
                                GlassToaster.show("底栏至少保留 ${NavBarLayout.MIN_ITEMS} 项")
                            is NavBarLayout.RemoveCheck.NotPresent -> Unit
                        }
                    }
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = "当前保留 ${items.size} / ${StartupPage.entries.size} 项。" +
                            "底栏至少保留 ${NavBarLayout.MIN_ITEMS} 项，「我的」不可移除 —— " +
                            "否则改坏了就再也进不来这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            SystemSecondaryButton(
                text = "恢复默认导航",
                onClick = {
                    apply(NavBarLayout.default)
                    GlassToaster.show("已恢复默认导航")
                },
                modifier = Modifier.fillMaxWidth().testTag("nav-bar-restore"),
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

/** 编辑列表里的一行：图标 + 名字 + 上移/下移/移除。 */
@Composable
private fun NavBarEditRow(
    page: StartupPage,
    index: Int,
    lastIndex: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    val isPinned = page == NavBarLayout.pinned
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nav-bar-row-${page.route}"),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconSpecView(
                spec = page.toSymbolSpec(),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = page.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isPinned) {
                    SystemStatusBadge(text = "常驻 · 不可移除", tone = SystemTone.Info)
                }
            }
            SystemIconButton(
                icon = Icons.Filled.KeyboardArrowUp,
                contentDescription = "上移",
                onClick = onMoveUp,
                enabled = index > 0,
                chip = false
            )
            SystemIconButton(
                icon = Icons.Filled.KeyboardArrowDown,
                contentDescription = "下移",
                onClick = onMoveDown,
                enabled = index < lastIndex,
                chip = false
            )
            // 「我的」不显示移除按钮：那会形成死循环（改坏了再也改不回来）。
            // 这是刻意的 —— 不是忘了加。
            if (!isPinned) {
                SystemIconButton(
                    icon = Icons.Outlined.RemoveCircleOutline,
                    contentDescription = "移除",
                    onClick = onRemove,
                    tint = com.hnnujw.course.ui.theme.SemanticDanger,
                    chip = false
                )
            } else {
                // 占位保持行尾图标对齐，视觉上不会因为少一个按钮而让文字区变宽。
                Spacer(modifier = Modifier.size(40.dp))
            }
        }
    }
}

/**
 * 底栏图标（静态版）：[AppSymbol] 的 `progress = 1f` 即「完全展开」的定态。
 * 编辑列表里不需要动画，只要那一枚看得清的线图标。
 */
@Composable
private fun IconSpecView(
    spec: AppSymbolSpec,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    AppSymbol(spec = spec, progress = 1f, tint = tint, modifier = modifier)
}

/** 页面 → 底栏图标。与 [com.hnnujw.course.BottomNavItem] 的映射保持同一口径。 */
private fun StartupPage.toSymbolSpec(): AppSymbolSpec = when (this) {
    StartupPage.Schedule -> AppSymbolSpec.Schedule
    StartupPage.Xuegong -> AppSymbolSpec.Xuegong
    StartupPage.Grades -> AppSymbolSpec.Grades
    StartupPage.SecondClass -> AppSymbolSpec.Achievement
    StartupPage.Settings -> AppSymbolSpec.Settings
}
