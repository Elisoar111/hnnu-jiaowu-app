package com.hnnujw.course.schedule

/**
 * 桌面卡片「点某一行 → 打开那门课」的落地判据。
 *
 * 卡片是**异步渲染**的：点击发生时课表可能还没从缓存/教务读出来。所以"带过来的课程 id
 * 找不到"有两种完全不同的含义，必须分开对待 ——
 *
 *  - 还在加载：请求要**留着**，等课程列表到了再打开。此时就丢掉的话，用户看到的是
 *    "点了没反应"，而实际只是慢了一拍。
 *  - 已经加载完还是没有：请求是**过期的**（换了账号、换了学期、课表被重新同步过、
 *    或者卡片是几小时前画的），要丢掉，否则它会在下一次课表刷新时突然弹出一个
 *    跟用户当下操作无关的课程详情。
 *
 * 把这段判断抽成纯函数是为了能单测：它只有三个输入，但四种结果各自的时机错了都会
 * 表现为"偶尔点了没反应 / 偶尔莫名弹出详情"这种最难复现的 bug。
 */
internal sealed class CourseOpenDecision {
    /** 找到了：打开它，并清掉请求。 */
    object Open : CourseOpenDecision()

    /** 课表还没加载出来：保留请求，等下一轮。 */
    object Wait : CourseOpenDecision()

    /** 已经加载完却没有这门课：请求过期，清掉，不打开。 */
    object Drop : CourseOpenDecision()

    /** 本来就没有请求。 */
    object None : CourseOpenDecision()
}

/**
 * @param requestedId 卡片带过来的课程 id；null / 空白表示没有请求。
 * @param availableIds 当前课表里已有的课程 id。
 * @param isLoading 课表是否仍在加载。
 *
 * **顺序有意义**：先看找没找到，再看加载中。命中必须优先于"加载中" ——
 * 课表已经加载完、但列表正在被后续的同步替换时，`isLoading` 可能又变回 true，
 * 这时候课程其实就在手上，没有理由让它再等一轮。
 */
internal fun decideCourseOpen(
    requestedId: String?,
    availableIds: Collection<String>,
    isLoading: Boolean,
): CourseOpenDecision = when {
    requestedId.isNullOrBlank() -> CourseOpenDecision.None
    requestedId in availableIds -> CourseOpenDecision.Open
    isLoading -> CourseOpenDecision.Wait
    else -> CourseOpenDecision.Drop
}
