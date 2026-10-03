package com.hnnujw.course.manager

import androidx.compose.runtime.mutableIntStateOf

/**
 * 底栏布局变更信号。
 *
 * 底栏的渲染在 `MainActivity`，而编辑入口在设置页（`SettingsRoute`）→ 设置页弹窗，
 * 三处不在同一棵子树里，靠参数回传会让每一层都要中转一遍。
 *
 * 用全局可观察的 revision 计数器最直接：编辑弹窗写入时自增，
 * 底栏与设置页摘要各自订阅 —— 谁需要刷新谁读它，不需要中间人。
 *
 * 与 [com.hnnujw.course.ui.system.PageDataClearSignal] 是同一套写法。
 */
object NavBarLayoutSignal {
    val revision = mutableIntStateOf(0)

    /** 底栏布局发生变化后调用（写入磁盘之后）。 */
    fun bump() {
        revision.intValue++
    }
}
