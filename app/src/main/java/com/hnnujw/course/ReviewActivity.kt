package com.hnnujw.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.review.PracticeSession
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.ReviewMode
import com.hnnujw.course.review.ui.AiSettingsScreen
import com.hnnujw.course.review.ui.BankDetailScreen
import com.hnnujw.course.review.ui.BankFormatHelpScreen
import com.hnnujw.course.review.ui.BankStatsScreen
import com.hnnujw.course.review.ui.PracticeScreen
import com.hnnujw.course.review.ui.ReviewHomeScreen
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 复习模块宿主（题库管理 + 练习 + AI 答疑）。
 *
 * 从「我的 → 校园服务 → 复习」进入。内部切页走**同一个 composition 里的状态**
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
                ReviewFlow(onExit = { finish() })
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ReviewActivity::class.java))
        }
    }
}

/** 复习模块的页面。 */
private sealed interface ReviewPage {
    data object Home : ReviewPage
    data object Practice : ReviewPage
    data object AiSettings : ReviewPage
    data object FormatHelp : ReviewPage

    /** 某个题库的详情页（练习/统计都从这里进出）。 */
    data class BankDetail(val bankId: String) : ReviewPage

    /** 某个题库的统计页。 */
    data class BankStats(val bankId: String) : ReviewPage
}

@Composable
private fun ReviewFlow(onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<PracticeSession?>(null) }
    var practiceTitle by remember { mutableStateOf("练习") }
    var page by remember { mutableStateOf<ReviewPage>(ReviewPage.Home) }
    // AI 设置页的来处，默认 Home；只有从练习页点「配置 AI」进来时才改成 Practice。
    var aiSettingsReturnTo by remember { mutableStateOf<ReviewPage>(ReviewPage.Home) }
    // 练习结束/返回时回到哪个题库详情页（从哪个库进去的就回哪儿）。
    var practiceReturnTo by remember { mutableStateOf<ReviewPage>(ReviewPage.Home) }

    // 开练的公共逻辑，Home 与 BankDetail 两处入口共用。
    val startPractice: (String?, String?, ReviewMode, Boolean, Boolean, ReviewPage) -> Unit =
        { bankId, chapter, mode, onlyWrong, onlyFavorite, returnTo ->
            scope.launch {
                // 抽题在 IO 线程（几千题的库不该卡住点击反馈）。
                val questions = withContext(Dispatchers.IO) {
                    ReviewDatabase.get(context).pickQuestions(
                        bankId = bankId,
                        chapter = chapter,
                        mode = mode,
                        onlyWrong = onlyWrong,
                        onlyFavorite = onlyFavorite
                    )
                }
                val title = when {
                    onlyWrong -> "错题本"
                    onlyFavorite -> "收藏"
                    !chapter.isNullOrBlank() -> chapter
                    else -> mode.label
                }
                if (questions.isEmpty()) {
                    GlassToaster.show("这里还没有题目")
                } else {
                    practiceTitle = title
                    practiceReturnTo = returnTo
                    session = PracticeSession(questions, oneByOne = true)
                    page = ReviewPage.Practice
                }
            }
        }

    when (val current = page) {
        is ReviewPage.Home -> {
            ReviewHomeScreen(
                context = context,
                onBack = onExit,
                onStartPractice = { bankId, chapter, mode, onlyWrong, onlyFavorite ->
                    startPractice(bankId, chapter, mode, onlyWrong, onlyFavorite, ReviewPage.Home)
                },
                // 点题库行 → 进详情页（不再直接开练）。
                onOpenBankDetail = { bankId -> page = ReviewPage.BankDetail(bankId) },
                // 从首页进来时把来处重置成 Home —— 否则「练习页进去配过一次」之后
                // 再从首页进设置页，按返回会莫名其妙跳回练习页。
                onOpenAiSettings = {
                    aiSettingsReturnTo = ReviewPage.Home
                    page = ReviewPage.AiSettings
                },
                onOpenTemplate = { page = ReviewPage.FormatHelp }
            )
        }
        is ReviewPage.BankDetail -> {
            val bankId = current.bankId
            BankDetailScreen(
                context = context,
                bankId = bankId,
                onBack = { page = ReviewPage.Home },
                onStartPractice = { b, chapter, mode, onlyWrong, onlyFavorite ->
                    startPractice(b, chapter, mode, onlyWrong, onlyFavorite, ReviewPage.BankDetail(b))
                },
                onOpenStats = { page = ReviewPage.BankStats(bankId) }
            )
        }
        is ReviewPage.BankStats -> {
            BankStatsScreen(
                context = context,
                bankId = current.bankId,
                onBack = { page = ReviewPage.BankDetail(current.bankId) }
            )
        }
        is ReviewPage.Practice -> {
            val active = session
            if (active == null) {
                // 会话被回收（极端情况下）时安全回首页，不要卡在空练习页。
                page = ReviewPage.Home
            } else {
                PracticeScreen(
                    context = context,
                    session = active,
                    title = practiceTitle,
                    onBack = {
                        session = null
                        // 回到进练习时记下的来处（题库详情页 / 首页）。
                        page = practiceReturnTo
                    },
                    onFinished = {
                        // 成绩在判分那一刻已逐题落库，这里回到来处。
                        session = null
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
        is ReviewPage.FormatHelp -> BankFormatHelpScreen(onBack = { page = ReviewPage.Home })
    }
}
