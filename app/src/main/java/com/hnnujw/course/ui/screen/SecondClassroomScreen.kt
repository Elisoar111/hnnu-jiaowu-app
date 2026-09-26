package com.hnnujw.course.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.secondclass.SecondClassExtraScore
import com.hnnujw.course.secondclass.SecondClassModule
import com.hnnujw.course.secondclass.SecondClassPointLedger
import com.hnnujw.course.secondclass.SecondClassProfile
import com.hnnujw.course.secondclass.SecondClassRankEntry
import com.hnnujw.course.secondclass.SecondClassRankLevel
import com.hnnujw.course.secondclass.SecondClassSnapshot
import com.hnnujw.course.ui.system.GlassProgressBar
import com.hnnujw.course.ui.system.GlassStatChip
import com.hnnujw.course.ui.system.InsetGroupedRow
import com.hnnujw.course.ui.system.InsetGroupedSection
import com.hnnujw.course.ui.system.LiquidSwitch
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SectionSpacing
import com.hnnujw.course.ui.system.SystemEmptyState
import com.hnnujw.course.ui.system.SystemIconButton
import com.hnnujw.course.ui.system.SystemLoadingState
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSegmentedControl
import com.hnnujw.course.ui.system.SystemTopBar
import com.hnnujw.course.ui.theme.SemanticDanger
import com.hnnujw.course.ui.theme.SemanticSuccess
import com.hnnujw.course.ui.theme.SemanticWarning
import com.hnnujw.course.ui.theme.moduleEntrance
import java.util.Locale

/**
 * 第二课堂页面的全部可渲染状态。由 `SecondClassroomRoute` 组装。
 */
data class SecondClassroomUi(
    /** 当前学校是否接入第二课堂；false 时整页显示"未接入"。 */
    val available: Boolean = true,
    /** 是否已经拿到 token（或至少存了密码）。 */
    val bound: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String = "",
    val snapshot: SecondClassSnapshot = SecondClassSnapshot(),
    val level: SecondClassRankLevel = SecondClassRankLevel.Classmates,
    val boardLoading: Boolean = false,
    val boardError: String = "",
    /** 设置页的「显示班级同学排名」开关。关掉后班级榜单只留"我的排名"。 */
    val showClassRank: Boolean = true,
    /**
     * 成绩单内部的二级分段：0 = 总览（表头 + 模块 + 排行榜）/ 1 = 分类与学期统计。
     *
     * 为什么要分段而不是并成一个长页：「总览」是"我现在多少分"，「统计」是
     * "这些分从哪来"。两者信息密度都高，一路往下滚会让"我的排名"被埋在明细后面。
     *
     * ⚠️ 这个下标由 Route 持有（[SecondClassroomRoute] 的 `transcriptSegment`），
     * 因为"统计"这一段要按需联网拉明细 —— 状态在谁手上，加载 effect 就该在谁那儿。
     */
    val transcriptSegment: Int = 0,
    /** 积分明细（分类 + 学期）。0 条 = 还没拉或该校没数据。 */
    val pointLedger: SecondClassPointLedger = SecondClassPointLedger(),
    val pointLoading: Boolean = false,
    val pointError: String = "",
)

/** 成绩单内部的分段标签。下标有语义，见 [SecondClassroomUi.transcriptSegment]。 */
val TRANSCRIPT_SEGMENTS: List<String> = listOf("总览", "分类与学期统计")

@Composable
fun SecondClassTranscriptScreen(
    ui: SecondClassroomUi,
    onBind: () -> Unit,
    onRefresh: () -> Unit,
    onLevelSelect: (SecondClassRankLevel) -> Unit,
    onShowClassRankChange: (Boolean) -> Unit,
    onClose: (() -> Unit)? = null,
    /**
     * 切换到成绩单内部的某个分段（0 总览 / 1 分类与学期统计）。
     * 切到 1 时 Route 会去拉积分明细。
     */
    onSegmentSelect: (Int) -> Unit = {},
    /** 「分类与学期统计」的重试 / 刷新。 */
    onPointsRefresh: () -> Unit = {},
    /** 「分类与学期统计」里凭据失效时的重新绑定。 */
    onPointsBind: () -> Unit = onBind,
    /**
     * true = 作为别页的 Tab 内容嵌入（如「二课 → 成绩单」）：
     * 不画自己的顶栏、不吃窗口内边距 —— 否则会出现两条标题、两段顶部留白。
     */
    embedded: Boolean = false,
) {
    val scrollState = rememberScrollState()
    val headerCollapse by remember {
        derivedStateOf { (scrollState.value / 96f).coerceIn(0f, 1f) }
    }
    // 切分段时把滚动位置归零：从统计的中间位置切回总览会停在半空，很怪。
    LaunchedEffect(ui.transcriptSegment) { scrollState.scrollTo(0) }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!embedded) Box(Modifier.moduleEntrance(0)) {
                SystemTopBar(
                    title = "第二课堂成绩单",
                    collapseFraction = headerCollapse,
                    navigationIcon = if (onClose != null) {
                        {
                            SystemIconButton(
                                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "返回",
                                onClick = onClose
                            )
                        }
                    } else {
                        null
                    },
                    actions = {
                        if (ui.available && ui.bound) {
                            SystemIconButton(
                                icon = Icons.Outlined.Refresh,
                                contentDescription = "刷新第二课堂数据",
                                onClick = onRefresh,
                                enabled = !ui.loading && !ui.refreshing
                            )
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    start = PagePadding,
                    end = PagePadding,
                    // 嵌入模式外层已经给过顶栏高度，这里只留一点呼吸位
                    top = if (embedded) 8.dp else padding.calculateTopPadding() + 8.dp,
                    bottom = com.hnnujw.course.ui.system.LocalAppOverlayBottomInset.current + 24.dp
                ),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing)
        ) {
            when {
                !ui.available -> SystemEmptyState(
                    title = "该校暂未接入第二课堂",
                    message = "当前学校的第二课堂成绩单系统还没有在应用里配置。",
                    icon = Icons.Outlined.School
                )

                !ui.bound -> BindPrompt(ui = ui, onBind = onBind)

                ui.loading && !ui.snapshot.profile.hasIdentity -> SystemLoadingState(text = "正在读取第二课堂成绩单…")

                else -> {
                    if (ui.error.isNotBlank()) {
                        NoticeCard(text = ui.error, tone = NoticeTone.Danger, modifier = Modifier.moduleEntrance(1))
                    }
                    // 二级分段：总览 / 分类与学期统计。
                    // 放在表头**下面**而不是顶栏里 —— 底面是"二课 → 成绩单"，
                    // 顶上已经有一层 Tab（活动/已报/申报/消息/成绩单），再往顶栏塞
                    // 会变成三层嵌套，没人看得懂自己在第几层。
                    SystemSegmentedControl(
                        options = TRANSCRIPT_SEGMENTS,
                        selectedIndex = ui.transcriptSegment.coerceIn(0, TRANSCRIPT_SEGMENTS.lastIndex),
                        onSelect = onSegmentSelect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .moduleEntrance(1),
                        height = 44.dp,
                    )
                    if (ui.transcriptSegment == 1) {
                        SecondClassPointStatsContent(
                            ledger = ui.pointLedger,
                            profile = ui.snapshot.profile,
                            loading = ui.pointLoading,
                            error = ui.pointError,
                            onRefresh = onPointsRefresh,
                            modifier = Modifier.moduleEntrance(2),
                        )
                    } else {
                        ProfileHeader(
                            profile = ui.snapshot.profile,
                            modules = ui.snapshot.modules,
                            modifier = Modifier.moduleEntrance(2)
                        )
                        ModulesSection(
                            ui.snapshot.modules,
                            ui.snapshot.profile,
                            modifier = Modifier.moduleEntrance(3)
                        )
                        RankSection(
                            ui = ui,
                            modifier = Modifier.moduleEntrance(4),
                            onLevelSelect = onLevelSelect,
                            onShowClassRankChange = onShowClassRankChange
                        )
                    }
                    if (ui.refreshing) {
                        Text(
                            text = "正在刷新…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

// ── 未绑定 ────────────────────────────────────────────────────────────────

@Composable
private fun BindPrompt(ui: SecondClassroomUi, onBind: () -> Unit) {
    SystemEmptyState(
        title = "还没绑定第二课堂",
        message = "用教务系统的学号登录一次第二课堂成绩单系统，就能在这里看到各模块积分、总学分和排行榜。",
        icon = Icons.Outlined.EmojiEvents,
        action = {
            SystemPrimaryButton(
                text = "登录第二课堂",
                onClick = onBind,
                modifier = Modifier.width(200.dp)
            )
        }
    )
    if (ui.error.isNotBlank()) {
        NoticeCard(text = ui.error, tone = NoticeTone.Danger)
    }
}

// ── 成绩单表头 ────────────────────────────────────────────────────────────

@Composable
private fun ProfileHeader(
    profile: SecondClassProfile,
    modules: List<SecondClassModule>,
    modifier: Modifier = Modifier,
) {
    // 综测附加分＝各模块积分加权求和（口径见 SecondClassExtraScore）。
    // 一个模块都认不出来时是 null —— 宁可不显示，也不要给用户一个假的 0 分。
    val extraScore = remember(modules) { SecondClassExtraScore.compute(modules) }
    val heroShape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(heroShape)
            .background(com.hnnujw.course.ui.system.glassSurfaceColor())
            .border(0.5.dp, com.hnnujw.course.ui.system.glassBorderColor(), heroShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = profile.name.take(1).ifBlank { "二" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = profile.name.ifBlank { "同学" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = listOf(profile.code, profile.grade.takeIf { it.isNotBlank() }?.let { "$it 级" })
                        .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (profile.collegeName.isNotBlank() || profile.majorName.isNotBlank()) {
            Text(
                text = listOf(profile.collegeName, profile.majorName)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassStatChip(
                    value = formatNumber(profile.score),
                    label = profile.scoreUnit.ifBlank { "学分" },
                    modifier = Modifier.weight(1f)
                )
                GlassStatChip(
                    value = formatNumber(profile.hours),
                    label = if (profile.hourUnit.isBlank()) "总积分" else "总${profile.hourUnit}",
                    modifier = Modifier.weight(1f)
                )
                // 总积分右边的第三个框：综测附加分（由下方各模块积分算出，站点不直接给）
                if (extraScore != null) {
                    GlassStatChip(
                        value = formatNumber(extraScore),
                        label = "综测附加分",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (extraScore != null) {
                Text(
                    text = "综测附加分＝各模块积分加权求和，单项封顶 ${formatNumber(SecondClassExtraScore.CAP)} 分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

// ── 活动中心入口 ──────────────────────────────────────────────────────────

@Composable
private fun ModulesSection(
    modules: List<SecondClassModule>,
    profile: SecondClassProfile,
    modifier: Modifier = Modifier,
) {
    InsetGroupedSection(
        modifier = modifier,
        header = "各模块积分状况",
        footer = if (modules.isEmpty()) null else "进度按学校要求的最低值计算；未设下限的模块只显示积分。"
    ) {
        if (modules.isEmpty()) {
            SectionPlaceholder("学校还没有为你配置分类模块，或本学期的活动尚未开始记录。")
            return@InsetGroupedSection
        }
        modules.forEachIndexed { index, module ->
            ModuleRow(module, profile, showDivider = index != modules.lastIndex)
        }
    }
}

@Composable
private fun ModuleRow(module: SecondClassModule, profile: SecondClassProfile, showDivider: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = module.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val progress = module.progress
            Text(
                text = if (module.required > 0.0) {
                    "${formatNumber(module.mine)} / ${formatNumber(module.required)}"
                } else {
                    formatNumber(module.mine)
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    progress == null -> MaterialTheme.colorScheme.onSurface
                    progress >= 1f -> SemanticSuccess
                    // 未达学校要求：与达标绿对称，用红色提示还差多少
                    else -> SemanticDanger
                }
            )
            if (profile.hourUnit.isNotBlank()) {
                Text(
                    text = profile.hourUnit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        module.progress?.let { GlassProgressBar(progress = it, tint = progressTint(it)) }
        if (module.average > 0.0) {
            Text(
                text = "全校参考 ${formatNumber(module.average)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
    if (showDivider) SectionDivider()
}

// ── 排行榜 ────────────────────────────────────────────────────────────────

@Composable
private fun RankSection(
    ui: SecondClassroomUi,
    modifier: Modifier = Modifier,
    onLevelSelect: (SecondClassRankLevel) -> Unit,
    onShowClassRankChange: ((Boolean) -> Unit)? = null,
) {
    val levels = SecondClassRankLevel.all
    val board = ui.snapshot.boards[ui.level]

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "排行榜",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.padding(start = 18.dp)
        )
        SystemSegmentedControl(
            options = levels.map { it.label },
            selectedIndex = levels.indexOf(ui.level).coerceAtLeast(0),
            onSelect = { onLevelSelect(levels[it]) },
            modifier = Modifier.fillMaxWidth()
        )

        // 「显示班级同学排名」开关就放在榜单旁边（从「我的」页设置区迁来）：
        // 用户看到名单/隐私的当下就能调整，不必再跳去设置里找。
        if (onShowClassRankChange != null) {
            InsetGroupedRow(
                title = "显示班级同学排名",
                subtitle = if (ui.showClassRank) {
                    "「班级」榜单会列出全部同学"
                } else {
                    "已隐藏同学名单，只保留我的排名"
                },
                trailing = {
                    LiquidSwitch(
                        checked = ui.showClassRank,
                        onCheckedChange = onShowClassRankChange
                    )
                }
            )
        }

        board?.myRank?.let { MyRankCard(it, ui.level, ui.snapshot.profile) }

        // 班级层级展示**完整榜单**（数据侧已翻页取全，不再截断在 20 条）。
        // 专业/院系/全校的人数太多，铺出来没有意义，只看自己的名次
        // （数据侧也只请求 self/rank，不拉整份名单）。
        if (ui.level == SecondClassRankLevel.Classmates) {
            if (!ui.showClassRank) {
                // 用户关掉了同学名单，只剩"我的排名"
                InsetGroupedSection(footer = "可用上方「显示班级同学排名」开关重新打开") {
                    SectionPlaceholder("班级同学排名已隐藏。")
                }
            } else {
                InsetGroupedSection(footer = board?.let { "共 ${it.total} 人参与排名" }) {
                    when {
                        ui.boardError.isNotBlank() -> SectionPlaceholder(ui.boardError)
                        ui.boardLoading && board == null -> SectionPlaceholder("正在读取榜单…")
                        board == null || board.entries.isEmpty() -> SectionPlaceholder(
                            "该层级暂时没有可显示的排名数据。"
                        )
                        else -> board.entries.forEachIndexed { index, entry ->
                            RankRow(entry, ui.snapshot.profile, showDivider = index != board.entries.lastIndex)
                        }
                    }
                }
            }
        } else if (board?.myRank == null) {
            InsetGroupedSection {
                SectionPlaceholder(
                    when {
                        ui.boardError.isNotBlank() -> ui.boardError
                        ui.boardLoading && board == null -> "正在读取排名…"
                        else -> "该层级暂时没有你的排名数据。"
                    }
                )
            }
        }
    }
}

@Composable
private fun MyRankCard(entry: SecondClassRankEntry, level: SecondClassRankLevel, profile: SecondClassProfile) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "我的排名 · ${level.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = entry.name.ifBlank { "我" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (entry.rank > 0) "第 ${entry.rank} 名" else "未入榜",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${formatNumber(entry.score)} ${profile.scoreUnit}".trim(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RankRow(entry: SecondClassRankEntry, profile: SecondClassProfile, showDivider: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(rankBadgeColor(entry.rank).copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (entry.rank > 0) entry.rank.toString() else "–",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = rankBadgeColor(entry.rank)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.name.ifBlank { "同学" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.majorName.isNotBlank()) {
                Text(
                    text = entry.majorName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = formatNumber(entry.score),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    if (showDivider) SectionDivider()
}

// ── 小零件 ────────────────────────────────────────────────────────────────

private enum class NoticeTone { Danger, Warning, Info }

@Composable
private fun NoticeCard(text: String, tone: NoticeTone, modifier: Modifier = Modifier) {
    val accent = when (tone) {
        NoticeTone.Danger -> SemanticDanger
        NoticeTone.Warning -> SemanticWarning
        NoticeTone.Info -> MaterialTheme.colorScheme.primary
    }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.10f))
            .border(0.5.dp, accent.copy(alpha = 0.28f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun SectionPlaceholder(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp)
    )
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    )
}

@Composable
private fun progressTint(progress: Float): Color = when {
    progress >= 1f -> SemanticSuccess
    progress >= 0.6f -> MaterialTheme.colorScheme.primary
    else -> SemanticWarning
}

private fun rankBadgeColor(rank: Int): Color = when (rank) {
    1 -> Color(0xFFD4A017)
    2 -> Color(0xFF8E9BA8)
    3 -> Color(0xFFB07B4F)
    else -> Color(0xFF5E5CE6)
}

/** 去掉无意义的小数尾巴：12.0 → "12"，12.5 → "12.5"，12.345 → "12.35"。 */
private fun formatNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val rounded = String.format(Locale.ROOT, "%.2f", value)
    return if (rounded.endsWith(".00")) rounded.dropLast(3) else rounded.trimEnd('0').trimEnd('.')
}
