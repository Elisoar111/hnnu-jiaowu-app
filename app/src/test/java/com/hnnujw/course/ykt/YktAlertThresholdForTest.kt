package com.hnnujw.course.ykt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 电费阈值「按量纲分派」的真机行为回归（Robolectric 提供真实 SharedPreferences）。
 *
 * 为什么单独一个类：`thresholdFor` 要读 prefs，纯 JVM 单测里 `Context` 是空桩，
 * 传 null 根本编译不过。而这个分派恰恰是**最该被钉住**的一处 ——
 * 单位认错一次，就是把度数当金额、把 312 度当成 312 元，提醒直接失效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YktAlertThresholdForTest {

    private lateinit var context: Context

    private fun setUpContext() {
        context = ApplicationProvider.getApplicationContext()
        // 每个用例从干净状态开始：prefs 是全局文件，用例之间会互相污染。
        context.getSharedPreferences("ykt_alert_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `金额型返回金额阈值`() {
        setUpContext()
        YktAlertSettings.setThresholdYuan(context, 35.0)
        assertEquals(35.0, YktAlertSettings.thresholdFor(context, ElectricityUnit.YUAN)!!, 0.001)
    }

    @Test
    fun `度数型返回度数阈值`() {
        setUpContext()
        YktAlertSettings.setThresholdKwh(context, 8.0)
        assertEquals(8.0, YktAlertSettings.thresholdFor(context, ElectricityUnit.KWH)!!, 0.001)
    }

    /** 🔴 核心：两套阈值互不影响。改金额不能动到度数。 */
    @Test
    fun `金额与度数阈值互相独立`() {
        setUpContext()
        YktAlertSettings.setThresholdYuan(context, 100.0)
        YktAlertSettings.setThresholdKwh(context, 5.0)
        assertEquals(100.0, YktAlertSettings.thresholdYuan(context), 0.001)
        assertEquals(5.0, YktAlertSettings.thresholdKwh(context), 0.001)
    }

    /** 🔴 单位未知时必须不给阈值 —— 调用方据此决定"不提醒"。 */
    @Test
    fun `单位未知时返回 null`() {
        setUpContext()
        assertNull(YktAlertSettings.thresholdFor(context, ElectricityUnit.UNKNOWN))
    }

    @Test
    fun `没设置过时返回各自的默认值`() {
        setUpContext()
        assertEquals(
            YktAlertSettings.DEFAULT_THRESHOLD_YUAN,
            YktAlertSettings.thresholdFor(context, ElectricityUnit.YUAN)!!,
            0.001
        )
        assertEquals(
            YktAlertSettings.DEFAULT_THRESHOLD_KWH,
            YktAlertSettings.thresholdFor(context, ElectricityUnit.KWH)!!,
            0.001
        )
    }

    /** 存进去的脏数据（越界/非数字）必须回落，不能让提醒"永不触发"。 */
    @Test
    fun `脏数据回落到合法范围`() {
        setUpContext()
        context.getSharedPreferences("ykt_alert_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("threshold_yuan", "not-a-number")
            .putString("threshold_kwh", "-99")
            .commit()
        assertEquals(YktAlertSettings.DEFAULT_THRESHOLD_YUAN, YktAlertSettings.thresholdYuan(context), 0.001)
        assertEquals(YktAlertSettings.MIN_THRESHOLD_KWH, YktAlertSettings.thresholdKwh(context), 0.001)
    }

    @Test
    fun `阈值写入会被夹取`() {
        setUpContext()
        YktAlertSettings.setThresholdKwh(context, 99999.0)
        assertEquals(YktAlertSettings.MAX_THRESHOLD_KWH, YktAlertSettings.thresholdKwh(context), 0.001)
        YktAlertSettings.setThresholdYuan(context, 0.0)
        assertEquals(YktAlertSettings.MIN_THRESHOLD_YUAN, YktAlertSettings.thresholdYuan(context), 0.001)
    }

    @Test
    fun `金额型与度数型都给出非空阈值`() {
        setUpContext()
        assertNotNull(YktAlertSettings.thresholdFor(context, ElectricityUnit.YUAN))
        assertNotNull(YktAlertSettings.thresholdFor(context, ElectricityUnit.KWH))
    }
}
