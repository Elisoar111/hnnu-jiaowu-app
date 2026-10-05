package com.hnnujw.course.review

/**
 * 题库管理（重命名 / 批量删题 / 批量选择）的**纯判断逻辑**，不碰 Android API、不碰 DB。
 *
 * 抽成纯函数的理由（项目红线）：这些判断全是「输入 → 结论」的纯数据变换，
 * 放在 Composable 或 DB 层里就没法单测。而且**一律返回原因枚举而不是 Boolean** ——
 * 返回布尔会把原因丢掉，界面只好对所有失败印同一句「操作失败」
 * （考级退报事故的教训）。
 *
 * 「创建题库」「合并题库」已于 2026-10 按用户要求下线，本对象里的合并判据
 * （MergeCheck / resolveMerge / planQidRemap / MergeReport）随之删除。
 */
object BankManageLogic {

    /** 题库名长度上限。够长到能写「计算机网络期末·第 3 章」，又不至于撑爆列表行。 */
    const val MAX_NAME_LENGTH: Int = 40

    /** 改名时的名字校验结果。 */
    sealed interface NameCheck {
        /** 校验通过，[trimmed] 是归一后的可用名（已 trim）。 */
        data class Ok(val trimmed: String) : NameCheck
        /** 空白名。 */
        data object Empty : NameCheck
        /** 超长。 */
        data object TooLong : NameCheck
        /** 与现有题库重名。 */
        data object Duplicate : NameCheck

        /** 给界面用的一句话原因；[Ok] 返回空串。 */
        val message: String
            get() = when (this) {
                is Ok -> ""
                Empty -> "名字不能为空"
                TooLong -> "名字太长（最多 $MAX_NAME_LENGTH 个字）"
                Duplicate -> "已有同名题库"
            }
    }

    /**
     * 校验题库名。[existing] 是**除自己以外**的现有题库名集合 ——
     * 改名时把自己排除掉，否则「不改名直接保存」会被误判成重名。
     *
     * 判定顺序：空 → 超长 → 重名。空名字优先报「不能为空」，
     * 因为那是用户最先要修的。
     */
    fun validateBankName(raw: String, existing: Set<String>): NameCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return NameCheck.Empty
        if (trimmed.length > MAX_NAME_LENGTH) return NameCheck.TooLong
        // 重名判定用归一后的名字：空白差异不该让「 我的题库 」和「我的题库」并存
        if (existing.any { it.trim() == trimmed }) return NameCheck.Duplicate
        return NameCheck.Ok(trimmed)
    }

    /** 批量删除的确认文案用：把 qids 数量转成一句人话。 */
    fun deleteQuestionsHint(count: Int): String =
        if (count <= 0) "" else "将删除 $count 道题，连同它们的答题记录、错题与收藏一并清除。"

    // ── 批量选择（全选 / 反选）─────────────────────────────────────────
    // 选择集合操作抽成纯函数：Composable 里直接改 SnapshotStateList 没法单测，
    // 而且「反选」的差集语义手写容易漏（例如把顺序打乱、把未选中的漏掉）。

    /**
     * 全选：返回按 [allIds] 顺序的完整 id 列表。
     *
     * [current] 只是入参里的陪衬（全选不依赖已选），保留它让三个选择操作签名一致，
     * 调用点可以无脑换函数名。
     */
    fun selectAll(allIds: List<String>, current: Collection<String>): List<String> =
        allIds.toList()

    /**
     * 反选：返回「在 [allIds] 里但不在 [current] 里」的 id，**保持 [allIds] 的顺序**。
     *
     * 顺序必须稳定 —— 反选后列表若乱跳，用户刚记住的勾选位置就全错了。
     */
    fun invertSelection(allIds: List<String>, current: Collection<String>): List<String> {
        val selected = current.toSet()
        return allIds.filter { it !in selected }
    }
}
