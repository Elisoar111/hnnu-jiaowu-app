package com.hnnujw.course.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 启动期预热 SharedPreferences。
 *
 * ## 为什么要做这件事
 *
 * `getSharedPreferences(name, MODE)` 本身不读盘，但**第一次调用它的任何读方法**
 * （`getBoolean` / `getString` / `contains`）会同步把整个 XML 解析进内存。
 * 这个解析发生在调用线程上 —— 如果调用点在主线程（启动路径、首帧组合里几乎都是），
 * 就是一次实打实的主线程磁盘 IO。
 *
 * 本应用有十几个 prefs 文件，冷启动时它们会被陆续在主线程上首次访问：
 * `appearance`（主题）、`schedule_settings`（课表）、`course_reminders`（提醒）、
 * 各账号的缓存……单个文件只要几毫秒，但串起来就是启动时可见的卡顿，
 * 而且这类卡顿在低端机上会被放大好几倍。
 *
 * ## 做法：在后台线程先把它们读一遍
 *
 * 用一个低优先级后台线程依次 `getSharedPreferences(...).getAll()`，把解析成本
 * 从主线程挪走。**不阻塞启动** —— 预热是"顺手做"，主线程该读的时候如果还没预热完，
 * 它就自己读（正确性不受影响，只是那次没省到）。
 *
 * ## 为什么用 getAll() 而不是逐个读键
 *
 * `getAll()` 会触发同一套完整解析（这是我们要的），同时不依赖"知道有哪些键"——
 * 键名散落在十几个类里，写死一份清单等于给自己留一个必然过期的维护点。
 *
 * ## 为什么线程优先级调到最低
 *
 * 预热是纯优化，**绝不能和首帧抢 CPU**。`Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)`
 * 让它在主线程忙的时候自动让路。
 */
object PrefsWarmer {

    private const val TAG = "PrefsWarmer"

    /**
     * 需要预热的 prefs 文件名。
     *
     * 只列**启动路径或首屏会读到**的那些。不列全部：每个文件都要读盘 + 解析，
     * 把几十个都预热反而会把磁盘队列占满，让真正要用的那个更慢。
     */
    private val WARMUP_NAMES = listOf(
        // 主题与外观：Application.onCreate 就要用，最靠前
        "appearance_settings",
        // 课表：首页/课表页首帧
        "schedule_settings",
        "schedule_cache",
        // 提醒：启动时 schedule() 会读
        "course_reminders",
        "exam_reminders",
        // 账号与设置：首页「我的」与各页头部
        "course_selector_prefs",
        // 题库：首页题库卡片
        "review_prefs",
        // 一卡通：首屏余额卡片
        "ykt_prefs",
        "ykt_selection_prefs",
        "ykt_alert_prefs",
        // 二课：底栏第 4 个页签
        "second_classroom_prefs",
    )

    @Volatile
    private var started = false

    /**
     * 开始预热。幂等，多次调用只跑一次。
     *
     * 由 `CourseApplication.onCreate` 调用。**不会抛异常、不会阻塞**：
     * 任何失败都只记日志 —— 预热失败的最坏结果是"回到没优化的状态"。
     */
    fun warmUp(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            var warmed = 0
            val startedAt = android.os.SystemClock.elapsedRealtime()
            WARMUP_NAMES.forEach { name ->
                runCatching {
                    val prefs: SharedPreferences =
                        app.getSharedPreferences(name, Context.MODE_PRIVATE)
                    // getAll() 触发完整解析；结果不用，只是为了让解析发生在这里。
                    prefs.all.size
                    warmed++
                }.onFailure {
                    Log.w(TAG, "预热 $name 失败: ${it.message}")
                }
            }
            val cost = android.os.SystemClock.elapsedRealtime() - startedAt
            Log.d(TAG, "已预热 $warmed/${WARMUP_NAMES.size} 个 prefs，耗时 ${cost}ms")
        }, "prefs-warmer").apply {
            // 守护线程：不让它拖住进程退出。
            isDaemon = true
            start()
        }
    }

    /** 供测试与排查：预热清单（不可变副本）。 */
    fun warmupNames(): List<String> = WARMUP_NAMES
}
