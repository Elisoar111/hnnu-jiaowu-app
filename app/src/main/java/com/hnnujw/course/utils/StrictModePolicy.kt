package com.hnnujw.course.utils

import android.content.Context
import android.os.StrictMode
import android.util.Log

/**
 * 只在 debug 构建里打开的 StrictMode 策略。
 *
 * ## 为什么值得单独写一个类
 *
 * 项目里有 74 处 `getSharedPreferences`、219 处 `Log.*`、以及大量"顺手读一下"
 * 的 IO。主线程 IO 是最难靠 review 发现的一类问题 —— 单个 prefs 读取只要几毫秒，
 * 肉眼看不出卡顿，但它会在冷启动、列表滚动、每帧重组里被放大成掉帧。
 *
 * 与其一条条人工审查，不如让**系统在开发时直接吵出来**：
 * 违反策略时 logcat 会打印完整调用栈，谁写的一眼可见。
 *
 * ## 为什么必须只在 debug 打开
 *
 * - `detectDiskReads/diskWrite` 本身有开销（每次 IO 都要抓调用栈），release 里开着
 *   是纯粹的性能损失；
 * - `penaltyLog` 会往 logcat 灌大量信息，正式包不需要；
 * - 最重要的是：StrictMode 能做的只有"提醒与记录"，它**不能**让已经跑在主线程的
 *   IO 变得不违规。线上打开没有收益，只有成本。
 *
 * ## 口径
 *
 * - `detectDiskReads / detectDiskWrites`：只监控磁盘，不监控网络
 *   （`detectNetwork` 在 debug 下噪音太大：OkHttp 的 DNS/连接本来就在线程池里，
 *   但还是会有一些合法的主线程调用点，开了反而淹没真正的问题）。
 * - `detectCustomSlowCalls`：抓自己写的慢方法。
 * - 策略用 `penaltyLog` 而不是 `penaltyDeath`：崩溃会打断正常开发，
 *   而这类问题需要"看一眼栈、记下来、之后统一处理"。
 */
object StrictModePolicy {

    private const val TAG = "StrictMode"

    /**
     * 安装策略。
     *
     * @param enabled 传 [BuildConfig.DEBUG]。刻意由调用方传入而不是内部读
     *   `BuildConfig`：这个类在 `utils` 包里，让它对构建类型零依赖，单元测试也能直接调。
     */
    fun install(enabled: Boolean) {
        if (!enabled) return
        runCatching {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    // 未关闭的 Closeable：SQLite / 文件流漏关，长期会耗尽 fd。
                    .detectLeakedClosableObjects()
                    // 未注销的注册项：广播接收器/监听器泄漏，是最常见的 Activity 泄漏来源。
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }.onFailure {
            // 个别 ROM 的 StrictMode 实现会抛（极罕见）。它只是开发辅助，
            // 装不上就不装，绝不能让启动失败。
            Log.w(TAG, "StrictMode 安装失败: ${it.message}")
        }
    }

    /**
     * 供 `adb shell dumpsys` 之外的一种排查手段：把当前策略读出来。
     *
     * 不参与业务逻辑，只在需要确认"策略到底有没有装上去"时用。
     */
    fun describe(): String = runCatching {
        "thread=" + StrictMode.getThreadPolicy().toString() +
            " vm=" + StrictMode.getVmPolicy().toString()
    }.getOrDefault("unavailable")

    /** 占位参数只是为了将来若有"按 context 分支"的需求时不必改签名。 */
    @Suppress("UNUSED_PARAMETER")
    fun installFor(context: Context, enabled: Boolean) = install(enabled)
}
