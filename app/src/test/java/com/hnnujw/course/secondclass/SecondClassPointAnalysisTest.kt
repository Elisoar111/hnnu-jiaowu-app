package com.hnnujw.course.secondclass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「分类与学期统计」纯函数回归。
 *
 * 这里的每条断言都对应一个**真实会出问题的场景**，不是为凑覆盖率：
 * 账目对账、跨维度缺口、缺字段容错、排序口径 —— 都是"界面看起来正常但数字是错的"
 * 那一类缺陷，只有断言期望值才能挡住。
 */
class SecondClassPointAnalysisTest {

    // ── 构造工具 ──────────────────────────────────────────────────────────

    private fun record(
        name: String,
        hours: Double,
        classify: String = "",
        time: Long = 0L,
        sourceType: Int = 0,
        amount: Double = 0.0,
        relationId: String = "",
    ) = SecondClassPointRecord(
        name = name,
        hours = hours,
        classifyName = classify,
        time = time,
        sourceType = sourceType,
        amount = amount,
        relationId = relationId,
    )

    private fun group(
        name: String,
        siteTotal: Double?,
        records: List<SecondClassPointRecord> = emptyList(),
        required: Double = 0.0,
        termNumber: String = "",
        unit: String = "",
    ) = SecondClassPointGroup(
        name = name,
        siteTotal = siteTotal,
        required = required,
        records = records,
        termNumber = termNumber,
        unit = unit,
    )

    // ── 对账：完全一致 ────────────────────────────────────────────────────

    /** 两个维度小计相等、且各自与明细求和相等 → Balanced，缺口为 0。 */
    @Test
    fun balancedLedgerIsRecognised() {
        val ledger = SecondClassPointLedger(
            byClassify = listOf(
                group("思想政治素养", 12.0, listOf(record("活动A", 7.0), record("活动B", 5.0))),
                group("社会责任担当", 3.5, listOf(record("活动C", 3.5))),
            ),
            byTerm = listOf(
                group("2025-2026-1", 15.5, listOf(record("活动A", 7.0), record("活动B", 5.0), record("活动C", 3.5))),
            ),
            profileScore = 15.5,
        )
        val assessment = SecondClassPointAnalysis.trust(ledger)
        assertEquals(SecondClassPointAnalysis.Trust.Balanced, assessment.trust)
        assertEquals(0.0, assessment.unexplained, 1e-9)
        assertEquals(0.0, assessment.dimensionGap, 1e-9)
        assertTrue(assessment.isBalanced)
    }

    // ── 对账：两个维度小计互相矛盾 ────────────────────────────────────────

    /**
     * 分类合计 15.5、学期合计 12.0 → Mismatch，缺口 3.5。
     *
     * 这是**站点实际存在**的情况（学期维度不含补录账），也是最需要提示用户的一种。
     */
    @Test
    fun dimensionMismatchIsFlaggedWithGap() {
        val ledger = SecondClassPointLedger(
            byClassify = listOf(group("思想政治素养", 15.5, listOf(record("A", 15.5)))),
            byTerm = listOf(group("2025-2026-1", 12.0, listOf(record("A", 12.0)))),
        )
        val assessment = SecondClassPointAnalysis.trust(ledger)
        assertEquals(SecondClassPointAnalysis.Trust.Mismatch, assessment.trust)
        assertEquals(3.5, assessment.dimensionGap, 1e-9)
        assertEquals(3.5, assessment.unexplained, 1e-9)
        assertFalse(assessment.isBalanced)
    }

    // ── 对账：小计与明细对不上（但两维度一致）─────────────────────────────

    /**
     * 两维度小计都是 20，明细只列出 12 → PartialDetail。
     *
     * 语义与 Mismatch 不同：账本身没错，只是**明细没下发全**。
     * 界面据此说"还有 8 分没有明细"，而不是说"两个口径矛盾"。
     */
    @Test
    fun partialDetailIsDistinctFromMismatch() {
        val ledger = SecondClassPointLedger(
            byClassify = listOf(group("思想政治素养", 20.0, listOf(record("A", 12.0)))),
            byTerm = listOf(group("2025-2026-1", 20.0, listOf(record("A", 12.0)))),
        )
        val assessment = SecondClassPointAnalysis.trust(ledger)
        assertEquals(SecondClassPointAnalysis.Trust.PartialDetail, assessment.trust)
        assertEquals(8.0, assessment.unexplained, 1e-9)
        assertEquals(0.0, assessment.dimensionGap, 1e-9)
    }

    /**
     * 只有一维有数据时**不能**报 Mismatch。
     *
     * `dimensionGap` 此时会等于"有数据那一维的合计"（另一边是空的、按 0 参与相减），
     * 所以它在这里**没有"两个口径矛盾"的含义** —— 界面文案必须靠 [trust] 分支来选，
     * 不能凭 dimensionGap 非零就断言账目有问题。这条测试锁的就是这个区分。
     */
    @Test
    fun singleDimensionNeverReportsDimensionMismatch() {
        val ledger = SecondClassPointLedger(
            byClassify = listOf(group("思想政治素养", 9.0, listOf(record("A", 9.0)))),
            byTerm = emptyList(),
        )
        val assessment = SecondClassPointAnalysis.trust(ledger)
        assertNotEquals(SecondClassPointAnalysis.Trust.Mismatch, assessment.trust)
        // 单维度且自身对得上 → 认定为 Balanced，不得因为维度差而误报
        assertEquals(SecondClassPointAnalysis.Trust.Balanced, assessment.trust)
        // 自身小计与明细一致，缺口必须是 0
        assertEquals(0.0, assessment.unexplained, 1e-9)
    }

    /** 两端都空 → Unknown，不报警也不说"对得上"。 */
    @Test
    fun emptyLedgerIsUnknown() {
        val assessment = SecondClassPointAnalysis.trust(SecondClassPointLedger())
        assertEquals(SecondClassPointAnalysis.Trust.Unknown, assessment.trust)
        assertEquals(0.0, assessment.unexplained, 1e-9)
    }

    /**
     * 站点完全没给小计（siteTotal 全为 null）时，退回用明细求和比较。
     *
     * 若这里不兜底，两维度都会被当成"小计 0"，与明细求和差出满额缺口 → 误报 Mismatch。
     */
    @Test
    fun missingSiteTotalsFallBackToDetailSumWithoutFalseAlarm() {
        val ledger = SecondClassPointLedger(
            byClassify = listOf(group("分类一", null, listOf(record("A", 4.0), record("B", 6.0)))),
            byTerm = listOf(group("2025-2026-1", null, listOf(record("A", 4.0), record("B", 6.0)))),
        )
        val assessment = SecondClassPointAnalysis.trust(ledger)
        assertEquals(SecondClassPointAnalysis.Trust.Balanced, assessment.trust)
        assertEquals(0.0, assessment.unexplained, 1e-9)
    }

    /**
     * 浮点尾差不该被当成"对不上"。
     *
     * 站点下发的 hours 是字符串转来的 double，累加必然带尾差；阈值必须挡住它。
     */
    @Test
    fun floatingPointNoiseStaysWithinTolerance() {
        val ledger = SecondClassPointLedger(
            byClassify = listOf(
                group("分类一", 0.1 + 0.2, listOf(record("A", 0.1), record("B", 0.2))),
            ),
            byTerm = listOf(
                group("学期一", 0.1 + 0.2, listOf(record("A", 0.1), record("B", 0.2))),
            ),
        )
        assertEquals(SecondClassPointAnalysis.Trust.Balanced, SecondClassPointAnalysis.trust(ledger).trust)
    }

    // ── 分组级 reconciled ─────────────────────────────────────────────────

    /** 分组自己就能判"小计 vs 明细"；站点没给小计时一律算对得上（不误报）。 */
    @Test
    fun groupLevelReconciliation() {
        val ok = group("甲", 10.0, listOf(record("A", 10.0)))
        assertTrue(ok.reconciled)
        val bad = group("乙", 10.0, listOf(record("A", 6.0)))
        assertFalse(bad.reconciled)
        val noSiteTotal = group("丙", null, listOf(record("A", 6.0)))
        assertTrue(noSiteTotal.reconciled)
    }

    /** 展示用合计优先站点小计；站点没给才退回明细求和。 */
    @Test
    fun groupTotalPrefersSiteValue() {
        assertEquals(10.0, group("甲", 10.0, listOf(record("A", 6.0))).total, 1e-9)
        assertEquals(6.0, group("甲", null, listOf(record("A", 6.0))).total, 1e-9)
        assertEquals(0.0, group("空", null).total, 1e-9)
    }

    // ── 排序 ──────────────────────────────────────────────────────────────

    /** 分类按积分从高到低；这决定用户第一眼看到"大头在哪"。 */
    @Test
    fun categoriesSortedByPointsDescending() {
        val sorted = SecondClassPointAnalysis.categoryBreakdown(
            listOf(group("低", 3.0), group("高", 30.0), group("中", 12.0)),
        )
        assertEquals(listOf("高", "中", "低"), sorted.map { it.name })
    }

    /** 积分相同时按记录条数多的在前（信息量更大的先露）。 */
    @Test
    fun categoriesTieBreakByRecordCount() {
        val sorted = SecondClassPointAnalysis.categoryBreakdown(
            listOf(
                group("少", 5.0, listOf(record("A", 5.0))),
                group("多", 5.0, listOf(record("B", 2.0), record("C", 3.0))),
            ),
        )
        assertEquals(listOf("多", "少"), sorted.map { it.name })
    }

    /** 学期按新到旧：`termNumber` 大的在前。 */
    @Test
    fun termsSortedNewestFirstByTermNumber() {
        val sorted = SecondClassPointAnalysis.termBreakdown(
            listOf(
                group("2024-2025-1", 1.0, termNumber = "1"),
                group("2025-2026-1", 2.0, termNumber = "3"),
                group("2024-2025-2", 3.0, termNumber = "2"),
            ),
        )
        assertEquals(listOf("2025-2026-1", "2024-2025-2", "2024-2025-1"), sorted.map { it.name })
    }

    /**
     * 没有 `termNumber` 时从学期名抽数字排序。
     *
     * "2025-2026-1" 抽成 202520261，"2024-2025-2" 抽成 202420252 —— 后者更小，
     * 于是新学年排前面。这条覆盖的是"站点不给 termNumber"的学校。
     */
    @Test
    fun termsFallBackToDigitsInName() {
        val sorted = SecondClassPointAnalysis.termBreakdown(
            listOf(
                group("2024-2025-2", 1.0),
                group("2025-2026-1", 2.0),
            ),
        )
        assertEquals(listOf("2025-2026-1", "2024-2025-2"), sorted.map { it.name })
    }

    /** 完全抽不出数字的学期名排到最后，且不抛异常。 */
    @Test
    fun termNameWithoutDigitsSortsLast() {
        val sorted = SecondClassPointAnalysis.termBreakdown(
            listOf(group("未知学期", 1.0), group("2025-2026-1", 2.0)),
        )
        assertEquals("2025-2026-1", sorted.first().name)
        assertEquals(0L, SecondClassPointAnalysis.termSortKey(group("未知学期", 1.0)))
    }

    /** 分组内记录按时间倒序，未知时间排最后。 */
    @Test
    fun recordsSortedNewestFirstWithUnknownLast() {
        val sorted = SecondClassPointAnalysis.recordsNewestFirst(
            listOf(
                record("旧", 1.0, time = 100L),
                record("未知", 1.0, time = 0L),
                record("新", 1.0, time = 900L),
            ),
        )
        assertEquals(listOf("新", "旧", "未知"), sorted.map { it.name })
    }

    // ── 补全分类名 ────────────────────────────────────────────────────────

    /**
     * 分类维度下记录不带 classifyName，要由分组补上。
     *
     * 不补的话：按学期看有分类、按分类看反而没有 —— 同一个页面自相矛盾。
     */
    @Test
    fun groupCategoryIsInjectedWhenRecordLacksIt() {
        val g = group("思想政治素养", 5.0, listOf(record("A", 5.0)))
        val fixed = SecondClassPointAnalysis.withGroupCategory(g, g.records)
        assertEquals("思想政治素养", fixed.single().classifyName)
    }

    /** 记录自己带了分类（学期维度）时不覆盖 —— 站点给的是权威值。 */
    @Test
    fun existingCategoryIsNotOverwritten() {
        val g = group("2025-2026-1", 5.0, listOf(record("A", 5.0, classify = "社会实践")))
        val fixed = SecondClassPointAnalysis.withGroupCategory(g, g.records)
        assertEquals("社会实践", fixed.single().classifyName)
    }

    // ── 来源计数 ──────────────────────────────────────────────────────────

    /**
     * 来源数按名字去重（两个维度会重复计同一条账，必须先去重）。
     *
     * "共 N 个来源"是"我参加了多少件事"的口径，条数会被维度翻倍，不能用。
     */
    @Test
    fun distinctSourcesDeduplicatesAcrossDimensions() {
        val ledger = SecondClassPointLedger(
            byClassify = listOf(group("甲", 10.0, listOf(record("活动A", 5.0), record("活动B", 5.0)))),
            byTerm = listOf(group("学期一", 10.0, listOf(record("活动A", 5.0), record("活动C", 5.0)))),
        )
        assertEquals(3, SecondClassPointAnalysis.distinctSources(ledger))
    }

    /** 记录总条数是两个维度条数之和（"共有多少笔账要核"）。 */
    @Test
    fun recordCountSumsBothDimensions() {
        val ledger = SecondClassPointLedger(
            byClassify = listOf(group("甲", 10.0, listOf(record("A", 5.0), record("B", 5.0)))),
            byTerm = listOf(group("学期一", 10.0, listOf(record("A", 10.0)))),
        )
        assertEquals(3, ledger.recordCount)
    }

    // ── 记录的稳定标识 ────────────────────────────────────────────────────

    /**
     * 同名但金额不同的两笔账**必须**是两条记录。
     *
     * 若 identity 只用名字，列表 key 会重复（LazyColumn 直接崩），
     * 且跨维度比对会把真实的两笔合成一笔，账反而对不上。
     */
    @Test
    fun sameNameDifferentHoursProduceDifferentIdentity() {
        assertNotEquals(
            record("同名活动", 3.0).identity,
            record("同名活动", 4.0).identity,
        )
        assertEquals(record("同名活动", 3.0).identity, record("同名活动", 3.0).identity)
    }

    /** 类型码认不出时**不显示**来源标签，而不是编一个。 */
    @Test
    fun sourceLabelIsBlankWhenTypeUnknown() {
        assertEquals("", record("A", 1.0, sourceType = 0).sourceLabel)
        assertEquals("来源 1", record("A", 1.0, sourceType = 1).sourceLabel)
    }

    // ── 站点真实响应形态：以脱敏夹具覆盖字段缺失 ───────────────────────────
    //
    // 2505010308 的二课密码非默认值，且站点在连续 5 次失败后锁定 30 分钟，
    // 因此**无法**用真实账号跑网络验证。以下夹具按 `docs/adaptation/
    // 2026-09-18-hnnu-second-classroom.md` §4 记录的字段名逐字构造，用来锁住
    // "站点少给某个字段时账还能不能对上"这一族最容易静默出错的场景。

    /**
     * 站点对分组只给 `classifyHours`、不给 `hoursRecordList` 时：
     * 归为 PartialDetail（有总数没明细），**不能**误报 Mismatch ——
     * 报成 Mismatch 会让用户以为"分数丢了"，实际只是站点没下发明细。
     */
    @Test
    fun classifyWithoutDetailIsPartialNotMismatch() {
        val ledger = SecondClassPointLedger(
            byClassify = listOf(group("思想成长", 12.0)),           // 只有小计，无明细
            byTerm = listOf(group("2024-2025-1", 12.0, listOf(record("团课", 12.0)))),
        )
        val verdict = SecondClassPointAnalysis.trust(ledger)
        assertEquals(SecondClassPointAnalysis.Trust.PartialDetail, verdict.trust)
        assertFalse(verdict.isBalanced)
    }

    /**
     * 跨维度缺口必须由 `dimensionGap` 分支判出 Mismatch，且报出精确差额。
     *
     * 这条测试刻意让两个维度**各自内部自洽**（小计 == 明细），只让两维小计不等。
     * 只有这样，`unexplained` 才会因 `dimensionGap` 被计入而等于差额；若被测逻辑
     * 把"维度差"这条判据误删/误放宽，`unexplained` 就只剩 0，Mismatch 会退化成
     * Balanced —— 断言随即失败。构造数据时必须保持"各自自洽"，否则另一条
     * `unexplained > TOLERANCE` 分支会顶上来，把错误掩盖掉（变异测试已验证过这一点）。
     */
    @Test
    fun crossDimensionGapIsSurfacedWithMagnitude() {
        val ledger = SecondClassPointLedger(
            byClassify = listOf(group("甲", 10.0, listOf(record("A", 10.0)))),   // 自洽
            byTerm = listOf(group("学期一", 8.0, listOf(record("B", 8.0)))),     // 自洽
        )
        val verdict = SecondClassPointAnalysis.trust(ledger)
        assertEquals(SecondClassPointAnalysis.Trust.Mismatch, verdict.trust)
        assertEquals(2.0, verdict.dimensionGap, 1e-9)
        // 缺口只来自维度差，不含任何"小计 vs 明细"的缺口
        assertEquals(2.0, verdict.unexplained, 1e-9)
    }

    /**
     * 站点把 `classifyHours` 下发成 JSON `null` 时，应保持 `siteTotal == null`
     * （而不是 0），使对账退化为"以明细求和为准"而不是误报差了一个总数。
     */
    @Test
    fun nullSiteTotalFallsBackToDetailSum() {
        val g = group("甲", null, listOf(record("A", 3.0), record("B", 4.5)))
        assertEquals(7.5, g.total, 1e-9)
        assertTrue(g.reconciled)
    }

    /**
     * 学期维度按 `termNumber` 倒序（新在前）；没有 `termNumber` 时退化为
     * 从 `termName` 里抽数字比较 —— 保证"2024-2025-2"排在"2024-2025-1"之前。
     */
    @Test
    fun termsSortNewestFirstByNameFallback() {
        val ledger = SecondClassPointLedger(
            byTerm = listOf(
                group("2024-2025-1", 5.0, termNumber = ""),
                group("2024-2025-2", 5.0, termNumber = ""),
                group("2025-2026-1", 5.0, termNumber = ""),
            ),
        )
        val names = SecondClassPointAnalysis.termBreakdown(ledger.byTerm).map { it.name }
        assertEquals(listOf("2025-2026-1", "2024-2025-2", "2024-2025-1"), names)
    }

    /** 同一笔账在分类与学期两个维度都出现时，来源去重后只算一条。 */
    @Test
    fun sameRecordAcrossDimensionsCountsOnce() {
        val shared = record("志愿活动", 4.0, classify = "社会实践", relationId = "aa-1")
        val ledger = SecondClassPointLedger(
            byClassify = listOf(group("社会实践", 4.0, listOf(shared))),
            byTerm = listOf(group("2025-2026-1", 4.0, listOf(shared))),
        )
        assertEquals(1, SecondClassPointAnalysis.distinctSources(ledger))
        assertEquals(SecondClassPointAnalysis.Trust.Balanced, SecondClassPointAnalysis.trust(ledger).trust)
    }

    /**
     * 由真实响应推出的回归：**学期维记录不得携带 `relationId`**。
     *
     * 实测 2505050101：分类维每条记录带 `relationId`（77266/77680/…，各不同），
     * 学期维 12 条记录全都没有该字段，但都有一个 `identity`，且**全部等于 "3"**。
     * `identity` 是"参与者身份"枚举，不是记录 id —— 一旦被当作记录标识填进
     * `relationId`，同一学期内所有记录的 [SecondClassPointRecord.identity]
     * 都会退化成同一个值。
     *
     * 这条测试就是在锁这个后果：**同学期内名称与学时不同的两笔账必须有不同 identity**。
     */
    @Test
    fun recordsInSameTermWithDifferentHoursStayDistinct() {
        // 学期维记录：无 relationId（与真实响应一致 —— 站点没给，解析就不该编）
        val a = record("讲座参与", 1.0, time = 1761667200000L)
        val b = record("志愿服务活动", 2.0, time = 1761235200000L)
        assertNotEquals(a.identity, b.identity)
    }

    /**
     * `distinctSources` 的去重键是**记录名称**，不是 `(name,time,hours)`。
     *
     * 实测 2505050101 两维各 12 条、以名称去重后为 11 个来源 —— 说明确实存在
     * 同名两笔（不同时间），它们被合并成 1 个来源。这是该口径的**预期**行为：
     * 卡片上的"共 N 个来源"回答的是"我参加了多少类事"，不是"有多少笔账"
     * （后者由 [SecondClassPointLedger.recordCount] 给）。这里锁住这个语义，
     * 避免有人误改成按三元组去重导致口径漂移。
     */
    @Test
    fun distinctSourcesDeduplicatesByNameOnly() {
        val t1 = 1762428146000L
        val t2 = 1761235200000L
        val ledger = SecondClassPointLedger(
            byClassify = listOf(group("甲", 6.0, listOf(record("同名活动", 3.0, time = t1)))),
            byTerm = listOf(
                group("2025-2026学年", 5.0, listOf(
                    record("同名活动", 3.0, time = t1),   // 与分类维同一笔
                    record("同名活动", 2.0, time = t2),   // 同名但另一笔
                )),
            ),
        )
        // 3 条记录，按名称去重后只有 1 个来源
        assertEquals(3, ledger.recordCount)
        assertEquals(1, SecondClassPointAnalysis.distinctSources(ledger))
    }

    // ── 解析层：真实字段语义（防静默污染）──────────────────────────────────

    /**
     * **`identity` 绝不允许漏进 `relationId`。**
     *
     * 实测 2505050101 学期维 12 条记录的 `identity` 全部是 `"3"`（参与者身份枚举）。
     * 若解析层写成 `relationId = relationId.ifBlank { identity }`，这 12 条记录会
     * 拿到**同一个** relationId，进而使 [SecondClassPointRecord.identity] 全部相同 ——
     * LazyColumn key 重复直接抛异常，且"同名不同笔"彻底分不开。
     *
     * 这类缺陷编译不报错、也不影响对账数字，只有断言能拦住。
     *
     * 用变异测试确认过：把 `pointRecordFrom` 里的 `relationId = relationId`
     * 改成 `.ifBlank { identity }` 时，**本用例失败**，其余用例全绿 ——
     * 也就是说这条断言是该缺陷唯一的守卫。
     */
    @Test
    fun identityMustNotLeakIntoRelationId() {
        val parsed = pointRecordFrom(
            name = "廉洁文化知识竞答参与学生",
            hours = 3.0,
            amount = 3.0,
            time = 1762428146000L,
            classifyName = "思想政治素养",
            sourceType = 7,
            relationId = "",            // 学期维真实情况：没有 relationId
            identity = "3",             // 学期维真实情况：identity 恒为 "3"
        )
        assertEquals("", parsed.relationId)
        // 单笔的 identity 只由 name/hour/time 组成，不含 identity 字段
        assertEquals(
            SecondClassPointRecord(
                name = "廉洁文化知识竞答参与学生",
                hours = 3.0,
                amount = 3.0,
                time = 1762428146000L,
                classifyName = "思想政治素养",
                sourceType = 7,
                relationId = "",
            ).identity,
            parsed.identity,
        )
    }

    /**
     * 同一学期内两条真实记录（identity 都是 "3"）解析后必须 identity 互不相同。
     *
     * 这是上一条的"后果级"断言：直接演示"污染会导致列表 key 碰撞"。
     */
    @Test
    fun twoRealTermRecordsStayDistinctAfterParsing() {
        val a = pointRecordFrom(
            name = "2025年9月19日23级换届大会观众和志愿者", hours = 1.0, amount = 0.0,
            time = 1758211200000L, classifyName = "社会责任担当", sourceType = 7,
            relationId = "", identity = "3",
        )
        val b = pointRecordFrom(
            name = "招聘会插、拔旗子,餐饮文化节工作人员", hours = 1.0, amount = 0.0,
            time = 1761445275000L, classifyName = "社会责任担当", sourceType = 7,
            relationId = "", identity = "3",
        )
        assertNotEquals(a.identity, b.identity)
    }

    // ── 学期标题：只给学年时必须补上"第几学期" ────────────────────────────

    /**
     * 站点 `termName` 只给学年（实测 `2025-2026`），学期号在 `termNumber` 里。
     *
     * 若不补：同一学年的两个学期会显示成**两行一模一样的标题**，
     * 用户根本分不清哪行是第一/第二学期 —— 而"按学期看账"的全部意义就在这里。
     */
    @Test
    fun termNameWithoutSemesterGetsSemesterAppended() {
        val g = group("2025-2026", 12.0, termNumber = "1")
        assertEquals("2025-2026 第一学期", SecondClassPointAnalysis.termDisplayName(g))
    }

    @Test
    fun secondSemesterIsLabelledSecond() {
        val g = group("2025-2026", 12.0, termNumber = "2")
        assertEquals("2025-2026 第二学期", SecondClassPointAnalysis.termDisplayName(g))
    }

    /** 两个学期必须产出**不同**的标题，否则用户无法区分（本改动的核心目的）。 */
    @Test
    fun twoSemestersOfSameYearGetDistinctTitles() {
        val first = SecondClassPointAnalysis.termDisplayName(group("2025-2026", 1.0, termNumber = "1"))
        val second = SecondClassPointAnalysis.termDisplayName(group("2025-2026", 1.0, termNumber = "2"))
        assertNotEquals(first, second)
        assertTrue(first.contains("第一学期"))
        assertTrue(second.contains("第二学期"))
    }

    /** `termName` 已自带学期号（`2025-2026-1`）时原样返回，不重复拼接。 */
    @Test
    fun termNameWithOwnSemesterMarkerIsNotDoubled() {
        val g = group("2025-2026-1", 12.0, termNumber = "1")
        assertEquals("2025-2026-1", SecondClassPointAnalysis.termDisplayName(g))
    }

    /**
     * `2025-2026` 的末段是**四位年份**，不能被误判成学期号。
     *
     * 这正是 `endsWithSemesterMarker` 只看「一位数字」的原因 ——
     * 若判据放宽到"末段是数字"，学年就会被当成学期号，学期号再也补不上去。
     */
    @Test
    fun academicYearTailIsNotMistakenForSemesterMarker() {
        val g = group("2025-2026", 12.0, termNumber = "2")
        assertEquals("2025-2026 第二学期", SecondClassPointAnalysis.termDisplayName(g))
    }

    /** 站点没给 `termNumber` 时不编造学期号，退回原学年 —— 宁可少显示也不写错。 */
    @Test
    fun missingTermNumberFallsBackToRawName() {
        val g = group("2025-2026", 12.0)
        assertEquals("2025-2026", SecondClassPointAnalysis.termDisplayName(g))
    }

    /** 空名不抛异常。 */
    @Test
    fun blankTermNameIsHandledWithoutCrash() {
        assertEquals("", SecondClassPointAnalysis.termDisplayName(group("", 1.0, termNumber = "1")))
    }

    /** 超常见范围的学期号按 `第N学期` 原样拼，不产生"第 0 学期"这种鬼话。 */
    @Test
    fun outOfRangeSemesterNumberIsKeptVerbatim() {
        assertEquals("2025-2026 第0学期", SecondClassPointAnalysis.termDisplayName(group("2025-2026", 1.0, termNumber = "0")))
        assertEquals("2025-2026 第99学期", SecondClassPointAnalysis.termDisplayName(group("2025-2026", 1.0, termNumber = "99")))
    }

    /** 非数字的 `termNumber` 也原样带上，信息不丢。 */
    @Test
    fun nonNumericTermNumberIsKeptVerbatim() {
        assertEquals("2025-2026 第春季学期", SecondClassPointAnalysis.termDisplayName(group("2025-2026", 1.0, termNumber = "春季")))
    }

    // ── 单位：站点的数字枚举码不能直接当单位显示 ──────────────────────────

    /**
     * ⚠️ 真实缺陷回归：站点 `termHoursUnit` 下发的是**数字枚举码**（实测恒为 `"3"`），
     * 不是文案。直接拼到数字后面 → 界面上每个数值都变成 `25.8 3`，
     * 用户反馈就是"每个数字后面都有个 3"。
     *
     * 期望：数字码一律丢弃，回退到表头的真实单位。
     */
    @Test
    fun numericUnitCodeIsDiscardedInFavourOfRealUnit() {
        assertEquals("学时", SecondClassPointAnalysis.displayUnit("3", "学时"))
        assertEquals("学时", SecondClassPointAnalysis.displayUnit("", "学时"))
        assertEquals("学时", SecondClassPointAnalysis.displayUnit("0", "学时"))
    }

    /** 站点确实给了中文单位时用它 —— 那是权威值，不能被表头覆盖。 */
    @Test
    fun chineseUnitFromSiteWins() {
        assertEquals("学分", SecondClassPointAnalysis.displayUnit("学分", "学时"))
        assertEquals("学时", SecondClassPointAnalysis.displayUnit("学时", "学分"))
    }

    /** 两边都拿不到时返回空串 → 界面只显示数字，**绝不显示一个裸的 3**。 */
    @Test
    fun unitIsBlankWhenNothingUsable() {
        assertEquals("", SecondClassPointAnalysis.displayUnit("3", ""))
        assertEquals("", SecondClassPointAnalysis.displayUnit("", ""))
        assertEquals("", SecondClassPointAnalysis.displayUnit("   ", ""))
    }

    /**
     * 端到端后果断言：真实 `termHoursUnit="3"` 的一学期，
     * 渲染出的数值文案里**不能出现那个 3**。
     */
    @Test
    fun renderedAmountNeverCarriesTheRawUnitCode() {
        val unit = SecondClassPointAnalysis.displayUnit("3", "学时")
        val rendered = "25.80" + if (unit.isBlank()) "" else " $unit"
        assertEquals("25.80 学时", rendered)
        assertFalse(rendered.contains("3"))
    }
}
