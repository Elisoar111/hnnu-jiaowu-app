package com.hnnujw.course.utils

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StrictModePolicy] 的边界回归。
 *
 * 这个类本身几乎没有逻辑（只是装策略），但它有一个必须守住的性质：
 * **enabled = false 时绝不能装**。写反了是灾难性的 ——
 * release 包里开着 `detectDiskReads` 等于每次 IO 都抓一次调用栈，
 * 而且会把线上 logcat 灌满，用户反馈的日志里全是 StrictMode 噪音。
 *
 * ⚠️ 这里**不能**断言 `install(true)` 之后策略真的变了：`android.os.StrictMode`
 * 在 JVM 单测里是空桩（`unitTests.returnDefaultValues = true`），
 * `getThreadPolicy()` 只会返回桩值。断言它"装上了"等于自欺欺人。
 */
class StrictModePolicyTest {

    @Test
    fun `关闭时安装不抛异常`() {
        // 唯一需要保证的：关闭路径是纯粹的空操作，且不会因为某些 ROM 的桩实现而崩。
        StrictModePolicy.install(false)
    }

    @Test
    fun `重复关闭安装是幂等的`() {
        repeat(5) { StrictModePolicy.install(false) }
    }

    @Test
    fun `describe 在任何情况下都返回字符串而不是抛`() {
        // describe() 包了 runCatching：桩环境下 StrictMode.get*Policy() 可能抛，
        // 但调用方（调试面板）不该因此崩溃。
        val text = StrictModePolicy.describe()
        assertTrue("describe 必须有返回值", text.isNotEmpty())
    }
}
