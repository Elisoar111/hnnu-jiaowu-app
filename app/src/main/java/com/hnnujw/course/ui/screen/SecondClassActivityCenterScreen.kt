package com.hnnujw.course.ui.screen

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.hnnujw.course.secondclass.SecondClassActivity
import com.hnnujw.course.secondclass.SecondClassActivityDetail
import com.hnnujw.course.secondclass.SecondClassActivityNotice
import com.hnnujw.course.secondclass.SecondClassActivityPhase
import com.hnnujw.course.secondclass.SecondClassActivitySort
import com.hnnujw.course.secondclass.SecondClassAttachment
import com.hnnujw.course.secondclass.SecondClassEnrollAnswer
import com.hnnujw.course.secondclass.SecondClassEnrollField
import com.hnnujw.course.secondclass.SecondClassEnrollState
import com.hnnujw.course.secondclass.resolveActivityCoverUrl
import com.hnnujw.course.ui.system.GlassDatePickerDialog
import com.hnnujw.course.ui.system.GlassOptionWheelDialog
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassProgressBar
import com.hnnujw.course.ui.system.GlassTextField
import com.hnnujw.course.ui.system.InsetGroupedSection
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SystemCard
import com.hnnujw.course.ui.system.SystemConfirmDialog
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemEmptyState
import com.hnnujw.course.ui.system.SystemActionMenu
import com.hnnujw.course.ui.system.SystemIconButton
import com.hnnujw.course.ui.system.SystemLoadingState
import com.hnnujw.course.ui.system.SystemMenuAction
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import com.hnnujw.course.ui.system.SystemSegmentedControl
import com.hnnujw.course.ui.theme.SemanticDanger
import com.hnnujw.course.ui.theme.SemanticSuccess
import com.hnnujw.course.ui.theme.SemanticWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 活动列表本地筛选的日志标签（与 Route 侧同名前缀，便于一起过滤 logcat）。 */
private const val FILTER_TAG = "SecondClassActivity"

/**
 * 第二课堂「活动中心」的全部可渲染状态。由 [com.hnnujw.course.ui.route.SecondClassActivityCenterRoute] 组装。
 */
data class SecondClassActivityCenterUi(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /**
     * 上一页拉取失败。为 true 时**停止自动翻页**，由用户点页脚「加载失败，点击重试」再发起，
     * 否则 loadingMore 在 true/false 之间翻转会把尾部的 LaunchedEffect 反复唤醒 → 请求风暴。
     */
    val loadMoreError: Boolean = false,
    val error: String = "",
    /**
     * 二课凭据缺失或已失效。为 true 时空列表要显示「去绑定」引导，
     * 而不是那句会误导人的「没有符合条件的活动」—— 用户会以为真没活动可报。
     */
    val needBind: Boolean = false,
    /** 0 活动 / 1 已报 / 2 申报 / 3 消息 / 4 成绩单（见 [TABS] 的说明，别按旧下标理解） */
    val tab: Int = 0,
    val keyword: String = "",
    val categories: List<com.hnnujw.course.secondclass.SecondClassActivityCategory> = emptyList(),
    val classifyId: String = "",
    val sort: SecondClassActivitySort = SecondClassActivitySort.Default,
    val activities: List<SecondClassActivity> = emptyList(),
    val hasMore: Boolean = false,
    val myActivities: List<SecondClassActivity> = emptyList(),
    val myHasMore: Boolean = false,
    /** 「消息」页（/message/notice/list）。 */
    val messages: List<com.hnnujw.course.secondclass.SecondClassMessage> = emptyList(),
    val msgHasMore: Boolean = false,
    /** 未读消息数（驱动一键已读按钮显隐）。 */
    val msgUnread: Int = 0,
    /** 「申报」页：我的申报记录（来自 `/project/request/list1`）。 */
    val applications: List<com.hnnujw.course.secondclass.SecondClassApplication> = emptyList(),
    /** 申报分类（六大能力模块，classifyId），「分类」下拉的数据源。 */
    val appCategories: List<com.hnnujw.course.secondclass.SecondClassActivityCategory> = emptyList(),
    /** 申报**类型**（`/dict/school/system-para/list?fieldType=honor`，「类别」下拉的数据源）。 */
    val declareTypes: List<com.hnnujw.course.secondclass.SecondClassActivityCategory> = emptyList(),
    /** 「申报 · 未申报」项目列表（`/project/home/page/list`）。 */
    val declareProjects: List<com.hnnujw.course.secondclass.SecondClassDeclareProject> = emptyList(),
    val declareHasMore: Boolean = false,
    /** 0 = 未申报（项目列表）/ 1 = 已申报（我的申报记录）。 */
    val declareSegment: Int = 0,
    /** 申报**类型**（categoryId，对应站点「类别」下拉；空 = 全部）。 */
    val declareCategory: String = "",
    /** 申报分类（classifyId，对应站点「分类」下拉；空 = 全部）。 */
    val declareClassify: String = "",
    /** 0 = 最新 / 1 = 最热（站点 sortType）。 */
    val declareSort: Int = 0,
    /** 申报项目搜索词（防抖后由 Route 发请求）。 */
    val declareKeyword: String = "",
)

/** 活动详情（含报名表单与签到信息）。 */
data class SecondClassActivityDetailUi(
    val loading: Boolean = false,
    val acting: Boolean = false,
    val error: String = "",
    val message: String = "",
    val bundle: com.hnnujw.course.secondclass.SecondClassActivityDetailBundle? = null,
    val notices: List<SecondClassActivityNotice> = emptyList(),
    /** 我在本活动里的 userId（签到提交要用）。 */
    val myUserId: String = "",
    /** 我的等待签到码内容（`qutuo://waitSign?...`）。 */
    val mySignCode: String = "",
)

/**
 * 「申报」填报页（站点 `creditProject` 路由的同构实现）：
 * 选奖项 → 起止日期 → 总结报告 → 证明材料 → 提交。
 */
data class SecondClassDeclareFormUi(
    val project: com.hnnujw.course.secondclass.SecondClassDeclareProject,
    val detail: com.hnnujw.course.secondclass.SecondClassDeclareProjectDetail? = null,
    val loadingDetail: Boolean = false,
    /** 选中的奖项（档位）id；0 = 未选。 */
    val optionId: Int = 0,
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val report: String = "",
    /** 关联活动（可选）。 */
    val relateActivity: SecondClassActivity? = null,
    val relateKeyword: String = "",
    val relateOptions: List<SecondClassActivity> = emptyList(),
    val relateSearching: Boolean = false,
    val relateDialog: Boolean = false,
    /** 已上传的证明材料。 */
    val materials: List<com.hnnujw.course.secondclass.SecondClassDeclareMaterial> = emptyList(),
    val uploading: Boolean = false,
    val submitting: Boolean = false,
    val error: String = "",
) {
    val selectedOption: com.hnnujw.course.secondclass.SecondClassDeclareOption?
        get() = detail?.options?.firstOrNull { it.id == optionId }
}

/**
 * 活动中心的分段标签。
 *
 * ⚠️ 下标是有语义的，插项必须同步所有 `when (tab)`：
 * 0 = 活动 / 1 = 已报 / **2 = 申报**（1.2.4 新增，插在「已报」右边）/ 3 = 消息 / 4 = 成绩单。
 * 「成绩单」由宿主注入内容，它挪到 4 之后，`SecondClassActivityCenterRoute` 里
 * 判断"要不要去拉成绩单数据"的下标也要跟着改。
 */
private val TABS = listOf("活动", "已报", "申报", "消息", "成绩单")

/** 活动中心整页。列表与详情在同一层级里切换（与公告中心同构）。 */
@Composable
fun SecondClassActivityCenterScreen(
    ui: SecondClassActivityCenterUi,
    detail: SecondClassActivityDetailUi?,
    /** null = 作为底栏 Tab 使用（不显示返回箭头）；非 null = 子页，点返回即整页关闭。 */
    onClose: (() -> Unit)?,
    onTab: (Int) -> Unit,
    onKeyword: (String) -> Unit,
    onCategory: (String) -> Unit,
    onSort: (SecondClassActivitySort) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: (Int) -> Unit,
    onCloseDetail: () -> Unit,
    onEnroll: (List<SecondClassEnrollAnswer>) -> Unit,
    onCancelEnroll: (String) -> Unit,
    onScan: () -> Unit,
    onScanFromGallery: () -> Unit = {},
    /** 一键已读：把「消息」页全部消息标为已读（服务端）。 */
    onReadAllMessages: () -> Unit = {},
    /** 点击一条消息（Route 负责把未读标记为已读）。 */
    onOpenMessage: (com.hnnujw.course.secondclass.SecondClassMessage) -> Unit = {},
    // ── 申报（1.2.5）────────────────────────────────────────────────────
    /** 任一申报筛选项变化：segment（0 未申报 / 1 已申报）、类型、分类、排序。 */
    onDeclareFilters: (segment: Int, category: String, classify: String, sort: Int) -> Unit = { _, _, _, _ -> },
    /** 申报项目搜索词（Route 里防抖）。 */
    onDeclareKeyword: (String) -> Unit = {},
    /** 点项目行 / 「立即申请」：Route 拉项目详情并打开填报页。 */
    onOpenDeclareProject: (com.hnnujw.course.secondclass.SecondClassDeclareProject) -> Unit = {},
    /** 选证明材料（Route 持有选图 launcher）。 */
    onPickDeclareMaterial: () -> Unit = {},
    /** 删除一份已上传的证明材料。 */
    onRemoveDeclareMaterial: (String) -> Unit = {},
    /** 关联活动搜索（弹窗里输入关键词）。 */
    onSearchRelateActivity: (String) -> Unit = {},
    /**
     * 「申报 · 填报页」状态，**状态提升到 Route**（提交/上传/详情拉取都在 Route 发请求，
     * 状态必须与请求方同层 —— 之前 Screen 里自己 remember 一份、Route 改的是自己那份，
     * 两边永不相等，点「立即申请」就永远打不开填报页）。
     */
    declareForm: SecondClassDeclareFormUi? = null,
    onDeclareFormChange: (SecondClassDeclareFormUi?) -> Unit = {},
    /** 详情拉取失败后的重试（只重拉 detail，不动已上传的材料）。 */
    onRetryDeclareDetail: (SecondClassDeclareFormUi) -> Unit = {},
    /** 提交申报。 */
    onSubmitDeclare: () -> Unit = {},
    coverBase: String = "",
    onOpenAttachment: (SecondClassAttachment) -> Unit,
    /**
     * 「成绩单」Tab 的内容，由 [SecondClassActivityCenterRoute] 的宿主注入：
     * 二课的会话与快照在 [com.hnnujw.course.ui.route.SecondClassroomRoute] 手里，
     * 活动中心这条加载链路只认识活动与消息。
     */
    transcriptContent: @Composable () -> Unit = {},
    /** 「成绩单」Tab 的顶栏刷新（与活动列表不是同一份数据）。 */
    onTranscriptRefresh: () -> Unit = {},
    /** 凭据缺失 / 失效时「去绑定」：拉起二课登录框（由宿主提供）。 */
    onBindSecondClass: () -> Unit = {},
    /**
     * 本人院系 id（来自二课 `/student/achievement/detail` 的 `user.collegeId`）。
     * 0 = 还没拿到，此时「本院系可报」按**不限院系**放行，而不是把列表筛空。
     */
    myCollegeId: Int = 0,
    /** 本人院系名称，仅用于筛选条的说明文案。 */
    myCollegeName: String = "",
    /** 正在拉取本人院系（筛选条上给个即时反馈，别让用户以为没反应）。 */
    collegeLoading: Boolean = false,
    /** 用户打开「本院系可报」开关时调一次：宿主按需去取本人院系。 */
    onRequestCollegeInfo: () -> Unit = {},
) {
    val currentDetail = detail
    // 扫码入口合并成一个图标：点开后再选相机扫码或从相册识别
    var showScanPicker by rememberSaveable { mutableStateOf(false) }
    // 活动筛选弹窗：只看未报满 / 只看本院系可报
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    var filterNotFull by rememberSaveable { mutableStateOf(false) }
    var filterAbleEnroll by rememberSaveable { mutableStateOf(false) }
    // 「我的」的分段：0 进行中 / 1 已结束（放页面级，切 Tab 回来保持）
    var mySegment by rememberSaveable { mutableIntStateOf(0) }
    /**
     * 「申报」页的页内详情。
     *
     * 申报记录的字段在列表接口里已经给全（项目 / 档位 / 模块 / 学时 / 状态 / 时间），
     * 所以点开**不需要再发一次请求** —— 与活动详情那条链路不同。
     */
    var applicationDetail by remember {
        mutableStateOf<com.hnnujw.course.secondclass.SecondClassApplication?>(null)
    }
    // declareForm（申报填报页）状态提升在 Route，经参数传入 —— 这里不再自己 remember

    GlassPageScaffold(
        title = when {
            currentDetail != null -> "活动详情"
            applicationDetail != null -> "申报详情"
            declareForm != null -> "奖励申报"
            else -> "第二课堂"
        },
        subtitle = if (currentDetail == null && declareForm == null) {
            when (ui.tab) {
                1 -> "我报名的活动"
                2 -> "类型 · 分类 · 奖励申报"
                3 -> if (ui.msgUnread > 0) "未读 ${ui.msgUnread}" else "站内消息"
                4 -> "模块积分与排行榜"
                else -> null
            }
        } else {
            null
        },
        // 详情打开时永远显示返回（回列表）；列表层级是否显示返回取决于 onClose 是否为 null（Tab 模式隐藏）
        onBack = when {
            currentDetail != null -> ({ onCloseDetail() })
            applicationDetail != null -> ({ applicationDetail = null })
            declareForm != null -> ({ onDeclareFormChange(null) })
            else -> onClose
        },
        actions = {
            if (currentDetail == null && applicationDetail == null && declareForm == null) {
                when (ui.tab) {
                    0 -> {
                        // 活动页的动作收进「更多」菜单（与课表顶栏同款交互）：
                        // 筛选 / 刷新 / 扫码，不再占顶栏一排图标位。
                        SystemActionMenu(
                            description = "更多活动操作",
                            actions = listOf(
                                SystemMenuAction("筛选活动", Icons.Outlined.FilterList, onClick = { showFilterSheet = true }),
                                SystemMenuAction("刷新", Icons.Outlined.Refresh, onClick = { onRefresh() }),
                                SystemMenuAction("扫码签到", Icons.Outlined.QrCodeScanner, onClick = { showScanPicker = true })
                            )
                        )
                    }
                    2 -> SystemIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "刷新申报记录",
                        onClick = onRefresh,
                    )
                    1 -> SystemIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "刷新",
                        onClick = onRefresh,
                    )
                    3 -> {
                        // 一键已读（同选课页 DoneAll 图标，仅点击、无长按）：常显，无未读时点了只提示
                        SystemIconButton(
                            icon = Icons.Default.DoneAll,
                            contentDescription = "一键已读",
                            onClick = onReadAllMessages,
                        )
                        SystemIconButton(
                            icon = Icons.Outlined.Refresh,
                            contentDescription = "刷新",
                            onClick = onRefresh,
                        )
                    }
                    4 -> SystemIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "刷新成绩单",
                        onClick = onTranscriptRefresh,
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val openApplication = applicationDetail
            val openDeclareForm = declareForm
            if (currentDetail != null) {
                ActivityDetailBody(
                    ui = currentDetail,
                    coverBase = coverBase,
                    onEnroll = onEnroll,
                    onCancelEnroll = onCancelEnroll,
                    onOpenAttachment = onOpenAttachment,
                    onScan = onScan,
                )
            } else if (openApplication != null) {
                ApplicationDetailBody(openApplication)
            } else if (openDeclareForm != null) {
                DeclareFormBody(
                    state = openDeclareForm,
                    onState = onDeclareFormChange,
                    onPickMaterial = onPickDeclareMaterial,
                    onRemoveMaterial = onRemoveDeclareMaterial,
                    onSearchRelate = onSearchRelateActivity,
                    onRetryDetail = { onRetryDeclareDetail(openDeclareForm) },
                    onSubmit = onSubmitDeclare,
                )
            } else {
                ActivityListBody(
                    ui = ui,
                    coverBase = coverBase,
                    filterNotFull = filterNotFull,
                    filterAbleEnroll = filterAbleEnroll,
                    myCollegeId = myCollegeId,
                    mySegment = mySegment,
                    onMySegment = { mySegment = it },
                    onTab = onTab,
                    onKeyword = onKeyword,
                    onCategory = onCategory,
                    onSort = onSort,
                    onRefresh = onRefresh,
                    onLoadMore = onLoadMore,
                    onOpenDetail = onOpenDetail,
                    onOpenApplication = { applicationDetail = it },
                    onDeclareSegment = { onDeclareFilters(it, ui.declareCategory, ui.declareClassify, ui.declareSort) },
                    onDeclareCategory = { onDeclareFilters(ui.declareSegment, it, ui.declareClassify, ui.declareSort) },
                    onDeclareClassify = { onDeclareFilters(ui.declareSegment, ui.declareCategory, it, ui.declareSort) },
                    onDeclareSort = { onDeclareFilters(ui.declareSegment, ui.declareCategory, ui.declareClassify, it) },
                    onDeclareKeyword = onDeclareKeyword,
                    onOpenProject = onOpenDeclareProject,
                    onOpenMessage = onOpenMessage,
                    transcriptContent = transcriptContent,
                    onBindSecondClass = onBindSecondClass,
                )
            }
        }
    }

    // 详情打开时拦一下系统返回键：先回列表，而不是直接退出（与顶栏箭头一致）
    BackHandler(enabled = currentDetail != null) {
        onCloseDetail()
    }
    // 申报详情同理：返回键先回申报列表
    BackHandler(enabled = applicationDetail != null) {
        applicationDetail = null
    }
    // 申报填报页：返回键先回申报列表
    BackHandler(enabled = declareForm != null) {
        onDeclareFormChange(null)
    }

    // 扫码方式选择：相机扫码 / 从相册识别（合并成一个入口后的二级选择）
    if (showScanPicker) {
        SystemDialog(onDismissRequest = { showScanPicker = false }, title = {
            Text(
                text = "扫码签到",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SystemPrimaryButton(
                    text = "相机扫码",
                    onClick = {
                        showScanPicker = false
                        onScan()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                SystemSecondaryButton(
                    text = "从相册识别",
                    onClick = {
                        showScanPicker = false
                        onScanFromGallery()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // 活动筛选：报名人数已满 / 本院系可报（本地过滤）
    if (showFilterSheet) {
        SystemDialog(onDismissRequest = { showFilterSheet = false }, title = {
            Text(
                text = "筛选活动",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterSwitchRow(
                    title = "只看未报满",
                    subtitle = "隐藏报名人数已满的活动",
                    checked = filterNotFull,
                    onChange = { filterNotFull = it },
                )
                FilterSwitchRow(
                    title = "本院系可报",
                    subtitle = when {
                        collegeLoading -> "正在获取你的院系…"
                        myCollegeName.isNotBlank() -> "只显示对「$myCollegeName」开放的活动"
                        myCollegeId > 0 -> "只显示对你所在院系开放的活动"
                        // 拿不到院系时要说清楚：否则用户会以为开关坏了
                        else -> "隐藏限制其它院系报名的活动；暂时取不到你的院系，全部照常显示"
                    },
                    checked = filterAbleEnroll,
                    onChange = { on ->
                        filterAbleEnroll = on
                        // 打开开关才去取本人院系：不点就一个请求都不发
                        if (on) onRequestCollegeInfo()
                    },
                )
                SystemPrimaryButton(
                    text = "完成",
                    onClick = { showFilterSheet = false },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

// ── 列表 ──────────────────────────────────────────────────────────────────

@Composable
private fun ActivityListBody(
    ui: SecondClassActivityCenterUi,
    coverBase: String,
    filterNotFull: Boolean,
    filterAbleEnroll: Boolean,
    myCollegeId: Int,
    mySegment: Int,
    onMySegment: (Int) -> Unit,
    onTab: (Int) -> Unit,
    onKeyword: (String) -> Unit,
    onCategory: (String) -> Unit,
    onSort: (SecondClassActivitySort) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: (Int) -> Unit,
    /** 打开一条申报记录的页内详情（只读，不再发请求）。 */
    onOpenApplication: (com.hnnujw.course.secondclass.SecondClassApplication) -> Unit = {},
    onDeclareSegment: (Int) -> Unit = {},
    onDeclareCategory: (String) -> Unit = {},
    onDeclareClassify: (String) -> Unit = {},
    onDeclareSort: (Int) -> Unit = {},
    onDeclareKeyword: (String) -> Unit = {},
    onOpenProject: (com.hnnujw.course.secondclass.SecondClassDeclareProject) -> Unit = {},
    onOpenMessage: (com.hnnujw.course.secondclass.SecondClassMessage) -> Unit,
    transcriptContent: @Composable () -> Unit = {},
    onBindSecondClass: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SystemSegmentedControl(
            options = TABS,
            selectedIndex = ui.tab,
            onSelect = onTab,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PagePadding, vertical = 8.dp),
            height = 44.dp,
        )
        when (ui.tab) {
            0 -> ActivityTab(
                ui = ui,
                coverBase = coverBase,
                filterNotFull = filterNotFull,
                filterAbleEnroll = filterAbleEnroll,
                myCollegeId = myCollegeId,
                onKeyword = onKeyword,
                onCategory = onCategory,
                onSort = onSort,
                onLoadMore = onLoadMore,
                onOpenDetail = onOpenDetail,
                onRefresh = onRefresh,
                onBindSecondClass = onBindSecondClass,
            )
            1 -> MyActivityTab(
                ui = ui,
                coverBase = coverBase,
                segment = mySegment,
                onSegment = onMySegment,
                onLoadMore = onLoadMore,
                onOpenDetail = onOpenDetail,
                onRefresh = onRefresh,
                onBindSecondClass = onBindSecondClass,
            )
            2 -> ApplicationTab(
                ui = ui,
                onRefresh = onRefresh,
                onLoadMore = onLoadMore,
                onOpenDetail = onOpenApplication,
                onBindSecondClass = onBindSecondClass,
                onDeclareSegment = onDeclareSegment,
                onDeclareCategory = onDeclareCategory,
                onDeclareClassify = onDeclareClassify,
                onDeclareSort = onDeclareSort,
                onDeclareKeyword = onDeclareKeyword,
                onOpenProject = onOpenProject,
            )
            // 「成绩单」现在是最右边的第 5 个 Tab：宿主注入的内容直接占据余下空间
            4 -> Box(Modifier.weight(1f)) { transcriptContent() }
            // 3 = 消息
            else -> MessageTab(
                ui = ui,
                onLoadMore = onLoadMore,
                onOpenMessage = onOpenMessage,
                onRefresh = onRefresh,
                onBindSecondClass = onBindSecondClass,
            )
        }
        // 列表有内容时失败只剩这一行轻提示（不抢内容位置）；列表空着的情况由各 Tab
        // 自己的错误态完整交代，这里就不再重复一遍。
        val listEmpty = when (ui.tab) {
            1 -> ui.myActivities.isEmpty()
            2 -> if (ui.declareSegment == 0) ui.declareProjects.isEmpty() else ui.applications.isEmpty()
            3 -> ui.messages.isEmpty()
            // 成绩单的错误由它自己的加载链路负责
            4 -> true
            else -> ui.activities.isEmpty()
        }
        if (ui.error.isNotBlank() && !listEmpty) {
            Text(
                text = ui.error,
                style = MaterialTheme.typography.labelMedium,
                color = SemanticDanger,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PagePadding, vertical = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 列表层的失败态 / 未绑定态。
 *
 * 以前这两种情况都会落进各 Tab 的「没有符合条件的活动」「还没有报名任何活动」空态里 ——
 * 用户看到的是"真没东西"，实际是凭据失效或请求失败。现在明确区分：未绑定给「去绑定」，
 * 其它失败给「重试」。
 */
@Composable
private fun ListFailureState(
    ui: SecondClassActivityCenterUi,
    onRefresh: () -> Unit,
    onBindSecondClass: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SystemEmptyState(
            title = if (ui.needBind) "尚未绑定第二课堂" else "加载失败",
            message = ui.error.ifBlank {
                if (ui.needBind) {
                    "绑定后即可浏览活动中心、报名活动与查看站内消息。"
                } else {
                    "网络或学校服务暂时不可用，点重试再来一次。"
                }
            },
        ) {
            if (ui.needBind) {
                SystemPrimaryButton(text = "去绑定", onClick = onBindSecondClass)
            } else {
                SystemPrimaryButton(text = "重试", onClick = onRefresh)
            }
        }
    }
}

@Composable
private fun ActivityTab(
    ui: SecondClassActivityCenterUi,
    coverBase: String,
    filterNotFull: Boolean,
    filterAbleEnroll: Boolean,
    myCollegeId: Int,
    onKeyword: (String) -> Unit,
    onCategory: (String) -> Unit,
    onSort: (SecondClassActivitySort) -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: (Int) -> Unit,
    onRefresh: () -> Unit,
    onBindSecondClass: () -> Unit,
) {
    // 报名已截止的活动直接剔除：窗口关了还摆在列表上只会误导，点进去也报不了名。
    // 时间基准取当下即可 —— 截止是分钟级的事，组合期间重算不会让列表抖动。
    val now = System.currentTimeMillis()
    val (endedActivities, openActivities) = ui.activities.partition { it.enrollmentEndedOf(now) }
    // 本地筛选：只看未报满 / 只看本院系可报。
    //
    // 「本院系可报」**只认活动的 `collegeLimit` 原始院系配置**（不限院系 或 含本人院系）。
    // 以前这里用的是服务端的 `isAbleEnroll`，那个字段把年级 / 诚信分 / 名额 / 时间
    // 一起揉进了复合判定 —— 一个"对全校开放、只是名额已满"的活动会被它判 false，
    // 于是开关一开就整片误杀。实测见 `SecondClassActivity.isAbleEnroll` 的注释。
    val filtered = openActivities.filter { activity ->
        (!filterNotFull || !activity.isFull) &&
            (!filterAbleEnroll || activity.enrollableForCollege(myCollegeId))
    }
    // 筛选结果落一条日志：开关打开后列表空了、或条数对不上时，能一眼看出是
    // "被报名截止剔掉"还是"院系不匹配"，不用再猜。
    LaunchedEffect(filterNotFull, filterAbleEnroll, myCollegeId, ui.activities.size) {
        if (filterNotFull || filterAbleEnroll) {
            Log.d(
                FILTER_TAG,
                "活动列表筛选：原始 ${ui.activities.size} 条 → 剔除已截止 ${endedActivities.size} 条" +
                    " → 显示 ${filtered.size} 条" +
                    "（未报满=$filterNotFull，本院系可报=$filterAbleEnroll，collegeId=$myCollegeId）",
            )
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        GlassTextField(
            value = ui.keyword,
            onValueChange = onKeyword,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PagePadding, vertical = 4.dp),
            placeholder = "搜索活动名称",
        )
        CategoryChips(
            categories = ui.categories,
            selected = ui.classifyId,
            sort = ui.sort,
            onSelect = onCategory,
            onSort = onSort,
        )
        when {
            ui.loading && ui.activities.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                SystemLoadingState("正在获取活动列表…")
            }
            // 拉取失败（凭据失效 / 网络不通）时不能落到"没有符合条件的活动"上：
            // 那是在告诉用户"确实没有活动"，而真相是"没拉到"。
            filtered.isEmpty() && (ui.error.isNotBlank() || ui.needBind) -> Box(Modifier.weight(1f)) {
                ListFailureState(ui = ui, onRefresh = onRefresh, onBindSecondClass = onBindSecondClass)
            }
            filtered.isEmpty() -> Box(Modifier.weight(1f)) {
                // 全部被过滤掉但还有下一页时给一条继续加载的路：否则用户会卡在一张空页上，
                // 既看不到内容、也没有触发下一页的入口。
                val loadMoreAction: (@Composable () -> Unit)? = if (ui.hasMore && ui.activities.isNotEmpty()) {
                    {
                        SystemPrimaryButton(
                            text = "继续加载",
                            onClick = onLoadMore,
                            enabled = !ui.loadingMore,
                        )
                    }
                } else null
                SystemEmptyState(
                    title = "没有符合条件的活动",
                    message = if (endedActivities.isNotEmpty()) {
                        "这一批里 ${endedActivities.size} 个活动的报名已经截止，暂时没有可报名的活动。"
                    } else {
                        "换个关键词或分类试试；筛选条件太严也会是空的。"
                    },
                    action = loadMoreAction,
                )
            }
            else -> ActivityList(
                items = filtered,
                coverBase = coverBase,
                hasMore = ui.hasMore,
                loadingMore = ui.loadingMore,
                loadMoreError = ui.loadMoreError,
                loadedPageHint = listOfNotNull(
                    "已隐藏 ${endedActivities.size} 个报名已截止的活动".takeIf { endedActivities.isNotEmpty() },
                    "已按筛选条件显示 ${filtered.size} 条".takeIf { filterNotFull || filterAbleEnroll },
                ).joinToString("；"),
                onLoadMore = onLoadMore,
                onOpenDetail = onOpenDetail,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CategoryChips(
    categories: List<com.hnnujw.course.secondclass.SecondClassActivityCategory>,
    selected: String,
    sort: SecondClassActivitySort,
    onSelect: (String) -> Unit,
    onSort: (SecondClassActivitySort) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PagePadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val options = buildList {
            add("" to "全部")
            categories.forEach { add(it.id to it.name) }
        }
        options.forEach { (id, name) ->
            val isSelected = id == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .clickable { onSelect(id) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        // 排序循环切换：默认 → 最新 → 近期 → 学时最多
        val next = when (sort) {
            SecondClassActivitySort.Default -> SecondClassActivitySort.Latest
            SecondClassActivitySort.Latest -> SecondClassActivitySort.Recent
            SecondClassActivitySort.Recent -> SecondClassActivitySort.HoursDesc
            SecondClassActivitySort.HoursDesc -> SecondClassActivitySort.Default
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable { onSort(next) }
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(
                text = "排序：${sort.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ActivityList(
    items: List<SecondClassActivity>,
    coverBase: String,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenDetail: (Int) -> Unit,
    modifier: Modifier = Modifier,
    loadedPageHint: String = "",
    pageHint: String = "",
    loadMoreError: Boolean = false,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = PagePadding, vertical = 8.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // key 用「id-下标」而不是裸 id：服务端翻页时插入新数据会让同一条出现在两页里，
        // 裸 id 会撞 key 直接崩（LazyColumn 要求 key 唯一）
        items(items.size, key = { index -> "${items[index].id}-$index" }) { index ->
            ActivityCard(items[index], coverBase = coverBase, onClick = { onOpenDetail(items[index].id) })
        }
        item {
            // 上拉分页：滚到底自动加载下一页（1、2、3…页），无需手动点击。
            // 失败后必须停下来等用户点重试：否则 loadingMore 翻转会让这个 effect 反复触发 → 请求风暴
            val footerModifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp)
                .let { if (loadMoreError) it.clickable { onLoadMore() } else it }
            LaunchedEffect(hasMore, loadingMore, loadMoreError, items.size) {
                if (hasMore && !loadingMore && !loadMoreError && items.isNotEmpty()) onLoadMore()
            }
            Box(modifier = footerModifier, contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        loadingMore -> "正在加载…"
                        loadMoreError -> "加载失败，点击重试"
                        hasMore -> "上拉加载更多"
                        else -> if (pageHint.isNotBlank()) pageHint else "没有更多了"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (loadMoreError) SemanticDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loadedPageHint.isNotBlank()) {
                Text(
                    text = loadedPageHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ActivityCard(activity: SecondClassActivity, coverBase: String, onClick: () -> Unit) {
    val coverUrl = resolveActivityCoverUrl(activity.logo, coverBase)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (coverUrl.isNotBlank()) {
            Box {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = "活动封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                if ((activity.quotaProgress ?: 0f) >= 1f) {
                    // 名额已满：直接盖在封面图上，不用点进详情就知道报不了名
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(SemanticDanger.copy(alpha = 0.92f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "已满",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = activity.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            EnrollBadge(activity.enrollState)
            if (activity.isFull && coverUrl.isBlank()) {
                // 没有封面图时把「已满」补在标题行，否则这类活动完全看不出报满
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(SemanticDanger.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "已满",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SemanticDanger,
                    )
                }
            }
            if (activity.endedByStatus && activity.grantHours > 0) {
                // 已结束的活动：直接亮出到手积分（服务端发了 grantHours 用实发，否则按标称学时）
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(SemanticSuccess.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "已获得 ${formatHoursValue(activity.grantHours)} 积分",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SemanticSuccess,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.AccessTime, contentDescription = null,
                modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${formatRange(activity.startTime, activity.endTime)} · ${formatHours(activity.hours)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (activity.address.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Place, contentDescription = null,
                    modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = activity.address,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (activity.classifyName.isNotBlank()) {
                Text(
                    text = activity.classifyName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            activity.quotaProgress?.let { progress ->
                Icon(
                    Icons.Outlined.Group, contentDescription = null,
                    modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GlassProgressBar(
                    progress = progress,
                    modifier = Modifier.width(64.dp),
                    height = 5.dp,
                    tint = if (progress >= 1f) SemanticDanger else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EnrollBadge(state: SecondClassEnrollState) {
    val (text, color) = when (state) {
        SecondClassEnrollState.NotEnrolled -> "未报名" to MaterialTheme.colorScheme.onSurfaceVariant
        SecondClassEnrollState.Pending -> "待审核" to SemanticWarning
        SecondClassEnrollState.Approved -> "已报名" to SemanticSuccess
        SecondClassEnrollState.Rejected -> "已驳回" to SemanticDanger
        SecondClassEnrollState.Canceled -> "已取消" to SemanticDanger
        SecondClassEnrollState.Unknown -> "状态未知" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun MyActivityTab(
    ui: SecondClassActivityCenterUi,
    coverBase: String,
    /** 0 进行中 / 1 已结束；由页面级持有，切 Tab 回来不会被重置。 */
    segment: Int,
    onSegment: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: (Int) -> Unit,
    onRefresh: () -> Unit,
    onBindSecondClass: () -> Unit,
) {
    when {
        ui.loading && ui.myActivities.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SystemLoadingState("正在读取我的活动…")
        }
        ui.myActivities.isEmpty() && (ui.error.isNotBlank() || ui.needBind) -> ListFailureState(
            ui = ui,
            onRefresh = onRefresh,
            onBindSecondClass = onBindSecondClass,
        )
        ui.myActivities.isEmpty() -> SystemEmptyState(
            title = "还没有报名任何活动",
            message = "去「活动」标签挑一个报名吧，报名审核通过后会出现在这里。",
        )
        else -> {
            // 与网页端"我的活动"一致：status 11/12 = 已结束，其余为进行中
            val ongoing = ui.myActivities.filter { !it.endedByStatus }
            val ended = ui.myActivities.filter { it.endedByStatus }
            val visible = if (segment == 0) ongoing else ended
            // 学时合计直接在已加载的清单上算 —— 不再为一张汇总卡多打一次概览接口
            val earnedHours = ui.myActivities.sumOf { it.earnedHours }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = PagePadding, vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "my-summary") {
                    MyHoursSummary(
                        count = ui.myActivities.size,
                        earnedHours = earnedHours,
                        // 还有下一页时合计会随着翻页变大，得说清楚，别让人以为这就是终值
                        partial = ui.myHasMore,
                    )
                }
                item(key = "my-segment") {
                    SystemSegmentedControl(
                        options = listOf(
                            if (ongoing.isEmpty()) "进行中" else "进行中 ${ongoing.size}",
                            if (ended.isEmpty()) "已结束" else "已结束 ${ended.size}",
                        ),
                        selectedIndex = segment,
                        onSelect = onSegment,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (visible.isEmpty()) {
                    item(key = "my-empty") {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (segment == 0) "暂无进行中的活动" else "暂无已结束的活动",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(visible.size, key = { index -> "${visible[index].id}-$index" }) { index ->
                        ActivityCard(visible[index], coverBase = coverBase, onClick = { onOpenDetail(visible[index].id) })
                    }
                }
                item {
                    // 失败即停（等用户点重试），避免 loadingMore 翻转触发请求风暴
                    LaunchedEffect(ui.myHasMore, ui.loadingMore, ui.loadMoreError, ui.myActivities.size) {
                        if (ui.myHasMore && !ui.loadingMore && !ui.loadMoreError && ui.myActivities.isNotEmpty()) onLoadMore()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp)
                            .let { if (ui.loadMoreError) it.clickable { onLoadMore() } else it },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when {
                                ui.loadingMore -> "正在加载…"
                                ui.loadMoreError -> "加载失败，点击重试"
                                ui.myHasMore -> "上拉加载更多"
                                else -> "没有更多了"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (ui.loadMoreError) SemanticDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ── 消息 ──────────────────────────────────────────────────────────────────

/**
 * 「我的」顶部的学时汇总。
 *
 * 数据全部来自已加载的「我的活动」清单（`grantHours` 优先、缺省回退活动标称学时），
 * 所以**不额外发请求** —— 「成绩单」Tab 的概览接口仍然只在用户点开它时才调用。
 * 代价是它是"已加载部分"的合计，[partial] 为 true 时必须说清楚。
 */
@Composable
private fun MyHoursSummary(count: Int, earnedHours: Double, partial: Boolean) {
    SystemCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "已获得学时",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatHoursValue(earnedHours),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "已报名",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "$count 项",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (partial) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "合计基于已加载的活动，继续下滑会自动补全",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 申报 Tab：**类型 / 分类筛选 + 未申报项目列表 + 已申报记录**。
 *
 * 与站点 `/service/declare`（奖励申报）页同构：顶部分段切「未申报 / 已申报」，
 * 未申报里先选**类型**（类别）与**分类**，再从项目列表点「立即申请」进填报页。
 */
@Composable
private fun ApplicationTab(
    ui: SecondClassActivityCenterUi,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: (com.hnnujw.course.secondclass.SecondClassApplication) -> Unit,
    onBindSecondClass: () -> Unit,
    onDeclareSegment: (Int) -> Unit,
    onDeclareCategory: (String) -> Unit,
    onDeclareClassify: (String) -> Unit,
    onDeclareSort: (Int) -> Unit,
    onDeclareKeyword: (String) -> Unit,
    onOpenProject: (com.hnnujw.course.secondclass.SecondClassDeclareProject) -> Unit,
) {
    when {
        ui.loading && ui.applications.isEmpty() && ui.declareProjects.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SystemLoadingState("正在读取申报信息…")
            }
        (ui.applications.isEmpty() && ui.declareProjects.isEmpty()) && (ui.error.isNotBlank() || ui.needBind) ->
            ListFailureState(
                ui = ui,
                onRefresh = onRefresh,
                onBindSecondClass = onBindSecondClass,
            )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = PagePadding, vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SystemSegmentedControl(
                    options = listOf("未申报", "已申报"),
                    selectedIndex = ui.declareSegment,
                    onSelect = onDeclareSegment,
                    modifier = Modifier.fillMaxWidth(),
                    height = 44.dp,
                )
            }
            if (ui.declareSegment == 0) {
                // 类型（类别）+ 分类：用户点名要求的「选择类型来申报，查看类型」
                item {
                    FilterChipRow(
                        title = "类型",
                        options = ui.declareTypes,
                        selectedId = ui.declareCategory,
                        onSelect = onDeclareCategory,
                    )
                }
                item {
                    FilterChipRow(
                        title = "分类",
                        options = ui.appCategories,
                        selectedId = ui.declareClassify,
                        onSelect = onDeclareClassify,
                    )
                }
                item {
                    GlassTextField(
                        value = ui.keyword,
                        onValueChange = onDeclareKeyword,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "搜索申报项目名称",
                        singleLine = true,
                    )
                }
                if (ui.declareProjects.isEmpty()) {
                    item {
                        SystemEmptyState(
                            title = if (ui.error.isNotBlank()) "项目加载失败" else "没有可申报的项目",
                            message = if (ui.error.isNotBlank()) {
                                ui.error
                            } else {
                                "换个类型或分类试试；学校发布新项目后会出现在这里。"
                            },
                            action = if (ui.error.isNotBlank()) {
                                {
                                    SystemPrimaryButton(
                                        text = "重试",
                                        onClick = onRefresh,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                } else {
                    items(ui.declareProjects.size, key = { ui.declareProjects[it].identity }) { index ->
                        DeclareProjectRow(
                            project = ui.declareProjects[index],
                            onClick = { onOpenProject(ui.declareProjects[index]) },
                        )
                    }
                    if (ui.declareHasMore) {
                        item {
                            SystemSecondaryButton(
                                text = "加载更多",
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item {
                        Text(
                            text = "申报项目与认定档位由校方配置；提交后等待审核，结果以第二课堂站点为准。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp,
                        )
                    }
                }
            } else {
                if (ui.applications.isEmpty()) {
                    item {
                        SystemEmptyState(
                            title = "还没有申报记录",
                            message = "在「未申报」里选好类型并提交的申报，会出现在这里。",
                        )
                    }
                } else {
                    items(ui.applications.size, key = { index -> ui.applications[index].identity }) { index ->
                        ApplicationRow(ui.applications[index]) { onOpenDetail(ui.applications[index]) }
                    }
                }
            }
        }
    }
}

/** 一行横向滚动的筛选胶囊（类型 / 分类共用）；首位是「全部」。 */
@Composable
private fun FilterChipRow(
    title: String,
    options: List<com.hnnujw.course.secondclass.SecondClassActivityCategory>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterPill(label = "全部", selected = selectedId.isBlank()) { onSelect("") }
            options.forEach { option ->
                FilterPill(label = option.name, selected = option.id == selectedId) { onSelect(option.id) }
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            SemanticSuccess.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) SemanticSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 申报项目行：项目名 + 模块 + 奖项数；行尾「立即申请」或「已截止」。 */
@Composable
private fun DeclareProjectRow(
    project: com.hnnujw.course.secondclass.SecondClassDeclareProject,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(if (project.closed) "已截止" else "立即申请")
            }
            val meta = listOfNotNull(
                project.classifyName.takeIf { it.isNotBlank() },
                if (project.optionsCount > 0) "共 ${project.optionsCount} 个奖项" else null,
                project.limitTypeName.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (project.closed && project.closeApplyRemark.isNotBlank()) {
                Text(
                    text = project.closeApplyRemark,
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticWarning,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun ApplicationRow(
    application: com.hnnujw.course.secondclass.SecondClassApplication,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = application.projectName.ifBlank { "申报记录" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(application.statusLabel)
            }
            if (application.optionName.isNotBlank()) {
                Text(
                    text = application.optionName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val meta = listOfNotNull(
                application.classifyName.takeIf { it.isNotBlank() },
                if (application.hours > 0) "${trimDecimal(application.hours)} 学时" else null,
                application.limitTypeName.takeIf { it.isNotBlank() },
                if (application.lastTime > 0) formatFull(application.lastTime) else null,
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 申报 · 填报页（站点 creditProject 的同构实现）────────────────────────

/**
 * 申报填报页：选奖项 → 起止日期 → 总结报告 → 证明材料 → 提交。
 *
 * 校验口径与站点一致：奖项、开始、结束、总结报告、证明材料**都必填**；
 * 开始不得早于本学期开始（由站点校验，这里只做先后顺序）、结束不早于开始。
 */
@Composable
private fun DeclareFormBody(
    state: SecondClassDeclareFormUi,
    onState: (SecondClassDeclareFormUi) -> Unit,
    onPickMaterial: () -> Unit,
    onRemoveMaterial: (String) -> Unit,
    onSearchRelate: (String) -> Unit,
    onRetryDetail: () -> Unit,
    onSubmit: () -> Unit,
) {
    val detail = state.detail
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmSubmit by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.error.isNotBlank()) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = SemanticDanger,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = PagePadding),
            )
        }
        InsetGroupedSection(header = "申报项目") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ApplicationInfoLine("项目名称", state.project.name)
                ApplicationInfoLine("所属分类", state.project.classifyName)
                ApplicationInfoLine("申报类型", state.project.limitTypeName)
                if (!detail?.explains.isNullOrBlank()) {
                    ApplicationInfoLine("填写说明", detail!!.explains)
                }
            }
        }
        InsetGroupedSection(header = "认定档位与时间") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 奖项名称（档位）：站点是 picker，这里是玻璃滚轮弹窗；
                // 详情没拉到时点这一行改为触发重试，绝不让用户停在死路上
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !state.loadingDetail) {
                        if (detail == null) onRetryDetail() else picker = "option"
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "奖项名称 *",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(84.dp),
                    )
                    Text(
                        text = when {
                            state.loadingDetail -> "正在读取档位…"
                            detail == null -> "档位读取失败，点按重试"
                            detail.options.isEmpty() -> "该项目暂无可选档位"
                            else -> state.selectedOption?.label ?: "请选择奖项名称"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (state.selectedOption != null) FontWeight.Medium else FontWeight.Normal,
                        color = if (state.selectedOption != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clickable { picker = "start" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "开始日期 *",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(84.dp),
                    )
                    Text(
                        text = if (state.startTime > 0) dateFormat.format(Date(state.startTime)) else "请选择开始日期",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.startTime > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clickable { picker = "end" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "结束日期 *",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(84.dp),
                    )
                    Text(
                        text = if (state.endTime > 0) dateFormat.format(Date(state.endTime)) else "请选择结束日期",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.endTime > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        InsetGroupedSection(header = "总结报告") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTextField(
                    value = state.report,
                    onValueChange = { onState(state.copy(report = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "请输入总结报告（必填）",
                    singleLine = false,
                    minHeight = 120.dp,
                )
                // 关联活动（可选）：站点申请页的搜索选择
                Row(
                    Modifier.fillMaxWidth().clickable { onState(state.copy(relateDialog = true, relateKeyword = "")) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "关联活动",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(84.dp),
                    )
                    Text(
                        text = state.relateActivity?.name ?: "选填，点击搜索关联的活动",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.relateActivity != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.relateActivity != null) {
                        Text(
                            text = "清除",
                            style = MaterialTheme.typography.labelMedium,
                            color = SemanticDanger,
                            modifier = Modifier.clickable { onState(state.copy(relateActivity = null)) },
                        )
                    }
                }
            }
        }
        InsetGroupedSection(
            header = "证明材料",
            footer = "图片单张不超过 8M；至少上传一份证明材料（站点校验要求）。",
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.materials.forEach { material ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = material.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "删除",
                            style = MaterialTheme.typography.labelMedium,
                            color = SemanticDanger,
                            modifier = Modifier.clickable { onRemoveMaterial(material.url) },
                        )
                    }
                }
                SystemSecondaryButton(
                    text = if (state.uploading) "正在上传…" else "添加图片",
                    onClick = onPickMaterial,
                    enabled = !state.uploading && !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        SystemPrimaryButton(
            text = when {
                state.submitting -> "正在提交…"
                else -> "提交申报"
            },
            onClick = { confirmSubmit = true },
            enabled = !state.submitting && !state.uploading && !state.loadingDetail,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "提交后等待审核；如需修改或补充材料，请到「已申报」查看状态并在官方页面跟进。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
    }

    when (picker) {
        "option" -> {
            val options = detail?.options.orEmpty()
            GlassOptionWheelDialog(
                title = "选择奖项名称",
                options = options.map { it.label },
                selectedIndex = options.indexOfFirst { it.id == state.optionId }.coerceAtLeast(0),
                onConfirm = { index ->
                    onState(state.copy(optionId = options[index].id))
                    picker = null
                },
                onDismiss = { picker = null },
            )
        }
        "start" -> GlassDatePickerDialog(
            title = "项目开始日期",
            initialMillis = state.startTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            onConfirm = { onState(state.copy(startTime = it)); picker = null },
            onDismiss = { picker = null },
        )
        "end" -> GlassDatePickerDialog(
            title = "项目结束日期",
            initialMillis = state.endTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            onConfirm = { onState(state.copy(endTime = it)); picker = null },
            onDismiss = { picker = null },
        )
    }

    if (state.relateDialog) {
        SystemDialog(onDismissRequest = { onState(state.copy(relateDialog = false)) }, title = {
            Text(
                text = "关联活动",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = state.relateKeyword,
                    onValueChange = { onState(state.copy(relateKeyword = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "输入活动名称搜索",
                    singleLine = true,
                )
                SystemSecondaryButton(
                    text = "搜索",
                    onClick = { onSearchRelate(state.relateKeyword) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.relateSearching) {
                    SystemLoadingState("搜索中…")
                } else if (state.relateOptions.isEmpty()) {
                    Text(
                        text = "没有找到相关活动，可直接留空提交。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.relateOptions.forEach { option ->
                            Text(
                                text = option.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onState(state.copy(relateActivity = option, relateDialog = false))
                                    }
                                    .padding(vertical = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmSubmit) {
        val problem = declareFormProblem(state)
        if (problem != null) {
            confirmSubmit = false
            onState(state.copy(error = problem))
        } else {
            SystemConfirmDialog(
                title = "提交申报",
                text = buildString {
                    append("项目：").append(state.project.name).append('\n')
                    state.selectedOption?.let { append("档位：").append(it.name).append('\n') }
                    if (state.startTime > 0) append("开始：").append(dateFormat.format(Date(state.startTime))).append('\n')
                    if (state.endTime > 0) append("结束：").append(dateFormat.format(Date(state.endTime))).append('\n')
                    append("材料：").append(state.materials.size).append(" 份\n\n确认提交？")
                },
                confirmText = "确认提交",
                onConfirm = {
                    confirmSubmit = false
                    onSubmit()
                },
                onDismiss = { confirmSubmit = false },
            )
        }
    }
}

/** 提交前的本地校验，与站点 submit() 的判定一致（学期起点由站点校验）。 */
internal fun declareFormProblem(state: SecondClassDeclareFormUi): String? = when {
    state.optionId <= 0 -> "请选择奖项名称"
    state.startTime <= 0L -> "请选择项目开始日期"
    state.endTime <= 0L -> "请选择项目结束日期"
    state.endTime < state.startTime -> "结束日期不能小于开始日期"
    state.report.isBlank() -> "请输入总结报告"
    state.materials.isEmpty() -> "请上传证明材料"
    else -> null
}

/** 申报详情的页内视图（数据全部来自列表项，不再发请求）。 */
@Composable
private fun ApplicationDetailBody(
    application: com.hnnujw.course.secondclass.SecondClassApplication,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = PagePadding, vertical = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = application.projectName.ifBlank { "申报记录" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ApplicationInfoLine("认定档位", application.optionName)
                    ApplicationInfoLine("所属模块", application.classifyName)
                    ApplicationInfoLine("申报类型", application.limitTypeName)
                    ApplicationInfoLine(
                        "认定学时",
                        if (application.hours > 0) "${trimDecimal(application.hours)} 学时" else "",
                    )
                    ApplicationInfoLine("当前状态", application.statusLabel)
                    ApplicationInfoLine(
                        "申报开始",
                        if (application.startTime > 0) formatFull(application.startTime) else "",
                    )
                    ApplicationInfoLine(
                        "申报截止",
                        if (application.endTime > 0) formatFull(application.endTime) else "",
                    )
                    ApplicationInfoLine(
                        "最近更新",
                        if (application.lastTime > 0) formatFull(application.lastTime) else "",
                    )
                }
            }
        }
        if (application.remark.isNotBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "备注",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = application.remark,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 22.sp,
                        )
                    }
                }
            }
        }
        item {
            Text(
                text = "状态由第二课堂站点判定，本应用原样展示、不替站点下结论。" +
                    "要看审核意见或补充材料，请到第二课堂官方页面。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
            )
        }
    }
}

/** 一行「标签 + 值」；值为空时整行不渲染。 */
@Composable
private fun ApplicationInfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            lineHeight = 20.sp,
        )
    }
}

/** 中性状态胶囊。站点没给状态文案，所以这里不做颜色语义（不臆造"已通过"用绿色）。 */
@Composable
private fun StatusChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** `3.0 → "3"`、`2.5 → "2.5"`：学时在站点上是浮点，整数时不显示多余小数位。 */
private fun trimDecimal(value: Double): String {
    val asLong = value.toLong()
    return if (value == asLong.toDouble()) asLong.toString() else value.toString()
}

@Composable
private fun MessageTab(
    ui: SecondClassActivityCenterUi,
    onLoadMore: () -> Unit,
    onOpenMessage: (com.hnnujw.course.secondclass.SecondClassMessage) -> Unit,
    onRefresh: () -> Unit,
    onBindSecondClass: () -> Unit,
) {
    when {
        ui.loading && ui.messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SystemLoadingState("正在读取消息…")
        }
        ui.messages.isEmpty() && (ui.error.isNotBlank() || ui.needBind) -> ListFailureState(
            ui = ui,
            onRefresh = onRefresh,
            onBindSecondClass = onBindSecondClass,
        )
        ui.messages.isEmpty() -> SystemEmptyState(
            title = "暂无消息",
            message = "报名审核结果、学时发放等通知都会出现在这里。",
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = PagePadding, vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(ui.messages.size, key = { index -> "${ui.messages[index].id}-$index" }) { index ->
                MessageRow(ui.messages[index], onClick = { onOpenMessage(ui.messages[index]) })
            }
            item {
                // 失败即停（等用户点重试），避免 loadingMore 翻转触发请求风暴
                LaunchedEffect(ui.msgHasMore, ui.loadingMore, ui.loadMoreError, ui.messages.size) {
                    if (ui.msgHasMore && !ui.loadingMore && !ui.loadMoreError && ui.messages.isNotEmpty()) onLoadMore()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                        .let { if (ui.loadMoreError) it.clickable { onLoadMore() } else it },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            ui.loadingMore -> "正在加载…"
                            ui.loadMoreError -> "加载失败，点击重试"
                            ui.msgHasMore -> "上拉加载更多"
                            else -> "没有更多了"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ui.loadMoreError) SemanticDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: com.hnnujw.course.secondclass.SecondClassMessage,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (message.isRead) Spacer(Modifier.width(8.dp)) else Box(
                Modifier.size(8.dp).padding(top = 6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = message.content.ifBlank { message.subTypeName.ifBlank { message.typeName } },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (message.isRead) FontWeight.Normal else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    message.subTypeName.ifBlank { message.typeName }.takeIf { it.isNotBlank() },
                    if (message.time > 0) formatFull(message.time) else null,
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ── 详情 ──────────────────────────────────────────────────────────────────

@Composable
private fun ActivityDetailBody(
    ui: SecondClassActivityDetailUi,
    coverBase: String,
    onEnroll: (List<SecondClassEnrollAnswer>) -> Unit,
    onCancelEnroll: (String) -> Unit,
    onOpenAttachment: (SecondClassAttachment) -> Unit,
    onScan: () -> Unit,
) {
    val bundle = ui.bundle
    when {
        ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SystemLoadingState("正在读取活动详情…")
        }
        bundle == null -> SystemEmptyState(
            title = "活动详情拿不到",
            message = ui.error.ifBlank { "活动可能已经下线，或不在你所在的年级/学院开放范围内。" },
        )
        else -> DetailContent(
            ui = ui,
            detail = bundle.detail,
            coverBase = coverBase,
            enrollFields = bundle.enrollFields,
            onEnroll = onEnroll,
            onCancelEnroll = onCancelEnroll,
            onOpenAttachment = onOpenAttachment,
            onScan = onScan,
        )
    }
}

@Composable
private fun DetailContent(
    ui: SecondClassActivityDetailUi,
    detail: SecondClassActivityDetail,
    coverBase: String,
    enrollFields: List<SecondClassEnrollField>,
    onEnroll: (List<SecondClassEnrollAnswer>) -> Unit,
    onCancelEnroll: (String) -> Unit,
    onOpenAttachment: (SecondClassAttachment) -> Unit,
    onScan: () -> Unit,
) {
    var showEnrollDialog by rememberSaveable { mutableStateOf(false) }
    var showCancelDialog by rememberSaveable { mutableStateOf(false) }
    var showMyCode by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PagePadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 封面
        val coverUrl = resolveActivityCoverUrl(detail.logo, coverBase)
        if (coverUrl.isNotBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = "活动封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
        }
        // 头部信息
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = detail.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                EnrollBadge(detail.enrollState)
            }
            DetailRow(Icons.Outlined.AccessTime, "活动时间", formatRange(detail.startTime, detail.endTime))
            DetailRow(
                Icons.Outlined.AccessTime,
                "报名时间",
                formatRange(detail.enrollStartTime, detail.enrollEndTime).ifBlank { "不限" },
            )
            if (detail.address.isNotBlank()) DetailRow(Icons.Outlined.Place, "地点", detail.address)
            DetailRow(Icons.Outlined.Group, "名额", quotaText(detail.peopleLimit, detail.joinMemberCount))
            if (detail.hours > 0.0) {
                DetailRow(Icons.Outlined.Group, "学时", formatHours(detail.grantHours.takeIf { it > 0 } ?: detail.hours))
            }
            if (detail.organizationName.isNotBlank()) DetailRow(null, "主办", detail.organizationName)
            if (detail.managerName.isNotBlank()) DetailRow(null, "负责人", detail.managerName)
            if (detail.contact.isNotBlank()) DetailRow(null, "联系", detail.contact)
        }

        detail.quotaProgress?.let { progress ->
            GlassProgressBar(progress = progress, tint = if (progress >= 1f) SemanticDanger else MaterialTheme.colorScheme.primary)
        }

        if (detail.applyRejectReason.isNotBlank()) {
            Text(
                text = "驳回原因：${detail.applyRejectReason}",
                style = MaterialTheme.typography.bodySmall,
                color = SemanticDanger,
            )
        }

        // 附件
        if (detail.attachments.isNotEmpty()) {
            InsetGroupedSection(header = "附件（${detail.attachments.size}）") {
                detail.attachments.forEachIndexed { index, attachment ->
                    AttachmentRow(
                        attachment = attachment,
                        showDivider = index != detail.attachments.lastIndex,
                        onClick = { onOpenAttachment(attachment) },
                    )
                }
            }
        }

        // 简介
        if (detail.introduce.isNotBlank()) {
            InsetGroupedSection(header = "活动介绍") {
                Text(
                    text = detail.introduce,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        // 签到
        if (detail.supportsCodeSign) {
            InsetGroupedSection(
                header = "签到",
                footer = if (detail.supportsLocationSign) "本活动还要求定位打卡，App 暂不支持定位，请到现场页面完成。" else null,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SystemSecondaryButton(
                        text = "扫码签到",
                        onClick = onScan,
                        modifier = Modifier.weight(1f),
                        enabled = !ui.acting,
                    )
                    if (ui.mySignCode.isNotBlank()) {
                        SystemSecondaryButton(
                            text = "我的签到码",
                            onClick = { showMyCode = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // 通知
        if (ui.notices.isNotEmpty()) {
            InsetGroupedSection(header = "活动通知") {
                ui.notices.forEachIndexed { index, notice ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = notice.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = notice.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (index != ui.notices.lastIndex) {
                        Box(
                            Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        )
                    }
                }
            }
        }

        // 底部操作
        val enrolled = detail.enrollState.isEnrolled
        val pending = detail.enrollState == SecondClassEnrollState.Pending
        val now = System.currentTimeMillis()
        val quotaFull = (detail.quotaProgress ?: 0f) >= 1f
        val enrollNotStarted = detail.enrollStartTime > 0 && now < detail.enrollStartTime
        val enrollEnded = detail.enrollEndTime > 0 && now >= detail.enrollEndTime
        val notEnrolled = detail.enrollState == SecondClassEnrollState.NotEnrolled
        val canEnroll = notEnrolled && !quotaFull && !enrollEnded && !enrollNotStarted
        when {
            notEnrolled && quotaFull -> SystemSecondaryButton(
                text = "报名人数已满",
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )
            notEnrolled && enrollEnded -> SystemSecondaryButton(
                text = "报名已截止",
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )
            notEnrolled && enrollNotStarted -> SystemSecondaryButton(
                text = "报名未开始",
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )
            canEnroll -> SystemPrimaryButton(
                text = if (enrollFields.isEmpty()) "立即报名" else "填写报名信息",
                onClick = {
                    if (enrollFields.isEmpty()) onEnroll(emptyList()) else showEnrollDialog = true
                },
                enabled = !ui.acting,
                modifier = Modifier.fillMaxWidth(),
            )
            pending || enrolled -> SystemSecondaryButton(
                text = "取消报名",
                onClick = { showCancelDialog = true },
                enabled = !ui.acting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (ui.acting) {
            Text(
                text = "正在提交…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        if (ui.message.isNotBlank()) {
            Text(
                text = ui.message,
                style = MaterialTheme.typography.labelMedium,
                color = SemanticSuccess,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        if (ui.error.isNotBlank()) {
            Text(
                text = ui.error,
                style = MaterialTheme.typography.labelMedium,
                color = SemanticDanger,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }

    // 报名表单弹窗
    if (showEnrollDialog) {
        EnrollFieldsDialog(
            fields = enrollFields,
            onDismiss = { showEnrollDialog = false },
            onConfirm = { answers ->
                showEnrollDialog = false
                onEnroll(answers)
            },
        )
    }
    // 取消报名
    if (showCancelDialog) {
        CancelEnrollDialog(
            onDismiss = { showCancelDialog = false },
            onConfirm = { reason ->
                showCancelDialog = false
                onCancelEnroll(reason)
            },
        )
    }
    // 我的签到码：二维码必须画在弹窗内容里（放在 Dialog 外会被弹窗挡住）
    if (showMyCode && ui.mySignCode.isNotBlank()) {
        SystemDialog(onDismissRequest = { showMyCode = false }, title = {
            Text(
                text = "我的签到码",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QrCodeImage(content = ui.mySignCode, modifier = Modifier.size(220.dp))
                Text(
                    text = "出示给签到员扫。动态码约 10 秒一变，过期就重新生成一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                SystemPrimaryButton(text = "关闭", onClick = { showMyCode = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, value: String) {
    if (value.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AttachmentRow(
    attachment: SecondClassAttachment,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Outlined.AttachFile,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (attachment.canPreviewInApp) {
                    "点击在应用内查看"
                } else {
                    attachment.unsupportedReason
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (attachment.canPreviewInApp) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    if (showDivider) {
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        )
    }
}

@Composable
private fun EnrollFieldsDialog(
    fields: List<SecondClassEnrollField>,
    onDismiss: () -> Unit,
    onConfirm: (List<SecondClassEnrollAnswer>) -> Unit,
) {
    val values = remember(fields) { Array(fields.size) { "" } }
    SystemDialog(onDismissRequest = onDismiss, title = {
        Text(
            text = "填写报名信息",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fields.forEachIndexed { index, field ->
                GlassTextField(
                    value = values[index],
                    onValueChange = { values[index] = it },
                    placeholder = field.name + if (field.required) "（必填）" else "",
                    singleLine = !field.multiline,
                )
            }
            SystemPrimaryButton(
                text = "提交报名",
                onClick = {
                    onConfirm(fields.mapIndexed { index, field ->
                        SecondClassEnrollAnswer(
                            key = field.key,
                            value = values[index],
                            title = field.title.ifBlank { field.name },
                        )
                    })
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CancelEnrollDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    SystemDialog(onDismissRequest = onDismiss, title = {
        Text(
            text = "取消报名",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(
                value = reason,
                onValueChange = { reason = it },
                placeholder = "取消原因（选填）",
                singleLine = false,
            )
            SystemPrimaryButton(text = "确认取消", onClick = { onConfirm(reason) }, modifier = Modifier.fillMaxWidth())
            SystemSecondaryButton(text = "再想想", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { makeQrBitmap(content, 512) }
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "我的签到二维码", modifier = modifier)
    }
}

private fun makeQrBitmap(content: String, size: Int): Bitmap? = try {
    val matrix = QRCodeWriter().encode(
        content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1)
    )
    val pixels = IntArray(size * size) { index ->
        if (matrix.get(index % size, index / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
} catch (t: Throwable) {
    null
}

// ── 格式化 ────────────────────────────────────────────────────────────────

private val rangeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
private val shortFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

private fun formatFull(millis: Long): String =
    if (millis <= 0L) "" else rangeFormat.format(Date(millis))

private fun formatRange(start: Long, end: Long): String = when {
    start <= 0 && end <= 0 -> ""
    start <= 0 -> "至 ${formatFull(end)}"
    end <= 0 -> formatFull(start)
    // 同一天只显示一次日期
    SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(start)) ==
        SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(end)) ->
        "${shortFormat.format(Date(start))} ~ ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(end))}"
    else -> "${formatFull(start)} ~ ${formatFull(end)}"
}

private fun formatHours(hours: Double): String =
    if (hours <= 0.0) "" else if (hours == hours.toLong().toDouble()) "${hours.toLong()} 学时" else "$hours 学时"

/** 纯数值的学时（积分）：2.0 → "2"、0.5 → "0.5"。 */
private fun formatHoursValue(hours: Double): String =
    if (hours == hours.toLong().toDouble()) hours.toLong().toString() else hours.toString()

private fun quotaText(limit: Int, joined: Int): String = when {
    limit <= 0 -> if (joined > 0) "不限 · 已报 $joined" else "不限"
    else -> "$joined / $limit"
}
