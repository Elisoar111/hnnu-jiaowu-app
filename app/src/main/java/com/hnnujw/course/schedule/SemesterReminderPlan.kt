package com.hnnujw.course.schedule

/**
 * 「按学期一键全开 / 全关」的**纯判断逻辑**。
 *
 * 抽成纯函数的理由（项目红线）：
 * 1. 「哪些课要建记录、哪些要改、哪些要跳过」全是「输入列表 → 输出列表」的纯数据变换，
 *    只有抽出来才能在 JVM 单测里钉死（循环里调 `setEnabled` 每次都会 save + reconcile，
 *    既没法测又会疯狂重排闹钟）；
 * 2. 统计结果一律返回**枚举计数**而不是 Boolean —— 界面要能说清「待授权 2 门 · 周次待核对 1 门」，
 *    返回布尔会把原因丢掉（考级退报事故的教训）。
 *
 * 与参考项目 `SemesterReminders` 的关键差异，本文件刻意做了两处偏离：
 * - **提前量不逐条继承**：本项目提前量是**设备级全局**（`ReminderSettings.leadMinutes`），
 *   不是每条 `CourseReminder` 自己的。所以调用方传进来的新记录统一带全局提前量，
 *   本函数**不**去读 `old.leadMinutes`（照抄参考的 `old?.leadMinutes ?: 15` 会与总偏好脱节）。
 *   为保持本文件纯粹（不碰 Scheduler），提前量由调用方以 [leadMinutes] 入参给进来。
 * - **revision 语义沿用本项目**：只在「真的变了」时 +1，已在目标态则原样保留，
 *   这样反复点「全部开启」不会让 revision 无意义地涨（幂等）。
 */
object SemesterReminderPlan {

    /**
     * 计算「按学期批量开关」之后的完整记录列表。
     *
     * @param current 现有记录（`records()` 的全量；可能还含**其它账号/学期**的记录）
     * @param account 当前账号 storageKey
     * @param term    当前学期
     * @param courses 本学期课表快照（来自 `updateSnapshot` 存的那份）
     * @param enabled 目标状态：true = 全部开启，false = 全部关闭
     * @param leadMinutes 全局提前量；新记录与状态变更后的记录都用它
     * @return 新的全量记录列表。**与 [current] 语义等价时返回同一个对象**（便于调用方判等跳过落盘）。
     *
     * 语义要点（照参考项目，已核对我们项目的 records 口径）：
     * - `account` / `term` 为空，或 `courses` 为空 → **原样返回**（无从下手）
     * - 只为**快照里的课**建/改记录；`records()` 里其它账号/学期的记录**一律原样保留**
     * - 关闭时**不给从未开过的课建记录**（`old == null && !enabled → 跳过`）——
     *   否则用户点一次"全部关闭"会凭空多出一堆 false 记录
     * - 课表里有重复 id → 按 id 去重，不产生重复记录
     * - 已在目标态（`enabled` 相同且 course 相同）→ 不产生修改（revision 不动）
     */
    fun planSemesterToggle(
        current: List<CourseReminder>,
        account: String,
        term: String,
        courses: List<ScheduleCourseRecord>,
        enabled: Boolean,
        leadMinutes: Int
    ): List<CourseReminder> {
        if (account.isBlank() || term.isBlank() || courses.isEmpty()) return current

        // 按 storageId 建有序映射：既保留现有顺序，又便于 O(1) 查找。
        val result = LinkedHashMap<String, CourseReminder>(current.size)
        current.forEach { result[it.key.storageId] = it }

        for (course in courses.distinctBy { it.id }) {
            if (course.id.isBlank()) continue
            val key = CourseReminderKey(account, term, course.id)
            val old = result[key.storageId]

            // 关闭时不给没建过记录的课建记录（参考项目的关键语义）
            if (old == null && !enabled) continue
            // 已在目标态：状态相同且课程内容未变 → 完全跳过，避免 revision 无谓增长
            if (old != null && old.enabled == enabled && old.course == course) continue

            result[key.storageId] = CourseReminder(
                key = key,
                course = course,
                enabled = enabled,
                leadMinutes = leadMinutes,
                revision = (old?.revision ?: 0L) + 1L
            )
        }
        return result.values.toList()
    }

    /**
     * 学期提醒概况：给设置页「本学期课程」区块显示「已开启 N/M 门」+ 按状态分组。
     *
     * [statuses] 是每门课当前状态的映射（由调用方用 [CourseReminderPlanner.plan] /
     * `scheduler.status(key)` 算好传进来）——本函数不碰时间、不碰权限，纯计数。
     */
    data class SemesterReminderSummary(
        val account: String,
        val term: String,
        val enabledCount: Int,
        val totalCount: Int,
        /** 按状态分组的计数，只保留非零项，顺序固定（枚举定义序），便于断言与稳定渲染。 */
        val byAvailability: Map<ReminderAvailability, Int>
    ) {
        val allEnabled: Boolean get() = totalCount > 0 && enabledCount == totalCount
        val noneEnabled: Boolean get() = enabledCount == 0
        /** 有快照才有课可操作；没有快照时界面应禁用按钮并提示先加载课表。 */
        val hasCourses: Boolean get() = totalCount > 0
    }

    /**
     * 汇总本学期课程与它们的状态。
     *
     * @param enabledCount 已开启的课数（`enabled == true` 的记录数，且仅限本账号本学期）
     * @param statuses courseId → 状态
     */
    fun summarize(
        account: String,
        term: String,
        enabledCount: Int,
        statuses: Map<String, ReminderAvailability>
    ): SemesterReminderSummary {
        val byAvailability = LinkedHashMap<ReminderAvailability, Int>()
        // 固定按枚举定义序输出，保证同一份数据每次渲染顺序一致
        for (state in ReminderAvailability.entries) {
            val n = statuses.values.count { it == state }
            if (n > 0) byAvailability[state] = n
        }
        return SemesterReminderSummary(
            account = account,
            term = term,
            enabledCount = enabledCount,
            totalCount = statuses.size,
            byAvailability = byAvailability
        )
    }

    /**
     * 「本学期课程」区块的按钮可用性。
     *
     * 返回**枚举原因**而不是 Boolean：无快照时按钮要禁用并说明「需要先加载本学期课表」，
     * 而不是给一个点了没反应的按钮（项目红线）。
     */
    sealed interface ToggleAvailability {
        /** 可以操作。[canEnable] / [canDisable] 指出两个方向各自是否还有意义。 */
        data class Ready(val canEnable: Boolean, val canDisable: Boolean) : ToggleAvailability
        /** 没有本学期的课表快照。 */
        data object NoSnapshot : ToggleAvailability
        /** 总开关关着：先开总开关才有意义。 */
        data object MasterOff : ToggleAvailability
    }

    /**
     * 判断两个批量按钮的可用状态。
     *
     * @param summary 学期概况（[SemesterReminderSummary.hasCourses] 为 false = 无快照）
     * @param masterEnabled 提醒总开关
     */
    fun toggleAvailability(
        summary: SemesterReminderSummary,
        masterEnabled: Boolean
    ): ToggleAvailability = when {
        !summary.hasCourses -> ToggleAvailability.NoSnapshot
        !masterEnabled -> ToggleAvailability.MasterOff
        else -> ToggleAvailability.Ready(
            canEnable = !summary.allEnabled,
            canDisable = !summary.noneEnabled
        )
    }

    /** [ReminderAvailability] 的中文短标签，用于设置页分组明细。 */
    fun availabilityLabel(state: ReminderAvailability): String = when (state) {
        ReminderAvailability.Off -> "已关闭"
        ReminderAvailability.NeedsPermission -> "待授权"
        ReminderAvailability.NeedsTime -> "待补齐作息"
        ReminderAvailability.InvalidWeeks -> "周次待核对"
        ReminderAvailability.Scheduled -> "已排期"
        ReminderAvailability.NoUpcoming -> "本学期已无课"
        ReminderAvailability.InactiveAccount -> "非当前账号"
    }

    /**
     * 把分组计数拼成一句人话（如「待授权 2 门 · 周次待核对 1 门」）。
     * 空分组返回空串，界面据此决定要不要显示这一行。
     */
    fun availabilityBreakdown(summary: SemesterReminderSummary): String =
        summary.byAvailability.entries
            .joinToString(" · ") { (state, count) -> "${availabilityLabel(state)} $count 门" }
}
