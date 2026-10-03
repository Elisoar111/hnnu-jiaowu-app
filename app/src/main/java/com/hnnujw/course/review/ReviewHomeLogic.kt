package com.hnnujw.course.review

/**
 * 首页两个练习入口（顺序练习 / 随机练习）的目标题库解析。**纯函数**，不碰数据库与 Compose。
 *
 * 为什么抽出来：这两个按钮默认作用于「第一个题库」，原实现直接写 `banks.first().id`。
 * `first()` 在空列表上抛 `NoSuchElementException`，而按钮的 `enabled` 又是硬编码的
 * `true`，一旦题库为空而卡片仍被构造（首页分支改版、或者删库后状态未刷新），
 * 点一下就闪退。抽成返回「能不能开练 + 开练用哪个库」的纯函数后：
 *  - Composable 只负责按结果渲染，判不到事；
 *  - 空库这条路径能被单测直接覆盖。
 */
sealed interface PracticeEntry {
    /** 可以开练，[bankId] 是要抽题的题库。 */
    data class Ready(val bankId: String) : PracticeEntry

    /** 一个题库都没有，按钮必须禁用。 */
    data object NoBank : PracticeEntry
}

/**
 * 解析默认练习目标题库。
 *
 * @param bankIds 题库 id 列表，顺序即展示顺序（[ReviewDatabase.listBanks] 按创建时间倒序）。
 * @return 第一个**非空白** id 对应的 [PracticeEntry.Ready]；没有可用 id 时返回 [PracticeEntry.NoBank]。
 */
fun resolvePracticeEntry(bankIds: List<String>): PracticeEntry =
    bankIds.firstOrNull { it.isNotBlank() }
        ?.let { PracticeEntry.Ready(it) }
        ?: PracticeEntry.NoBank
