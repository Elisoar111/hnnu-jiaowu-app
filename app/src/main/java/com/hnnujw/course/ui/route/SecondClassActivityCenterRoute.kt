package com.hnnujw.course.ui.route

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.secondclass.SecondClassDeepLink
import com.hnnujw.course.secondclass.SecondClassDeepLinkNavigation
import com.hnnujw.course.secondclass.SecondClassScanCodec
import com.hnnujw.course.secondclass.SecondClassScanPayload
import com.hnnujw.course.secondclass.SecondClassActivitySort
import com.hnnujw.course.secondclass.SecondClassEnrollAnswer
import com.hnnujw.course.secondclass.SecondClassException
import com.hnnujw.course.secondclass.SecondClassroomClient
import com.hnnujw.course.secondclass.SecondClassroomRepository
import com.hnnujw.course.secondclass.SecondClassroomStore
import com.hnnujw.course.secondclass.decodeQrFromImage
import com.hnnujw.course.secondclass.readSecondClassClipboard
import com.hnnujw.course.ui.document.DocumentViewerActivity
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.screen.SecondClassActivityCenterScreen
import com.hnnujw.course.ui.screen.SecondClassActivityCenterUi
import com.hnnujw.course.ui.screen.SecondClassActivityDetailUi
import com.hnnujw.course.ui.system.GlassSubpage
import com.hnnujw.course.ui.system.SystemConfirmDialog
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 活动中心链路日志标签（列表加载 / 筛选 / 本人院系）。 */
private const val TAG = "SecondClassActivity"

@Composable
fun SecondClassActivityCenterRoute(
    /** null = 底栏 Tab 直达模式（顶栏不显示返回箭头）。 */
    onClose: (() -> Unit)? = null,
    /**
     * 「成绩单」Tab 被选中时回调。活动中心自己不认识二课的会话与快照，只能通知宿主
     * （[com.hnnujw.course.ui.route.SecondClassroomRoute]）去加载 —— 按需加载，
     * 不点这个 Tab 就不会平白多发一个请求。
     */
    onTranscriptTabSelected: () -> Unit = {},
    /** 「成绩单」Tab 的刷新（与活动列表不是同一条链路）。 */
    onTranscriptRefresh: () -> Unit = {},
    /** 「成绩单」Tab 的内容，由宿主注入。 */
    transcriptContent: @Composable () -> Unit = {},
    /**
     * 宿主的会话版本号（[SecondClassroomRoute] 的 revision）。
     *
     * 作用只有一个：用户在活动中心里点「去绑定」、由宿主弹窗登录成功之后，宿主会
     * revision++；这里把它并进加载 effect 的 key，才能自动把列表重新拉起来 ——
     * 否则页面会一直停在「尚未绑定第二课堂」上（token 已经在库里了，但没人去读）。
     */
    hostRevision: Int = 0,
    /** 未绑定 / 凭据失效时「去绑定」：由宿主拉起二课登录弹窗。 */
    onBindSecondClass: () -> Unit = {},
) {
    val context = LocalContext.current
    val userManager = remember { UserManager.getInstance() }
    val accountKey = userManager.currentAccountKey
    val school = userManager.currentSchool
    val coverBase = school?.secondClassroomBaseUrl.orEmpty()
    val isDemo = userManager.isDemoMode

    var ui by remember { mutableStateOf(SecondClassActivityCenterUi()) }
    var detail by remember { mutableStateOf<SecondClassActivityDetailUi?>(null) }
    // 刻意不用 by 委托：回调 lambda 里也要读写，State 对象 + .value 更直白
    val openActivityId = remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    /** 通过安全闸门、等待用户确认提交的签到码。 */
    var scannedCode by remember { mutableStateOf<SecondClassScanPayload.SignCode?>(null) }
    var revision by remember { mutableStateOf(0) }
    var feedPage by remember { mutableIntStateOf(1) }
    var myPage by remember { mutableIntStateOf(1) }
    var msgPage by remember { mutableIntStateOf(1) }
    /** 关键词防抖后的真实查询词（输入框每敲一个字就打接口太吵）。 */
    var feedKeyword by remember { mutableStateOf("") }
    /** 已通过安全闸门、等待用户确认的扫码结果。 */
    var scanDecision by remember { mutableStateOf<SecondClassScanCodec.SignScanDecision?>(null) }
    var scanConfirm by remember { mutableStateOf<SecondClassScanCodec.SignScanDecision?>(null) }
    var pendingScanPayload by remember { mutableStateOf<SecondClassScanPayload?>(null) }
    /** 剪贴板 / 深链里识别出的入口，弹提示让用户确认后再处理。 */
    var clipboardPrompt by remember { mutableStateOf<SecondClassDeepLink?>(null) }

    // ── 本人院系（活动中心「本院系可报」筛选用）────────────────────────────
    //
    // 刻意**不常驻请求**：只有用户真的打开那个筛选开关时才拉一次（[ensureCollegeInfo]）。
    // 教务侧拿不到院系 —— `CourseParser` 只解析姓名与学号；本人院系唯一来源是二课
    // `/student/achievement/detail` 的 `user.collegeId` / `user.collegeName`。
    var myCollegeId by remember { mutableIntStateOf(0) }
    var myCollegeName by remember { mutableStateOf("") }
    var collegeLoading by remember { mutableStateOf(false) }

    val client: SecondClassroomClient? = remember(accountKey) {
        if (isDemo) null else SecondClassroomStore.clientFor(school)
    }

    /**
     * 列表类请求失败。**不能按「详情是否打开」来分流**：那样详情开着时列表请求失败会把错误
     * 写进详情，而列表自己的 loading/loadingMore 永远停在 true（页脚一直"正在加载…"）。
     */
    fun failList(e: Exception, fromLoadMore: Boolean = false) {
        val message = SecondClassroomStore.handleFailure(context, accountKey, e)
        ui = ui.copy(
            loading = false,
            refreshing = false,
            loadingMore = false,
            // 翻页失败置位：让尾部停下来等用户点「点击重试」，否则会无限重试
            loadMoreError = fromLoadMore,
            error = message,
            // 会话过期 = 凭据已被 store 清掉，再点刷新也没用，得让用户重新绑定
            needBind = (e as? SecondClassException)?.sessionExpired == true,
        )
    }

    /** 详情类请求（拉详情 / 报名 / 取消 / 扫码）失败：错误只写进详情页。 */
    fun failDetail(e: Exception) {
        val message = SecondClassroomStore.handleFailure(context, accountKey, e)
        detail = detail?.copy(acting = false, loading = false, error = message)
    }

    /**
     * 按需拉取「本人院系」（活动中心「本院系可报」筛选的判据）。
     *
     * 只在用户打开开关时调一次，成功后缓存在 [myCollegeId] / [myCollegeName] 里，
     * 同一次会话内不再重复请求。失败**静默**：拿不到院系时筛选按"一律放行"处理，
     * 列表照常可用，不该因为一个可选筛选项报错打断用户。
     */
    fun ensureCollegeInfo() {
        if (collegeLoading || myCollegeId > 0) return
        val c = client ?: return
        collegeLoading = true
        scope.launch {
            try {
                val token = SecondClassroomStore.token(context, accountKey)
                if (token.isBlank()) {
                    Log.w(TAG, "取本人院系跳过：二课未绑定")
                    return@launch
                }
                val profile = withContext(Dispatchers.IO) { c.profile(token) }
                myCollegeId = profile.collegeId
                myCollegeName = profile.collegeName
                Log.d(
                    TAG,
                    "本人院系已取到：collegeId=${profile.collegeId}，" +
                        "院系名${if (profile.collegeName.isBlank()) "为空" else "非空"}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "取本人院系失败，「本院系可报」按不限院系放行：${e.javaClass.simpleName}")
            } finally {
                collegeLoading = false
            }
        }
    }

    // ── 扫码入口（顶栏 / 详情页按钮共用）───────────────────────────────────
    // 用 zxing 的 ScanContract：输入是 ScanOptions，回调直接拿到 ScanIntentResult
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { scanResult ->
        val text = scanResult.contents
        if (!text.isNullOrBlank()) {
            pendingScanPayload = SecondClassScanCodec.parse(text)
        }
    }

    fun launchScanner() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("对准第二课堂签到码")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }

    // 「图片导入扫码」：系统相册选图 → 本地解码 → 与相机扫码同一条处理链路。
    val galleryScanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val text = runCatching { decodeQrFromImage(context, uri) }.getOrNull()
            launch(Dispatchers.Main) {
                when {
                    text.isNullOrBlank() -> GlassToaster.show("图片里没有识别到二维码，换一张试试")
                    else -> pendingScanPayload = SecondClassScanCodec.parse(text)
                }
            }
        }
    }

    fun scanFromGallery() {
        galleryScanLauncher.launch("image/*")
    }

    // 扫到码（或深链/剪贴板给了一个码）→ 拉详情判权限 → 过安全闸门
    LaunchedEffect(pendingScanPayload) {
        val payload = pendingScanPayload ?: return@LaunchedEffect
        pendingScanPayload = null
        // 活动码 → 直接打开对应活动详情页：这是「看活动」，不是「签到」，
        // 不进签到闸门，也不需要网络权限判定。
        if (payload is SecondClassScanPayload.ActivityCode) {
            openActivityId.value = payload.activityId
            return@LaunchedEffect
        }
        val c = client
        if (c == null) {
            scanDecision = SecondClassScanCodec.SignScanDecision.Blocked("演示模式不支持签到")
            return@LaunchedEffect
        }
        val token = SecondClassroomStore.token(context, accountKey)
        if (token.isBlank()) {
            scanDecision = SecondClassScanCodec.SignScanDecision.Blocked("请先绑定第二课堂")
            return@LaunchedEffect
        }
        try {
            val activityId = when (payload) {
                is SecondClassScanPayload.SignCode -> payload.activityId
                is SecondClassScanPayload.ActivityCode -> payload.activityId
                is SecondClassScanPayload.OrganizationCode -> 0
                is SecondClassScanPayload.Text -> 0
            }
            if (activityId <= 0) {
                val reason = (payload as? SecondClassScanPayload.Text)?.raw
                    ?.takeIf { it.isNotBlank() } ?: "无法识别的二维码，请确认扫的是活动签到码"
                scanDecision = SecondClassScanCodec.SignScanDecision.Blocked(reason)
                return@LaunchedEffect
            }
            // 判定依据全都要网络数据（是否已报名、我是不是签到员），先取详情
            val bundle = withContext(Dispatchers.IO) {
                SecondClassroomRepository.activityDetailBundle(c, token, activityId)
            }
            val myUserId = withContext(Dispatchers.IO) { runCatching { c.myUserId(token) }.getOrDefault("") }
            val decision = SecondClassScanCodec.evaluateSignScan(
                payload = payload,
                myUserId = myUserId,
                enrolled = bundle.detail.enrollState.isEnrolled,
                isSigner = bundle.detail.isSigner,
            )
            // pendingScanPayload 在上面已被清空，提交时要从这里拿码
            scannedCode = payload as? SecondClassScanPayload.SignCode
            if (decision is SecondClassScanCodec.SignScanDecision.Allow) {
                scanConfirm = decision
            } else {
                scanDecision = decision
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failDetail(e)
        }
    }

    // ── 深链 / 剪贴板（任务 #13）──────────────────────────────────────────
    // 必须是 LaunchedEffect(pending) 而不是 LaunchedEffect(Unit)：二课 Tab 现在常驻组合中，
    // 用 Unit 的话"已经停在活动中心时又扫一次码/点一次链接"永远不会被处理（pending 一直挂着）。
    LaunchedEffect(SecondClassDeepLinkNavigation.pending) {
        val link = SecondClassDeepLinkNavigation.pending ?: return@LaunchedEffect
        SecondClassDeepLinkNavigation.consume()
        when (link) {
            is SecondClassDeepLink.Activity -> openActivityId.value = link.activityId
            is SecondClassDeepLink.Scan -> pendingScanPayload = link.payload
            SecondClassDeepLink.None -> Unit
        }
    }

    // 剪贴板只在进入页面时读一次：命中才提示，绝不静默处理、不落原文日志
    LaunchedEffect(Unit) {
        val fromClipboard = readSecondClassClipboard(context)
        if (fromClipboard != SecondClassDeepLink.None) clipboardPrompt = fromClipboard
    }

    // ── 列表数据 ──────────────────────────────────────────────────────────
    LaunchedEffect(ui.keyword) {
        delay(400)
        feedKeyword = ui.keyword
    }

    LaunchedEffect(accountKey, client, revision, hostRevision, feedKeyword, ui.classifyId, ui.sort) {
        if (isDemo) {
            ui = ui.copy(loading = false, refreshing = false, error = "演示模式不支持活动中心")
            return@LaunchedEffect
        }
        val c = client ?: return@LaunchedEffect
        val token = SecondClassroomStore.token(context, accountKey)
        if (token.isBlank()) {
            ui = ui.copy(
                loading = false,
                refreshing = false,
                error = "绑定第二课堂后即可浏览活动中心",
                needBind = true,
            )
            return@LaunchedEffect
        }
        ui = ui.copy(
            loading = ui.activities.isEmpty(),
            refreshing = ui.activities.isNotEmpty(),
            error = "",
            // 走到这里说明凭据可用（多半是刚绑定成功）：把"去绑定"态收掉
            needBind = false,
        )
        try {
            val page = withContext(Dispatchers.IO) {
                SecondClassroomRepository.activityFeed(c, token, 1, 20, feedKeyword, ui.classifyId, ui.sort)
            }
            feedPage = 1
            Log.d(
                TAG,
                "活动列表第 1 页：${page.items.size} 条，hasMore=${page.hasMore}，" +
                    "关键词${if (feedKeyword.isBlank()) "空" else "有"}，分类=${ui.classifyId.ifBlank { "全部" }}",
            )
            ui = ui.copy(
                activities = page.items,
                hasMore = page.hasMore,
                loadMoreError = false,
                loading = false,
                refreshing = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "活动列表第 1 页拉取失败：${e.javaClass.simpleName}")
            failList(e)
        }
    }

    LaunchedEffect(accountKey, client, revision) {
        if (isDemo) return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        val token = SecondClassroomStore.token(context, accountKey)
        if (token.isBlank()) return@LaunchedEffect
        // 分类拉不到只是少了筛选条，不该整页报错；失败保持原分类即可
        val categories = try {
            withContext(Dispatchers.IO) { SecondClassroomRepository.activityCategories(c, token) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (categories.isNotEmpty()) ui = ui.copy(categories = categories)
    }

    // 切 Tab / 刷新（revision++）时重拉那一页的第一页。
    // 刻意不做 `if (isEmpty())` 短路：报名/取消成功后 revision++ 只能靠这里把新数据带进来，
    // 否则「我的」里永远看不到刚报名的活动。列表非空时静默替换，不打断阅读。
    LaunchedEffect(accountKey, client, revision, ui.tab) {
        if (isDemo) return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        val token = SecondClassroomStore.token(context, accountKey)
        if (token.isBlank()) return@LaunchedEffect
        try {
            when (ui.tab) {
                1 -> {
                    ui = ui.copy(loading = ui.myActivities.isEmpty(), loadMoreError = false)
                    val page = withContext(Dispatchers.IO) { c.myActivities(token, pageNum = 1) }
                    myPage = 1
                    ui = ui.copy(myActivities = page.items, myHasMore = page.hasMore, loading = false)
                }
                2 -> {
                    ui = ui.copy(loading = ui.messages.isEmpty(), loadMoreError = false)
                    val page = withContext(Dispatchers.IO) { c.messages(token, pageNum = 1) }
                    msgPage = 1
                    val unread = withContext(Dispatchers.IO) {
                        runCatching { c.messageUnreadCount(token) }.getOrDefault(0)
                    }
                    ui = ui.copy(
                        messages = page.items,
                        msgHasMore = page.hasMore,
                        msgUnread = unread,
                        loading = false,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failList(e)
        }
    }

    // ── 详情数据 ──────────────────────────────────────────────────────────
    LaunchedEffect(openActivityId.value, revision) {
        val activityId = openActivityId.value ?: return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        val token = SecondClassroomStore.token(context, accountKey)
        if (token.isBlank()) return@LaunchedEffect
        detail = SecondClassActivityDetailUi(loading = true)
        try {
            val bundle = withContext(Dispatchers.IO) {
                SecondClassroomRepository.activityDetailBundle(c, token, activityId)
            }
            val myUserId = withContext(Dispatchers.IO) { runCatching { c.myUserId(token) }.getOrDefault("") }
            val notices = withContext(Dispatchers.IO) {
                runCatching { c.activityNotices(token, activityId, pageNum = 1) }.getOrDefault(emptyList())
            }
            // 我的等待签到码：sp 用站点给的动态种子（没有就取当前时间，站点按毫秒校验）
            val sp = bundle.signCount?.timestamp ?: System.currentTimeMillis()
            detail = SecondClassActivityDetailUi(
                bundle = bundle,
                notices = notices,
                myUserId = myUserId,
                mySignCode = if (myUserId.isNotBlank()) {
                    SecondClassScanCodec.buildWaitSignCode(activityId, myUserId, sp)
                } else {
                    ""
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            detail = SecondClassActivityDetailUi(error = SecondClassroomStore.handleFailure(context, accountKey, e))
        }
    }

    // ── 动作 ──────────────────────────────────────────────────────────────
    fun refreshDetail() {
        revision++
    }

    SecondClassActivityCenterScreen(
        ui = ui,
        /** 「本院系可报」筛选的判据（0 = 还没拿到，按不限院系放行）。 */
        myCollegeId = myCollegeId,
        myCollegeName = myCollegeName,
        collegeLoading = collegeLoading,
        onRequestCollegeInfo = { ensureCollegeInfo() },
        transcriptContent = transcriptContent,
        onTranscriptRefresh = onTranscriptRefresh,
        detail = detail,
        onClose = onClose,
        onTab = {
            ui = ui.copy(tab = it)
            if (it == 3) onTranscriptTabSelected()
        },
        onKeyword = { ui = ui.copy(keyword = it) },
        onCategory = { ui = ui.copy(classifyId = it) },
        onSort = { ui = ui.copy(sort = it) },
        onRefresh = { revision++ },
        onLoadMore = {
            val c = client ?: return@SecondClassActivityCenterScreen
            // 加载更多放在协程里：列表尾部"正在加载…"由 loadingMore 状态驱动
            run {
                scope.launch {
                    val token = SecondClassroomStore.token(context, accountKey)
                    if (token.isBlank()) return@launch
                    // 用户点「重试」进入时先清失败标记，否则守卫会一直拦着
                    ui = ui.copy(loadMoreError = false)
                    try {
                        when (ui.tab) {
                            0 -> {
                                ui = ui.copy(loadingMore = true)
                                val nextPage = feedPage + 1
                                val page = withContext(Dispatchers.IO) {
                                    SecondClassroomRepository.activityFeed(
                                        c, token, nextPage, 20, feedKeyword, ui.classifyId, ui.sort
                                    )
                                }
                                feedPage = nextPage
                                ui = ui.copy(
                                    // 站点分页边界会重复下发同一条：追加前按 id 去重，
                                    // 否则 LazyColumn 会因 key 重复直接崩
                                    activities = (ui.activities + page.items).distinctBy { it.id },
                                    hasMore = page.hasMore,
                                    loadingMore = false,
                                )
                            }
                            1 -> {
                                ui = ui.copy(loadingMore = true)
                                val nextPage = myPage + 1
                                val page = withContext(Dispatchers.IO) { c.myActivities(token, pageNum = nextPage) }
                                myPage = nextPage
                                ui = ui.copy(
                                    myActivities = (ui.myActivities + page.items).distinctBy { it.id },
                                    myHasMore = page.hasMore,
                                    loadingMore = false,
                                )
                            }
                            2 -> {
                                ui = ui.copy(loadingMore = true)
                                val nextPage = msgPage + 1
                                val page = withContext(Dispatchers.IO) { c.messages(token, pageNum = nextPage) }
                                msgPage = nextPage
                                ui = ui.copy(
                                    messages = (ui.messages + page.items).distinctBy { it.id },
                                    msgHasMore = page.hasMore,
                                    loadingMore = false,
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failList(e, fromLoadMore = true)
                    }
                }
            }
        },
        onOpenDetail = { openActivityId.value = it },
        onCloseDetail = { openActivityId.value = null; detail = null },
        onEnroll = { answers ->
            val c = client ?: return@SecondClassActivityCenterScreen
            val activityId = openActivityId.value ?: return@SecondClassActivityCenterScreen
            run {
                scope.launch {
                    detail = detail?.copy(acting = true, error = "", message = "")
                    try {
                        val token = SecondClassroomStore.token(context, accountKey)
                        val message = withContext(Dispatchers.IO) { c.activityEnroll(token, activityId, answers) }
                        detail = detail?.copy(acting = false, message = message.ifBlank { "报名已提交" })
                        refreshDetail()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failDetail(e)
                    }
                }
            }
        },
        onCancelEnroll = { reason ->
            val c = client ?: return@SecondClassActivityCenterScreen
            val activityId = openActivityId.value ?: return@SecondClassActivityCenterScreen
            run {
                scope.launch {
                    detail = detail?.copy(acting = true, error = "", message = "")
                    try {
                        val token = SecondClassroomStore.token(context, accountKey)
                        val message = withContext(Dispatchers.IO) { c.activityCancelEnroll(token, activityId, reason) }
                        detail = detail?.copy(acting = false, message = message.ifBlank { "已提交取消申请" })
                        refreshDetail()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failDetail(e)
                    }
                }
            }
        },
        onScan = { launchScanner() },
        onScanFromGallery = { scanFromGallery() },
        onReadAllMessages = {
            // 常显按钮：没有未读就不打接口，只提示一句
            if (ui.msgUnread <= 0) {
                GlassToaster.show("没有未读消息")
            } else {
                val c = client ?: return@SecondClassActivityCenterScreen
                scope.launch {
                    try {
                        val token = SecondClassroomStore.token(context, accountKey)
                        withContext(Dispatchers.IO) { c.messageReadAll(token) }
                        // 本地直接全标已读，不整页刷新，避免列表闪动
                        ui = ui.copy(messages = ui.messages.map { it.copy(isRead = true) }, msgUnread = 0)
                        GlassToaster.show("已全部标为已读")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failList(e)
                    }
                }
            }
        },
        onOpenMessage = { message ->
            if (!message.isRead) {
                // 乐观更新：先标已读，再把服务端记账补上
                val unread = (ui.msgUnread - 1).coerceAtLeast(0)
                ui = ui.copy(
                    messages = ui.messages.map { if (it.id == message.id) it.copy(isRead = true) else it },
                    msgUnread = unread,
                )
                val c = client ?: return@SecondClassActivityCenterScreen
                scope.launch {
                    try {
                        val token = SecondClassroomStore.token(context, accountKey)
                        withContext(Dispatchers.IO) { c.messageRead(token, message.id) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 后台记账失败不打扰用户：界面已按已读渲染，下次刷新以服务端为准
                    }
                }
            }
        },
        coverBase = coverBase,
        onOpenAttachment = { attachment ->
            DocumentViewerActivity.start(context, attachment)
        },
        onBindSecondClass = onBindSecondClass,
    )

    // 扫码结果：拦截原因 / 确认提交
    scanDecision?.let { decision ->
        val reason = (decision as? SecondClassScanCodec.SignScanDecision.Blocked)?.reason.orEmpty()
        SystemConfirmDialog(
            title = "无法签到",
            text = reason,
            confirmText = "知道了",
            showCancel = false,
            onConfirm = { scanDecision = null },
            onDismiss = { scanDecision = null },
        )
    }
    scanConfirm?.let { decision ->
        // 只处理 Allow；后面的提交要用 decision.type，先转型拿到具体类型
        val allow = decision as? SecondClassScanCodec.SignScanDecision.Allow
        if (allow == null) {
            scanConfirm = null
        }
        if (allow != null) SystemConfirmDialog(
            title = "确认签到",
            text = "${allow.note}\n\n提交后无法撤销，确认继续？",
            confirmText = "确认提交",
            onConfirm = {
                scanConfirm = null
                val c = client ?: return@SystemConfirmDialog
                val token = SecondClassroomStore.token(context, accountKey)
                val signCode = scannedCode ?: return@SystemConfirmDialog
                // 提交在协程里做；结果用 detail.message 反馈
                run {
                    scope.launch {
                        try {
                            val message = withContext(Dispatchers.IO) {
                                c.signInOut(token, signCode.activityId, signCode.userId, allow.type, signCode.sp)
                            }
                            // 从列表页顶栏扫码时详情页可能没打开（detail == null），
                            // 此时用 Toast 反馈，否则签到成功用户毫无感知
                            if (detail != null) {
                                detail = detail?.copy(message = message.ifBlank { "签到成功" })
                            } else {
                                GlassToaster.show(message.ifBlank { "签到成功" })
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failDetail(e)
                        }
                    }
                }
            },
            onDismiss = { scanConfirm = null },
        )
    }

    // 剪贴板提示：用户确认后才处理（绝不静默）
    clipboardPrompt?.let { link ->
        SystemConfirmDialog(
            title = "发现第二课堂内容",
            text = when (link) {
                is SecondClassDeepLink.Activity -> "剪贴板里有一个活动链接，要打开活动详情吗？"
                is SecondClassDeepLink.Scan -> "剪贴板里有一个签到码，要去签到吗？"
                SecondClassDeepLink.None -> ""
            },
            confirmText = "打开",
            onConfirm = {
                clipboardPrompt = null
                when (link) {
                    is SecondClassDeepLink.Activity -> openActivityId.value = link.activityId
                    is SecondClassDeepLink.Scan -> pendingScanPayload = link.payload
                    SecondClassDeepLink.None -> Unit
                }
            },
            onDismiss = { clipboardPrompt = null },
        )
    }
}
