package com.hnnujw.course.review

import com.hnnujw.course.review.ReviewDatabase.BankSummary

/**
 * 题库管理（创建 / 重命名 / 合并）的**纯判断逻辑**，不碰 Android API、不碰 DB。
 *
 * 抽成纯函数的理由（项目红线）：这些判断全是「输入 → 结论」的纯数据变换，
 * 放在 Composable 或 DB 层里就没法单测。而且**一律返回原因枚举而不是 Boolean** ——
 * 返回布尔会把原因丢掉，界面只好对所有失败印同一句「操作失败」
 * （考级退报事故的教训）。
 */
object BankManageLogic {

    /** 题库名长度上限。够长到能写「计算机网络期末·第 3 章」，又不至于撑爆列表行。 */
    const val MAX_NAME_LENGTH: Int = 40

    /** 创建 / 改名时的名字校验结果。 */
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

    /** 合并前置校验结果。 */
    sealed interface MergeCheck {
        /** 通过。[sources] 是归一后的源库列表（去重、去空、按 [all] 顺序稳定）。 */
        data class Ok(val target: String, val sources: List<String>) : MergeCheck
        /** 没选源库。 */
        data object NoSource : MergeCheck
        /** 没选目标库（或目标为空串）。 */
        data object NoTarget : MergeCheck
        /** 目标库不在题库列表里。 */
        data object TargetMissing : MergeCheck
        /** 源库里混进了目标库自己。 */
        data object TargetInSources : MergeCheck
        /** 有源库不存在于题库列表里。 */
        data object SourceMissing : MergeCheck
        /** 目标库和源库是同一个（选了自己当唯一源）。 */
        data object SameBank : MergeCheck

        val message: String
            get() = when (this) {
                is Ok -> ""
                NoSource -> "请至少选择一个要合并进来的题库"
                NoTarget -> "请选择合并到哪个题库"
                TargetMissing -> "目标题库不存在"
                TargetInSources -> "目标题库不能同时作为被合并的题库"
                SourceMissing -> "有源题库不存在，请刷新后重试"
                SameBank -> "不能把题库合并到它自己"
            }
    }

    /**
     * 校验合并请求。
     *
     * [all] 是当前题库列表，用来确认目标与源都真实存在（去重、稳定顺序）。
     * 语义要点：**只合并题目，不迁移答题记录** —— 见 [planQidRemap] 与
     * [ReviewDatabase.mergeBanks] 的注释。
     */
    fun resolveMerge(target: String, sources: List<String>, all: List<BankSummary>): MergeCheck {
        if (target.isBlank()) return MergeCheck.NoTarget
        val allIds = all.map { it.id }.toSet()
        if (target !in allIds) return MergeCheck.TargetMissing

        val cleaned = sources.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty()) return MergeCheck.NoSource
        if (target in cleaned) {
            return if (cleaned.size == 1) MergeCheck.SameBank else MergeCheck.TargetInSources
        }
        if (cleaned.any { it !in allIds }) return MergeCheck.SourceMissing
        return MergeCheck.Ok(target, cleaned)
    }

    /**
     * 为一个题库内「源题的 qid 要在目标库里落地」计算 qid 重映射。
     *
     * 目标库里 qid 是 `(bank_id, qid)` 联合主键的一半，合并时源题撞上目标库已有 qid
     * 就会被 `INSERT OR REPLACE` **覆盖掉目标库的原题** —— 这是合并里最容易出的 bug。
     * 所以冲突必须重分配：
     * - **无冲突的 qid 保持原样**（避免无意义 rename，用户看到的题号不会乱跳）
     * - 冲突的按 `原名_merge1`、`_merge2` … 递增，直到不与「目标已有」及「本次已分配」重复
     *
     * [existingQids] = 目标库现有 qid 集合；[incomingQids] = 源题的 qid（按顺序）。
     * 返回 `原 qid -> 落库 qid`；同一原 qid 重复出现时共用同一个新 qid（幂等）。
     */
    fun planQidRemap(existingQids: Set<String>, incomingQids: List<String>): Map<String, String> {
        val taken = HashSet<String>(existingQids)
        // 顺序敏感的映射：无冲突保原样，冲突才分配新号。用 LinkedHashMap 让分配顺序可复现。
        val remap = LinkedHashMap<String, String>()
        for (qid in incomingQids) {
            if (qid in remap) continue // 同一原 qid 已在本次处理过
            if (qid !in taken) {
                remap[qid] = qid
                taken.add(qid)
                continue
            }
            var suffix = 1
            var candidate = "${qid}_merge$suffix"
            while (candidate in taken) {
                suffix++
                candidate = "${qid}_merge$suffix"
            }
            remap[qid] = candidate
            taken.add(candidate)
        }
        return remap
    }

    /** 合并结果报告，交给 UI 展示并询问是否删除源库。 */
    data class MergeReport(
        /** 迁移过去的题目数（含因冲突改名的）。 */
        val totalMoved: Int,
        /** 因 qid 冲突被重新编号的题目数。 */
        val renamedCount: Int,
        /** 被合并的源库名字，用于「是否删除这些源题库」的二次确认文案。 */
        val sourceNames: List<String>
    ) {
        /** 是否发生了重编号；UI 据此决定要不要多提一句。 */
        val hasRenamed: Boolean get() = renamedCount > 0
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
