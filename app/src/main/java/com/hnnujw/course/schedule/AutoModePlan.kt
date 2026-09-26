package com.hnnujw.course.schedule

import android.app.NotificationManager

/**
 * 上课期间自动切换到的勿扰档位。
 *
 * 全部走 [NotificationManager.setInterruptionFilter] 这一条路径，**不做「改响铃模式」那一套**：
 * 从 Android N 起 `RINGER_MODE_SILENT` 本身就等价于开勿扰，并且同样要求勿扰访问权限
 * （AOSP 在 `setRingerMode` 里会检查是不是 notification policy access holder）。
 * 也就是说它既不多出一个能力，又多一条「想静音却把勿扰打开」的路径。
 * 档位直接对应系统的三档勿扰，界面上的说明与实际效果不会对不上。
 */
enum class AutoModeKind(
    val storageValue: String,
    val label: String,
    /** 给用户看的一句话说明。与 [interruptionFilter] 的实际效果必须一致。 */
    val description: String,
    val interruptionFilter: Int?
) {
    /** 不接管系统设置（默认）。 */
    Off("off", "关闭", "上课时不自动改系统设置", null),

    /** 完全静默：来电、消息、提醒一律不响，也不震动。 */
    DoNotDisturb("dnd", "勿扰", "上课时完全静默：来电、消息、提醒都不响，也不震动", NotificationManager.INTERRUPTION_FILTER_NONE),

    /** 只放行闹钟：上课时定时器、闹钟仍然有效，其余静音。 */
    AlarmsOnly("alarms", "仅闹钟", "上课时只放行闹钟，其余来电与消息静音", NotificationManager.INTERRUPTION_FILTER_ALARMS),

    /** 只放行优先打扰：需要用户自己先在系统里配过优先联系人，否则等同勿扰。 */
    PriorityOnly("priority", "仅优先", "上课时只放行优先打扰（需先在系统里设好优先联系人）", NotificationManager.INTERRUPTION_FILTER_PRIORITY);

    companion object {
        fun fromStorage(value: String?): AutoModeKind =
            entries.firstOrNull { it.storageValue == value } ?: Off
    }
}

/**
 * 自动模式的运行态。
 *
 * [applied] 表示**系统当前状态是本应用改的**，必须由本应用还原 —— 这是整个功能里
 * 唯一可能造成实际困扰的地方：切了勿扰却没还原，手机就会一直安静下去。
 * 所以它连同「改之前是哪一档」一起落盘，进程被杀、重启后仍能还原。
 */
data class AutoModeRuntime(
    val applied: AutoModeKind? = null,
    val previousInterruptionFilter: Int = UNKNOWN
) {
    companion object {
        /** 读不到「改之前是哪一档」时的哨兵值。只用于还原判定，绝不写回系统。 */
        const val UNKNOWN = Int.MIN_VALUE
    }
}

sealed interface AutoModeAction {
    /** 不碰系统设置。 */
    data object None : AutoModeAction

    /**
     * 施加 [kind]。
     *
     * [capturePrevious] 为 true 时必须先把系统**当前**档位存进运行态；为 false 表示
     * 我们已经在接管中（例如上课上到一半把「勿扰」改成「仅闹钟」），此时若覆盖运行态，
     * 还原目标就变成我们自己刚设的那一档 —— 手机再也回不到原样了。
     */
    data class Apply(val kind: AutoModeKind, val capturePrevious: Boolean) : AutoModeAction

    /** 还原成运行态里记的那一档，并清空运行态。 */
    data object Restore : AutoModeAction
}

/**
 * 此刻该做什么。
 *
 * 「该还原」与「该施加」是**两个独立判断**：开关被关掉、或者不在课时内，只要运行态说
 * 我们改过，就必须还原。下面把 `kind == Off || !inClass` 合并成第一个分支，正是为了
 * 让「已经接管过、但条件不再成立」一定走到 Restore 上。
 */
internal fun decideAutoMode(kind: AutoModeKind, applied: AutoModeKind?, inClass: Boolean): AutoModeAction = when {
    kind == AutoModeKind.Off || !inClass -> if (applied == null) AutoModeAction.None else AutoModeAction.Restore
    applied == kind -> AutoModeAction.None
    else -> AutoModeAction.Apply(kind, capturePrevious = applied == null)
}
