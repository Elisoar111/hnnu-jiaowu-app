package com.hnnujw.course.ui.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import com.hnnujw.course.demo.DemoData
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.secondclass.SecondClassRankBoard
import com.hnnujw.course.secondclass.SecondClassRankLevel
import com.hnnujw.course.secondclass.SecondClassSnapshot
import com.hnnujw.course.secondclass.SecondClassroomRepository
import com.hnnujw.course.secondclass.SecondClassroomStore
import com.hnnujw.course.ui.screen.SecondClassLoginHost
import com.hnnujw.course.ui.screen.SecondClassTranscriptScreen
import com.hnnujw.course.ui.screen.SecondClassroomUi
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.SystemEmptyState
import com.hnnujw.course.ui.system.SystemLoadingState
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.rememberPageData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 二课页的数据装配。
 *
 * 两种形态共用同一条加载链路：
 * - 默认（底栏「二课」Tab）：**直达活动中心**（[SecondClassActivityCenterRoute]，无前置主页）；
 *   未绑定时显示极简引导页并自动拉起登录框；
 * - [transcriptOnly]（「我的 → 第二课堂成绩单」子页）：成绩单表头、模块情况与排行榜。
 *
 * 与教务页不同，这里的会话是**独立**的：教务 Cookie 还在有效期内，第二课堂的
 * access_token 也可能已经过期。所以 token 失效时只清第二课堂自己的凭据，
 * 绝不去动教务会话。
 *
 * 若用户存过密码，token 过期会在这里静默重新登录一次；没存密码就退回"未绑定"。
 */
@Composable
fun SecondClassroomRoute(
    transcriptOnly: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val userManager = remember { UserManager.getInstance() }
    val accountKey = userManager.currentAccountKey
    val school = userManager.currentSchool
    val isDemo = userManager.isDemoMode
    val available = if (isDemo) true else SecondClassroomStore.isAvailable(school)

    var snapshot by rememberPageData("secondclass.snapshot") { SecondClassSnapshot() }
    var boards by rememberPageData("secondclass.boards") {
        emptyMap<SecondClassRankLevel, SecondClassRankBoard>()
    }
    var bound by remember(accountKey) { mutableStateOf(false) }
    var loading by remember(accountKey) { mutableStateOf(available) }
    var refreshing by remember(accountKey) { mutableStateOf(false) }
    var error by remember(accountKey) { mutableStateOf("") }
    var revision by remember(accountKey) { mutableIntStateOf(0) }
    var level by rememberSaveable(accountKey) { mutableStateOf(SecondClassRankLevel.Classmates) }
    var boardLoading by remember(accountKey) { mutableStateOf(false) }
    var boardError by remember(accountKey) { mutableStateOf("") }
    var showLogin by remember { mutableStateOf(false) }
    /** 「成绩单」Tab 是否被打开过：只在打开后才去拉二课快照与榜单。 */
    var transcriptRequested by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(accountKey, revision, available, transcriptRequested) {
        if (isDemo) {
            // 演示模式：跳过第二课堂网络，直接喂一份虚构快照（不含真实个人信息）。
            val demo = DemoData.secondClassroomSnapshot()
            snapshot = demo
            boards = demo.boards
            bound = true
            loading = false
            refreshing = false
            return@LaunchedEffect
        }
        if (!available) {
            loading = false
            refreshing = false
            bound = false
            return@LaunchedEffect
        }
        val client = SecondClassroomStore.clientFor(school) ?: run {
            loading = false
            refreshing = false
            return@LaunchedEffect
        }
        if (revision == 0 && snapshot.profile.hasIdentity) {
            // 切回来时先用上一次的结果渲染，后台再刷新
            loading = false
        }
        error = ""
        // 是否在本轮里发起了静默登录（下面 catch 里用来决定要不要拉起登录框）
        var attemptedSilentLogin = false
        try {
            var token = SecondClassroomStore.token(context, accountKey)
            if (token.isBlank()) {
                // 新用户（或换账号）从没登录过二课：先用**没有存过的默认初始密码**
                // （学号 + &Zhtx，学校统一规则）自动登录一次，绝大多数同学零操作；
                // 存过密码就静默续登。默认密码被拒（在官网改过密码）时由 catch
                // 拉起登录框，让用户输入真正的密码 —— 不该让用户对着一张错误卡片发呆。
                val saved = SecondClassroomStore.loadPassword(context, accountKey)
                val password = saved?.takeIf { it.isNotBlank() }
                    ?: SecondClassroomStore.defaultPassword(SecondClassroomStore.academicStudentId())
                if (password.isBlank()) {
                    bound = false
                    return@LaunchedEffect
                }
                attemptedSilentLogin = true
                // 隐私红线：密码不落日志，只在内存里走这一次登录
                token = withContext(Dispatchers.IO) {
                    client.login(SecondClassroomStore.academicStudentId(), password)
                }
                SecondClassroomStore.saveToken(context, accountKey, token)
                // 登录成功才落库这份密码，后续静默续登有据可依；
                // 用户之后改密码导致续登失败，同样会由 catch 拉起登录框更新。
                SecondClassroomStore.savePassword(context, accountKey, password)
                // 登录已成功，后续 overview 等失败只是网络问题，不该误弹登录框
                attemptedSilentLogin = false
            }
            bound = true
            // 活动中心 Tab 用不到快照/榜单，跳过 overview 省一次请求；
            // 只有「成绩单」被打开过（或本来就是成绩单子页）才拉。
            if (transcriptOnly || transcriptRequested) {
                // 先点亮榜单的加载态，避免"正在读取"与"暂无数据"之间闪一帧
                if (boards[level] == null) boardLoading = true
                val loaded = withContext(Dispatchers.IO) { SecondClassroomRepository.overview(client, token) }
                snapshot = loaded
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bound = false
            snapshot = SecondClassSnapshot()
            boards = emptyMap()
            error = SecondClassroomStore.handleFailure(context, accountKey, e)
            // 静默登录被拒（多半是默认密码不对 / 用户改过密码）：
            // 直接拉起登录框让用户输入正确密码，输入框已预填默认密码可覆盖。
            if (attemptedSilentLogin) showLogin = true
        } finally {
            if (coroutineContext[Job]?.isActive == true) {
                loading = false
                refreshing = false
            }
        }
    }

    LaunchedEffect(accountKey, level, revision, bound) {
        // 排行榜只在成绩单里展示；活动中心 Tab 用不到，除非成绩单 Tab 被打开过
        if (!transcriptOnly && !transcriptRequested) return@LaunchedEffect
        if (!available || !bound) return@LaunchedEffect
        if (boards[level] != null) return@LaunchedEffect
        val client = SecondClassroomStore.clientFor(school) ?: return@LaunchedEffect
        val token = SecondClassroomStore.token(context, accountKey)
        if (token.isBlank()) return@LaunchedEffect
        boardLoading = true
        boardError = ""
        try {
            val board = withContext(Dispatchers.IO) {
                // 专业/院系/全校只看我的名次，不拉整份名单（界面也不展示）
                if (level == SecondClassRankLevel.Classmates) {
                    SecondClassroomRepository.board(client, token, level)
                } else {
                    SecondClassroomRepository.myRank(client, token, level)
                }
            }
            boards = boards + (level to board)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            boardError = SecondClassroomStore.handleFailure(context, accountKey, e)
        } finally {
            if (coroutineContext[Job]?.isActive == true) boardLoading = false
        }
    }

    if (transcriptOnly) {
        SecondClassTranscriptScreen(
            ui = SecondClassroomUi(
                available = available,
                bound = bound,
                loading = loading,
                refreshing = refreshing,
                error = error,
                snapshot = snapshot.copy(boards = boards),
                level = level,
                boardLoading = boardLoading,
                boardError = boardError,
                // 直接读全局 state：拨动开关后整页重组，榜单随之显隐
                showClassRank = com.hnnujw.course.manager.AppearanceSettingsManager.showClassRank,
            ),
            onBind = { showLogin = true },
            onRefresh = {
                refreshing = true
                boards = emptyMap()
                revision++
            },
            onLevelSelect = { level = it },
            onShowClassRankChange = {
                com.hnnujw.course.manager.AppearanceSettingsManager.updateShowClassRank(it)
            },
            onClose = onClose,
        )
    } else if (available && bound) {
        // 底栏「二课」Tab：直接进活动中心；「成绩单」是它的第 4 个 Tab（在「消息」右边），
        // 内容与数据链路都由这里注入 —— 二课的会话/快照只有本函数手上有。
        SecondClassActivityCenterRoute(
            onClose = null,
            // 凭据失效时「去绑定」直接复用宿主手里的登录弹窗，不需要另开一条登录链路
            onBindSecondClass = { showLogin = true },
            hostRevision = revision,
            onTranscriptTabSelected = { transcriptRequested = true },
            onTranscriptRefresh = {
                refreshing = true
                boards = emptyMap()
                revision++
            },
            transcriptContent = {
                SecondClassTranscriptScreen(
                    ui = SecondClassroomUi(
                        available = available,
                        bound = bound,
                        loading = loading,
                        refreshing = refreshing,
                        error = error,
                        snapshot = snapshot.copy(boards = boards),
                        level = level,
                        boardLoading = boardLoading,
                        boardError = boardError,
                        showClassRank = com.hnnujw.course.manager.AppearanceSettingsManager.showClassRank,
                    ),
                    onBind = { showLogin = true },
                    onRefresh = {
                        refreshing = true
                        boards = emptyMap()
                        revision++
                    },
                    onLevelSelect = { level = it },
                    onShowClassRankChange = {
                        com.hnnujw.course.manager.AppearanceSettingsManager.updateShowClassRank(it)
                    },
                    embedded = true,
                )
            },
        )
    } else {
        // 未绑定的极简引导页（不再是原二课主页）
        SecondClassBindPrompt(
            loading = loading,
            isDemo = isDemo,
            onBind = { showLogin = true },
        )
    }

    if (!transcriptOnly) {
        // 未绑定（且不是演示模式）时自动拉起登录框；用户取消后不重复弹，除非状态再变
        LaunchedEffect(loading, bound, available) {
            if (!loading && available && !bound) showLogin = true
        }
    }

    SecondClassLoginHost(
        visible = showLogin,
        onDismiss = { showLogin = false },
        onLoggedIn = {
            showLogin = false
            boards = emptyMap()
            revision++
        },
    )
}

/**
 * 底栏「二课」Tab 未绑定时的极简引导：绑定后直接进活动中心。
 * 演示模式不会走到这里（available && bound 恒真，活动中心自己会提示）。
 */
@Composable
private fun SecondClassBindPrompt(
    loading: Boolean,
    isDemo: Boolean,
    onBind: () -> Unit,
) {
    GlassPageScaffold(title = "第二课堂") { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> SystemLoadingState("正在连接第二课堂…")
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SystemEmptyState(
                        title = "尚未绑定第二课堂",
                        message = "绑定后即可浏览活动中心、报名活动与查看站内消息。",
                    )
                    SystemPrimaryButton(
                        text = if (isDemo) "演示模式" else "去绑定",
                        onClick = onBind,
                        enabled = !isDemo,
                    )
                }
            }
        }
    }
}
