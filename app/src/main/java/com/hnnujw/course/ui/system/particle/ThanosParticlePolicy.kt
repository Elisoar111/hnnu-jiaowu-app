package com.hnnujw.course.ui.system.particle

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Thanos 粒子消散的**策略层**（纯算术，无 Android / GL 依赖，可被 JVM 单测覆盖）。
 *
 * 移植自 Telegram / NagramX 的 ThansEffect 实现（经参考项目 BiliPai 引入）。
 * 原始来源：
 *  - NagramX 2db685af00a4352c877ecf96474cbf0494284715, ThanosEffect.java
 *  - Telegram f2908b14133bbffbf7ab04f641ecb5bfaf533242, 同一份 shader
 * 两个 glsl 与本文件同目录之外（res/raw/thanos_vertex.glsl、thanos_fragment.glsl）
 * 与上游**逐字节相同**，没有改过一个字符。上游按 GPL 分发，与本仓库许可一致。
 *
 * 这里只保留策略常数与网格算法 —— 渲染在 [ParticleRenderer]，宿主在 [ThanosEffectView]。
 */

/** 粒子存活时长（秒），上游同值。 */
internal const val THANOS_LONGEVITY = 1.5f

/** 时间缩放：真实秒数乘以它才是着色器里的 time。 */
internal const val THANOS_TIME_SCALE = 1.15f

/** 尾段时长（秒）。 */
internal const val THANOS_TAIL_SECONDS = 0.9f

/** 整段动画时长（秒）。(1.5 + 0.9) / 1.15 ≈ 2.09 秒，与上游一致。 */
internal const val THANOS_DURATION_SECONDS = THANOS_LONGEVITY + THANOS_TAIL_SECONDS

/** 快速档的时间缩放。 */
internal const val THANOS_QUICK_TIME_SCALE = THANOS_DURATION_SECONDS / 0.7f

/** Transform Feedback 每个粒子的步长：uv(2) + position(2) + velocity(2) + time(1) = 7 个 float。 */
internal const val THANOS_PARTICLE_STRIDE_BYTES = 28

/** 收尾的重叠窗口：列表回流在粒子播完前 180ms 就开始，但模拟本身不缩短。 */
internal const val THANOS_REFLOW_OVERLAP_SECONDS = 0.18f

/** GLES3 才支持 Transform Feedback；低于 3.0 直接不播粒子（而不是降级成别的效果）。 */
internal fun supportsThanosGlVersion(glEsVersion: Int): Boolean = glEsVersion >= 0x30000

/** 粒子网格：列数、行数、以及每个点覆盖的像素尺寸。 */
internal data class ThanosParticleGrid(
    val columns: Int,
    val rows: Int,
    val pointSize: Float,
) {
    val count: Int get() = columns * rows
}

/**
 * 上游 Animation.calcParticlesGrid 的直译：按密度估粒子数，再按宽高比拆成行列，
 * 最后补齐到不小于目标数（多出来的部分落在最后一行/列，和上游一样）。
 *
 * 三档预算与上游一致：低内存设备 30,000、普通 60,000、大内存 120,000。
 */
internal fun resolveThanosParticleGrid(
    width: Int,
    height: Int,
    density: Float,
    maxParticles: Int,
): ThanosParticleGrid {
    require(width > 0 && height > 0) { "粒子网格需要有效的宽高" }
    val spacing = max(density * 0.4f, 1f)
    val count = (width.toDouble() * height / (spacing * spacing)).toInt()
        .coerceIn(10, maxParticles.coerceAtLeast(10))
    val aspect = width.toDouble() / height
    var rows = sqrt(count / aspect).roundToInt().coerceAtLeast(1)
    var columns = (count.toDouble() / rows).roundToInt().coerceAtLeast(1)
    while (columns * rows < count) {
        if (columns.toDouble() / rows < aspect) columns++ else rows++
    }
    // 补齐循环会让总数略微越过 count（例如 43×28 = 1204 > 1200）。上游不在意这点
    // 溢出，但预算是按显存/内存算出来的硬上限：超了就沿更富余的那条边收一格。
    val budget = maxParticles.coerceAtLeast(10)
    while (columns * rows > budget && columns > 1 && rows > 1) {
        if (columns.toDouble() / rows > aspect) columns-- else rows--
    }
    return ThanosParticleGrid(columns, rows, max(width.toFloat() / columns, height.toFloat() / rows))
}

/** 动画是否播完 —— 判据只有一个，且不可重入。 */
internal fun shouldNotifyParticleAnimationComplete(
    hasAnimationCompleted: Boolean,
    currentTimeSec: Float,
    animationDurationSec: Float,
): Boolean = !hasAnimationCompleted && currentTimeSec > animationDurationSec

/** 是否进入尾段（用于提前回流，不改变粒子模拟）。 */
internal fun shouldBeginThanosReflow(
    currentTimeSec: Float,
    timeScale: Float = THANOS_TIME_SCALE,
): Boolean = currentTimeSec >= THANOS_DURATION_SECONDS - THANOS_REFLOW_OVERLAP_SECONDS * timeScale

/**
 * 按设备内存分档给的粒子预算，与上游三档一致。
 *
 * @param isLowRam 系统自己报告的 low-RAM 设备
 * @param memoryClass 应用的 Java 堆上限（MB）
 */
internal fun particleBudget(isLowRam: Boolean, memoryClass: Int): Int = when {
    isLowRam -> 30_000
    memoryClass >= 256 -> 120_000
    else -> 60_000
}
