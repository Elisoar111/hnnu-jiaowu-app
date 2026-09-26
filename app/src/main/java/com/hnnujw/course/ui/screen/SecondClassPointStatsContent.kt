package com.hnnujw.course.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.secondclass.SecondClassPointAnalysis
import com.hnnujw.course.secondclass.SecondClassPointGroup
import com.hnnujw.course.secondclass.SecondClassPointLedger
import com.hnnujw.course.secondclass.SecondClassPointRecord
import com.hnnujw.course.secondclass.SecondClassProfile
import com.hnnujw.course.ui.system.GlassProgressBar
import com.hnnujw.course.ui.system.GlassStatChip
import com.hnnujw.course.ui.system.InsetGroupedSection
import com.hnnujw.course.ui.system.SystemEmptyState
import com.hnnujw.course.ui.system.SystemSegmentedControl
import com.hnnujw.course.ui.theme.SemanticDanger
import com.hnnujw.course.ui.theme.SemanticSuccess
import com.hnnujw.course.ui.theme.SemanticWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「分类与学期统计」的界面层。
 *
 * 目标是一句话：**二课的每一分都能查到出处**。所以这个页面不是"再放一份图表"，
 * 而是把账拆成三层，层层可核对：
 *
 * 1. 顶部对账卡片：总积分 → 两个维度小计 → 明细条数，以及"能不能对上"的结论；
 * 2. 分组小计：每一类的合计、占学校要求的进度、与明细求和是否一致；
 * 3. 逐条记录：名称 / 积分 / 时间 / 分类，点开分组才展开。
 *
 * 数据全部来自宿主注入的 [SecondClassPointLedger]，本文件**不发起任何请求**，
 * 因此可以直接在预览 / 单测里喂数据渲染。所有聚合都走
 * [SecondClassPointAnalysis] 的纯函数，界面不做算术。
 */
@Composable
fun SecondClassPointStatsContent(
    ledger: SecondClassPointLedger,
    profile: SecondClassProfile,
    loading: Boolean,
    error: String,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 分段：0 按分类 / 1 按学期。放页面级而不是每个分组里 —— 切换维度时
    // 展开状态本就该重置，一起管更直观。
    var segment by rememberSaveable { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            loading && ledger.isEmpty -> Box(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "正在读取积分明细…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error.isNotBlank() && ledger.isEmpty -> Box(Modifier.fillMaxWidth()) {
                SystemEmptyState(
                    title = "积分明细加载失败",
                    message = error,
                    action = {
                        androidx.compose.material3.TextButton(onClick = onRefresh) {
                            Text("重试")
                        }
                    },
                )
            }

            ledger.isEmpty -> Box(Modifier.fillMaxWidth()) {
                SystemEmptyState(
                    title = "还没有积分明细",
                    message = "学校还没给你记过账，或者该校没开放明细查询。" +
                        "等参加活动并完成认定后，这里会逐条列出每一分的来源。",
                )
            }

            else -> {
                ReconciliationCard(
                    ledger = ledger,
                    profile = profile,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                if (error.isNotBlank()) {
                    NoticeLine(text = error, tone = StatsNoticeTone.Danger)
                }
                SystemSegmentedControl(
                    options = listOf("按分类", "按学期"),
                    selectedIndex = segment,
                    onSelect = { segment = it },
                    modifier = Modifier.fillMaxWidth(),
                    height = 44.dp,
                )
                Spacer(Modifier.height(12.dp))

                if (segment == 0) {
                    ClassifyBreakdown(
                        groups = SecondClassPointAnalysis.categoryBreakdown(ledger.byClassify),
                        profile = profile,
                    )
                } else {
                    TermBreakdown(
                        groups = SecondClassPointAnalysis.termBreakdown(ledger.byTerm),
                        profile = profile,
                    )
                }
            }
        }
    }
}

// ── 对账卡片 ──────────────────────────────────────────────────────────────

/**
 * 顶部对账卡片：把"总积分是怎么来的"摊开。
 *
 * ⚠️ 必须显示 [SecondClassPointAnalysis.Trust] 的结论，而不是只报数字。
 * 站点对小计和明细的口径并不总是一致（实测学期维度不含某些补录账），
 * 如果界面只把两边数字并排一放，用户会自己发现"加不起来"却不知道谁对 ——
 * 那比不显示更糟。
 */
@Composable
private fun ReconciliationCard(
    ledger: SecondClassPointLedger,
    profile: SecondClassProfile,
    modifier: Modifier = Modifier,
) {
    val assessment = remember(ledger) { SecondClassPointAnalysis.trust(ledger) }
    val sourceCount = remember(ledger) { SecondClassPointAnalysis.distinctSources(ledger) }
    val shape = RoundedCornerShape(24.dp)
    val accent = when (assessment.trust) {
        SecondClassPointAnalysis.Trust.Balanced -> SemanticSuccess
        SecondClassPointAnalysis.Trust.PartialDetail -> SemanticWarning
        SecondClassPointAnalysis.Trust.Mismatch -> SemanticDanger
        SecondClassPointAnalysis.Trust.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(com.hnnujw.course.ui.system.glassSurfaceColor())
            .border(0.5.dp, com.hnnujw.course.ui.system.glassBorderColor(), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "积分可追溯性",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "每一分都有出处",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ⚠️ 三个 chip 必须是**同一单位**才能并排比较。
        //
        // 实测数据：`/student/achievement/detail` 的 `user.score` = 5.16（折算积分，
        // 单位见 scoreUnit）而 `user.hours` = 25.8（学时），明细记录里的 `hours`
        // 也是学时。若把 score 与两个合计并排，用户看到 "5.16 | 25.80 | 25.80"
        // 只会得出"总积分和明细对不上"的错误结论 —— 它们本来就不是一个量纲。
        //
        // 因此这一行统一用**学时**：总学时 vs 分类合计 vs 学期合计，三者可直接
        // 对账。折算积分另起一行单独展示，不参与对账。
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassStatChip(
                value = okFormat(profile.hours),
                label = "总学时",
                modifier = Modifier.weight(1f),
            )
            GlassStatChip(
                value = okFormat(ledger.classifyTotal),
                label = "分类合计",
                modifier = Modifier.weight(1f),
            )
            GlassStatChip(
                value = okFormat(ledger.termTotal),
                label = "学期合计",
                modifier = Modifier.weight(1f),
            )
        }

        // 折算积分：与学时不是一个量纲，单独一行说明来源，绝不混进上面的对账行。
        // 站点没给 score（或为 0）时不显示，避免出现无意义的 "0.00 折算积分"。
        if (profile.score > 0.0) {
            Text(
                text = "折算积分 ${okFormat(profile.score)} ${
                    profile.scoreUnit.ifBlank { "分" }
                }（由学时按学校规则换算，与上方学时不是同一单位）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 结论句：一句话说清"对得上 / 对不上多少"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(7.dp)
                    .background(accent, CircleShape),
            )
            Text(
                text = trustSentence(assessment, ledger),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 18.sp,
            )
        }

        // 缺口提示：只有真对不上时才占位，对得上不废话
        if (assessment.unexplained > SecondClassPointAnalysis.TOLERANCE) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "有 ${okFormat(assessment.unexplained)} 分没有对应明细",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                Text(
                    text = "常见原因是学校补录历史数据、或某一维度的明细未开放。" +
                        "这属于站点数据问题，不是你漏了什么。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
            }
        }

        Text(
            text = "共 ${ledger.recordCount} 条记账记录 · ${sourceCount} 个来源活动 / 项目",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
    }
}

/** 把可信度翻译成一句人话。文案要与实际判据严格对应，不要写"大概率"这种模糊表述。 */
private fun trustSentence(
    assessment: SecondClassPointAnalysis.Assessment,
    ledger: SecondClassPointLedger,
): String = when (assessment.trust) {
    SecondClassPointAnalysis.Trust.Balanced ->
        "按分类与按学期两个口径的合计一致，且每一笔都已列出 —— 账目完全对得上。"

    SecondClassPointAnalysis.Trust.PartialDetail ->
        "两个口径的合计一致，但部分分组的小计与已列出的明细有差额，说明有记录没有下发。"

    SecondClassPointAnalysis.Trust.Mismatch ->
        "两个口径的合计相差 ${okFormat(kotlin.math.abs(assessment.dimensionGap))}，" +
            "说明有一边的明细不全。下方两个维度都按站点原样展示，不做任何补算。"

    SecondClassPointAnalysis.Trust.Unknown ->
        "暂时拿不到可核对的明细（两个维度都为空），无法判断账目是否一致。"
}

// ── 按分类 ────────────────────────────────────────────────────────────────

@Composable
private fun ClassifyBreakdown(groups: List<SecondClassPointGroup>, profile: SecondClassProfile) {
    if (groups.isEmpty()) {
        InsetGroupedSection {
            StatsPlaceholder("该校没有按分类下发明细，或你还没有任何一个分类的记账。")
        }
        return
    }
    InsetGroupedSection(
        header = "各分类明细",
        footer = "小计取自站点；「明细求和」是按下方逐条记录加出来的，两者不一致时会标出。",
    ) {
        groups.forEachIndexed { index, group ->
            PointGroupBlock(
                group = group,
                unit = profile.hourUnit,
                required = group.required,
                showDivider = index != groups.lastIndex,
            )
        }
    }
}

// ── 按学期 ────────────────────────────────────────────────────────────────

@Composable
private fun TermBreakdown(groups: List<SecondClassPointGroup>, profile: SecondClassProfile) {
    if (groups.isEmpty()) {
        InsetGroupedSection {
            StatsPlaceholder("该校没有按学期下发明细。可以切到「按分类」看分类维度。")
        }
        return
    }
    InsetGroupedSection(
        header = "各学期明细",
        footer = "最近学期在最上面；学期内每一分都逐条列出，并标注所属分类。",
    ) {
        groups.forEachIndexed { index, group ->
            PointGroupBlock(
                group = group,
                // 学期维度自带单位，优先用它（不同学期可能换算口径不同）。
                // ⚠️ 站点该字段是**数字枚举码**（实测恒为 "3"），不是文案 ——
                // 直接用会渲染成 "25.8 3"。displayUnit 负责过滤成真实单位。
                unit = SecondClassPointAnalysis.displayUnit(group.unit, profile.hourUnit),
                // 标题补上"第几学期"：站点 termName 只给学年，同一学年两个学期
                // 会显示成两行一模一样的标题，用户分不清。
                title = SecondClassPointAnalysis.termDisplayName(group),
                required = 0.0,
                showDivider = index != groups.lastIndex,
            )
        }
    }
}

// ── 分组块（两个维度共用）─────────────────────────────────────────────────

/**
 * 一个分组：标题行 + 展开/收起 + 明细列表。
 *
 * 默认**收起**：一屏铺开所有记录会把页面变成流水账，先看小计、需要时再展开，
 * 才符合"先看账、再查账"的顺序。
 */
@Composable
private fun PointGroupBlock(
    group: SecondClassPointGroup,
    unit: String,
    required: Double,
    showDivider: Boolean,
    /**
     * 展示用标题。缺省用 [SecondClassPointGroup.name]；
     * 学期维度会传补全后的"学年 + 第几学期"（站点只给学年）。
     */
    title: String = group.name,
) {
    var expanded by rememberSaveable(group.name, group.termNumber) { mutableStateOf(false) }
    val records = remember(group) {
        SecondClassPointAnalysis.recordsNewestFirst(
            SecondClassPointAnalysis.withGroupCategory(group, group.records)
        )
    }
    val progress = if (required > 0.0) (group.total / required).toFloat().coerceIn(0f, 1f) else null

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = records.isNotEmpty()) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title.ifBlank { "未命名分组" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!group.reconciled) {
                        // 小计与明细对不上：在这一行就标出来，不必点开才知道
                        Text(
                            text = "对不上",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = SemanticWarning,
                        )
                    }
                }
                Text(
                    text = buildString {
                        append("${records.size} 条记录")
                        if (!group.reconciled) {
                            append(" · 明细求和 ${okFormat(group.detailSum)}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = okFormat(group.total) + unit.let { if (it.isBlank()) "" else " $it" },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        progress == null -> MaterialTheme.colorScheme.onSurface
                        progress >= 1f -> SemanticSuccess
                        else -> SemanticDanger
                    },
                )
                if (required > 0.0) {
                    Text(
                        text = "要求 ${okFormat(required)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (records.isNotEmpty()) {
                // 直角「‹」旋转成展开指示，避免再引入一个图标依赖
                Text(
                    text = "‹",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) -90f else 90f),
                )
            }
        }

        progress?.let {
            GlassProgressBar(
                progress = it,
                tint = if (it >= 1f) SemanticSuccess else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
        }

        if (expanded && records.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            ) {
                records.forEachIndexed { index, record ->
                    PointRecordRow(
                        record = record,
                        unit = unit,
                        // 分组标题就是分类名时不再重复显示分类（说了两遍没信息量）。
                        // 学期维度下分组标题是学期名，分类必须显示出来。
                        showCategory = record.classifyName.isNotBlank() &&
                            record.classifyName != group.name,
                        showDivider = index != records.lastIndex,
                    )
                }
            }
        }
    }
    if (showDivider) HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/** 一条记账记录：来源名称 + 分类 + 时间，右侧是这一笔的积分。 */
@Composable
private fun PointRecordRow(
    record: SecondClassPointRecord,
    unit: String,
    showCategory: Boolean,
    showDivider: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = record.name.ifBlank { "未命名来源" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    if (showCategory && record.classifyName.isNotBlank()) add(record.classifyName)
                    formatRecordTime(record.time)?.let { add(it) }
                    // ⚠️ 刻意**不显示** sourceType。
                    //
                    // 站点没有下发类型文案，也没有权威取值表，所以只能渲染成裸码
                    // （实测大量记录的 sourceType 是 3，界面上就是一行行"来源 3"）。
                    // 这个数字对用户没有任何信息量，只会把有用信息（分类 / 时间）挤掉，
                    // 所以干脆不显示 —— 与其显示一个用户读不懂的编码，不如不显示。
                    // amount 与 hours 不同才有信息量（它是折算前的原始值）
                    if (record.amount > 0.0 && kotlin.math.abs(record.amount - record.hours) > 0.005) {
                        add("原始 ${okFormat(record.amount)}")
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = okFormat(record.hours) + unit.let { if (it.isBlank()) "" else " $it" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
        }
        if (showDivider) HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )
    }
}

// ── 小零件 ────────────────────────────────────────────────────────────────

private enum class StatsNoticeTone { Danger, Warning }

@Composable
private fun NoticeLine(text: String, tone: StatsNoticeTone) {
    val accent = when (tone) {
        StatsNoticeTone.Danger -> SemanticDanger
        StatsNoticeTone.Warning -> SemanticWarning
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(0.5.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatsPlaceholder(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
    )
}

/** 记账时间 → "2026-03-14"。时间未知（站点没给）时返回 null，界面不显示这一项。 */
private fun formatRecordTime(millis: Long): String? {
    if (millis <= 0L) return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(millis))
    }.getOrNull()
}

/** 与成绩单页同一套数字口径：去掉无意义的小数尾巴。 */
private fun okFormat(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val rounded = String.format(Locale.ROOT, "%.2f", value)
    return if (rounded.endsWith(".00")) rounded.dropLast(3) else rounded.trimEnd('0').trimEnd('.')
}
