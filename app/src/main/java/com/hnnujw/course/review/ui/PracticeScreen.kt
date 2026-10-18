package com.hnnujw.course.review.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hnnujw.course.review.ExamTimeoutAction
import com.hnnujw.course.review.PracticeResult
import com.hnnujw.course.review.PracticeSession
import com.hnnujw.course.review.PracticeSummary
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.ReviewPrefs
import com.hnnujw.course.review.ReviewQuestion
import com.hnnujw.course.review.elapsedSeconds
import com.hnnujw.course.review.examTimeouts
import com.hnnujw.course.review.practiceSummaryOf
import com.hnnujw.course.review.remainingSeconds
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassProgressBar
import com.hnnujw.course.ui.system.GlassSubpage
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.rememberGlassAccessibilityMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自动下一题的等待时长（毫秒）。
 *
 * 选完立刻跳的话，"选中"那一下的高亮根本来不及被看见，用户会以为自己点空了；
 * 等太久又会觉得卡。220ms 够看清一次高亮，又不打断连续作答的节奏。
 */
private const val AUTO_NEXT_DELAY_MS = 220L

/** 题间切换过渡的时长（毫秒）。短到不打断作答节奏，长到能看清"换了一题"。 */
private const val QUESTION_TRANSITION_MS = 240

/**
 * 作答快照的写入去抖窗口（毫秒）。
 *
 * 连点选项时只有最后一次会真正编码并写回 saved state。见 PracticeScreen 里
 * 保存效果的注释：丢的只是最近窗口内还没判分的选择，已判分的答案早已落库。
 */
private const val SNAPSHOT_DEBOUNCE_MS = 250L

/**
 * 练习屏：逐题作答。
 *
 * 状态分层刻意做得很死：
 *  - 作答与判分全在 [PracticeSession]（纯逻辑，可单测）；
 *  - 本 Composable 只持有 index / 面板开合这类**界面状态**；AI 状态机在
 *    [rememberPracticeAiController]，答题卡在 [AnswerSheetPage]，总结页在 [SummaryPane]
 *    —— 各自独立文件，本文件只负责把它们拼起来；
 *  - 落库（records、错题本）在判分那一刻做，走 IO。
 */
@Composable
fun PracticeScreen(
    context: Context,
    session: PracticeSession,
    title: String,
    onBack: () -> Unit,
    onFinished: (PracticeResult) -> Unit,
    /**
     * 悬浮球在**还没配 Key** 时被点时调用 —— 用来跳去 AI 设置页。
     *
     * 悬浮球常显（项目红线：入口不按状态隐藏），没配 Key 时点击直接去设置页，
     * 用户才不会对着一个点了没反应的球发呆。
     */
    onOpenAiSettings: () -> Unit = {},
    /** 「继续上次练习」带回来的题号与作答快照；新开一轮时保持默认。 */
    initialIndex: Int = 0,
    initialSnapshot: String = "",
    /** 作答进度变化时回调（用于把进度写到本地存档，支持"继续上次练习"）。 */
    onProgress: (index: Int, snapshot: String) -> Unit = { _, _ -> }
) {
    val db = remember { ReviewDatabase.get(context) }
    val scope = rememberCoroutineScope()
    val ai = rememberPracticeAiController(context)
    // 系统开了"减弱动效"时，题间切换退化成瞬时淡入，不做滑动。
    val reduceMotion = rememberGlassAccessibilityMode().reduceMotion

    // ── 抗进程死亡 ────────────────────────────────────────────────────────
    // 题号走 rememberSaveable：进程被系统回收后重建时，用户回到的是**同一题**，
    // 而不是被踢回第一题。以前全模块没有一个 rememberSaveable，切后台久了就全丢。
    var index by rememberSaveable { mutableIntStateOf(initialIndex) }
    // 作答与判分状态：PracticeSession 是内存对象，进程死了就没了。这里把它的快照
    // （JSON，见 PracticeSession.exportState）一并存进 saved state，重建会话后灌回去。
    var answersSnapshot by rememberSaveable { mutableStateOf(initialSnapshot) }
    // 临时 UI 状态收进 PracticeUiState（分层说明见 PracticeUiState.kt）：
    // 这些值切页即丢、进程被杀不救，与上面两个 rememberSaveable 是两类东西。
    val ui = rememberPracticeUiState(autoNextDefault = ReviewPrefs.isAutoNext(context))

    // ── 提交与总结页 ──────────────────────────────────────────────────────
    // 非 null 即表示"已提交"，整屏换成总结页。**刻意留在本 Composable 内**而不是
    // 加一个 ReviewPage：总结页要显示本次答题明细，而明细只有这个会话知道；
    // 上抛成新页面就得把整个 session 也传出去，等于把会话的生命周期摊到宿主里。
    var summary by remember { mutableStateOf<PracticeSummary?>(null) }

    /**
     * 批量落库：整批一个事务。交卷补判 N 题不再起 N 个协程开 N 个独立事务。
     * （局部函数不能前向引用，所以它声明在单题便捷入口 [record] 之前。）
     *
     * ⚠️ 背题模式（session.recordsAnswers == false）**不落库** —— 背题不计入统计，
     * 否则背一遍就会把题洗成"已掌握"。
     *
     * 🔴 **同一道题在一次会话里只落库一次**：`records` 是累加计数，
     * 「确认答案 → 再想一下 → 再确认」会把同一题记两遍，`wrong_book.streak` 也会被
     * 单次会话刷满（点三次就能把一道题刷成「已掌握」）。去重判据在
     * [PracticeSession.markRecorded]，它随快照一起恢复，进程重建后同样生效。
     */
    fun recordAll(items: List<Pair<ReviewQuestion, Boolean>>) {
        if (!session.recordsAnswers) return
        val firstTime = items.filter { (q, _) -> session.markRecorded(q) }
        if (firstTime.isEmpty()) return
        val records = firstTime.map { (q, correct) -> ReviewDatabase.AnswerRecord(q.bankId, q.id, correct) }
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { db.recordAnswers(records) }
            }.isSuccess
            if (!ok && !ui.recordFailureShown) {
                ui.recordFailureShown = true
                GlassToaster.show("作答记录保存失败，请检查存储空间")
            }
        }
    }

    /** 判分即落库：答对答错都要记，否则统计和错题本会漏。 */
    fun record(q: ReviewQuestion, correct: Boolean) = recordAll(listOf(q to correct))

    // 提交（= 旧的「完成」）。顺序 / 乱序 / 考试都走这里；背题模式不判分，
    // 仍走「完成」直接返回，见底部导航。
    //
    // 用时按**墙钟差值**算，与倒计时同一口径：进程被杀后重建会话会沿用原来的
    // startedAt，所以中途被系统回收也算得进来。
    fun submit(timedOut: Boolean = false) {
        // 🔴 交卷时把"选了答案但没点确认答案"的题一并判分（2026-10 用户要求）。
        //
        // 起因：用户选了选项就点「下一题」往下走，交卷后总结页把这些题报成「未作答」、
        // 正确率也不对。根因是「确认答案」被当成了"这题算不算数"的开关 —— 它其实只是
        // "现在揭晓解析"。补判的这批同样要**落库**，否则交卷后数字变了、错题本却没记住。
        val newlyJudged = session.judgePending()
        if (newlyJudged.isNotEmpty()) {
            ui.answerVersion++
            // 补判的这批一次批量落库（单事务），不再每题一个协程一个事务。
            recordAll(newlyJudged.map { q -> q to session.isCorrect(q) })
        }
        summary = practiceSummaryOf(
            result = session.result(),
            elapsedSeconds = elapsedSeconds(session.startedAt, System.currentTimeMillis()),
            durationSeconds = session.timing.durationSeconds,
            timedOut = timedOut
        )
    }

    // ── 限时考试：到点自动交卷 + 两次警告 ────────────────────────────────
    //
    // 🔴 这条循环**不再持有"剩余秒数"状态**：剩余时间的显示下沉到了
    // [ExamCountdownText]（每秒只重组那一行小字）。这里只在真正有动作时才碰界面 ——
    // 以前 remaining 是顶层状态，每秒一次让整个 PracticeScreen 陪着重启。
    // 仍按墙钟差值算（后台回来不会漂），循环对齐到秒边界避免忙等。
    LaunchedEffect(session.timing.durationSeconds, session.startedAt) {
        if (!session.timing.isTimed) return@LaunchedEffect
        ui.resetTimeWarnings()
        val total = session.timing.durationSeconds
        val startedAt = session.startedAt.takeIf { it > 0L }
            // startedAt 未设定（<= 0）时以进入本屏为起点，至少让倒计时能走。
            ?: System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val elapsedSec = ((now - startedAt) / 1000L).toInt()
            val left = remainingSeconds(total, elapsedSec)
            val actions = examTimeouts(left, ui.warnedFive, ui.warnedOne)
            var submitted = false
            for (action in actions) {
                when (action) {
                    ExamTimeoutAction.Submit -> {
                        // 到点自动交卷：走与手动提交**完全同一条路径**（submit 里会先
                        // judgePending 把"选了没确认"的题补判，没碰过的题才计为未作答）。
                        //
                        // 🔴 交卷也走**总结页**（而不是直接弹回题库）：限时考试到点被
                        // 强制收卷的用户，比任何人都更需要立刻看到"我答了多少、对了多少"。
                        GlassToaster.show("时间到，已自动交卷")
                        submit(timedOut = true)
                        submitted = true
                    }
                    ExamTimeoutAction.WarnFiveMinutes -> {
                        ui.warnedFive = true
                        GlassToaster.show("还剩 5 分钟")
                    }
                    ExamTimeoutAction.WarnOneMinute -> {
                        ui.warnedOne = true
                        GlassToaster.show("还剩 1 分钟")
                    }
                }
            }
            if (submitted) return@LaunchedEffect
            delay(1000L - (now % 1000L))
        }
    }

    val question = session.questions.getOrNull(index)
    // 是否最后一题。**刻意提到这里算**：题干卡片里的「下一题」与底部常驻栏的「提交」
    // 都要用它，两处各算一次迟早会分叉。
    val isLast = index >= session.total - 1

    // 自动下一题：选完选项后**稍等一下**再跳。立刻跳的话"选中"那一下的高亮根本来不及
    // 被看见，用户会以为自己点空了。
    //
    // 🔴 落跳前**必须再确认一次**，不能无脑 index++：等待的这段时间里用户可能已经
    // ① 改选了别的选项（信号自增 ⇒ 本效果重启 ⇒ 重新计时，所以不会连跳）
    // ② 把开关关掉了 ③ 手动翻了页 ④ 点了提交。
    LaunchedEffect(ui.autoAdvanceTick) {
        if (ui.autoAdvanceTick == 0) return@LaunchedEffect
        delay(AUTO_NEXT_DELAY_MS)
        if (ui.autoNext && summary == null && index < session.total - 1) index++
    }

    // 换题时重置本题的界面状态：收藏读库、AI 弹窗收起、**对话历史清空**、累计用量归零；
    // 同时把内容区滚回顶部 —— 长题滚到中段再翻页，新题不该从屏幕中间开始看。
    val scrollState = rememberScrollState()
    LaunchedEffect(index) {
        val q = session.questions.getOrNull(index) ?: return@LaunchedEffect
        ui.favorite = withContext(Dispatchers.IO) { db.isFavorite(q.bankId, q.id) }
        ui.showAi = false
        ai.resetForNewQuestion(keepHistory = ReviewPrefs.isKeepAiHistory(context))
        scrollState.scrollTo(0)
    }

    // ── 抗进程死亡的恢复 / 保存 ───────────────────────────────────────────
    // 恢复：进程被杀后由外层重建出来的会话是空的，把上次的快照灌回去。
    // 只在会话确实"一次都没作答"时灌，避免覆盖用户这一轮已经做的题。
    // ⚠️ 这个效果**必须声明在下面的保存效果之前**：两者都在首帧启动，
    // 先恢复后保存才不会把待恢复的快照覆盖成空。
    LaunchedEffect(session) {
        if (ui.restored) return@LaunchedEffect
        ui.restored = true
        if (answersSnapshot.isNotEmpty() &&
            session.answeredCount == 0 && session.judgedCount == 0
        ) {
            session.restoreState(answersSnapshot)
            // 自增版本号，逼 QuestionCard / 答题卡按恢复后的作答重算
            // （PracticeSession 内部是普通 Map，改它不会触发重组）。
            ui.answerVersion++
        }
    }

    // 保存：作答相关操作（ui.answerVersion 自增）后写一次快照。
    //
    // ⚠️ 不允许把"新会话的空快照"覆盖掉待恢复的快照 —— 上面那条顺序虽然成立，
    // 但不该依赖它，这里再加一道。
    //
    // 🔴 **必须节流**：`answerVersion` 每点一次选项就 +1，而 `exportState()` 是把
    // 整轮作答与判分重新拼成 JSON（320 题的题库连答 320 次 ≈ 5 万次字符串拼接），
    // 并且每次都要写回 `rememberSaveable`。这里用「重启即取消」的 delay 做去抖：
    // 连点期间只有最后一次会真正编码。
    //
    // 丢的只有最近 250ms 内的**选择**（还没判分的那一下）；已判分的答案在判分那一刻
    // 就落了库，不受影响。附带好处：恢复效果一定先于保存效果执行，不再依赖声明顺序。
    LaunchedEffect(ui.answerVersion, index, session) {
        if (ui.answerVersion > 0) delay(SNAPSHOT_DEBOUNCE_MS)
        val encoded = session.exportState()
        if (encoded.isNotEmpty() || answersSnapshot.isEmpty()) answersSnapshot = encoded
        // 同步写一份到本地存档：「继续上次练习」靠它，而不只依赖 saved state
        // （主动退回首页不销毁 Activity，saved state 并不会被创建）。
        onProgress(index, encoded)
    }

    // 已提交 → 整屏换成总结页。
    //
    // 🔴 总结页放在**本 Composable 内部**，不加 `ReviewPage`：它要显示本次的逐题
    // 明细，而明细只有这个会话知道。上抛成新页面就得把整个 session 也传出去，
    // 等于把会话的生命周期摊到宿主里 —— 而宿主现在只在开练/退出时碰会话。
    //
    // `question == null`（题号越界）是防御分支，正常流程到不了。以前它单独渲染一个
    // 结果页，现在与「提交后」共用同一个总结页 —— 少一套口径，也少一处会写错的地方。
    val submitted = summary
    if (submitted != null || question == null) {
        val shown = submitted ?: practiceSummaryOf(
            result = session.result(),
            elapsedSeconds = elapsedSeconds(session.startedAt, System.currentTimeMillis()),
            durationSeconds = session.timing.durationSeconds
        )
        SummaryPane(
            summary = shown,
            cells = answerSheetCells(session),
            onDone = { onFinished(session.result()) }
        )
        return
    }

    // 悬浮球与整页同层：球浮在练习页之上（含底栏区域），拖拽范围即本 Box。
    Box(Modifier.fillMaxSize()) {
    GlassPageScaffold(
        title = title,
        subtitle = if (session.total == 0) {
            null
        } else {
            "第 ${index + 1} / ${session.total} 题 · 正确率 ${(session.accuracy * 100).toInt()}%"
            // 倒计时不再放 subtitle：它每秒变一次，留在顶层标题里整个页面陪着闪。
            // 显示在下面进度条一行的行尾（ExamCountdownText），只重组它自己。
        },
        onBack = onBack
    ) { padding ->

        // 「先撑满、放不下才滚动」+ 底栏常驻（2026-10 用户要求）。
        //
        // 外层只负责撑满整屏并吃掉 scaffold 的底部 inset；内容区用 weight(1f) 吃掉
        // 剩余高度、装不下才滚动；底部「上一题 / 下一题」**常驻屏幕底部**。
        //
        // 之前整页是**一个** verticalScroll 的 Column，底部那行也在滚动列里 ——
        // 题干一长就被顶到屏幕外，用户必须先滑到底才能翻页。
        //
        // ⚠️ 同轴滚动容器**仍然只有这一个**：weight(1f) 给的是确定高度，不是
        // Constraints.Infinity，所以不会触发"点开始练习退回课表页"那个崩溃。
        // 反过来，**绝不要**再给外层 Column 加 verticalScroll —— 那就成了同轴嵌套。
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 底部同样要吃掉 scaffold 的 bottom inset，否则最后一行（上一题/下一题）
                // 会被系统导航条压住 —— 与题库详情页同一个坑。
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 8.dp
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                // 进度条：走玻璃件的弹簧动画，与课表/成绩页同一条观感。
                //
                // 🔴 显示的是**作答进度**（已答/总题数），不是正确率 —— 正确率已经
                // 在副标题里了，进度条再放一份既重复又误导：答一半全对时条是满的，
                // 用户会以为"做完了"。限时考试的剩余时间显示在同一行行尾。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassProgressBar(
                        progress = if (session.total == 0) 0f
                        else session.answeredCount.toFloat() / session.total,
                        modifier = Modifier.weight(1f),
                        height = 4.dp
                    )
                    if (session.timing.isTimed) {
                        ExamCountdownText(
                            totalSeconds = session.timing.durationSeconds,
                            startedAt = session.startedAt
                        )
                    }
                }

                // 答题卡：点一下打开独立页面跳题（2026-10 用户要求改成弹窗形态）。
                //
                // 🔴 从"页内 FlowRow 折叠"改成独立页，直接消灭了一个结构性风险：页内版本
                // 处在 `Column(Modifier.verticalScroll(...))` 里，任何同轴懒加载容器都会收到
                // `maxHeight = Infinity` 并在测量阶段抛异常崩进程（就是"点开始练习退回课表页"
                // 那个事故）。独立页里没有外层滚动，于是可以用 `LazyVerticalGrid` 真正懒加载 ——
                // 内置题库最大 320 题，页内版本必须一次性测量全部格子。
                AnswerSheetTrigger(
                    session = session,
                    version = ui.answerVersion,
                    autoNext = ui.autoNext,
                    onAutoNextChange = { enabled ->
                        ui.autoNext = enabled
                        ReviewPrefs.setAutoNext(context, enabled)
                    },
                    onOpen = { ui.showAnswerSheet = true }
                )

                if (ui.showAnswerSheet) {
                    GlassSubpage(onDismiss = { ui.showAnswerSheet = false }) { close ->
                        AnswerSheetPage(
                            session = session,
                            version = ui.answerVersion,
                            currentIndex = index,
                            // 背题模式不判分，提交没有意义 —— 页内改成一句说明而不是
                            // 一个点了没反应的灰按钮（本项目红线：入口常显，但禁用必须给原因）。
                            memorize = session.memorize,
                            onSubmit = {
                                close()
                                submit()
                            },
                            onJump = { target ->
                                index = target
                                close()
                            }
                        )
                    }
                }

                // 题间切换过渡：新题从行进方向滑入（前进从右、后退从左），旧题滑出。
                // 此前换题是内容瞬时替换 —— 长题干接短题干时视觉跳变非常突兀，
                // 而这个模块明明做了精心设计的入场动画，题与题之间却是"闪切"。
                // 幅度刻意只有 1/4 屏宽：这是"翻到下一题"的位移暗示，不是翻页特效。
                // 系统开了"减弱动效"时退化成瞬时淡入。
                AnimatedContent(
                    targetState = index,
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn(tween(1)) togetherWith fadeOut(tween(1))
                        } else {
                            val forward = targetState > initialState
                            val enter = slideInHorizontally(
                                animationSpec = tween(QUESTION_TRANSITION_MS, easing = FastOutSlowInEasing)
                            ) { full -> if (forward) full / 4 else -full / 4 } + fadeIn(tween(QUESTION_TRANSITION_MS))
                            val exit = slideOutHorizontally(
                                animationSpec = tween(QUESTION_TRANSITION_MS, easing = FastOutSlowInEasing)
                            ) { full -> if (forward) -full / 4 else full / 4 } + fadeOut(tween(QUESTION_TRANSITION_MS))
                            enter togetherWith exit
                        }
                    },
                    label = "question"
                ) { questionIndex ->
                    val current = session.questions.getOrNull(questionIndex)
                        ?: return@AnimatedContent
                    QuestionCard(
                        question = current,
                        session = session,
                        version = ui.answerVersion,
                        onSelect = { letter ->
                            session.select(current, letter)
                            ui.answerVersion++
                            // 自动下一题：只有"能自动跳的题"才起跳 —— 判据在
                            // PracticeSession.canAutoAdvance（背题 / 多选 / 开关关着都不跳），
                            // 最后一题则无处可跳。
                            if (!isLast && session.canAutoAdvance(current, ui.autoNext)) {
                                ui.autoAdvanceTick++
                            }
                        },
                        onConfirm = {
                            val correct = session.judge(current)
                            ui.answerVersion++
                            record(current, correct)
                        },
                        onUnjudge = {
                            session.unjudge(current)
                            ui.answerVersion++
                        },
                        isFavorite = ui.favorite,
                        onToggleFavorite = {
                            // 先翻转本地状态给即时反馈，再落库。
                            ui.favorite = !ui.favorite
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    db.toggleFavorite(current.bankId, current.id)
                                }
                            }
                        },
                        memorize = session.memorize,
                        // 限时考试中途不揭晓对错与解析：看到"答错了 + 正确答案"就等于
                        // 把答案提前给了考生，后面几题的正确率会被污染。
                        hideVerdict = session.timing.isTimed
                    )
                }
            }

            // 底部导航：上一题 / 下一题 / 提交。**常驻**在屏幕底部，不随题干滚动 ——
            // 这是「先撑满、放不下才滚动」在练习页上的落点：内容区吃掉剩余高度，
            // 翻页按钮永远在手边。
            //
            // 🔴 最后一题在顺序 / 乱序 / 考试下是「提交」：它不再直接退回题库，而是进
            // 总结页（2026-10 用户要求）。背题模式（不判分）仍叫「完成」并直接返回 ——
            // 对着一个没有分数的会话说"提交"是假话。
            // 「提交」走主按钮样式，与「下一题」拉开层级：它是这一轮唯一的终点。
            //
            // 🔴 右侧那颗会在这三种之间切换（下一题 / 完成 / 提交），**高度必须跟着变**：
            // 主按钮 48dp、次级 42dp，而 Row 默认按顶部对齐 —— 不显式抬高次级那颗，
            // 底部就会露出参差的一截（2026-10 按钮排版统一时修）。
            val canSubmit = !session.memorize && isLast
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SecondaryAction(
                    text = "上一题",
                    enabled = index > 0,
                    onClick = { index-- },
                    height = ConfirmButtonHeight,
                    modifier = Modifier.weight(1f)
                )
                if (canSubmit) {
                    ConfirmButton(
                        text = "提交",
                        enabled = true,
                        onClick = { submit() },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    SecondaryAction(
                        text = if (isLast) "完成" else "下一题",
                        enabled = true,
                        onClick = {
                            if (isLast) onFinished(session.result()) else index++
                        },
                        height = ConfirmButtonHeight,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

        // AI 悬浮球：常驻练习页、可拖拽、贴边吸附；生成中带进度环（取代旧的全局
        // 「AI 正在生成」胶囊），点击打开 AI 讲解弹窗，未配 Key 时直接去设置页。
        AiFloatingBall(
            busy = ai.busy,
            contentDescription = "AI 讲解",
            onClick = { if (ai.configured) ui.showAi = true else onOpenAiSettings() }
        )
    }

    if (ui.showAi) {
        AiExplainDialog(
            question = question,
            state = ai.state,
            userAnswer = session.answerOf(question),
            aiConfigured = ai.configured,
            history = ai.history,
            totalUsage = ai.usageTotal,
            onAsk = { extra -> ai.ask(question, session.answerOf(question), extra) },
            onOpenSettings = onOpenAiSettings,
            onDismiss = { ui.showAi = false }
        )
    }
}
