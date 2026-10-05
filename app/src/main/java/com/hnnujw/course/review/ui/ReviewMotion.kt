package com.hnnujw.course.review.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.hnnujw.course.ui.system.rememberGlassAccessibilityMode
import com.hnnujw.course.ui.theme.ModuleMotion
import com.hnnujw.course.ui.theme.moduleEntrance

/**
 * 复习模块的「卡片入场」进度源（淡入 + 上移 16dp）。
 *
 * **为什么不直接用 [com.hnnujw.course.ui.theme.LocalModuleEntrance]**：
 * 那个 CompositionLocal 由 `NavigationMotion` 在 MainActivity 的底栏切页里提供，而复习模块
 * 是独立的 [com.hnnujw.course.ReviewActivity]（另一个 composition），拿不到它的值 ——
 * `moduleEntrance` 里 `LocalModuleEntrance.current ?: return this` 会直接变成**空操作**，
 * 卡片一动不动。所以这里自带一份进度源，通过 [LocalReviewEntrance] 下发。
 *
 * **为什么用「页面级时间轴」而不是每张卡片自己 `LaunchedEffect`**：
 * 每张卡片自持动画的话，`LazyColumn` 里的行**滚回来会重新播一遍**（离开组合再进入），
 * 滑一下列表就满屏闪。页面级时间轴只在进这一页时走一次（605ms），之后所有卡片
 * 读到的进度恒为 1，滚入滚出都稳定。这与 `LocalModuleEntrance` 的注释
 * （"lazy items never own or restart entrance animations"）是同一个设计意图。
 *
 * @param key 时间轴的复位键。传当前页面（`ReviewPage`）—— 换页即重新播放入场。
 */
@Composable
fun rememberReviewEntrance(key: Any?): () -> Float {
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val timeline = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key, reduced) {
        if (reduced) {
            // 系统开了"减弱动效"：直接落到终态，不做动画。
            timeline.snapTo(1f)
            return@LaunchedEffect
        }
        timeline.snapTo(0f)
        // 线性推进整条时间轴；每张卡片的错峰与缓动由 ModuleMotion.progress(group) 负责，
        // 所以这里**不能**再叠一层缓动，否则前段会被压成几乎静止。
        timeline.animateTo(1f, tween(ModuleMotion.TimelineMillis, easing = LinearEasing))
    }
    return remember(timeline) { { timeline.value } }
}

/** 复习模块内的卡片入场时间轴。未提供时 [reviewCardEntrance] 退化为空操作。 */
val LocalReviewEntrance = compositionLocalOf<(() -> Float)?> { null }

/**
 * 给卡片挂上「淡入上移」入场动效。
 *
 * 直接转发给全项目共用的 [moduleEntrance]（alpha 0→1、translationY 16dp→0、
 * `CompositingStrategy.ModulateAlpha` 保住玻璃阴影），只是把进度源换成复习模块自己的。
 * 未提供 [LocalReviewEntrance] 时返回原 Modifier，不做任何事。
 *
 * @param group 错峰组号（0..[ModuleMotion.MaxGroups]），同屏卡片按它依次入场。
 */
@Composable
fun Modifier.reviewCardEntrance(group: Int = 0): Modifier {
    val progress = LocalReviewEntrance.current ?: return this
    return moduleEntrance(group = group, progress = progress)
}
