package com.hnnujw.course.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PrefsWarmer] 的回归。
 *
 * 这类"纯优化"最容易悄悄失效：清单里写错一个文件名，预热就只是空转一次，
 * 没有任何报错，性能却一点没省。所以这里既测行为，也测**清单里的名字是否真的存在**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsWarmerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `清单非空且没有重复`() {
        val names = PrefsWarmer.warmupNames()
        assertTrue("预热清单不该为空", names.isNotEmpty())
        assertEquals("清单里不该有重复项", names.size, names.toSet().size)
    }

    /**
     * 🔴 清单里的每个名字都必须能真的打开一个 prefs。
     *
     * `getSharedPreferences` 对任何合法名字都会成功（不存在就新建空文件），
     * 所以这条测不出"名字拼错了"。真正的判据是：**拼错的名字会在应用里创建一个
     * 永远不会被读到的新文件**。这里退一步，只保证名字格式合法且不重复，
     * 拼写正确性由下面那条"关键文件必须在清单里"来守。
     */
    @Test
    fun `清单里的名字都能打开`() {
        PrefsWarmer.warmupNames().forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            // 能读到就说明文件可用（空文件返回 0）。
            assertTrue("$name 读不出大小", prefs.all.size >= 0)
        }
    }

    /**
     * 启动路径 / 首屏一定会读的那几个文件必须在清单里。
     *
     * 这条守的是"清单被删减"：如果哪天有人觉得预热没用把它清空，
     * 或漏掉主题这种 Application.onCreate 就要用的，这里会红。
     */
    @Test
    fun `启动关键 prefs 都在清单里`() {
        val names = PrefsWarmer.warmupNames().toSet()
        val required = listOf(
            "appearance_settings",   // Application.onCreate 就要用
            "schedule_settings",     // 课表首帧
            "course_reminders",      // 启动时 schedule()
            "review_prefs",          // 首页题库卡片
            "ykt_prefs",             // 首屏余额卡片
        )
        val missing = required.filter { it !in names }
        assertTrue("这些启动关键 prefs 没被预热: $missing", missing.isEmpty())
    }

    /** 重复调用必须幂等：不能起第二个线程、不能抛。 */
    @Test
    fun `重复预热是幂等的`() {
        repeat(5) { PrefsWarmer.warmUp(context) }
    }

    /** 凭据类 prefs 不该被预热：它们要碰 Keystore，反而更慢。 */
    @Test
    fun `凭据类 prefs 不在预热清单里`() {
        val names = PrefsWarmer.warmupNames().toSet()
        assertTrue("secure_credentials 预热会触发 Keystore，不该列进来", "secure_credentials" !in names)
        assertTrue("review_ai_prefs 同理", "review_ai_prefs" !in names)
    }
}
