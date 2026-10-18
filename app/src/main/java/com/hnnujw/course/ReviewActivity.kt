package com.hnnujw.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.review.ExamTiming
import com.hnnujw.course.review.PracticeLaunch
import com.hnnujw.course.review.PracticeResume
import com.hnnujw.course.review.PracticeSession
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.ReviewMode
import com.hnnujw.course.review.ReviewPrefs
import com.hnnujw.course.review.ui.AiSettingsScreen
import com.hnnujw.course.review.ui.BankDetailScreen
import com.hnnujw.course.review.ui.BankFavoriteScreen
import com.hnnujw.course.review.ui.BankLibraryScreen
import com.hnnujw.course.review.ui.BankQuestionEditScreen
import com.hnnujw.course.review.ui.BankWrongBookScreen
import com.hnnujw.course.review.ui.LocalReviewEntrance
import com.hnnujw.course.review.ui.PracticeScreen
import com.hnnujw.course.review.ui.ReviewHomeScreen
import com.hnnujw.course.review.ui.rememberReviewEntrance
import com.hnnujw.course.ui.system.GlassLoadingState
import com.hnnujw.course.ui.system.GlassToastHost
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.GlassWindowHost
import com.hnnujw.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 题库练习模块宿主（题库仓库 + 练习 + AI 答疑）。
 *
 * 从「我的 → 校园服务 → 题库练习」进入。内部切页走**同一个 composition 里的状态**
 * 而不是再开 Activity：练习会话是内存对象，换 Activity 会被回收，正在做的题就没了。
 */
class ReviewActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserManager.getInstance().init(applicationContext)
        setContent {
            CourseSelectorTheme {
                // 🔴 复习模块此前**没有 overlay 宿主**：`GlassToaster.show()` 只是往一个
                // 全局状态写值，真正渲染它的是 `GlassToastHost`，而全项目只有 MainActivity
                // 挂了宿主 —— 于是复习模块里 23 处提示全部静默失效，包括限时考试的
                // 「时间到，已自动交卷」「还剩 5 分钟」、题库的「已保存」「已删除」、
                // 「导入失败：…」。这里补上根宿主。
                //
                // 挂 GlassWindowHost 而不是只加一个 Box：GlassPageScaffold 见到已有
                // DialogHost 就复用（`if (LocalDialogHost.current == null)`），
                // 整个复习模块因此共用同一套弹窗 / 玻璃折射 / overlay 栈，
                // 与 MainActivity 的结构一致。
                val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                GlassWindowHost(Modifier.fillMaxSize()) {
                    ReviewFlow(onExit = { finish() })
                    GlassToastHost(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = navInset + 24.dp)
                    )
                }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ReviewActivity::class.java))
        }
    }
}

/** 题库练习模块的页面。 */
private sealed interface ReviewPage {
    data object Home : ReviewPage
    data object Practice : ReviewPage
    data object AiSettings : ReviewPage

    /** 某个题库的详情页（练习入口 + 错题本 / 收藏）。 */
    data class BankDetail(val bankId: String) : ReviewPage

    /**
     * 错题本页（查看错题，不是直接开练）。
     *
     * [bankId] 为 null = **跨题库的总错题本**：用户不必先想"这道错题在哪个库里"，
     * 首页给一个总入口。数据层早已支持（`listWrongBook(null)` 走全库 JOIN）。
     */
    data class WrongBook(val bankId: String?) : ReviewPage

    /** 某个题库的收藏页（查看收藏的题，并从那里开练）。 */
    data class Favorites(val bankId: String) : ReviewPage

    /** 题库仓库页（取出 / 导入 / 逐题编辑 / 改名 / 删除）。 */
    data object Library : ReviewPage

    /** 某个题库的逐题编辑页（改题干/选项/答案/解析 + 批量删题）。 */
    data class BankQuestions(val bankId: String) : ReviewPage
}

@Composable
private fun ReviewFlow(
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 会话本身是内存对象，**刻意不** saveable：进程重启后由下面的效果重建。
    var session by remember { mutableStateOf<PracticeSession?>(null) }
    var practiceTitle by rememberSaveable { mutableStateOf("练习") }
    // ── 抗进程死亡：页面与两个"来处" ──────────────────────────────────────
    // 以前这里全是 remember，进程被系统回收后重建会**弹回首页**，正在做的一轮
    // 就白做了。改成 rememberSaveable 后，用户回到的是原来那一页。
    var page by rememberSaveable(stateSaver = ReviewPageSaver) {
        mutableStateOf<ReviewPage>(ReviewPage.Home)
    }
    // AI 设置页的来处，默认 Home；只有从练习页点「配置 AI」进来时才改成 Practice。
    var aiSettingsReturnTo by rememberSaveable(stateSaver = ReviewPageSaver) {
        mutableStateOf<ReviewPage>(ReviewPage.Home)
    }
    // 练习结束/返回时回到哪个题库详情页（从哪个库进去的就回哪儿）。
    var practiceReturnTo by rememberSaveable(stateSaver = ReviewPageSaver) {
        mutableStateOf<ReviewPage>(ReviewPage.Home)
    }
    // 重建练习会话所需的最小信息：开练参数（决定抽哪些题、按什么顺序）、开始时刻、
    // 乱序种子。有了这三个，会话就能原样重建 —— 见下面的重建效果。
    var launchEncoded by rememberSaveable { mutableStateOf("") }
    var practiceStartedAt by rememberSaveable { mutableStateOf(0L) }
    var practiceSeed by rememberSaveable { mutableStateOf(0L) }

    // ── 「继续上次练习」 ────────────────────────────────────────────────────
    // 主动退回首页不销毁 Activity（saved state 不会被创建），所以进度另存一份到
    // SharedPreferences。首页据此给一张「继续上次练习」卡片。
    var resumeIndex by rememberSaveable { mutableIntStateOf(0) }
    var resumeSnapshot by rememberSaveable { mutableStateOf("") }
    var resume by remember { mutableStateOf<PracticeResume.Snapshot?>(null) }
    LaunchedEffect(page) {
        if (page is ReviewPage.Home) {
            resume = withContext(Dispatchers.IO) { PracticeResume.load(context) }
        }
    }

    // 🔴 页面状态容器：`when` 切页时旧页离开组合，`rememberSaveable` 的值随组合注销 ——
    // 练习页的题号/作答快照/总结页在「练习 → AI 设置 → 返回」后会全部回卷（悬浮球把
    // 这条路径变成了没配 Key 用户的必经之路，等于一配 Key 就被踢回第一题）。
    // 按页面 key 隔离保存容器后，切页往返能原样找回；练习页的 key 带开练时刻 ——
    // 新开一轮拿到全新状态，不会带着上一轮的题号与作答。
    // （声明必须在 startPractice 之前：局部 val 不能被后面的闭包前向引用。）
    val pageStateHolder = rememberSaveableStateHolder()
    val pageKey = when (val p = page) {
        ReviewPage.Home -> "home"
        ReviewPage.Practice -> "practice:$practiceStartedAt"
        ReviewPage.AiSettings -> "ai"
        ReviewPage.Library -> "library"
        is ReviewPage.BankDetail -> "detail:${p.bankId}"
        is ReviewPage.WrongBook -> "wrong:${p.bankId.orEmpty()}"
        is ReviewPage.Favorites -> "fav:${p.bankId}"
        is ReviewPage.BankQuestions -> "questions:${p.bankId}"
    }

    // 开练的公共逻辑，Home 与 BankDetail 两处入口共用。
    // 参数收成 PracticeLaunch（具名数据类）—— 位置参数已多到"传错了不报错"。
    //
    // 「按参数抽题 + 建会话」与进程重建的 LaunchedEffect 是**同一段逻辑**
    // （此前两处几乎逐字重复，改一处忘一处迟早分叉），抽成下面的 rebuildSession 共用。
    suspend fun rebuildSession(
        launch: PracticeLaunch,
        startedAt: Long,
        seed: Long
    ): PracticeSession? {
        val questions = withContext(Dispatchers.IO) {
            ReviewDatabase.get(context).pickQuestions(
                bankId = launch.bankId,
                chapters = launch.chapters,
                mode = launch.mode,
                onlyWrong = launch.onlyWrong,
                onlyFavorite = launch.onlyFavorite,
                dueOnly = launch.dueOnly
            )
        }
        if (questions.isEmpty()) return null
        // ⚠️ timing / startedAt / memorize 都是带默认值的参数，漏传不报编译错 ——
        // 这里是**唯一**的建会话处，所有调用点都强制走齐这些字段：
        // 限时考试漏传会退化成不限时、背题漏传会误落库、种子漏传会让选项重新洗牌。
        return PracticeSession(
            questions = questions,
            oneByOne = true,
            timing = ExamTiming(launch.durationSeconds),
            startedAt = startedAt,
            memorize = launch.memorize,
            // 选项乱序：开关来自 ReviewPrefs（默认关），种子每轮新取 ——
            // 同一轮里同一题的排列稳定（重组不会跳），换一轮则整体重排。
            shuffleOptions = ReviewPrefs.isShuffleOptions(context),
            shuffleSeed = seed
        )
    }

    val startPractice: (PracticeLaunch, ReviewPage) -> Unit = { launch, returnTo ->
        scope.launch {
            val now = System.currentTimeMillis()
            val built = rebuildSession(launch, startedAt = now, seed = now)
            if (built == null) {
                GlassToaster.show("这里还没有题目")
            } else {
                practiceTitle = launch.title()
                practiceReturnTo = returnTo
                session = built
                // 旧一轮的页面状态（题号/作答快照）随 key 失效，顺手从 holder 清掉。
                // 此刻 practiceStartedAt 还是上一轮的值，移除的正是旧 key。
                pageStateHolder.removeState("practice:$practiceStartedAt")
                // 记下重建会话所需的信息（进程被杀后靠它把这一轮原样拼回来）。
                launchEncoded = launch.encode()
                practiceStartedAt = now
                practiceSeed = now
                // 新开一轮 = 作废上一轮的"继续练习"存档与恢复点。
                resumeIndex = 0
                resumeSnapshot = ""
                resume = null
                withContext(Dispatchers.IO) { PracticeResume.clear(context) }
                page = ReviewPage.Practice
            }
        }
    }

    // 进程被杀后重建练习会话。
    //
    // 会话是内存对象，恢复不了 —— 但它可以**重建**：抽题顺序是确定性的
    // （`pickQuestions` 对顺序模式走 `ORDER BY rowid`，对乱序/错题优先走
    // QuestionOrder 的 Kotlin 稳定哈希，都不依赖时间），所以用同一组参数
    // 再抽一次就是同一批题、同一顺序；作答与判分由 PracticeScreen 从
    // 自己的 saved state 灌回去。
    //
    // 正常开练时这个效果也会被触发（page 与 launchEncoded 同时变化），但那时
    // `session != null`，直接返回，不做任何多余工作。
    LaunchedEffect(page, launchEncoded) {
        if (page !is ReviewPage.Practice) return@LaunchedEffect
        if (session != null) return@LaunchedEffect
        val launch = PracticeLaunch.decode(launchEncoded) ?: return@LaunchedEffect
        // 沿用**原来的**开始时刻与种子：倒计时按真实墙钟走（被杀期间的时间本来就该
        // 算进去）；种子换了的话重建后选项会重新洗一遍 —— 用户会看到自己选中的选项
        // "跳"到了别的字母上。
        val built = rebuildSession(launch, startedAt = practiceStartedAt, seed = practiceSeed)
        if (built == null) {
            // 进程重启期间题库被删了 / 被清空了：老实回首页，不要卡在空练习页。
            GlassToaster.show("这一轮的题目已经不在了")
            page = ReviewPage.Home
            return@LaunchedEffect
        }
        session = built
    }

    // 卡片入场时间轴：以当前页为复位键 —— 换页即重新播放入场。
    //
    // 复习模块是独立 composition，`LocalModuleEntrance`（MainActivity 的底栏切页提供）
    // 在这里取不到，`moduleEntrance` 会退化成空操作。所以自带一份进度源下发，
    // 卡片侧统一走 `Modifier.reviewCardEntrance(group)`。见 ReviewMotion.kt。
    val reviewEntrance = rememberReviewEntrance(page)

    CompositionLocalProvider(LocalReviewEntrance provides reviewEntrance) {
        // 🔴 系统返回（返回键 / 侧滑手势）必须接进模块内的页面栈 —— 此前它走
        // Activity 默认行为（finish 整个 Activity），用户在练习 / 详情页里一滑
        // 就掉回「我的」页，只有左上角箭头才是"回到上一页"。现在两者走同一份
        // 页面映射：Home 之外的所有页面，系统返回 = 左上角返回。
        BackHandler(enabled = page !is ReviewPage.Home) {
            when (val p = page) {
                is ReviewPage.Library -> page = ReviewPage.Home
                is ReviewPage.BankQuestions -> page = ReviewPage.Library
                is ReviewPage.BankDetail -> page = ReviewPage.Home
                is ReviewPage.WrongBook -> page = p.bankId?.let { ReviewPage.BankDetail(it) } ?: ReviewPage.Home
                is ReviewPage.Favorites -> page = ReviewPage.BankDetail(p.bankId)
                is ReviewPage.Practice -> {
                    // 与练习页左上角返回同一口径：只离开页面，**保留**"可重建"信息，
                    // 首页的「继续上次练习」还要靠它把这一轮拼回来。
                    session = null
                    page = practiceReturnTo
                }
                is ReviewPage.AiSettings -> page = aiSettingsReturnTo
                ReviewPage.Home -> Unit
            }
        }
        pageStateHolder.SaveableStateProvider(pageKey) {
        when (val current = page) {
            is ReviewPage.Home -> {
                ReviewHomeScreen(
                    context = context,
                    onBack = onExit,
                    // 点题库行 → 进详情页（不再直接开练）。
                    onOpenBankDetail = { bankId -> page = ReviewPage.BankDetail(bankId) },
                    // 从首页进来时把来处重置成 Home —— 否则「练习页进去配过一次」之后
                    // 再从首页进设置页，按返回会莫名其妙跳回练习页。
                    onOpenAiSettings = {
                        aiSettingsReturnTo = ReviewPage.Home
                        page = ReviewPage.AiSettings
                    },
                    onOpenLibrary = { page = ReviewPage.Library },
                    // 继续上次练习：把存档里的题号/快照带回去，会话由下面的重建效果拼回来。
                    resume = resume,
                    onResumePractice = {
                        val saved = resume ?: return@ReviewHomeScreen
                        resumeIndex = saved.index
                        resumeSnapshot = saved.answers
                        practiceTitle = saved.title
                        practiceReturnTo = ReviewPage.Home
                        launchEncoded = saved.launch.encode()
                        practiceStartedAt = saved.startedAt
                        practiceSeed = saved.seed
                        session = null
                        page = ReviewPage.Practice
                    },
                    onOpenAllWrongBook = { page = ReviewPage.WrongBook(null) }
                )
            }
            is ReviewPage.Library -> {
                BankLibraryScreen(
                    context = context,
                    onBack = { page = ReviewPage.Home },
                    onOpenBankDetail = { bankId -> page = ReviewPage.BankDetail(bankId) },
                    onEditQuestions = { bankId -> page = ReviewPage.BankQuestions(bankId) }
                )
            }
            is ReviewPage.BankQuestions -> {
                BankQuestionEditScreen(
                    context = context,
                    bankId = current.bankId,
                    // 逐题编辑是从题库仓库的管理列表进来的，改完回那里（而不是首页）。
                    onBack = { page = ReviewPage.Library }
                )
            }
            is ReviewPage.BankDetail -> {
                val bankId = current.bankId
                BankDetailScreen(
                    context = context,
                    bankId = bankId,
                    onBack = { page = ReviewPage.Home },
                    onStartPractice = { launch ->
                        startPractice(launch, ReviewPage.BankDetail(bankId))
                    },
                    onOpenWrongBook = { page = ReviewPage.WrongBook(bankId) },
                    onOpenFavorites = { page = ReviewPage.Favorites(bankId) }
                )
            }
            is ReviewPage.WrongBook -> {
                val bankId = current.bankId
                BankWrongBookScreen(
                    context = context,
                    bankId = bankId,
                    onBack = { page = bankId?.let { ReviewPage.BankDetail(it) } ?: ReviewPage.Home },
                    // 从错题本开练，练完回错题本（而不是弹回详情页）——
                    // 用户来这儿就是为了刷错题，回去看一眼还剩多少是自然的下一步。
                    onStartPractice = { launch -> startPractice(launch, ReviewPage.WrongBook(bankId)) }
                )
            }
            is ReviewPage.Favorites -> {
                val bankId = current.bankId
                BankFavoriteScreen(
                    context = context,
                    bankId = bankId,
                    onBack = { page = bankId?.let { ReviewPage.BankDetail(it) } ?: ReviewPage.Home },
                    // 与错题本同一口径：从收藏页开练，练完回收藏页。
                    onStartPractice = { launch -> startPractice(launch, ReviewPage.Favorites(bankId)) }
                )
            }
            is ReviewPage.Practice -> {
                val active = session
                if (active == null) {
                    if (launchEncoded.isNotEmpty()) {
                        // 会话正在重建（进程被杀后重新回到练习页）：显示加载态。
                        // ⚠️ 这里**不能**像从前那样直接弹回首页 —— 那正是"进度丢失"的
                        // 表现；重建效果马上会把会话拼回来。
                        GlassLoadingState(text = "正在恢复练习…")
                    } else {
                        // 既没有会话也没有可重建的信息（异常路径）：安全回首页。
                        page = ReviewPage.Home
                    }
                } else {
                    PracticeScreen(
                        context = context,
                        session = active,
                        title = practiceTitle,
                        initialIndex = resumeIndex,
                        initialSnapshot = resumeSnapshot,
                        // 作答进度落一份到本地存档：主动退回首页后还能「继续上次练习」。
                        onProgress = progress@{ index, snapshot ->
                            val launch = PracticeLaunch.decode(launchEncoded) ?: return@progress
                            if (snapshot.isNotBlank()) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        PracticeResume.save(
                                            context,
                                            PracticeResume.Snapshot(
                                                launch = launch,
                                                startedAt = practiceStartedAt,
                                                seed = practiceSeed,
                                                title = practiceTitle,
                                                index = index,
                                                answers = snapshot
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        onBack = {
                            // ⚠️ 不再清掉「可重建」信息：退出练习只是离开页面，
                            // 首页的「继续上次练习」还要靠它把这一轮拼回来。
                            session = null
                            page = practiceReturnTo
                        },
                        onFinished = {
                            // 本轮已经交卷：成绩在判分那一刻已逐题落库，
                            // 同时作废"继续练习"存档，避免下次接着刷一个已结束的轮次。
                            session = null
                            launchEncoded = ""
                            resumeIndex = 0
                            resumeSnapshot = ""
                            resume = null
                            scope.launch { withContext(Dispatchers.IO) { PracticeResume.clear(context) } }
                            page = practiceReturnTo
                        },
                        // 练习页「问 AI」在未配置 Key 时点了要能到设置页。
                        // 记下来处，返回时回练习页而不是首页（否则这一轮作答就白做了）。
                        // ⚠️ **不要在这里置 session = null** —— 练习会话是内存对象。
                        onOpenAiSettings = {
                            aiSettingsReturnTo = ReviewPage.Practice
                            page = ReviewPage.AiSettings
                        }
                    )
                }
            }
            // AI 设置页的「返回」要回**来处**，不能固定回 Home。
            // 从练习页点「配置 AI」进来时，固定回 Home 等于把用户踢出正在做的这一轮
            // （PracticeSession 是内存对象，session 被清掉就得从第一题重来）。
            is ReviewPage.AiSettings -> AiSettingsScreen(onBack = { page = aiSettingsReturnTo })
        }
        }
    }
}

// ── 页面选择与开练参数的编码（抗进程死亡用） ─────────────────────────────────

/**
 * [ReviewPage] ↔ 字符串。
 *
 * 页面选择要进 `rememberSaveable`（`Bundle` 只收 Parcelable / 基本类型），
 * 而 [ReviewPage] 是 sealed interface。编码成字符串是最省事的做法 ——
 * 参考项目 Shiroha Quiz 的返回栈也是这么做的（`AppRouteSnapshot` 编码成 String）。
 */
private val ReviewPageSaver: Saver<ReviewPage, String> = Saver(
    save = { it.encode() },
    restore = { decodeReviewPage(it) }
)

private fun ReviewPage.encode(): String = when (this) {
    ReviewPage.Home -> "home"
    ReviewPage.Practice -> "practice"
    ReviewPage.AiSettings -> "ai"
    ReviewPage.Library -> "library"
    is ReviewPage.BankDetail -> "detail:$bankId"
    is ReviewPage.WrongBook -> "wrong:${bankId.orEmpty()}"
    is ReviewPage.Favorites -> "fav:$bankId"
    is ReviewPage.BankQuestions -> "questions:$bankId"
}

/** 解不出的一律回首页 —— 宁可回首页，也不要卡在一个不存在的页面上。 */
private fun decodeReviewPage(raw: String): ReviewPage = when {
    raw == "practice" -> ReviewPage.Practice
    raw == "ai" -> ReviewPage.AiSettings
    raw == "library" -> ReviewPage.Library
    raw.startsWith("detail:") -> ReviewPage.BankDetail(raw.removePrefix("detail:"))
    raw.startsWith("wrong:") -> ReviewPage.WrongBook(raw.removePrefix("wrong:").ifBlank { null })
    raw.startsWith("fav:") -> ReviewPage.Favorites(raw.removePrefix("fav:"))
    raw.startsWith("questions:") -> ReviewPage.BankQuestions(raw.removePrefix("questions:"))
    else -> ReviewPage.Home
}

// 页面选择与开练参数的编解码见 PracticeLaunch.encode / PracticeLaunch.decode
// （放在 review 包里而不是本文件：本地存档 PracticeResume 也要用同一套编码）。
