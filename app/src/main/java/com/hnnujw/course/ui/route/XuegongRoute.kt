package com.hnnujw.course.ui.route

import androidx.compose.runtime.Composable
import com.hnnujw.course.ui.screen.XuegongScreen

/**
 * 学工系统作为底栏第 1 个 Tab 的承载路由（原独立全屏页 XuegongActivity 上移而来，后者已删除）。
 *
 * 作为 Tab 时不需要返回键：[XuegongScreen] 内部在根页面会把 [onBack] 透传为 null，
 * 仅在打开「请假详情 / 去向登记详情 / 表单」等子页时才显示返回箭头收起子页。
 */
@Composable
fun XuegongRoute(onBack: (() -> Unit)? = null) {
    XuegongScreen(onBack = onBack)
}
