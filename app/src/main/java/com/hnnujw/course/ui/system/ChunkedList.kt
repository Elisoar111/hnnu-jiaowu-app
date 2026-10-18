package com.hnnujw.course.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「分块渲染 + 显示更多」的列表状态。
 *
 * ## 为什么需要它（而不是直接换 LazyColumn）
 *
 * 项目里有几处列表是**在 `verticalScroll` 容器里 `forEach` 渲染**的：
 * 一卡通账单明细（可达上千笔）、空闲教室结果（「加载更多」会一直累积）。
 * 这类写法在大数据量下会一次性组合全部子项，进页即卡。
 *
 * 直觉上应该换成 `LazyColumn`，但这个项目**不能**这么换：
 * 外层已经是一个 `verticalScroll`，再嵌一个 `LazyColumn` 就是**同轴嵌套滚动**
 * —— 子节点会收到 `maxHeight = Infinity` 的约束，`ScrollNode` 在测量阶段直接抛异常崩进程。
 * 项目里踩过这个坑（见 QuestionCard / BankQuestionEditScreen 的注释）。
 *
 * 所以这里取另一种解：**限制一次最多组合多少项**，剩下的等用户点「显示更多」。
 * 它不改变外层滚动结构，零崩溃风险，而首屏组合成本被钉死在 [pageSize] 个。
 *
 * ## 与「加载更多」的区别
 *
 * 「加载更多」是**网络**层面的分页（向服务端要下一页）。
 * 这里是**渲染**层面的截断（数据已经全在内存里，只是先不全部画出来）。
 * 两者互不替代：即使 `canLoadMore` 不为真，内存里也可能已经攒了几百条。
 */
class ChunkedListState internal constructor(
    initialTotal: Int,
    val pageSize: Int
) {
    /** 当前数据总条数（由调用方每次重组时同步，见 [syncTotal]）。 */
    var total by mutableIntStateOf(initialTotal)
        private set

    /** 已经放开的渲染条数。 */
    var shown by mutableIntStateOf(minOf(initialTotal, pageSize))
        private set

    /** 实际应当渲染多少条（永远不超过总条数）。 */
    val visibleCount: Int get() = shown.coerceAtMost(total)

    /** 是否还有没渲染出来的。 */
    val hasMore: Boolean get() = visibleCount < total

    /** 还剩多少条没渲染。 */
    val remaining: Int get() = (total - visibleCount).coerceAtLeast(0)

    /**
     * 同步总条数。
     *
     * 数据变多时（「加载更多」拿回新一页）**不会**自动全部展开 —— 那等于把
     * 截断的意义抹掉了。但也不会把已经展开的部分收回去：用户点过「显示更多」，
     * 说明他愿意看更多，突然收回会让滚动位置跳动。
     */
    fun syncTotal(newTotal: Int) {
        if (newTotal == total) return
        total = newTotal
        // 数据变少（切筛选条件）时把已展示数量压回合理的值。
        if (shown > newTotal) shown = newTotal
        // 首次从 0 变成有数据：至少放一页出来。
        if (shown == 0 && newTotal > 0) shown = minOf(newTotal, pageSize)
    }

    /** 再展开一页。 */
    fun showMore() {
        shown = (visibleCount + pageSize).coerceAtMost(total)
    }

    /** 一次性全部展开（「全部显示」用；大列表慎用）。 */
    fun showAll() {
        shown = total
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 40
    }
}

@Composable
fun rememberChunkedListState(
    total: Int,
    pageSize: Int = ChunkedListState.DEFAULT_PAGE_SIZE
): ChunkedListState {
    val state = remember(pageSize) { ChunkedListState(total, pageSize) }
    // 每次重组都同步一次总条数：数据由网络陆续回来时会频繁变化，
    // 用 effect 而不是在 getter 里改状态 —— 后者会在读取时写状态，是 Compose 的反模式。
    LaunchedEffect(total) { state.syncTotal(total) }
    return state
}

/**
 * 「还有 N 项未显示」的提示 + 展开按钮。
 *
 * 单独抽出来是因为这几处列表的视觉风格各不相同，但"被截断了"这件事必须
 * **明确告诉用户** —— 否则用户会以为数据只有这么多，那是比卡顿更糟的问题。
 *
 * @param onShowAll 传 null 表示不提供"全部显示"（例如还有网络分页时，
 *   让用户点网络层的「加载更多」更合适）。
 */
@Composable
fun ChunkedListFooter(
    state: ChunkedListState,
    noun: String = "项",
    onShowAll: (() -> Unit)? = null
) {
    if (!state.hasMore) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "还有 ${state.remaining} ${noun}未显示（当前显示 ${state.visibleCount} ${noun}）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SystemSecondaryButton(
                text = "再显示 ${state.pageSize} ${noun}",
                onClick = { state.showMore() },
                modifier = Modifier.weight(1f)
            )
            if (onShowAll != null) {
                SystemSecondaryButton(
                    text = "全部显示",
                    onClick = { state.showAll(); onShowAll() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
