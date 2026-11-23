package com.hnnujw.course.ui.system.particle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thanos 粒子消散策略层的测试（移植自 Telegram / NagramX，经参考项目引入）。
 *
 * 渲染本身只能上真机看，但「网格怎么算、预算给多少、什么时候算播完」这些口径是纯算术，
 * 一旦被改错就会表现为「粒子太稀 / 低端机卡死 / 弹层卡住两秒不关」，所以在这里钉住。
 */
class ThanosParticlePolicyTest {

    @Test
    fun glVersionGateRequiresGlEs3() {
        // Transform Feedback 是 GLES3 特性；2.0 设备必须直接跳过粒子，而不是降级硬跑。
        assertFalse("GLES2 不支持", supportsThanosGlVersion(0x20000))
        assertTrue("GLES3.0 支持", supportsThanosGlVersion(0x30000))
        assertTrue("GLES3.2 也支持", supportsThanosGlVersion(0x30002))
    }

    @Test
    fun budgetKeepsTheThreeUpstreamTiers() {
        // 三档与上游一致：低内存 3 万、普通 6 万、大内存 12 万。
        assertEquals(30_000, particleBudget(isLowRam = true, memoryClass = 512))
        assertEquals(60_000, particleBudget(isLowRam = false, memoryClass = 128))
        assertEquals(120_000, particleBudget(isLowRam = false, memoryClass = 256))
    }

    @Test
    fun gridPreservesAspectRatioAtEveryBudget() {
        listOf(30_000, 60_000, 120_000).forEach { budget ->
            val grid = resolveThanosParticleGrid(1080, 720, 3f, budget)
            assertTrue("粒子数不能超过预算", grid.count <= budget)
            // 16:9 的图，列数必须多于行数；反了就是粒子按竖条扫描。
            assertTrue("宽图应列多于行", grid.columns > grid.rows)
        }
    }

    @Test
    fun tinySnapshotStillYieldsAPositiveGrid() {
        // 极小的抓图（弹层只露出一条）也不能除零或返回 0 个粒子。
        val grid = resolveThanosParticleGrid(1, 1, 1f, 30_000)
        assertTrue(grid.columns >= 1) 
        assertTrue(grid.rows >= 1)
        assertTrue("点尺寸必须为正，否则 gl_PointSize 非法", grid.pointSize > 0f)
    }

    @Test
    fun durationMatchesUpstreamTimeline() {
        // (1.5 + 0.9) / 1.15 ≈ 2.09 秒 —— 弹层要在这之后才真正关闭。
        val seconds = THANOS_DURATION_SECONDS / THANOS_TIME_SCALE
        assertTrue("总时长应在 2 秒左右，实际 ${'$'}seconds", seconds > 1.9f && seconds < 2.2f)
    }

    @Test
    fun completionFiresExactlyOnceAndAfterTheTail() {
        assertFalse("还没到时长不能完成", shouldNotifyParticleAnimationComplete(false, 0.5f, THANOS_DURATION_SECONDS))
        assertTrue(shouldNotifyParticleAnimationComplete(false, THANOS_DURATION_SECONDS + 0.01f, THANOS_DURATION_SECONDS))
        // 重组会重复调用，报过一次就不能再报 —— 否则会连着关两次弹层。
        assertFalse("已上报过就不能再上报", shouldNotifyParticleAnimationComplete(true, 9f, THANOS_DURATION_SECONDS))
    }

    @Test
    fun reflowStartsBeforeParticlesFinish() {
        // 回流在尾段开始（重叠加 180ms 的缩放），但模拟本身不被截断。
        val end = THANOS_DURATION_SECONDS
        assertFalse("中段不该回流", shouldBeginThanosReflow(end * 0.5f))
        assertTrue("尾段应回流", shouldBeginThanosReflow(end - 0.01f))
    }
}
