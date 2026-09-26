package com.hnnujw.course.widgetboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面卡片"尺寸 → 版式"的边界。
 *
 * 这些算术原先埋在 RemoteViews 的渲染路径里，只能靠"上真机摆一个组件、肉眼看看
 * 有没有被挤出去"来验证。抽出来之后，四个档位（1×1 / 2×1 / 2×2 / 4×2）与
 * 字体放大后的行为都直接钉在这里。
 */
class CardWidgetLayoutTest {

    /** 课表卡片的典型内容：标题 + 大标题 + 副标题 + 汇总。 */
    private fun scheduleDraft() = CardDraft(
        title = "今日课表",
        badge = "正在上课",
        headline = "高等数学",
        subline = "08:00–09:40 · 博学楼 A101",
        footer = "今日 3 堂 · 还剩 2 堂",
    )

    /** 空态卡片：只有说明与动作，没有列表。 */
    private fun emptyDraft() = CardDraft(
        title = "下一节课",
        message = "今天没有课程",
        action = "查看课表",
    )

    @Test
    fun roomyCardKeepsEveryFixedElement() {
        val layout = cardWidgetLayout(widthDp = 250, heightDp = 250, fontScale = 1f, draft = scheduleDraft())
        assertTrue("2×2 上副标题与汇总都该留着", layout.showSubline && layout.showFooter)
        assertTrue("2×2 至少能放下一行", layout.maxRows >= 1)
        assertEquals("正常尺寸用常规内边距", 12, layout.paddingDp)
    }

    @Test
    fun crampedCardDropsFooterThenSubline() {
        // 130×90：固定元素共 109dp，比卡片还高。先砍汇总（109 → 92）还是超，
        // 再砍副标题（92 → 74）才落回可视区。
        val layout = cardWidgetLayout(widthDp = 130, heightDp = 90, fontScale = 1f, draft = scheduleDraft())
        assertFalse("放不下时先砍汇总", layout.showFooter)
        assertFalse("还是放不下才砍副标题", layout.showSubline)
    }

    @Test
    fun narrowButTallCardKeepsSubline() {
        // 2×2 的窄卡：宽 150 高 250。这里第一段时间看得全，是最有用的一段，不该砍。
        val layout = cardWidgetLayout(widthDp = 150, heightDp = 250, fontScale = 1f, draft = scheduleDraft())
        assertTrue("窄但高的卡片保留副标题", layout.showSubline)
    }

    @Test
    fun narrowAndShortCardDropsSublineEvenWhenItWouldFit() {
        // 2×1：宽 180 高 110。副标题会被省略号吃掉后半段，不如把高度让给正文。
        val layout = cardWidgetLayout(widthDp = 180, heightDp = 110, fontScale = 1f, draft = scheduleDraft())
        assertFalse(layout.showSubline)
    }

    @Test
    fun largerFontScaleNeverFitsMoreRows() {
        val small = cardWidgetLayout(250, 250, fontScale = 1f, draft = scheduleDraft())
        val large = cardWidgetLayout(250, 250, fontScale = 1.5f, draft = scheduleDraft())
        assertTrue(
            "字体放大后能放的行只可能变少或持平（${large.maxRows} vs ${small.maxRows}）",
            large.maxRows <= small.maxRows,
        )
    }

    @Test
    fun fontScaleBelowOneIsTreatedAsOne() {
        // fontScale < 1 在系统里几乎不会出现，但真出现了也不该"因此多塞几行"——
        // 那会让字号与行高对不上。
        val normal = cardWidgetLayout(250, 250, fontScale = 1f, draft = scheduleDraft())
        val tiny = cardWidgetLayout(250, 250, fontScale = 0.6f, draft = scheduleDraft())
        assertEquals(normal.maxRows, tiny.maxRows)
    }

    @Test
    fun unmeasuredSizeNeverYieldsNegativeRows() {
        // 部分桌面在组件刚加上去时会给出 0 —— 负数行数会让渲染层 take(-1) 抛异常。
        listOf(0 to 0, 0 to 110, 250 to 0, -1 to -1).forEach { (w, h) ->
            val layout = cardWidgetLayout(w, h, fontScale = 1f, draft = scheduleDraft())
            assertTrue("$w×$h 的行数不该为负", layout.maxRows >= 0)
            assertTrue("$w×$h 的内边距不该为负", layout.paddingDp > 0)
        }
    }

    @Test
    fun rowCountIsCappedSoAListNeverFillsAWholeScreen() {
        val layout = cardWidgetLayout(widthDp = 400, heightDp = 4_000, fontScale = 1f, draft = scheduleDraft())
        assertTrue("再高也不铺满（实际 ${layout.maxRows}）", layout.maxRows <= 10)
    }

    @Test
    fun footerIsDroppedBeforeTheSubline() {
        // 高 105 只够砍掉一项：汇总 17 砍掉后正好放下（109 → 92），副标题 18 砍掉后
        // 反而还多剩 1。所以"先砍汇总"这个顺序是能被观察到的，不是想当然。
        val layout = cardWidgetLayout(widthDp = 250, heightDp = 105, fontScale = 1f, draft = scheduleDraft())
        assertFalse("先砍的是汇总", layout.showFooter)
        assertTrue("副标题这时还留着", layout.showSubline)
    }

    @Test
    fun absentElementsAreNeverInvented() {
        val layout = cardWidgetLayout(widthDp = 250, heightDp = 250, fontScale = 1f, draft = emptyDraft())
        assertFalse("草稿没有副标题就不该显示副标题", layout.showSubline)
        assertFalse("草稿没有汇总就不该显示汇总", layout.showFooter)
    }

    @Test
    fun tinyCardHidesTheBadgeAndShrinksPadding() {
        val layout = cardWidgetLayout(widthDp = 60, heightDp = 60, fontScale = 1f, draft = scheduleDraft())
        assertFalse("1×1 上角标会把标题挤掉", layout.showBadge)
        assertEquals("小尺寸收掉内边距", 8, layout.paddingDp)
        assertEquals("小尺寸大标题收一档", 17f, layout.headlineSizeSp)
    }

    @Test
    fun wideCardKeepsTheBadge() {
        val layout = cardWidgetLayout(widthDp = 250, heightDp = 110, fontScale = 1f, draft = scheduleDraft())
        assertTrue(layout.showBadge)
    }

    @Test
    fun rowHeightGrowsWithFontScale() {
        val normal = cardWidgetLayout(250, 250, fontScale = 1f, draft = scheduleDraft())
        val large = cardWidgetLayout(250, 250, fontScale = 1.5f, draft = scheduleDraft())
        assertTrue(
            "行高必须跟着字体走，否则放大的字会被行容器裁掉",
            large.rowHeightDp > normal.rowHeightDp,
        )
    }

    // ── 行容器的上边距 ─────────────────────────────────────────────────────
    //
    // `card_widget.xml` 里行容器自己带 `layout_marginTop="6dp"`，这段开销不属于任何
    // 一个固定元素。漏掉它的后果不是"好看不好看"：行高 21dp，凭空多 6dp 预算会让
    // 约 6/21 ≈ 29% 的高度档位多算一行，而那一行会被容器裁掉一截 —— 半截的
    // "08:00–09:4…"比不显示更容易被当成数据错了。
    //
    // 这组断言取的是**同一个草稿、只差 10dp 的两个高度**：预算里含那 6dp 时，
    // 60dp 高刚好放不下一行、70dp 高刚好放得下一行。漏算的话 60dp 会算出 1 行。

    /** 只有标题 + 行，不含大标题 / 副标题 / 汇总 —— 把行容器的开销单独暴露出来。 */
    private fun rowsOnlyDraft() = CardDraft(
        title = "今日时间轴",
        rows = listOf(CardRowDraft("08:00", "高等数学")),
    )

    @Test
    fun rowsReserveTheirOwnLeadingMargin() {
        val tight = cardWidgetLayout(250, 60, fontScale = 1f, draft = rowsOnlyDraft())
        assertEquals(
            "60dp 高时行容器的 6dp 上边距把最后一行吃掉了，不能算成能放下",
            0, tight.maxRows,
        )
    }

    @Test
    fun oneRowFitsAgainTenDpTaller() {
        val roomy = cardWidgetLayout(250, 70, fontScale = 1f, draft = rowsOnlyDraft())
        assertEquals("再高 10dp 就该放得下一行，否则说明预算收得太狠", 1, roomy.maxRows)
    }
}
