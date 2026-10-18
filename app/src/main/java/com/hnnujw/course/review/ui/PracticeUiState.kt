package com.hnnujw.course.review.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 练习页里**只活在当前页面**的那批开关状态。
 *
 * 为什么收进一个类：`PracticeScreen` 原本在函数体顶部摊着十几个 `remember { ... }`，
 * 一个 500 多行的 composable 光读状态就要滚两屏，而且"哪个是持久化的、哪个是临时 UI 开关"
 * 完全看不出来。现在分三层，各自的寿命一目了然：
 *
 *  1. **[PracticeUiState]**（本类）—— 临时开关，切页即丢，进程被杀不救；
 *  2. **`index` / `answersSnapshot`** —— 留在 `PracticeScreen` 里用 `rememberSaveable`，
 *     进程被系统杀掉后要能把这一轮原样拼回来；
 *  3. **`PracticeSession`** —— 真正的作答数据，纯 Kotlin 类，不依赖 Compose。
 *
 * 🔴 第 2、3 层**没有**搬进这个类，是有意的：
 *  - `rememberSaveable` 需要一个 `Saver`，把 `index` 塞进普通类会绕过系统状态恢复；
 *  - `PracticeSession` 一旦改用 `mutableStateMapOf`，判分/正确率那十几个 JVM 单测
 *    就得全部迁到 Robolectric —— 拿测试速度换一次重构，不划算。
 *
 * 所以 `answerVersion` 这个"手动触发重组"的补丁**保留**：它是纯逻辑层与 Compose
 * 之间的边界成本，不是历史包袱。改动任何一处时，`select` / `judge` / `unjudge`
 * 之后仍然必须 `bumpAnswers()`。
 */
@Stable
internal class PracticeUiState(autoNextDefault: Boolean) {

    /** 是否已经尝试过恢复上一轮的作答快照（避免重复恢复把状态覆盖掉）。 */
    var restored by mutableStateOf(false)

    /** AI 讲解弹窗是否展开。 */
    var showAi by mutableStateOf(false)

    /** 当前题是否已收藏（跟着题目变，翻页时重读）。 */
    var favorite by mutableStateOf(false)

    /** 答题卡整页是否展开。 */
    var showAnswerSheet by mutableStateOf(false)

    /** 选完自动跳下一题的偏好（页面内可临时切换，落库走 ReviewPrefs）。 */
    var autoNext by mutableStateOf(autoNextDefault)

    /** 自动跳题的倒计时触发器：+1 表示"开始跳"。 */
    var autoAdvanceTick by mutableIntStateOf(0)

    /**
     * 作答版本号。
     *
     * `PracticeSession` 内部是普通 Map，改它不触发重组；select / judge / unjudge
     * 之后把这里 +1，界面上以它为 key 的 `remember` 才会重新取值。
     */
    var answerVersion by mutableIntStateOf(0)

    /** 落库失败提示是否已经弹过（只提醒一次，别每题都弹）。 */
    var recordFailureShown by mutableStateOf(false)

    /** 限时考试的两个时间警告是否已经放过（5 分钟 / 1 分钟各一次）。 */
    var warnedFive by mutableStateOf(false)
    var warnedOne by mutableStateOf(false)

    /** 作答变化后调用；**任何改动作答的操作都必须调用它**。 */
    fun bumpAnswers() {
        answerVersion++
    }

    /** 换一轮考试（或重新开始计时）时复位时间警告。 */
    fun resetTimeWarnings() {
        warnedFive = false
        warnedOne = false
    }
}

@Composable
internal fun rememberPracticeUiState(autoNextDefault: Boolean): PracticeUiState =
    remember { PracticeUiState(autoNextDefault) }
