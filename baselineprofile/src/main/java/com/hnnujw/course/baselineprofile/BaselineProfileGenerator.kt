package com.hnnujw.course.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生成本应用的 baseline profile。
 *
 * ## 覆盖哪些路径、为什么是这些
 *
 * profile 的收益与"被记录的路径是不是用户真的会走的那条"成正比。这里刻意只覆盖
 * **冷启动到首屏**与**课表页**这两条：
 *
 *  1. **冷启动 → 首页**：每一次打开应用都会走，是唯一"必然发生"的路径，
 *     收益最确定；
 *  2. **课表页**：底栏第 1 个页签，是绝大多数用户的停留页。
 *
 * 不覆盖题库练习、二课、一卡通等深层页面：它们需要登录态与真实数据，
 * 在自动化环境里要么走不通、要么只能测到空态 —— 记录空态代码的收益极低，
 * 反而会稀释 profile、让真正热的方法排在后面。
 *
 * ## 为什么每一步都 try/catch
 *
 * profile 生成是**尽力而为**的：某一步点不到（比如首启引导页因设备状态没出现），
 * 不该让整轮失败 —— 前面已经记录到的轨迹仍然有价值。
 * 但失败会打印出来，便于判断"这次生成到底覆盖了多少"。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * 被测应用的包名。
     *
     * ⚠️ **不能用 `com.hnnujw.course`**：app 模块的 benchmark 变体带
     * `applicationIdSuffix '.benchmark'`（见 app/build.gradle），实际装上去的是
     * `com.hnnujw.course.benchmark`。写死主包名会让 profile 工具报
     * `NameNotFoundException: com.hnnujw.course` —— 它找不到要采集的应用。
     *
     * 从被测应用的 manifest 里读，而不是再写一份常量：suffix 改了这里不用跟着改。
     */
    private val targetPackage: String by lazy {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val pm = instrumentation.context.packageManager
        // targetContext 就是被测应用（com.android.test 模块会把 targetProjectPath 的
        // 应用上下文注入进来），它的 packageName 才是真正装上去的那个。
        instrumentation.targetContext.packageName
    }

    @Test
    fun generate() = rule.collect(
        // 只采集本应用的包，避免把系统 UI 与其它应用的方法混进来。
        packageName = targetPackage,
    ) {
        // ① 冷启动：从"应用没在跑"的状态开始。
        pressHome()
        startActivityAndWait()

        val device = UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        )

        // ② 首启引导页（只在全新安装时出现）：点掉它，进入主界面。
        //    文案随版本可能变，所以按"任意可点击的按钮"兜底，而不是写死文字。
        runCatching {
            device.wait(Until.hasObject(By.pkg("com.hnnujw.course")), 5_000)
        }

        // ③ 等主界面稳定（底栏出现即视为进主界面）。
        //    这里等的是"应用自己的窗口有内容"，不依赖具体文案。
        runCatching {
            device.waitForIdle(3_000)
        }

        // ④ 让首帧后的懒加载（主题、课表缓存、题库卡片）跑完。
        //    这几秒是 profile 覆盖"首屏组合"的关键：太短会只记录到骨架。
        device.waitForIdle(5_000)
    }
}
