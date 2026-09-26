package com.hnnujw.course.ui.system

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Shared page shell for forms, history and standalone browser activities. */
@Composable
fun GlassPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    /**
     * 顶栏折叠进度（0=展开大标题，1=收成细玻璃条）。
     *
     * 默认 0 表示"不折叠"，因此旧调用点行为不变。需要跟手折叠的页面
     * 自己持有 [androidx.compose.foundation.ScrollState]，把
     * `(scrollState.value / 96f).coerceIn(0f, 1f)` 传进来即可 ——
     * 与课表 / 成绩 / 设置 / 二课页用的是同一套判据。
     */
    collapseFraction: Float = 0f,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val page: @Composable () -> Unit = {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                bottomBar = bottomBar,
                topBar = {
                    SystemTopBar(
                        title = title,
                        subtitle = subtitle,
                        collapseFraction = collapseFraction,
                        navigationIcon = {
                            if (onBack != null) SystemIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
                        },
                        actions = actions
                    )
                }
            ) { padding ->
                content(PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = maxOf(padding.calculateBottomPadding(), LocalAppOverlayBottomInset.current)
                ))
            }
    }
    if (LocalDialogHost.current == null) {
        GlassWindowHost(modifier) { page() }
    } else {
        Box(modifier.fillMaxSize()) { page() }
    }
}
