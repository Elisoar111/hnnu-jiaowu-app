package com.hnnujw.course.widgetboard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import com.hnnujw.course.R
import com.hnnujw.course.manager.AppThemeCoordinator
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 桌面卡片的渲染层。
 *
 * 分成两半，是为了让"填什么内容"能脱离 Android 单测：
 *  - [cardDraft]：纯函数，`(cardId, 数据) → 一屏文字`。不碰 Resources、不碰 RemoteViews。
 *  - [CardWidgetRenderer.views]：把 [CardDraft] 画进 RemoteViews，并按组件的**实际尺寸**
 *    决定留几行、哪些元素干脆不显示。
 *
 * 为什么不是 Compose：桌面（Launcher）只认 `RemoteViews`，它没有 Compose 运行时，
 * 也不能放大头贴图 —— 让桌面显示一整张渲染图就失去逐行点击与系统字号自适应。
 * 所以这里用系统视图重画一遍，代价是外观做不到与 App 内像素级一致；
 * 换来的是可单独点、可自由摆、随系统浅深色切换。
 */

/** 一屏文字。行是三元组：左侧标签、中间内容、右侧状态。 */
internal data class CardDraft(
    val title: String,
    val badge: String? = null,
    val headline: String? = null,
    val subline: String? = null,
    val rows: List<CardRowDraft> = emptyList(),
    val message: String? = null,
    val action: String? = null,
    val footer: String? = null,
    /**
     * 整张卡片就是在讲这一门课（目前只有「下一节课」卡）时填上它的 id。
     *
     * 点卡片就打开这门课的详情 —— 卡片上写着课名，点开却是课表总览，等于让用户
     * 自己再找一遍。行级点击另有 [CardRowDraft.courseId]，两者互不影响。
     */
    val courseId: String? = null,
)

internal data class CardRowDraft(
    val label: String,
    val text: String,
    val status: String? = null,
    /**
     * 这一行是不是"正在进行"的那一节。
     *
     * 只有时间轴卡在用：它要在行首画一个圆点，进行中的那一节点亮成强调色，
     * 其余用次要色 —— 与参考实现的轨道圆点同一套语义。别的卡片是纯文本行，
     * 这个字段恒为 false，不影响它们。
     */
    val active: Boolean = false,
    /**
     * 这一行对应哪门课。非空时这一行**单独可点**，点开的是这门课的详情；
     * 为空则点击穿透到卡片根节点（跳到课表页）。
     *
     * 注意 [CardWidgetNavigation] 的 PendingIntent 判重：它只看 action/data/type/
     * component/categories，**不看 extras** —— 所以逐行的意图必须靠不同的 data URI
     * 区分，否则所有行会共用一个 PendingIntent（见那里的注释）。
     */
    val courseId: String? = null,
)

/** 卡片标题：与组件选择器里的名字保持一致（`card_widget_*_name`）。 */
internal fun cardTitle(cardId: String): String = when (cardId) {
    "schedule.next" -> "下一节课"
    "schedule.today" -> "今日课表"
    "schedule.timeline" -> "今日时间轴"
    "exam.countdown" -> "考试倒计时"
    "grades.overview" -> "成绩概览"
    "secondclass.overview" -> "第二课堂积分"
    "message.unread" -> "未读消息"
    else -> "校园助理"
}

/**
 * 组装一屏文字。空态一律走 `message` + `action`，**绝不用 0 或占位符冒充数据** ——
 * 桌面上分不清"真的是 0"和"还没加载出来"是最误导人的一种显示。
 */
internal fun cardDraft(cardId: String, data: WidgetBoardData): CardDraft {
    val title = cardTitle(cardId)
    return when (cardId) {
        "schedule.next" -> {
            val s = data.schedule
            val primary = s.primary
            if (primary == null) {
                CardDraft(title, badge = s.date, message = s.message ?: "今天没有课程", action = s.actionLabel)
            } else {
                CardDraft(
                    title,
                    badge = primary.status,
                    headline = primary.name,
                    subline = listOf(primary.dateLabel, primary.time, primary.location)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    footer = s.summary.ifBlank { null },
                    // 整张卡就是这一门课：点开它自己的详情，而不是课表总览。
                    courseId = primary.courseId.ifBlank { null },
                )
            }
        }

        "schedule.today" -> {
            val s = data.schedule
            val primary = s.primary
            if (primary == null) {
                CardDraft(title, badge = s.heading, message = s.message ?: "今天没有课程", action = s.actionLabel)
            } else {
                CardDraft(
                    title,
                    badge = s.heading,
                    headline = primary.name,
                    subline = listOf(primary.time, primary.location).joinToString(" · "),
                    rows = s.next?.let { next ->
                        listOf(
                            CardRowDraft(
                                next.dateLabel.ifBlank { "下一节" },
                                next.name,
                                next.time,
                                courseId = next.courseId.ifBlank { null },
                            ),
                        )
                    }.orEmpty(),
                    footer = s.summary.ifBlank { null },
                )
            }
        }

        "schedule.timeline" -> {
            val s = data.schedule
            if (s.timeline.isEmpty()) {
                CardDraft(title, badge = s.date, message = s.message ?: "今天没有课程", action = s.actionLabel)
            } else {
                CardDraft(
                    title,
                    badge = s.date,
                    rows = s.timeline.map {
                        CardRowDraft(
                            it.time,
                            it.name,
                            it.status,
                            active = it.ongoing,
                            courseId = it.courseId.ifBlank { null },
                        )
                    },
                    footer = s.summary.ifBlank { "共 ${s.timeline.size} 堂" },
                )
            }
        }

        "exam.countdown" -> {
            val exam = data.exam
            if (exam == null) {
                CardDraft(title, message = "暂无考试安排")
            } else {
                val days = when {
                    exam.days > 0 -> "还有 ${exam.days} 天"
                    exam.days == 0 -> "今天开考"
                    else -> "已结束"
                }
                CardDraft(
                    title,
                    badge = exam.time.ifBlank { null },
                    headline = days,
                    subline = listOf(exam.courseName, exam.examName)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    rows = listOf(
                        CardRowDraft("考场", exam.location.ifBlank { "待定" }),
                        CardRowDraft("座位", exam.seat.ifBlank { "待定" }),
                    ),
                )
            }
        }

        "grades.overview" -> {
            val grades = data.grades
            if (grades == null) {
                CardDraft(title, message = "还没有成绩缓存")
            } else {
                CardDraft(
                    title,
                    badge = "已出 ${grades.count} 门",
                    headline = "绩点 ${grades.gpa}",
                    subline = "学分 ${grades.credits}",
                    rows = listOfNotNull(
                        grades.average?.let { CardRowDraft("平均分", number(it)) },
                        grades.highest?.let { CardRowDraft("最高分", number(it)) },
                        grades.lowest?.let { CardRowDraft("最低分", number(it)) },
                    ),
                    footer = cacheAgeLabel(grades.fetchedAt, data.now),
                )
            }
        }

        "secondclass.overview" -> {
            val second = data.secondClass
            when {
                second == null -> CardDraft(title, message = "还没有积分缓存")
                !second.bound -> CardDraft(title, message = "还没绑定第二课堂", action = "去「我的」绑定")
                else -> CardDraft(
                    title,
                    badge = second.extraScore?.let { "综测附加分 ${number(it)}" },
                    // 合计的**标签**同样随站点下发，与 App 内 SecondClassroomScreen 的
                    // `if (hourUnit.isBlank()) "总积分" else "总$hourUnit"` 同源。
                    // 写死"总积分"会让同一张卡片上"总积分 40"与下一行"12 / 20 学时"互相矛盾。
                    headline = "总${second.hourUnit.trim().ifBlank { "积分" }} ${number(second.modules.sumOf { it.mine })}",
                    rows = second.modules.map { module ->
                        // 行内的单位同样随站点下发（`hourUnit`：学时 / 学分 / 分数，各校不同），
                        // 拿不到就不显示，而不是退回一个可能是错的单位。
                        CardRowDraft(
                            module.name,
                            "${number(module.mine)} / ${number(module.required)}${second.unitSuffix}",
                        )
                    },
                    footer = cacheAgeLabel(second.updatedAt, data.now),
                )
            }
        }

        "message.unread" -> {
            val messages = data.messages
            if (!messages.loaded) {
                CardDraft(title, message = "还没有消息缓存")
            } else {
                CardDraft(
                    title,
                    badge = "共 ${messages.total} 条",
                    headline = if (messages.unread > 0) "${messages.unread} 条未读" else "没有未读",
                    subline = messages.latestTitle.ifBlank { "暂无消息" },
                    footer = messages.latestTime.ifBlank { null },
                )
            }
        }

        else -> CardDraft(title, message = "未知的卡片类型")
    }
}

/** 浮点数去掉多余的小数位：12.0 → 12，3.456 → 3.5。 */
internal fun number(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else BigDecimal(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** 缓存年龄。时间戳为 0（没有缓存时间）时返回 null，不显示比显示"54 年前"强。 */
internal fun cacheAgeLabel(at: Long, now: Long): String? {
    if (at <= 0L) return null
    val minutes = ((now - at).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        minutes < 1 -> "刚刚更新"
        minutes < 60 -> "$minutes 分钟前更新"
        minutes < 60 * 24 -> "${minutes / 60} 小时前更新"
        else -> "${minutes / (60 * 24)} 天前更新"
    }
}

internal object CardWidgetRenderer {

    private const val LIGHT_BG = R.drawable.card_widget_light
    private const val DARK_BG = R.drawable.card_widget_dark

    /** 时间轴卡片用带轨道圆点的行，其余卡片用通用的三列行。 */
    private fun rowLayout(cardId: String): Int =
        if (cardId == "schedule.timeline") R.layout.card_widget_timeline_row else R.layout.card_widget_row

    /**
     * 按桌面给出的**真实尺寸**出图。
     *
     * ## 为什么不能只传一个尺寸
     *
     * `AppWidgetManager.getAppWidgetOptions(id)` 里 `OPTION_APPWIDGET_MIN_*` 是用户能把组件
     * 拖到的**最小**尺寸。只按它出图，等于"无论用户拖多大，都只画紧凑版然后拉伸" ——
     * 大尺寸上该出现的教室、更多行、汇总全都没有，白白浪费了用户主动放大的那一块。
     *
     * 两条路：
     *  - **API 31+**：`OPTION_APPWIDGET_SIZES` 给出这台桌面上该组件**可能**用到的每一个尺寸，
     *    于是逐个尺寸各出一份 `RemoteViews`，交给系统的"响应式布局"（`RemoteViews(Map)`）。
     *    桌面用哪个尺寸就直接取哪一份，不再有拉伸。
     *  - **旧版本**没有这个 API，退回到"两个变体"：横向（max 宽 × min 高）与纵向（min 宽 × max 高）。
     *    这是 AppWidget 一直以来的老办法，能覆盖"拉宽"与"拉高"两个方向。
     *
     * 尺寸可能返回 0（组件刚加上去、桌面还没量出来），交给 [cardWidgetLayout] 夹住 ——
     * 那里不会因为 0 算出负数行数。
     */
    fun responsiveViews(
        context: Context,
        cardId: String,
        data: WidgetBoardData,
        options: Bundle,
    ): RemoteViews {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val sizes = options
                .getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                .orEmpty()
                .filter { it.width.isFinite() && it.height.isFinite() && it.width > 0f && it.height > 0f }
                .distinct()
                .take(16)
            if (sizes.isNotEmpty()) {
                return RemoteViews(sizes.associateWith {
                    views(app, cardId, data, it.width.toInt(), it.height.toInt())
                })
            }
        }
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(1)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110).coerceAtLeast(1)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth).coerceAtLeast(minWidth)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight).coerceAtLeast(minHeight)
        // RemoteViews(landscape, portrait)：先横向后纵向。拉宽看第一份，拉高看第二份。
        return RemoteViews(
            views(app, cardId, data, maxWidth, minHeight),
            views(app, cardId, data, minWidth, maxHeight),
        )
    }

    fun views(
        context: Context,
        cardId: String,
        data: WidgetBoardData,
        width: Int = 250,
        height: Int = 110,
    ): RemoteViews {
        val config = AppThemeCoordinator.wrapContext(context).resources.configuration
        val dark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val primary = if (dark) 0xFFF5F5FA.toInt() else 0xFF1D2433.toInt()
        val secondary = if (dark) 0xFFBFC7D9.toInt() else 0xFF536078.toInt()
        val accent = if (dark) 0xFF9CB8FF.toInt() else 0xFF345DC4.toInt()
        val dotMuted = if (dark) 0xFF5A6478.toInt() else 0xFFA8B2C4.toInt()
        val density = context.resources.displayMetrics.density
        val draft = cardDraft(cardId, data)
        // 尺寸与字体缩放怎么换算成版式，全部由这个纯函数决定（见 CardWidgetLayout 的注释）。
        val layout = cardWidgetLayout(width, height, config.fontScale, draft)

        // 先按已经定下来的元素估"留给行的空间"，再决定截到第几条 —— 顺序反过来的话，
        // 空态卡片会白留一大片，满内容卡片又会把底部汇总顶出可视区。
        val shown = draft.rows.take(layout.maxRows)
        val dropped = draft.rows.size - shown.size

        val views = RemoteViews(context.packageName, R.layout.card_widget)
        views.setInt(R.id.card_root, "setBackgroundResource", if (dark) DARK_BG else LIGHT_BG)
        val pad = (layout.paddingDp * density).toInt()
        views.setViewPadding(R.id.card_root, pad, pad, pad, pad)

        fun text(id: Int, value: String?) {
            views.setTextViewText(id, value.orEmpty())
            views.setViewVisibility(id, if (value.isNullOrBlank()) View.GONE else View.VISIBLE)
        }

        text(R.id.card_title, draft.title)
        text(R.id.card_badge, draft.badge.takeIf { layout.showBadge })
        text(R.id.card_headline, draft.headline)
        text(R.id.card_subline, draft.subline.takeIf { layout.showSubline })
        text(R.id.card_message, draft.message)
        text(R.id.card_action, draft.action)
        // 行被截断时把"还有几条"接到汇总后面 —— 而不是让用户以为今天只有这几节课。
        //
        // 卡片本身**没有**汇总行时（如 exam.countdown）不合成这一句：汇总行的高度是
        // [cardWidgetLayout] 预算里的一项，只有 `draft.footer != null` 才预留，凭空多画
        // 一行会把最后一行顶出行容器（`card_footer` 在垂直布局的末尾，超出的部分是直接
        // 被裁掉的，不是被挤走）。原先这里有一条 `?: if (dropped > 0 && layout.showFooter)`
        // 的兜底，但它**不可达** —— `showFooter` 为真蕴含 `draft.footer != null`。
        // 已知代价：这类"有行无汇总"的卡片在尺寸不够时会静默少画一行。这比把正文裁掉
        // 半截划算，也比为它硬塞一行汇总而把已有的正文挤下去划算。
        text(R.id.card_footer, draft.footer
            ?.takeIf { layout.showFooter }
            ?.let { if (dropped > 0) "$it（还有 $dropped 条）" else it })
        // 小卡片上把大标题收一档，否则"绩点 3.46"这种短句会撑满整张卡。
        // 正文与标题交给 XML 里的 sp 值自己跟系统字体缩放走，不在这里再算一遍。
        views.setTextViewTextSize(R.id.card_headline, TypedValue.COMPLEX_UNIT_SP, layout.headlineSizeSp)

        listOf(R.id.card_title, R.id.card_headline, R.id.card_message)
            .forEach { views.setTextColor(it, primary) }
        listOf(R.id.card_subline, R.id.card_footer).forEach { views.setTextColor(it, secondary) }
        listOf(R.id.card_badge, R.id.card_action).forEach { views.setTextColor(it, accent) }

        views.removeAllViews(R.id.card_rows)
        for (row in shown) {
            val item = RemoteViews(context.packageName, rowLayout(cardId))
            item.setTextViewText(R.id.card_row_label, row.label)
            item.setTextViewText(R.id.card_row_text, row.text)
            item.setTextViewText(R.id.card_row_status, row.status.orEmpty())
            item.setTextColor(R.id.card_row_label, secondary)
            item.setTextColor(R.id.card_row_text, primary)
            item.setTextColor(R.id.card_row_status, if (row.active) accent else secondary)
            item.setViewVisibility(R.id.card_row_status,
                if (row.status.isNullOrBlank()) View.GONE else View.VISIBLE)
            if (cardId == "schedule.timeline") {
                // 轨道圆点：进行中的那一节点亮，其余是静默的小点 —— 一眼就能定位到"现在上到哪了"。
                item.setInt(R.id.card_row_dot, "setBackgroundColor", if (row.active) accent else dotMuted)
            }
            // 逐行读屏：桌面上一行是一个整体（时间 + 课名 + 状态），分开播报会听不出对应关系。
            item.setContentDescription(
                R.id.card_row_root,
                listOf(row.label, row.text, row.status.orEmpty())
                    .filter { it.isNotBlank() }.joinToString("，"),
            )
            // 这一行能落到具体一门课时，让它**自己**可点；否则不给它挂意图，
            // 触摸事件自然落到卡片根节点上（跳课表页）。两种行为都不至于"点了没反应"。
            row.courseId?.let { courseId ->
                item.setOnClickPendingIntent(R.id.card_row_root, pendingIntent(context, cardId, courseId))
            }
            views.addView(R.id.card_rows, item)
        }
        // 有内容时行容器占满剩余高度；空态时让它整块消失，说明文字才能垂直居中。
        views.setViewVisibility(R.id.card_rows,
            if (shown.isEmpty()) View.GONE else View.VISIBLE)

        // 空态是一块可点的整体（说明 + 动作），读屏也要能听出"点它会怎样"。
        draft.message?.let {
            views.setContentDescription(R.id.card_message, listOf(it, draft.action.orEmpty()).filter(String::isNotBlank).joinToString("，"))
        }

        views.setOnClickPendingIntent(R.id.card_root, pendingIntent(context, cardId, draft.courseId))
        return views
    }

    /**
     * 卡片与行的点击意图。`requestCode` 恒为 0 是安全的 —— `PendingIntent` 的判重
     * 把 `Intent` 的 data 也算进去，而 [CardWidgetNavigation] 给每张卡、每一行都
     * 生成了不同的 data URI，所以它们不会互相覆盖（见那里的注释）。
     */
    private fun pendingIntent(context: Context, cardId: String, courseId: String?): PendingIntent =
        PendingIntent.getActivity(
            context, 0, CardWidgetNavigation.intent(context, cardId, courseId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 把这张卡片**真渲染一遍**再截成位图，给组件抽屉当预览。
     *
     * ## 为什么不另写一套 Compose 预览
     *
     * 抽屉里原本用 App 内的 Compose 卡片当预览，于是"预览长这样、桌面上却不是这样"：
     * 两套渲染各自演化，抽屉里好看、桌面上缺行，而且用户是在**添加之前**看预览的 ——
     * 这时候骗他，代价是加完才发现不对，再删一次。
     *
     * 这里直接复用 [views] 的产物（同一个 `cardDraft` + 同一套 [cardWidgetLayout] 决策），
     * 所以预览与桌面组件不可能不一致：改了渲染，预览跟着变。
     *
     * 尺寸传桌面声明的最小尺寸（见 `card_widget_*_info.xml` 的 `minWidth`/`minHeight`），
     * 得到的正是"刚放到桌面上、还没拉大"的那个紧凑形态。
     *
     * 渲染失败（老 ROM 上 `RemoteViews.apply` 偶有异常）返回 null，由调用方退回原预览 ——
     * 一个锦上添花的预览不该让整个抽屉打不开。
     */
    fun previewBitmap(
        context: Context,
        cardId: String,
        data: WidgetBoardData,
        widthDp: Int,
        heightDp: Int,
    ): Bitmap? = runCatching {
        val density = context.resources.displayMetrics.density
        val width = (widthDp.coerceAtLeast(1) * density).toInt().coerceAtLeast(1)
        val height = (heightDp.coerceAtLeast(1) * density).toInt().coerceAtLeast(1)
        // apply 需要一个 parent 来解析 layout 参数；这个 FrameLayout 不必挂到窗口上，
        // 我们只拿它量一次尺寸、画一次，不参与显示。
        val root = views(context, cardId, data, widthDp, heightDp).apply(context, FrameLayout(context))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
    }.getOrNull()
}
