package com.hnnujw.course.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.academic.AcademicGatewayFactory
import com.hnnujw.course.emptyroom.ALL_OPTION
import com.hnnujw.course.emptyroom.EmptyRoom
import com.hnnujw.course.emptyroom.EmptyRoomCampusOptions
import com.hnnujw.course.emptyroom.EmptyRoomClient
import com.hnnujw.course.emptyroom.EmptyRoomFilters
import com.hnnujw.course.emptyroom.EmptyRoomOption
import com.hnnujw.course.emptyroom.EmptyRoomQuery
import com.hnnujw.course.emptyroom.buildingOptionsFor
import com.hnnujw.course.emptyroom.periodOptionsFor
import com.hnnujw.course.emptyroom.validateQuery
import com.hnnujw.course.manager.ScheduleSettingsManager
import com.hnnujw.course.model.SchoolConfig
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.LocalWallpaperAppearanceColors
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SystemCard
import com.hnnujw.course.ui.system.SystemDivider
import com.hnnujw.course.ui.system.SystemEmptyState
import com.hnnujw.course.ui.system.SystemLoadingState
import com.hnnujw.course.ui.system.SystemPicker
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import kotlinx.coroutines.launch

/**
 * 教务「空闲教室查询」（正方 `cdjy/cdjy_cxKxcdlb.html`，gnmkdm=N2155）。
 *
 * ## 为什么是"周次 + 星期 + 节次"三选
 *
 * 这是正方空闲场地查询的主口径（`jyfs=0`）：问"第 3 周周一 1-2 节，哪里还空着"。
 * 另两种口径（按日期时段 / 连续-间断借用）服务于**场地借用申请**，本应用不做
 * 借用申请，所以不提供 —— 少一个入口，就少一次"以为能在这里预约"的误解。
 *
 * ## 只读边界
 *
 * 全程只发 GET 查询页与 `doType=query` 的查询 POST，与网页上点「查询」等价。
 * 网页上的「场地借用」「提交申请」一个都没有实现。
 *
 * ## 分页
 *
 * 一次查一页（[EmptyRoomClient.PAGE_SIZE] 条），结果尾部给「加载更多」。
 * 不自动翻到底：空教室查询的命中数可能上千，自动翻页会在用户看到第一屏之前
 * 连发十几个请求，对学校服务器不礼貌，也拖慢首屏。
 */
@Composable
fun EmptyRoomScreen(
    school: SchoolConfig?,
    accountKey: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var loadingPage by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf("") }
    var needLogin by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(EmptyRoomFilters.EMPTY) }
    var hidden by remember { mutableStateOf(emptyMap<String, String>()) }

    var condition by remember { mutableStateOf(EmptyRoomQuery()) }
    var rooms by remember { mutableStateOf<List<EmptyRoom>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var pageIndex by remember { mutableStateOf(1) }
    var querying by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var queryError by remember { mutableStateOf("") }
    var hasQueried by remember { mutableStateOf(false) }

    // 校区私有的楼号/节次（`cdjy/cdjy_cxXqjc.html`）。
    // `campusOptionsFor` 是**这份数据属于哪个校区**——没有它就没法判断手上这份能不能用：
    // 切到 B 校区但 B 的请求失败时，A 的楼号绝不能留在界面上（拿 A 的楼号查 B 不会报错，
    // 只会返回空集，用户会以为"这个校区没有空教室"）。
    var campusOptions by remember { mutableStateOf(EmptyRoomCampusOptions.EMPTY) }
    var campusOptionsFor by remember { mutableStateOf("") }
    var campusOptionsLoading by remember { mutableStateOf(false) }
    var campusOptionsError by remember { mutableStateOf("") }

    // 节次候选：校区给不出时的兜底。复用课表设置里的节次数（默认 12，用户可改到 16）。
    val periodCount = remember { ScheduleSettingsManager.getInstance().periodCount.coerceIn(1, 20) }

    val scrollState = rememberScrollState()
    val headerCollapse by remember {
        derivedStateOf { (scrollState.value / 96f).coerceIn(0f, 1f) }
    }

    /**
     * 拉取某校区的楼号与节次。
     *
     * 失败时**只**留下"全部"，并把原因显示出来 —— 继续显示上一个校区的楼号是
     * 比"没有楼号可选"严重得多的错误（前者会静默查错范围）。
     */
    suspend fun loadCampusOptions(campusId: String) {
        val target = school
        if (target == null || campusId.isBlank()) return
        campusOptionsLoading = true
        campusOptionsError = ""
        when (val result = EmptyRoomClient.campusOptions(
            AcademicGatewayFactory.transportFor(target, accountKey), target, campusId, condition.term,
        )) {
            is EmptyRoomClient.CampusResult.Success -> {
                campusOptions = result.options
                campusOptionsFor = campusId
            }
            is EmptyRoomClient.CampusResult.NeedLogin -> {
                needLogin = true
                campusOptions = EmptyRoomCampusOptions.EMPTY
                campusOptionsFor = ""
            }
            is EmptyRoomClient.CampusResult.Failure -> {
                campusOptions = EmptyRoomCampusOptions.EMPTY
                campusOptionsFor = ""
                campusOptionsError = result.message
            }
        }
        campusOptionsLoading = false
    }

    suspend fun loadPage() {
        val target = school
        if (target == null) {
            loadingPage = false
            pageError = "未选择学校，无法查询空闲教室"
            return
        }
        loadingPage = true
        pageError = ""
        needLogin = false
        when (val result = EmptyRoomClient.loadPage(AcademicGatewayFactory.transportFor(target, accountKey), target)) {
            is EmptyRoomClient.PageResult.Success -> {
                filters = result.filters
                hidden = result.hidden
                // 默认选中服务端标了 selected 的学期与第一个校区：
                // 直接进页面就能查，不必先做两次"其实只有一个选项"的选择。
                condition = condition.copy(
                    term = condition.term.ifBlank { result.filters.defaultTerm },
                    campusId = condition.campusId.ifBlank { result.filters.campuses.firstOrNull()?.value.orEmpty() },
                )
                // 和网页一样在初始化时取一次（`kxcdlb.js` 的 `hqjcList()`）：
                // 节次集合与名称是按校区下发的，页面直出的那份只对默认校区有效。
                loadCampusOptions(condition.campusId)
            }
            is EmptyRoomClient.PageResult.NeedLogin -> needLogin = true
            is EmptyRoomClient.PageResult.Failure -> pageError = result.message
        }
        loadingPage = false
    }

    LaunchedEffect(school?.id, accountKey) { loadPage() }

    suspend fun runQuery() {
        val target = school ?: return
        val invalid = validateQuery(condition)
        if (invalid != null) {
            GlassToaster.show(invalid)
            return
        }
        querying = true
        queryError = ""
        when (val result = EmptyRoomClient.query(
            AcademicGatewayFactory.transportFor(target, accountKey), target, hidden, condition, pageIndex = 1,
        )) {
            is EmptyRoomClient.QueryResult.Success -> {
                rooms = result.page.rooms
                total = result.page.total
                pageIndex = 1
                hasQueried = true
            }
            is EmptyRoomClient.QueryResult.NeedLogin -> {
                needLogin = true
                GlassToaster.show(result.message)
            }
            is EmptyRoomClient.QueryResult.Failure -> {
                queryError = result.message
                hasQueried = true
            }
        }
        querying = false
    }

    suspend fun loadMore() {
        val target = school ?: return
        if (pageIndex >= EmptyRoomClient.MAX_PAGES) {
            GlassToaster.show("已加载 ${EmptyRoomClient.MAX_PAGES} 页，请缩小范围后再查")
            return
        }
        loadingMore = true
        val next = pageIndex + 1
        when (val result = EmptyRoomClient.query(
            AcademicGatewayFactory.transportFor(target, accountKey), target, hidden, condition, pageIndex = next,
        )) {
            is EmptyRoomClient.QueryResult.Success -> {
                val known = rooms.mapTo(mutableSetOf()) { it.id }
                val fresh = result.page.rooms.filter { it.id.isNotBlank() && known.add(it.id) }
                if (fresh.isEmpty() && result.page.rooms.isNotEmpty()) {
                    // 服务端把第 2 页又发成了第 1 页（分页参数没生效）。这里**不能**
                    // 静默当成功：界面会停在"共 N 间"却只列出一页，用户以为数据就这么多。
                    GlassToaster.show("教务系统没有返回更多结果")
                } else {
                    rooms = rooms + fresh
                }
                pageIndex = next
                total = maxOf(total, result.page.total)
            }
            is EmptyRoomClient.QueryResult.NeedLogin -> {
                needLogin = true
                GlassToaster.show(result.message)
            }
            is EmptyRoomClient.QueryResult.Failure -> GlassToaster.show(result.message)
        }
        loadingMore = false
    }

    GlassPageScaffold(
        title = "空闲教室",
        subtitle = "按周次 / 星期 / 节次查询",
        onBack = onBack,
        collapseFraction = headerCollapse,
    ) { padding ->
        when {
            loadingPage -> SystemLoadingState(text = "正在读取查询条件…", modifier = Modifier.fillMaxSize().padding(padding))

            needLogin -> SystemEmptyState(
                title = "需要登录教务",
                message = "空闲教室查询使用教务系统的登录状态，请先在「我的」里登录教务账号后重试。",
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            pageError.isNotBlank() -> SystemEmptyState(
                title = "无法查询",
                message = pageError,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding(),
                    )
                    .padding(horizontal = PagePadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FilterCard(
                    filters = filters,
                    condition = condition,
                    campusOptions = campusOptions,
                    campusOptionsFor = campusOptionsFor,
                    campusOptionsLoading = campusOptionsLoading,
                    campusOptionsError = campusOptionsError,
                    periodCount = periodCount,
                    querying = querying,
                    onCampusChange = { campusId ->
                        // 网页在 `#xqh_id` 的 change 里把 `#lh` 与 `#selectTR_JC` 整个
                        // 清空重建（`kxcdlb.js` 的 `hqjcList()`）⇒ 楼号与节次的选择都要清掉。
                        // 留着就会提交一个属于**别的校区**的值：服务端不报错，只会返回空集。
                        condition = condition.copy(
                            campusId = campusId,
                            buildingId = "",
                            periods = emptySet(),
                        )
                        scope.launch { loadCampusOptions(campusId) }
                    },
                    onChange = { condition = it },
                    onQuery = { scope.launch { runQuery() } },
                )

                ResultCard(
                    rooms = rooms,
                    total = total,
                    hasQueried = hasQueried,
                    error = queryError,
                    loadingMore = loadingMore,
                    canLoadMore = rooms.isNotEmpty() && rooms.size < total,
                    onLoadMore = { scope.launch { loadMore() } },
                )
            }
        }
    }
}

// ── 查询条件 ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterCard(
    filters: EmptyRoomFilters,
    condition: EmptyRoomQuery,
    campusOptions: EmptyRoomCampusOptions,
    campusOptionsFor: String,
    campusOptionsLoading: Boolean,
    campusOptionsError: String,
    periodCount: Int,
    querying: Boolean,
    onCampusChange: (String) -> Unit,
    onChange: (EmptyRoomQuery) -> Unit,
    onQuery: () -> Unit,
) {
    // 页面直出的楼号只对**服务端默认校区**有效（`#lh` 是按默认校区渲染的）。
    val defaultCampusId = filters.campuses.firstOrNull()?.value.orEmpty()
    val buildingOptions = buildingOptionsFor(
        campusId = condition.campusId,
        defaultCampusId = defaultCampusId,
        pageBuildings = filters.buildings,
        loadedCampusId = campusOptionsFor,
        loadedBuildings = campusOptions.buildings,
    )
    val periodOptions = periodOptionsFor(
        campusId = condition.campusId,
        loadedCampusId = campusOptionsFor,
        loadedPeriods = campusOptions.periods,
        fallbackCount = periodCount,
    )

    SystemCard {
        Text(
            text = "查询条件",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SystemDivider()

        if (filters.terms.size > 1) {
            PickerRow(
                label = "学年学期",
                options = filters.terms.map { it.label },
                selectedIndex = filters.terms.indexOfFirst { it.value == condition.term },
                onSelect = { onChange(condition.copy(term = filters.terms[it].value)) },
            )
        } else {
            ReadOnlyRow(label = "学年学期", value = filters.terms.firstOrNull()?.label.orEmpty())
        }

        // 校区：自适应换行的胶囊。
        //
        // 曾经用等宽分段控件（一次点完、不必展开菜单），但 4 个校区的名字长度不一
        //（朝阳校区 / 应用技术学院 / 淮南联大 / 泉山校区），等分宽度会把每个都截成
        //「朝阳…」「应用…」—— 名字都读不全的选项等于没给。胶囊按内容定宽，长名字完整显示，
        // 放不下就换行。
        if (filters.campuses.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel("校区")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filters.campuses.forEach { campus ->
                        ChoiceChip(
                            text = campus.label,
                            selected = campus.value == condition.campusId,
                            onClick = { if (campus.value != condition.campusId) onCampusChange(campus.value) },
                        )
                    }
                }
            }
        }

        InlineOptionPicker(
            label = "楼号",
            options = buildingOptions,
            selectedValue = condition.buildingId,
            loading = campusOptionsLoading,
            hint = campusOptionsError.takeIf { it.isNotBlank() },
            onSelect = { onChange(condition.copy(buildingId = it)) },
        )
        InlineOptionPicker(
            label = "场地类别",
            options = filters.categories.ifEmpty { listOf(ALL_OPTION) },
            selectedValue = condition.categoryId,
            onSelect = { onChange(condition.copy(categoryId = it)) },
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("座位数（可留空）")
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeatField(
                    value = condition.minSeats,
                    placeholder = "最少",
                    modifier = Modifier.weight(1f),
                    onValueChange = { onChange(condition.copy(minSeats = it)) },
                )
                Spacer(Modifier.width(8.dp))
                Text("至", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                SeatField(
                    value = condition.maxSeats,
                    placeholder = "最多",
                    modifier = Modifier.weight(1f),
                    onValueChange = { onChange(condition.copy(maxSeats = it)) },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("周次")
            if (filters.weeks.isEmpty()) {
                HintText("未读到可选周次，请返回重进本页。")
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filters.weeks.forEach { week ->
                        ChoiceChip(
                            text = week.toString(),
                            selected = week in condition.weeks,
                            onClick = { onChange(condition.copy(weeks = condition.weeks.toggle(week))) },
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("星期")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..7).forEach { day ->
                    ChoiceChip(
                        text = weekdayLabel(day),
                        selected = day in condition.weekdays,
                        onClick = { onChange(condition.copy(weekdays = condition.weekdays.toggle(day))) },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("节次")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                periodOptions.forEach { option ->
                    // 非数字的值进不了位掩码（`jcd` 是 Σ2^(节次-1)），直接不给选
                    val number = option.value.toIntOrNull() ?: return@forEach
                    ChoiceChip(
                        text = option.label,
                        selected = number in condition.periods,
                        onClick = { onChange(condition.copy(periods = condition.periods.toggle(number))) },
                    )
                }
            }
        }

        SystemPrimaryButton(
            text = if (querying) "查询中…" else "查询空闲教室",
            onClick = onQuery,
            enabled = !querying,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 卡片内联的选项列表（楼号 / 场地类别）。
 *
 * 刻意**不用**带 portal 的下拉：那类控件的展开面板浮在页面之上，会整块盖住下面的字段
 *（座位数、周次…），而且行级显形由动画时钟驱动 —— 长列表（场地类别有 36 项）下
 * 靠后的选项可能一直停在半透明。内联展开只是把后面的内容往下推，
 * 选项文字是静态的，任何设备、任何列表长度都不会"看不见"。
 *
 * 选项超过 [FILTER_THRESHOLD] 项时给一个筛选框：36 个类别靠滚动找太慢。
 */
@Composable
private fun InlineOptionPicker(
    label: String,
    options: List<EmptyRoomOption>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    loading: Boolean = false,
    hint: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    val appearance = LocalWallpaperAppearanceColors.current
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label
        ?: options.firstOrNull()?.label.orEmpty()
    val visible = remember(options, keyword) {
        val needle = keyword.trim()
        if (needle.isEmpty()) options else options.filter { it.label.contains(needle, ignoreCase = true) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(appearance.surface)
                .border(0.5.dp, appearance.border, CircleShape)
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起选项" else "展开选项",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }

        if (hint != null) HintText(hint)

        if (expanded) {
            if (options.size >= FILTER_THRESHOLD) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text("筛选$label", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Surface(
                color = appearance.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.5.dp, appearance.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = INLINE_LIST_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (visible.isEmpty()) {
                        Box(modifier = Modifier.padding(14.dp)) {
                            HintText("没有匹配的$label")
                        }
                    } else {
                        visible.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelect(option.value)
                                        expanded = false
                                        keyword = ""
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                if (option.value == selectedValue) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "已选中",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 选项多到这个数就给筛选框（场地类别有 36 项，靠滚动找太慢）。 */
private const val FILTER_THRESHOLD = 12

/** 内联列表的最大高度：再高就把整页推得太长，超出的部分内部滚动。 */
private val INLINE_LIST_MAX_HEIGHT = 240.dp

@Composable
private fun PickerRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        SystemPicker(
            options = options.ifEmpty { listOf("暂无可选项") },
            selectedIndex = selectedIndex.takeIf { it in options.indices },
            onSelect = onSelect,
            enabled = options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FieldLabel(label, modifier = Modifier.width(84.dp))
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun SeatField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        // 只收数字：座位数是整数，放别的字符进来只会在提交时被判成"不限"，
        // 用户以为自己筛了、实际没筛。
        onValueChange = { text -> onValueChange(text.filter(Char::isDigit).take(5)) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/**
 * 可选项胶囊，用于校区、周次、星期、节次的选择。
 *
 * 按**内容**定宽（不是等分）：校区名长短不一，等分宽度会把「应用技术学院」截成「应用…」，
 * 名字读不全的选项等于没给。放不下时由外层 FlowRow 换行。
 *
 * 自绘而不是用 Material 的 FilterChip：本页在玻璃容器里，
 * FilterChip 的默认容器色与描边在这套外观下会变成一块不透明灰底。
 */
@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val appearance = LocalWallpaperAppearanceColors.current
    val shape = CircleShape
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primary else appearance.surface,
        border = if (selected) null else BorderStroke(0.5.dp, appearance.border),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else appearance.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

// ── 结果 ────────────────────────────────────────────────────────────────

@Composable
private fun ResultCard(
    rooms: List<EmptyRoom>,
    total: Int,
    hasQueried: Boolean,
    error: String,
    loadingMore: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
) {
    SystemCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "查询结果",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (total > 0) {
                Text(
                    text = "共 $total 间",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SystemDivider()

        when {
            error.isNotBlank() -> HintText(error)

            !hasQueried -> HintText("选好校区与时间后点「查询空闲教室」。")

            rooms.isEmpty() -> HintText("这段时间没有符合条件的空教室。可以换周次、星期或节次再试。")

            else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rooms.forEach { room ->
                    RoomRow(room)
                }
            }
        }

        if (canLoadMore) {
            SystemSecondaryButton(
                text = if (loadingMore) "加载中…" else "加载更多（已显示 ${rooms.size} 间）",
                onClick = onLoadMore,
                enabled = !loadingMore,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RoomRow(room: EmptyRoom) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = room.name.ifBlank { room.code },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (room.subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = room.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (room.seatText.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = room.seatText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
    SystemDivider()
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 19.sp,
    )
}

// ── 纯函数 ──────────────────────────────────────────────────────────────

/** 星期几的标签，1=周一。与课表共用同一套序号与映射（见 schedule/ScheduleWeekdayLabel）。 */
internal fun weekdayLabel(day: Int): String = com.hnnujw.course.schedule.scheduleWeekdayChar(day).toString()

/** 多选集合的开关：已在集合里就移除，否则加入。 */
internal fun Set<Int>.toggle(value: Int): Set<Int> = if (contains(value)) this - value else this + value
