package com.hnnujw.course.review.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.ALL_CHAPTERS_LABEL
import com.hnnujw.course.review.CustomDurationMinutes
import com.hnnujw.course.review.ExamDurationPresetsMinutes
import com.hnnujw.course.review.PracticeLaunch
import com.hnnujw.course.review.PracticeModeOption
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.ReviewMode
import com.hnnujw.course.review.ReviewPrefs
import com.hnnujw.course.review.accuracyPercent
import com.hnnujw.course.review.chapterSelectionLabel
import com.hnnujw.course.review.durationLabel
import com.hnnujw.course.review.resolvePracticeParams
import com.hnnujw.course.review.resolveSelectedChapters
import com.hnnujw.course.review.selectedChapterQuestionCount
import com.hnnujw.course.review.toggleChapterSelection
import com.hnnujw.course.ui.system.GlassLoadingState
import com.hnnujw.course.ui.system.GlassOptionWheelDialog
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassStatChip
import com.hnnujw.course.ui.system.LiquidSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 题库详情页 —— 本题的**开练中枢**。
 *
 * 2026-10 按用户要求重排成一条单向的决策链，顺序**不可打乱**：
 *
 *   ① 概况（这个库有多大、练到什么程度）
 *   ② **章节练习** —— 先定"练哪一部分"（可折叠、可多选）
 *   ③ **练习模式** —— 再定"怎么练"（滚轮选：顺序练习 / 乱序练习 / 考试 / 背题模式）
 *   ④ 错题本 / 收藏 —— 独立胶囊按钮，**跳页查看**而不是直接开练
 *
 * 🔴 2026-10 用户要求「将查看的统计删除」：本页「查看」里的「统计（X%）」入口已删掉，
 * 统计页（`BankStatsScreen`）整页随之删除，只服务于它的 `dailyStats()` 与 `daily_stats`
 * 表写入也一并去掉。**本页顶部概况卡里的「已练正确率」不受影响**，正确率仍然看得到；
 * 一轮练习结束后的总结页也会给出本次正确率。
 *
 * 本轮（2026-10 第三批）的两处改动：
 *
 *  - **练习模式改成滚轮选择**。上一版是四个并排的格子、点一下就开练；现在是一行字段
 *    （`练习模式   顺序练习 ›`），点开一个玻璃滚轮弹窗 —— 与一卡通里选宿舍电费是
 *    同一种交互。四选一用滚轮比四个格子好在：格子必须一次全塞进一屏，模式名一长就换行；
 *    而滚轮天然是"多选一"，且以后加模式不用再动布局。
 *  - **「考试时长」只在选中「考试」时出现**，且档位改成预设 + **自定义分钟数**。
 *    上一版是常显的四个 chip，其中「不限时」还得把「考试模拟」禁用掉并解释原因 ——
 *    因为"不限时的考试"就是顺序练习。现在这一档直接不存在了。
 *
 * 🔴 项目红线写死在这里：**入口常显，不按状态条件隐藏**。题数为 0 / 没有错题 / 没有收藏时，
 * 对应入口仍然渲染出来、只是禁用并给出原因 —— 数量为 0 就整个入口消失的话，
 * 用户会以为功能根本不存在（真机报过「没有错题本」）。
 */
@Composable
fun BankDetailScreen(
    context: Context,
    bankId: String,
    onBack: () -> Unit,
    onStartPractice: (PracticeLaunch) -> Unit,
    /** 打开错题本页面（查看该库的错题，而不是直接开练）。 */
    onOpenWrongBook: () -> Unit,
    /** 打开收藏页（查看该库收藏的题，并从这里开练）。 */
    onOpenFavorites: () -> Unit
) {
    val db = remember { ReviewDatabase.get(context) }
    var bankName by remember { mutableStateOf("") }
    var overview by remember { mutableStateOf<ReviewDatabase.Overview?>(null) }
    var chapters by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // 章节多选（空集 = 全部章节）与折叠状态都是界面状态。
    var selectedChapters by remember { mutableStateOf<Set<String>>(emptySet()) }
    var chaptersExpanded by remember { mutableStateOf(true) }
    // 练习模式（滚轮下标，对应 PracticeModeOption.entries）与考试时长（分钟）。
    var modeIndex by remember { mutableIntStateOf(0) }
    var durationMinutes by remember { mutableIntStateOf(DefaultExamMinutes) }
    var showModePicker by remember { mutableStateOf(false) }
    var showDurationPicker by remember { mutableStateOf(false) }
    var showCustomDurationPicker by remember { mutableStateOf(false) }
    // 「选项乱序」是**偏好级**开关（存 ReviewPrefs），不是本轮参数 ——
    // 用户调一次就该一直生效，不该每轮重选。所以这里读一次、改动即落盘。
    var shuffleOptions by remember { mutableStateOf(ReviewPrefs.isShuffleOptions(context)) }

    LaunchedEffect(bankId) {
        val result = withContext(Dispatchers.IO) {
            Triple(
                db.overview(bankId),
                db.chaptersWithCount(bankId),
                // 只要一个名字：bankName 单行查询，别为标题跑 listBanks 全表。
                db.bankName(bankId)
            )
        }
        overview = result.first
        chapters = result.second
        bankName = result.third
        loaded = true
    }

    GlassPageScaffold(
        title = bankName.ifBlank { "题库" },
        subtitle = overview?.let { "${it.total} 题" },
        onBack = onBack
    ) { padding ->
        if (!loaded) {
            GlassLoadingState(text = "正在读取题库…")
            return@GlassPageScaffold
        }
        val ov = overview ?: return@GlassPageScaffold
        // 章节列表可能被重算，把选择里已经不存在的章节剔掉 —— 否则会显示"已选 2 章"
        // 但实际只抽到 1 章的题。
        val validChapters = remember(chapters, selectedChapters) {
            selectedChapters.intersect(chapters.map { it.first }.toSet())
        }
        val selectedCount = selectedChapterQuestionCount(chapters, validChapters)
        val mode = PracticeModeOption.entries[modeIndex.coerceIn(0, PracticeModeOption.entries.lastIndex)]
        val params = resolvePracticeParams(mode, durationMinutes)

        // 所有开练入口共用的一组参数：当前章节选择 + 当前模式。
        fun launch(
            mode: ReviewMode = ReviewMode.SEQUENTIAL,
            memorize: Boolean = false,
            onlyWrong: Boolean = false,
            dueOnly: Boolean = false,
            onlyFavorite: Boolean = false,
            timedSeconds: Int = 0
        ) = onStartPractice(
            PracticeLaunch(
                bankId = bankId,
                chapters = resolveSelectedChapters(validChapters),
                mode = mode,
                onlyWrong = onlyWrong,
                dueOnly = dueOnly,
                onlyFavorite = onlyFavorite,
                memorize = memorize,
                durationSeconds = timedSeconds
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding() + 8.dp,
                    // 🔴 底部必须吃掉 scaffold 的 bottom inset。上一版只 pad 了 top，
                    // 末位内容被系统导航条压住，表现为"底部内容被截断、要上滑才看得全"。
                    bottom = padding.calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // —— ① 概况
            Card(entranceGroup = 0) {
                Text(
                    text = bankName.ifBlank { "题库" },
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassStatChip(
                        value = "${ov.total}",
                        label = "总题数",
                        modifier = Modifier.weight(1f)
                    )
                    GlassStatChip(
                        value = "${chapters.size}",
                        label = "章节数",
                        modifier = Modifier.weight(1f)
                    )
                    GlassStatChip(
                        value = "${accuracyPercent(ov.correct, ov.answered)}%",
                        label = "已练正确率",
                        valueColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // —— ② 章节练习（垂直列表 + 折叠 + 多选）
            Card(entranceGroup = 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { chaptersExpanded = !chaptersExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader("章节练习")
                    Spacer(Modifier.width(10.dp))
                    // 折叠状态下这里就是唯一的范围提示 —— 必须能一眼看出"要练哪一部分"。
                    Text(
                        text = "${chapterSelectionLabel(validChapters)} · $selectedCount 题",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (chaptersExpanded) "收起" else "展开",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (!chaptersExpanded) return@Card

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "可多选：勾中的章节会一起抽题。全部不勾等于「全部章节」。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                // 「全部章节」是一个"清空选择"的快捷项，不是普通章节，所以单独放第一行。
                ChapterRow(
                    label = ALL_CHAPTERS_LABEL,
                    count = ov.total,
                    checked = validChapters.isEmpty(),
                    onClick = { selectedChapters = emptySet() }
                )
                chapters.forEach { (name, count) ->
                    ChapterRow(
                        label = name,
                        count = count,
                        checked = name in validChapters,
                        onClick = { selectedChapters = toggleChapterSelection(validChapters, name) }
                    )
                }
            }

            // —— ③ 练习模式（滚轮选择 + 考试时长 + 开始练习）
            Card(entranceGroup = 2) {
                SectionHeader("练习模式")
                Spacer(Modifier.height(10.dp))

                // 模式本身：一行字段，点开滚轮弹窗。
                PickerField(
                    label = "模式",
                    value = mode.label,
                    enabled = true,
                    onClick = { showModePicker = true }
                )

                // 时长只在「考试」下出现 —— 其余三个模式不限时，显示了反而让人以为要设时间。
                if (mode.needsDuration) {
                    Spacer(Modifier.height(8.dp))
                    PickerField(
                        label = "考试时长",
                        value = durationLabel(durationMinutes),
                        enabled = true,
                        onClick = { showDurationPicker = true }
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = mode.hint(durationMinutes),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))
                ConfirmButton(
                    text = if (selectedCount > 0) "开始练习（$selectedCount 题）" else "开始练习",
                    enabled = selectedCount > 0,
                    onClick = {
                        launch(
                            mode = params.mode,
                            memorize = params.memorize,
                            timedSeconds = params.durationSeconds
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (selectedCount == 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "这个题库里没有可练的题 —— 先到「题库仓库 → 题库管理」里导入或添加题目。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(14.dp))
                // 选项乱序：偏好级开关，改一次一直生效。
                // 提示里**如实写出已知限制**（解析正文里的字母引用不会跟着重排），
                // 而不是含糊说一句"仅影响显示"。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "选项乱序",
                            fontSize = 14.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "打乱同一道题里 A/B/C/D 的排列。答案、错题本、记录都不受影响；" +
                                "但解析正文里若写死了「A 项」这类字母，可能对不上。",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    LiquidSwitch(
                        checked = shuffleOptions,
                        onCheckedChange = {
                            shuffleOptions = it
                            ReviewPrefs.setShuffleOptions(context, it)
                        }
                    )
                }
            }

            // —— ④ 错题本 / 收藏：两个独立入口，跳页查看
            Card(entranceGroup = 3) {
                SectionHeader("查看")
                Spacer(Modifier.height(10.dp))
                // 两颗胶囊**等分整行**（各 weight(1f)），不再按内容宽度挤在左侧 ——
                // 数字不大时右边留一大片空白，与本页其他卡片的按钮排法也不一致。
                // "错题本（N）"这种标签在半行宽度里足够放下，四位数依然绰绰有余。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 走 SecondaryAction（液态玻璃胶囊），与全站次级操作同一观感。
                    SecondaryAction(
                        text = "错题本（${ov.wrongPending}）",
                        enabled = true,
                        onClick = onOpenWrongBook,
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryAction(
                        text = "收藏（${ov.favorites}）",
                        enabled = true,
                        onClick = onOpenFavorites,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "错题本与收藏都能从各自页面开练；一轮练习结束后的总结页会给本次成绩。" +
                        "两个入口都常显，数量为 0 时点进去是空列表，而不是入口消失。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // —— 滚轮弹窗（与一卡通选宿舍电费同一形态：GlassOptionWheelDialog）
        if (showModePicker) {
            GlassOptionWheelDialog(
                options = PracticeModeOption.entries.map { it.label },
                selectedIndex = modeIndex,
                title = "选择练习模式",
                onConfirm = {
                    modeIndex = it
                    showModePicker = false
                },
                onDismiss = { showModePicker = false }
            )
        }

        if (showDurationPicker) {
            // 末档是「自定义…」，选中它再开一个滚轮选分钟数。
            val labels = remember { ExamDurationPresetsMinutes.map(::durationLabel) + CustomDurationEntry }
            GlassOptionWheelDialog(
                options = labels,
                // 当前是自定义值（如 35 分钟）时落在「自定义…」那一档上，而不是随便跳回某个预设 ——
                // 否则用户看到滚轮停在「30 分钟」，会以为自己设的 35 没生效。
                selectedIndex = ExamDurationPresetsMinutes.indexOf(durationMinutes)
                    .takeIf { it >= 0 }
                    ?: ExamDurationPresetsMinutes.size,
                title = "考试时长",
                onConfirm = { index ->
                    showDurationPicker = false
                    val preset = ExamDurationPresetsMinutes.getOrNull(index)
                    if (preset != null) {
                        durationMinutes = preset
                    } else {
                        showCustomDurationPicker = true
                    }
                },
                onDismiss = { showDurationPicker = false }
            )
        }

        if (showCustomDurationPicker) {
            val labels = remember { CustomDurationMinutes.map(::durationLabel) }
            GlassOptionWheelDialog(
                options = labels,
                selectedIndex = nearestCustomDurationIndex(durationMinutes),
                title = "自定义时长",
                onConfirm = { index ->
                    durationMinutes = CustomDurationMinutes[index.coerceIn(0, CustomDurationMinutes.lastIndex)]
                    showCustomDurationPicker = false
                },
                onDismiss = { showCustomDurationPicker = false }
            )
        }
    }
}

/** 默认考试时长（分钟）。30 分钟是校内小测最常见的长度。 */
private const val DefaultExamMinutes = 30

/** 时长滚轮里代表"我要自己填"的那一行。 */
private const val CustomDurationEntry = "自定义…"

/**
 * 把已有分钟数映射到 [CustomDurationMinutes] 里最近的一格。
 *
 * 不直接 `indexOf`：用户可能先选了预设 45，再进来改自定义 —— 45 在列表里（步进 5），
 * 但万一以后步进变了、或档位加了非 5 倍数的值，`indexOf` 返回 -1 会让滚轮跳回 5 分钟。
 */
private fun nearestCustomDurationIndex(minutes: Int): Int {
    var best = 0
    CustomDurationMinutes.forEachIndexed { index, value ->
        if (kotlin.math.abs(value - minutes) < kotlin.math.abs(CustomDurationMinutes[best] - minutes)) {
            best = index
        }
    }
    return best
}

/**
 * 表单式的一行选择字段：左边标签、右边当前值 + 右箭头，整行可点。
 *
 * 不用 Material 的 `DropdownMenu` / `ExposedDropdownMenuBox`：那两个是实心深色面板，
 * 在一屏液态玻璃里非常跳；点开后的选择器统一走 [GlassOptionWheelDialog]。
 */
@Composable
private fun PickerField(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 章节行：垂直列表里的一行，**多选**（勾选框而不是单选圆点）。
 *
 * 勾选框是自绘的（`Box` + 圆角 + 描边）而不是用 `Checkbox` 或 `☑` 字符：
 *  - Material 的 `Checkbox` 是实心深色控件，在一屏玻璃里很跳；
 *  - `☑` / `✓` 这类字符依赖字体覆盖，缺字时会在真机上显示成方框。
 */
@Composable
private fun ChapterRow(
    label: String,
    count: Int,
    checked: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckMark(checked = checked)
        Text(
            text = label,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        )
        Text(
            text = "$count 题",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 自绘勾选框：选中 = 主色实心 + 白色内块；未选 = 描边空框。 */
@Composable
private fun CheckMark(checked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(shape)
            .background(if (checked) scheme.primary else scheme.surface.copy(alpha = 0.5f))
            .border(1.dp, if (checked) scheme.primary else scheme.outlineVariant, shape),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(scheme.onPrimary)
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface
    )
}
