package com.hnnujw.course.manager

/**
 * 账号键的构造与归一化（从 [UserManager] 抽出的纯逻辑）。
 *
 * ## 为什么值得单独存在
 *
 * 这两条规则决定了"哪个账号的数据算哪个账号的"：
 *
 *  - **账号键**（`schoolId::identity`）是 `saved_accounts` 里每条记录的主键；
 *  - **storage key** 是它归一化后的形态，被用作缓存目录名、prefs 键后缀、
 *    提醒计划里的 `account` 字段、加密 Cookie 的 prefs 键。
 *
 * 只要这两条规则在**写入侧与读取侧不一致**，就会出现最严重的一类 bug：
 * 按 A 写入、按 B 读取 —— 表现为「切换账号后看到上一个账号的成绩」，
 * 或者「清除缓存清不掉」。而这类错**不会报任何异常**。
 *
 * 所以把它们从 `UserManager` 的私有方法提出来，成为可直接单测的纯函数：
 * 规则本身能被钉住，任何人改了一边都会被测试拦下。
 */
object AccountKeys {

    /**
     * 构造账号键：`<schoolId>::<identity>`。
     *
     * 身份按 `username → studentId → studentName` 的顺序取**第一个非空**的。
     *
     * 为什么是这个顺序：用户名（学号）最稳定；学号次之；姓名最不稳定
     * （重名、改名）。但学校里有些账号没有用户名，所以必须留后备。
     *
     * @return 空串表示**构造不出有效键**（学校缺失或三个身份全空）——
     *   调用方必须把空串当作"不合法"，而不是拿它去读写数据。
     */
    fun build(schoolId: String?, username: String?, studentId: String?, studentName: String?): String {
        if (schoolId.isNullOrBlank()) return ""
        val identity = firstNotBlank(username, studentId, studentName)
        if (identity.isEmpty()) return ""
        return schoolId + "::" + identity
    }

    /** 取第一个非空白项（会 trim）。全空返回空串。 */
    fun firstNotBlank(vararg values: String?): String {
        for (value in values) {
            if (value != null && value.isNotBlank()) return value.trim()
        }
        return ""
    }

    /**
     * 账号键 → storage key。
     *
     * 只保留 `[A-Za-z0-9_.-]`，其余（包括 `::`、中文姓名、空格）一律替换成 `_`。
     * 空键归一化成 `"default"`。
     *
     * 为什么要归一化：这个值会被直接当成文件名/目录名/prefs 键使用，
     * 中文与 `:` 在文件系统与某些 prefs 实现上都是雷。
     */
    fun storageKey(accountKey: String?): String {
        val key = accountKey?.takeIf { it.isNotEmpty() } ?: "default"
        return key.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }
}
