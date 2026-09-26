package com.hnnujw.course.ykt

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台电费巡检所需的系统权限引导。
 *
 * ## 为什么需要它
 *
 * 电费提醒靠 [YktAlertScheduler] 的 `AlarmManager.setInexactRepeating` 在 App
 * 未打开时唤醒 [YktAlertReceiver]。但**在国产 ROM 上这个闹钟不保证送达**：
 * 被系统判定为"耗电应用"后会被冻结，闹钟既不触发，`goAsync()` 也拿不到时间片。
 * 用户看到的症状是「提醒时有时无」，而代码层面完全不报错——这是最难排查的一类问题。
 *
 * ## 两个层次，能力不同
 *
 * 1. **Doze 电池优化白名单**（AOSP 标准，API 23+）：
 *    [isIgnoringBatteryOptimizations] 可**准确判断**是否已豁免；
 *    未豁免时可用 [batteryOptimizationIntent] 唤起系统弹窗一键加入。
 *    这是唯一有公开 API 的一层。
 * 2. **厂商自启动/后台运行白名单**（小米/华为/OPPO/vivo…）：
 *    **没有任何标准 API 可判断，也无法可靠地直接跳转**——各 ROM 的组件名不同且不公开，
 *    硬编码 `ComponentName` 会在多数机型上抛 `ActivityNotFoundException`。
 *    所以这里只提供**尽力而为**的跳转（按厂商尝试若干已知入口，失败则退到应用详情页），
 *    且**不假设成功**：用户回来后不代表已加白，界面文案要如实说明这是"手动步骤"。
 *
 * ## 设计口径
 *
 * - 判断方法**不缓存**：用户可能刚在系统设置里改过，缓存会显示过期状态。
 * - 跳转一律带 `FLAG_ACTIVITY_NEW_TASK`（从非 Activity 上下文启动时需要）。
 * - 不把"能否后台运行"做成硬性门槛：拿不到白名单只是提醒可能延迟，
 *   主功能（查余额、查电费）不受影响，不能因此拦住用户。
 */
object YktBackgroundPermission {

    /**
     * 是否已豁免电池优化（即已加入 Doze 白名单）。
     *
     * 低版本（< API 23）没有 Doze，恒视为 true。
     */
    @SuppressLint("BatteryLife")
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 唤起系统的「是否允许后台运行 / 忽略电池优化」弹窗。
     *
     * 部分 ROM 屏蔽了这个 Action，故用 [Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS]
     * （列表页）兜底——虽然多一步，但至少能把用户带到正确的位置。
     */
    fun batteryOptimizationIntent(context: Context): Intent {
        val pkg = context.packageName
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (direct.resolveActivity(context.packageManager) != null) return direct
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 厂商「自启动 / 后台运行管理」页面的**尽力而为**跳转。
     *
     * 逐个尝试已知的厂商入口组件名，**第一个能解析的**就用它；
     * 全部失败时退到本应用的详情页（用户可从那里进"权限/省电"）。
     *
     * ⚠️ 各 ROM 的组件名不公开且随版本变化，这里的清单**必然不全**。
     * 返回的是"最可能有用的一跳"，不代表一定能到自启动页——界面不要承诺。
     */
    fun autostartIntent(context: Context): Intent {
        val candidates = listOf(
            // 小米
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为 / 荣耀
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            // OPPO / 一加 / realme
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            // vivo / iQOO
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            // 魅族
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
        )
        for ((pkg, cls) in candidates) {
            val intent = Intent().setClassName(pkg, cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) return intent
        }
        // 兜底：应用详情页（几乎所有 ROM 都能到，且带"省电/自启动"相关入口）
        return appDetailsIntent(context)
    }

    /** 本应用详情页。 */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
