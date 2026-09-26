package com.hnnujw.course.ui.route

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hnnujw.course.AppTabNavigation
import com.hnnujw.course.MessageCenterActivity
import com.hnnujw.course.academic.MessageCenterManager
import com.hnnujw.course.manager.GradesCacheManager
import com.hnnujw.course.manager.StartupPage
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.schedule.ScheduleRepository
import com.hnnujw.course.secondclass.SecondClassOverviewCache
import com.hnnujw.course.secondclass.SecondClassroomRepository
import com.hnnujw.course.secondclass.SecondClassroomStore
import com.hnnujw.course.ui.screen.WidgetBoardScreen
import com.hnnujw.course.ui.system.GlassSubpage
import com.hnnujw.course.widgetboard.WidgetBoardData
import com.hnnujw.course.widgetboard.examCardData
import com.hnnujw.course.widgetboard.gradesCardData
import com.hnnujw.course.widgetboard.messagesCardData
import com.hnnujw.course.widgetboard.scheduleCardData
import com.hnnujw.course.widgetboard.secondClassCardData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 组件工作台的页面宿主与数据装配。
 *
 * 走 [GlassSubpage]（页面级 Dialog），**不占底栏 Tab** —— 排期上它更像"课表的一个
 * 延伸视图"而不是第六个一级入口，底栏也没地方再塞一个了。
 *
 * ## 数据来源全部是**现有缓存**，不新开接口
 * - 课表：[ScheduleRepository.snapshot] → [scheduleCardData]，与桌面组件同一口径
 * - 考试 / 成绩：[GradesCacheManager]（一个类同时缓存了两者）
 * - 消息：`MessageCenterManager.readCache`
 * - 二课积分：`SecondClassOverviewCache`，本页在后台补一次刷新
 *
 * 所以打开工作台**不会**触发任何教务请求：有没有网、有没有登录态，都能立刻
 * 看到上一次的数据，卡片各自显示自己的空态。
 */
@Composable
fun WidgetBoardRoute(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val user = remember { UserManager.getInstance() }
    val accountKey = remember { user.currentAccountStorageKey }
    val school = user.currentSchool
    val isDemo = user.isDemoMode

    val sessions = user.sessionState
    val session by sessions.state.collectAsState()

    // 与桌面组件 `activeSnapshot` 完全相同的可用性判据：会话 token 必须属于当前
    // 账号，且这个账号真的在保存列表里。少了这一层，切账号瞬间会读到上一个账号的课表。
    val sessionUsable = remember(session, accountKey) {
        !user.isDemoMode &&
            (user.isLoggedIn || (session.expired && user.hasSavedCookie())) &&
            session.token.accountStorageKey == user.currentAccountStorageKey &&
            user.savedAccounts.any { it.key == user.currentAccountKey }
    }

    // "现在"。每 30 秒自己往前走一步，让「正在上课 / 还剩几堂」不靠用户操作也会更新。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    // ── 课表 ────────────────────────────────────────────────────────────────
    val snapshot = remember(accountKey, school?.id, sessionUsable) {
        val target = school
        if (!sessionUsable || target == null) null
        else ScheduleRepository(context).snapshot(accountKey, target.id)
    }
    // 时间轴最多列 6 节：最高的卡片（两行高）只放得下 5 行，多备一条给低字体缩放的设备。
    val schedule = remember(snapshot, now) { scheduleCardData(snapshot, now, timelineLimit = 6) }

    // ── 成绩 / 考试 ─────────────────────────────────────────────────────────
    val gradesCache = remember(accountKey) {
        if (accountKey.isBlank()) null else GradesCacheManager.load(context, accountKey)
    }
    val grades = remember(gradesCache) { gradesCardData(gradesCache?.report, gradesCache?.fetchedAt ?: 0L) }
    val exam = remember(gradesCache, now) { examCardData(gradesCache?.exams.orEmpty(), now) }

    // ── 消息 ────────────────────────────────────────────────────────────────
    var messages by remember(accountKey) { mutableStateOf(messagesCardData(null)) }
    LaunchedEffect(accountKey) {
        // 先按缓存把红点点亮（离线也有未读数），再读一遍列表给卡片用。
        MessageCenterManager.refreshUnreadFromCache(context, accountKey)
        messages = withContext(Dispatchers.IO) {
            messagesCardData(MessageCenterManager.readCache(context, accountKey))
        }
    }

    // ── 第二课堂积分（缓存优先 + 后台补一次）────────────────────────────────
    var secondClass by remember(accountKey) {
        mutableStateOf(SecondClassOverviewCache.load(context, accountKey))
    }
    val client = remember(accountKey, school?.id) {
        if (isDemo) null else SecondClassroomStore.clientFor(school)
    }
    val secondClassToken = remember(accountKey) {
        SecondClassroomStore.token(context, accountKey)
    }
    LaunchedEffect(accountKey, client, secondClassToken) {
        val c = client ?: return@LaunchedEffect
        if (secondClassToken.isBlank()) return@LaunchedEffect
        if (!SecondClassOverviewCache.isStale(secondClass?.updatedAt ?: 0L)) return@LaunchedEffect
        val snapshot = runCatching {
            withContext(Dispatchers.IO) {
                SecondClassroomRepository.overview(c, secondClassToken)
            }
        }.getOrNull() ?: return@LaunchedEffect  // 失败保持旧缓存，不打回空白
        // 单位（hourUnit）必须与模块一起落盘：它是站点下发的，渲染层无从推导，
        // 不存就只能写死一个单位，在学分制/分数制的学校上会显示错。
        SecondClassOverviewCache.save(context, accountKey, snapshot.modules, snapshot.profile.hourUnit)
        secondClass = SecondClassOverviewCache.Cached(
            snapshot.modules, System.currentTimeMillis(), snapshot.profile.hourUnit,
        )
    }
    val secondClassBound = secondClassToken.isNotBlank()

    val data = WidgetBoardData(
        now = now,
        schedule = schedule,
        exam = exam,
        grades = grades,
        secondClass = secondClassCardData(
            bound = secondClassBound,
            modules = secondClass?.modules.orEmpty(),
            hourUnit = secondClass?.hourUnit.orEmpty(),
            updatedAt = secondClass?.updatedAt ?: 0L,
        ),
        messages = messages,
    )

    /** 点卡片 → 落到对应的一级页；先关工作台再跳，否则 Dialog 会盖在新页上面。 */
    fun open(sourceId: String) {
        when (sourceId) {
            "schedule.next", "schedule.today", "schedule.timeline" -> {
                AppTabNavigation.request(StartupPage.Schedule)
                // 与桌面卡片**同一条语义**（见 CardWidgetNavigation.resetsToToday）：
                // 点课表类卡片的意思是"看现在"，不是"回到上次翻到的那一周"。
                // 少了这一句，应用内点卡片会落在几天前的那一页上，而桌面上点同一张
                // 卡片却会复位 —— 同一张卡片在两处表现不一致，且发版说明里承诺的
                // "自动回到本周今天"只对桌面成立。
                AppTabNavigation.requestToday()
            }
            "exam.countdown", "grades.overview" ->
                AppTabNavigation.request(StartupPage.Grades)
            "secondclass.overview" ->
                AppTabNavigation.request(StartupPage.SecondClass)
            "message.unread" -> {
                onDismiss()
                context.startActivity(Intent(context, MessageCenterActivity::class.java))
                return
            }
        }
        onDismiss()
    }

    GlassSubpage(onDismiss = onDismiss) { close ->
        WidgetBoardScreen(
            data = data,
            onOpen = { sourceId, _ -> open(sourceId) },
            // 逐行点击：点某门课 = 跳到课表、复位今天、打开这门课。
            // 与桌面卡片逐行点击同一套语义（见 CardWidgetNavigation / AppTabNavigation.openCourse）。
            // 必须先请求、再关弹层：课表与课程详情在弹层**背后**已经挂载，
            // 不关掉就看不见（整卡点击的 open() 末尾也是 onDismiss()，这里必须对称）。
            onCourseOpen = { courseId ->
                AppTabNavigation.openCourse(courseId)
                onDismiss()
            },
            onClose = close,
        )
    }
}
