package com.tyust.course.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.tyust.course.demo.DemoData
import com.tyust.course.manager.UserManager
import com.tyust.course.secondclass.SecondClassRankBoard
import com.tyust.course.secondclass.SecondClassRankLevel
import com.tyust.course.secondclass.SecondClassSnapshot
import com.tyust.course.secondclass.SecondClassroomRepository
import com.tyust.course.secondclass.SecondClassroomStore
import com.tyust.course.ui.screen.SecondClassLoginHost
import com.tyust.course.ui.screen.SecondClassroomScreen
import com.tyust.course.ui.screen.SecondClassroomUi
import com.tyust.course.ui.system.rememberPageData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * "二课"页的数据装配。
 *
 * 与教务页不同，这里的会话是**独立**的：教务 Cookie 还在有效期内，第二课堂的
 * access_token 也可能已经过期。所以 token 失效时只清第二课堂自己的凭据，
 * 绝不去动教务会话。
 *
 * 若用户存过密码，token 过期会在这里静默重新登录一次；没存密码就退回"未绑定"。
 */
@Composable
fun SecondClassroomRoute() {
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

    LaunchedEffect(accountKey, revision, available) {
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
        try {
            var token = SecondClassroomStore.token(context, accountKey)
            if (token.isBlank()) {
                val saved = SecondClassroomStore.loadPassword(context, accountKey)
                if (saved.isNullOrBlank()) {
                    bound = false
                    return@LaunchedEffect
                }
                // 有密码就静默续一次，用户不需要重新输
                token = withContext(Dispatchers.IO) {
                    client.login(SecondClassroomStore.academicStudentId(), saved)
                }
                SecondClassroomStore.saveToken(context, accountKey, token)
            }
            bound = true
            // 先点亮榜单的加载态，避免"正在读取"与"暂无数据"之间闪一帧
            if (boards[level] == null) boardLoading = true
            val loaded = withContext(Dispatchers.IO) { SecondClassroomRepository.overview(client, token) }
            snapshot = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bound = false
            snapshot = SecondClassSnapshot()
            boards = emptyMap()
            error = SecondClassroomStore.handleFailure(context, accountKey, e)
        } finally {
            if (coroutineContext[Job]?.isActive == true) {
                loading = false
                refreshing = false
            }
        }
    }

    LaunchedEffect(accountKey, level, revision, bound) {
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

    SecondClassroomScreen(
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
            // 直接读全局 state：在设置页拨动开关，回到二课页立刻生效，无需重建页面
            showClassRank = com.tyust.course.manager.AppearanceSettingsManager.showClassRank,
        ),
        onBind = { showLogin = true },
        onRefresh = {
            refreshing = true
            boards = emptyMap()
            revision++
        },
        onLevelSelect = { level = it },
    )

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
