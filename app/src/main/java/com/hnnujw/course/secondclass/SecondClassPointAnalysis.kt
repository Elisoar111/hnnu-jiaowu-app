package com.hnnujw.course.secondclass

/**
 * 「分类与学期统计」的纯函数分析层。
 *
 * 刻意**不依赖任何 Android 类型**（没有 Context、没有 JSONObject、没有 Compose），
 * 因此可以在 JVM 单测里直接喂数据。界面与 Route 只调这里，不自己算 —— 否则
 * "账对不上"这类判断会散到多个地方，改一处漏一处。
 *
 * 这一层的存在意义就是把 [SecondClassPointRecord] 的**可信度**算出来：
 * "每一分有迹可循"不是把明细铺出来就完事，还要能回答三个问题：
 *   1. 这一分是哪来的？（[categoryBreakdown] / [termBreakdown] 给出分组与小计）
 *   2. 拆完之后账对不对？（[trust] 给出可信度）
 *   3. 对不上时差在哪？（[unexplained] 给出缺口）
 */
object SecondClassPointAnalysis {

    /**
     * 账目可信度。界面据此决定顶部那张对账卡片怎么画。
     *
     * 取值顺序即严重程度递增，便于比较。
     */
    enum class Trust {
        /** 站点没给可用数据（两端都空），无从判断 —— 不报错，显示空态。 */
        Unknown,

        /** 两个维度小计一致，且各自与自己的明细求和一致：完全对得上。 */
        Balanced,

        /**
         * 两个维度小计一致，但至少一边的"小计 vs 本地明细"对不上。
         *
         * 常见成因：站点把小计算上了没下发记录的账（如补录历史数据）。
         * 账本身没错，只是明细不全 —— 要提示"还有 N 分没有明细"。
         */
        PartialDetail,

        /**
         * 两个维度的小计本身就互相矛盾。
         *
         * 这通常意味着其中一边漏了记录（实测学期维度不含某些补录账）。
         * 属于**需要用户知道**的异常，界面要显式提示差额，不能让用户
         * 以为"总积分"和明细天然相等。
         */
        Mismatch,
    }

    /** 一次可信度评估的完整结果。 */
    data class Assessment(
        val trust: Trust,
        /**
         * 未能归因到任何明细的积分。
         *
         * 取"两个维度小计里的较大者"与"两个维度明细求和里的较大者"之差 ——
         * 因为小计可能横跨维度各自漏一点，取大者才不会把缺口算小。
         * 全部对得上时是 0。
         */
        val unexplained: Double,
        /** 两个维度的小计差（分类合计 − 学期合计），用于文案说明。 */
        val dimensionGap: Double,
    ) {
        val isBalanced: Boolean get() = trust == Trust.Balanced
    }

    /** 判定阈值：小于半分钱级别的浮点尾差不值得报警。 */
    const val TOLERANCE: Double = 0.005

    /**
     * 综合评估。
     *
     * 先用 [SecondClassPointLedger.reconcile] 判两维度是否一致，再看各自
     * "小计 vs 明细"是否对得上，取最严重的一个作为结论。
     */
    fun trust(ledger: SecondClassPointLedger): Assessment {
        if (ledger.isEmpty) return Assessment(Trust.Unknown, 0.0, 0.0)

        val hasClassify = ledger.byClassify.isNotEmpty()
        val hasTerm = ledger.byTerm.isNotEmpty()
        val bothSidesPresent = hasClassify && hasTerm

        val classifySite = sumSiteTotals(ledger.byClassify)
        val termSite = sumSiteTotals(ledger.byTerm)
        val classifyDetail = ledger.byClassify.sumOf { it.detailSum }
        val termDetail = ledger.byTerm.sumOf { it.detailSum }

        // 维度差只在**两个维度都有数据**时才有"口径矛盾"的含义。只有一维时另一边是
        // 空集，相减得到的只是"这一维的合计"，拿它当矛盾信号会误报，所以此时归零。
        val dimensionGap = if (bothSidesPresent) classifySite - termSite else 0.0

        // 缺口 = 各维度"小计 − 明细"里最大的那一个绝对值。
        //
        // ⚠️ 不能写成 `|max(小计) − max(明细)|`：当两个维度小计都是 15.5、明细
        // 也都是 15.5，而**其中一个维度的分组内部**对不上时（如分类小计 15.5
        // 但分类明细只有 12），那个差式恒等于 0，缺口会被算没 —— 正是这条
        // 让"有 N 分没有明细"的提示整个消失。逐维度算才对得上账。
        //
        // 只统计**有数据的那一维**，空维度不参与 —— 否则空维度会贡献一个
        // 等于另一维合计的假缺口。
        val gaps = buildList {
            if (hasClassify) add(kotlin.math.abs(classifySite - classifyDetail))
            if (hasTerm) add(kotlin.math.abs(termSite - termDetail))
            if (bothSidesPresent) add(kotlin.math.abs(dimensionGap))
        }
        val unexplained = gaps.maxOrNull() ?: 0.0

        val partial = ledger.byClassify.any { !it.reconciled } || ledger.byTerm.any { !it.reconciled }

        val trust = when {
            // 两个口径互相矛盾：最严重的一类。只有两维都有数据时才可能成立。
            kotlin.math.abs(dimensionGap) > TOLERANCE -> Trust.Mismatch
            partial -> Trust.PartialDetail
            // 单维度的"小计 vs 明细"缺口：账本身没错，只是明细不全，语义上属于 PartialDetail
            unexplained > TOLERANCE -> if (bothSidesPresent) Trust.Mismatch else Trust.PartialDetail
            else -> Trust.Balanced
        }
        return Assessment(trust, unexplained, dimensionGap)
    }

    /**
     * 站点小计求和。
     *
     * ⚠️ 必须只用 `siteTotal`，**不能**用 [SecondClassPointGroup.total]（它会在
     * 站点没给小计时退回本地明细求和）。对账的语义是"拿站点的两个口径互相比"，
     * 混进本地求和就等于拿自己的数跟自己比，永远对得上，白对。
     * 站点完全没给任何小计时退回本地求和，否则会得出 0 与另一维度差出满额缺口。
     */
    private fun sumSiteTotals(groups: List<SecondClassPointGroup>): Double {
        val declared = groups.mapNotNull { it.siteTotal }
        return if (declared.isEmpty()) groups.sumOf { it.detailSum } else declared.sum()
    }

    /**
     * 分类维度的展示序列，按积分从高到低排；积分为 0 的分组排在最后。
     *
     * 界面不该按站点返回顺序铺 —— 站点顺序是按配置的模块顺序，与"哪一项占分最多"
     * 无关，而用户看统计时最想知道的就是"大头在哪"。
     */
    fun categoryBreakdown(groups: List<SecondClassPointGroup>): List<SecondClassPointGroup> =
        groups.sortedWith(
            compareByDescending<SecondClassPointGroup> { it.total }
                .thenByDescending { it.records.size }
                .thenBy { it.name }
        )

    /**
     * 学期维度的展示序列：**按时间倒序**（最近学期在前）。
     *
     * 排序依据优先 `termNumber`（站点给的学期序号，纯数字可直接比大小），
     * 缺失时退回 `termName` 里的数字（如"2024-2025-2"取最后一段 2），
     * 两者都取不到才按名字排。**不能用分组原来的顺序** —— 站点给的是正序，
     * 用户最关心的是当前学期。
     */
    fun termBreakdown(groups: List<SecondClassPointGroup>): List<SecondClassPointGroup> =
        groups.sortedWith(
            compareByDescending<SecondClassPointGroup> { termSortKey(it) }.thenBy { it.name }
        )

    /**
     * 学期排序键，越大越新。
     *
     * `termNumber` 是学号里的"第几学期"语义（站点 `termNumber`），比名字可靠；
     * 缺失时从 `termName` 抽数字拼一个可比较的键（"2024-2025-2" → 202420252）。
     * 都抽不到返回 0，排到最后。
     */
    internal fun termSortKey(group: SecondClassPointGroup): Long {
        group.termNumber.trim().toLongOrNull()?.let { return it * 1_000_000L }
        val digits = group.name.filter { it.isDigit() }
        if (digits.isEmpty()) return 0L
        // 取末 9 位避免溢出（学期名里的数字不会更多），同时保住"学年 + 学期"的量级顺序
        return digits.takeLast(9).toLongOrNull() ?: 0L
    }

    /**
     * 明细里所有"来源名称"去重后的数量。
     *
     * 用于统计卡片的"共 N 条记录 / M 个来源" —— 条数会因两个维度重复计数，
     * 来源数不会，它是"我到底参加了多少件事"的更真实口径。
     */
    fun distinctSources(ledger: SecondClassPointLedger): Int =
        (ledger.byClassify.flatMap { it.records } + ledger.byTerm.flatMap { it.records })
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .size

    /**
     * 把记录按时间倒序排（未知时间的排最后）。
     *
     * 分组内部一律按这个顺序展示：同一分类下的多笔账，用户要看的是"最近加了什么"。
     */
    fun recordsNewestFirst(records: List<SecondClassPointRecord>): List<SecondClassPointRecord> =
        records.sortedWith(
            compareByDescending<SecondClassPointRecord> { it.time }.thenBy { it.name }
        )

    /**
     * 把一条记录补全分类名。
     *
     * 分类维度下分组自带 `classifyName`，而记录里没有 —— 展示每一条时要靠分组补上，
     * 否则在"按学期"视图里能看到分类、在"按分类"视图里反而看不到，自相矛盾。
     */
    fun withGroupCategory(
        group: SecondClassPointGroup,
        records: List<SecondClassPointRecord>,
    ): List<SecondClassPointRecord> = records.map { record ->
        if (record.classifyName.isNotBlank()) record else record.copy(classifyName = group.name)
    }

    /**
     * 学期分组的**展示标题**：把站点的 `termName` 补全成"学年 + 第几学期"。
     *
     * ## 为什么必须补
     *
     * 站点学期维的 `termName` 实测**只给学年**（如 `2025-2026`），学期号在
     * `termNumber` 里（`"1"` / `"2"` / `"3"`）。直接展示 `termName` 会让
     * **同一学年的两个学期显示成两行一模一样的标题** —— 用户根本分不清哪行是
     * 第一学期、哪行是第二学期，而这正是"按学期看账"的全部意义。
     *
     * ## 取值规则
     *
     * - `termName` 已经自带学期号（如 `2025-2026-1`）→ **原样返回**，不重复拼接；
     * - 否则用 `termNumber`：`1`→`第一学期`、`2`→`第二学期`、`3`→`第三学期`…
     *   追加在学年之后，如 `2025-2026 第一学期`；
     * - `termNumber` 取不到时退回原 `termName`（**不编造**学期号）；
     * - `termNumber` 是非数字或超出常见范围（>12）时不翻译成中文，按
     *   `第N学期` 原样拼，保证信息不丢且不产生"第 0 学期"这种鬼话。
     *
     * 这个函数**只做展示**，不参与排序（排序见 [termSortKey]）与对账，
     * 因此对 `termName` 的形态变更天然安全。
     */
    internal fun termDisplayName(group: SecondClassPointGroup): String {
        val raw = group.name.trim()
        if (raw.isBlank()) return raw
        val number = group.termNumber.trim()
        // 学期名里已经带学期号（末段是 1/2 这种一位数）就不重复拼
        if (raw.endsWithSemesterMarker()) return raw
        if (number.isEmpty()) return raw
        return raw + " " + semesterLabel(number)
    }

    /**
     * `termName` 是否已经自带学期号。
     *
     * 判据：末段是 `-` / `－` 分隔后的一位数字（如 `2025-2026-1`）。
     * 只看**一位**是有意的：`2025-2026` 的末段是四位年份，不能被误判成学期号。
     */
    private fun String.endsWithSemesterMarker(): Boolean {
        val tail = substringAfterLast('-').substringAfterLast('－')
        return tail.length == 1 && tail[0].isDigit()
    }

    /**
     * 学期序号 → 中文文案。
     *
     * `1/2/3` 翻成"第一/二/三学期"（站点与教务口径一致）；其余按"第N学期"，
     * 既保留原值又不臆造单位。
     */
    private fun semesterLabel(number: String): String {
        val index = number.toIntOrNull()
            ?: return "第${number}学期"
        if (index < 1 || index > 12) return "第${number}学期"
        val digits = listOf(
            "一", "二", "三", "四", "五", "六",
            "七", "八", "九", "十", "十一", "十二",
        )
        return "第${digits[index - 1]}学期"
    }

    /**
     * 积分数值的**展示单位**。
     *
     * ## 为什么不能直接用站点的 `termHoursUnit`
     *
     * 站点该字段下发的是**数字枚举码**（实测学期维恒为 `"3"`），不是文案。
     * 直接拼到数字后面会渲染成 `25.8 3` —— 用户看到的就是"每个数字后面都有个 3"。
     * （同一家族的 `waysConvert == 3` 才是"分数/学分"的判据，语义不同，别混。）
     *
     * ## 取值规则
     *
     * - 站点给的**确实是中文单位**（含中文且不是纯数字）→ 用它，这是权威值；
     * - 否则回退 [SecondClassProfile.hourUnit]（表头的真实单位，如"学时"）；
     * - 再取不到返回空串 → 界面只显示数字，**绝不显示一个裸的 `3`**。
     */
    internal fun displayUnit(rawUnit: String, fallbackUnit: String): String {
        val raw = rawUnit.trim()
        if (raw.isNotEmpty() && raw.any { it.code > 0x7F } && raw.toDoubleOrNull() == null) {
            return raw
        }
        return fallbackUnit.trim()
    }
}
