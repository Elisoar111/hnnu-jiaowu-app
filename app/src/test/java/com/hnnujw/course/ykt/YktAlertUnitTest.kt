package com.hnnujw.course.ykt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电费提醒「按量纲取阈值」的回归。
 *
 * 守的是**单位不能混**这一条：把 312.2 度当成 312.2 元会直接绕过 20 元阈值，
 * 用户以为余额充足、结果宿舍断电。这也是 `YktBalance` 注释里记着的那次误判风险。
 */
class YktAlertUnitTest {

    // —— thresholdFor 的分派需要真实 Context，见 YktAlertThresholdForTest（Robolectric）


    // —— 阈值夹取

    @Test
    fun `金额阈值夹到合法范围`() {
        assertEquals(YktAlertSettings.MIN_THRESHOLD_YUAN, YktAlertSettings.clampThreshold(0.0), 0.001)
        assertEquals(YktAlertSettings.MIN_THRESHOLD_YUAN, YktAlertSettings.clampThreshold(-5.0), 0.001)
        assertEquals(YktAlertSettings.MAX_THRESHOLD_YUAN, YktAlertSettings.clampThreshold(9999.0), 0.001)
        assertEquals(30.0, YktAlertSettings.clampThreshold(30.0), 0.001)
    }

    @Test
    fun `金额阈值遇到非法值回默认`() {
        assertEquals(
            YktAlertSettings.DEFAULT_THRESHOLD_YUAN,
            YktAlertSettings.clampThreshold(Double.NaN),
            0.001
        )
        assertEquals(
            YktAlertSettings.DEFAULT_THRESHOLD_YUAN,
            YktAlertSettings.clampThreshold(Double.POSITIVE_INFINITY),
            0.001
        )
    }

    @Test
    fun `度数阈值夹到合法范围`() {
        assertEquals(YktAlertSettings.MIN_THRESHOLD_KWH, YktAlertSettings.clampKwhThreshold(0.0), 0.001)
        assertEquals(YktAlertSettings.MAX_THRESHOLD_KWH, YktAlertSettings.clampKwhThreshold(9999.0), 0.001)
        assertEquals(50.0, YktAlertSettings.clampKwhThreshold(50.0), 0.001)
    }

    @Test
    fun `度数阈值遇到非法值回默认`() {
        assertEquals(
            YktAlertSettings.DEFAULT_THRESHOLD_KWH,
            YktAlertSettings.clampKwhThreshold(Double.NaN),
            0.001
        )
    }

    /** 两套阈值的范围必须真的不同：度数上限比金额宽松（宿舍可能存上百元/度）。 */
    @Test
    fun `金额与度数的取值范围互相独立`() {
        assertTrue(
            "度数上限应当比金额宽松",
            YktAlertSettings.MAX_THRESHOLD_KWH < YktAlertSettings.MAX_THRESHOLD_YUAN
        )
        assertEquals(20.0, YktAlertSettings.DEFAULT_THRESHOLD_YUAN, 0.001)
        assertEquals(20.0, YktAlertSettings.DEFAULT_THRESHOLD_KWH, 0.001)
    }

    // —— 通知文案的量纲

    @Test
    fun `金额型文案含元且不含度`() {
        val text = YktAlertPolicy.notificationText(12.5, 20.0, ElectricityUnit.YUAN)
        assertTrue("应含 ¥: $text", text.contains("¥"))
        assertFalse("不该出现'度': $text", text.contains("度"))
        assertTrue("应提示充值: $text", text.contains("充值"))
    }

    @Test
    fun `度数型文案含度且不含元`() {
        val text = YktAlertPolicy.notificationText(8.3, 20.0, ElectricityUnit.KWH)
        assertTrue("应含'度': $text", text.contains("度"))
        assertFalse("不该出现 ¥: $text", text.contains("¥"))
        assertTrue("应提示购电: $text", text.contains("购电"))
    }

    @Test
    fun `标题按量纲区分`() {
        assertTrue(YktAlertPolicy.notificationTitle(ElectricityUnit.YUAN).contains("余额"))
        assertTrue(YktAlertPolicy.notificationTitle(ElectricityUnit.KWH).contains("电量"))
    }

    /** 默认参数保持旧行为：既有调用点不传 unit 时仍是金额口径。 */
    @Test
    fun `不传单位时按金额处理`() {
        val text = YktAlertPolicy.notificationText(12.5, 20.0)
        assertTrue(text.contains("¥"))
        assertEquals(
            YktAlertPolicy.notificationTitle(ElectricityUnit.YUAN),
            YktAlertPolicy.notificationTitle()
        )
    }

    /** 文案必须把读数和阈值都写出来，用户才知道是"谁触发的"。 */
    @Test
    fun `文案同时包含读数与阈值`() {
        val text = YktAlertPolicy.notificationText(8.3, 20.0, ElectricityUnit.KWH)
        assertTrue("缺读数: $text", text.contains("8.30"))
        assertTrue("缺阈值: $text", text.contains("20.00"))
    }
}
