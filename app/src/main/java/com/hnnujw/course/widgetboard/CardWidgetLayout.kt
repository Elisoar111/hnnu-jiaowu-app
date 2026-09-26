package com.hnnujw.course.widgetboard

/**
 * 桌面卡片的**尺寸 → 版式**决策。
 *
 * 从 `CardWidgetRenderer.views` 里抽出来的唯一理由：这一段全是"放得下 / 放不下"的算术，
 * 却跑在 RemoteViews 的渲染路径上 —— 想验证"2×1 的卡片到底会不会把汇总挤出去"
 * 就必须上真机摆一个组件。抽成纯函数之后，边界由 `CardWidgetLayoutTest` 直接钉住。
 *
 * 这里**没有 Android 依赖**：长度单位一律 dp、字号一律 sp，字体缩放由调用方以
 * `fontScale` 传进来（取自 `Configuration.fontScale`）。换算成像素是渲染层的事。
 *
 * 三条口径与参考实现一致：
 *  1. **字体放大要占掉行数**。系统字号调大后，同样高度的卡片能放下的行更少，
 *     所以每一项固定开销都乘 `fontScale` —— 不乘的话，放大字体后汇总行会被顶出可视区。
 *  2. **挤不下时先砍信息量最低的**：汇总（本行有几节）→ 副标题（时间 · 地点）。
 *     大标题与空态说明是正文，不砍。
 *  3. **小尺寸连内边距一起收**。1×1 / 2×1 的卡片里，12dp 的留白比一行正文还贵。
 */

/** 一张卡片在某个体积下该怎么画。字段全部是"已经定下来的决定"，渲染层照着执行即可。 */
internal data class CardWidgetLayout(
    /** 四周内边距（dp）。 */
    val paddingDp: Int,
    /** 大标题字号（sp）。小卡片上收一档，否则"绩点 3.46"这种短句会撑满整张卡。 */
    val headlineSizeSp: Float,
    /** 一行的高度（dp，已含字体缩放）。行数上限就是按它除出来的。 */
    val rowHeightDp: Float,
    /** 是否画右上角的状态角标（"正在上课"这类）。窄卡片上它会把标题挤掉。 */
    val showBadge: Boolean,
    val showSubline: Boolean,
    val showFooter: Boolean,
    /** 最多画几行列表。0 = 一行都不画。 */
    val maxRows: Int,
)

/** 固定元素的高度开销（dp，未乘字体缩放）。与 `card_widget.xml` 里各 TextView 的 sp 值对应。 */
private const val TitleCost = 20.0
private const val HeadlineCost = 30.0
private const val SublineCost = 18.0
private const val MessageCost = 24.0
private const val ActionCost = 18.0
private const val FooterCost = 17.0

/** 一行的基准高度（dp）。`card_widget_row.xml` 的 12sp 正文 + 上下各 3dp 内边距。 */
private const val RowHeightBase = 21.0

/**
 * 行容器自己那条 `layout_marginTop`（`card_widget.xml` 里 `card_rows` 的 6dp）。
 *
 * 它**不属于任何一个固定元素** —— 标题、大标题、汇总的开销里都不含它，只有"要画行"
 * 时才产生。不单列出来的话，带行的卡片会凭空多出 6dp 预算：行高是 21dp，于是约
 * 6/21 ≈ 29% 的高度档位上会多算一行，而那一行会被容器裁掉一截 —— 正是下面这条注释
 * 里说必须避免的情况。
 *
 * 不乘 `fontScale`：它是 dp 的 margin，不随系统字号变（行高那部分由 [RowHeightBase] 承担）。
 */
private const val RowsLeadingCost = 6.0

/** 列表最多画几行。再高也不铺满 —— 桌面上扫一眼能读完的量就到这儿。 */
private const val MaxRowsCeiling = 10

internal fun cardWidgetLayout(
    widthDp: Int,
    heightDp: Int,
    fontScale: Float,
    draft: CardDraft,
): CardWidgetLayout {
    val scale = fontScale.coerceAtLeast(1f)
    // 尺寸可能因为桌面还没量出来而传 0/负数（`OPTION_APPWIDGET_*` 在部分桌面返回 0）。
    // 夹到 1 而不是直接算：负数会算出负的行数，再 coerce 就成了"能放 0 行"的假结论。
    val width = widthDp.coerceAtLeast(1)
    val height = heightDp.coerceAtLeast(1)

    val padding = if (height < 90 || width < 130) 8 else 12

    val titleCost = if (draft.title.isNotBlank()) TitleCost * scale else 0.0
    val headlineCost = if (draft.headline != null) HeadlineCost * scale else 0.0
    val sublineCost = if (draft.subline != null) SublineCost * scale else 0.0
    val messageCost = if (draft.message != null) MessageCost * scale else 0.0
    val actionCost = if (draft.action != null) ActionCost * scale else 0.0
    val footerCost = if (draft.footer != null) FooterCost * scale else 0.0

    val rowsCost = if (draft.rows.isNotEmpty()) RowsLeadingCost else 0.0

    // 内边距**故意**也乘了 `scale`：实际 `setViewPadding` 用的是 `paddingDp * density`，
    // 不随系统字号变，所以严格来说这里多算了。留着它是因为下面那些 `*Cost` 常数是按
    // sp 估算的、对 12sp/19sp 这类字号偏**低**（实测约差 3dp），字体放大后这个偏差会
    // 跟着 `scale` 一起放大；内边距这份多算正好把它抵掉，让整体预算在大字号下仍处于
    // 偏保守的一侧。要动就一起动：只把 `scale` 从内边距上摘掉，大字号下会变成欠预留，
    // 最后一行又被裁掉半截。
    var reserved = padding * 2.0 * scale + titleCost + headlineCost +
        sublineCost + messageCost + actionCost + footerCost + rowsCost

    // 一行高的组件（≈ 2×1）放不下全部固定元素时，宁可少画一行，也不要把字裁成半截：
    // 被裁一半的"08:00–09:4…"比不显示更容易被当成数据错了。
    var showFooter = draft.footer != null
    if (reserved > height && showFooter) {
        showFooter = false
        reserved -= footerCost
    }
    var showSubline = draft.subline != null
    if (reserved > height && showSubline) {
        showSubline = false
        reserved -= sublineCost
    }
    // 2 格宽 + 一行高（2×1）时连副标题也放不下：「08:00–09:40 · 博学楼 A101」被省略号
    // 吃掉后半段后，剩下的信息还不如把这点高度让给正文。2×2 及以上的窄卡片不砍 ——
    // 那里第一段时间是看得全的，也是这一行里最有用的一段。
    if (width < 200 && height < 140 && showSubline) {
        showSubline = false
        reserved -= sublineCost
    }

    val rowHeight = RowHeightBase * scale
    val maxRows = ((height - reserved) / rowHeight).toInt().coerceIn(0, MaxRowsCeiling)

    return CardWidgetLayout(
        paddingDp = padding,
        headlineSizeSp = if (height < 90) 17f else 19f,
        rowHeightDp = rowHeight.toFloat(),
        // 角标是"正在上课 / 共 N 条"这类补充信息。窄卡片上它与标题争同一行，
        // 标题被省略号吃掉比角标看不见更亏，所以窄的时候先让位。
        showBadge = width >= 130 && height >= 60,
        showSubline = showSubline,
        showFooter = showFooter,
        maxRows = maxRows,
    )
}
