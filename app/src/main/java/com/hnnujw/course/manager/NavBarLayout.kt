package com.hnnujw.course.manager

/**
 * 底部导航栏的可编辑布局 —— **纯函数，不碰 Android API**。
 *
 * 之所以独立成文件而不是写在 Composable 里：这套「容错归一 / 移动 / 能否移除」的
 * 判断逻辑全是纯数据变换，抽出来才能在 JVM 单测里钉死（项目红线：
 * 判断逻辑不许长在 Composable 里；返回 Boolean 会把原因丢掉，所以一律返回枚举）。
 *
 * 可选项**只有现有这 5 个页面**（课表 / 学工 / 成绩 / 二课 / 我的）。本项目没有
 * 插件体系，底栏不允许凭空多出页面 —— 用户能做的只有「重排 + 显隐」。
 */
object NavBarLayout {

    /** 出厂顺序，也是「恢复默认」的落点。 */
    val default: List<StartupPage> = StartupPage.entries.toList()

    /** 底栏至少要保留的项数。只剩一项时 [CapsuleNavigationBar] 的滑块动画会失真。 */
    const val MIN_ITEMS: Int = 2

    /**
     * 「我的」是唯一能进设置改回导航的入口，**必须常驻且不可移除** ——
     * 否则用户把它移掉之后就形成死循环，再也改不回来了。
     */
    val pinned: StartupPage = StartupPage.Settings

    /**
     * 把持久化里读到的 route 列表归一成一份可信的有序页面列表。
     *
     * 容错口径（项目红线，磁盘上的东西一律不可信）：
     * - `null`（从未存过）→ 默认顺序
     * - 未知 route → 丢弃该项
     * - 重复 route → 去重，保留首次出现的位置
     * - 列表为空 / 少于 [MIN_ITEMS] 项 / 不含 [pinned] → **整体回退默认**
     *   （半残的布局比默认布局更糟，宁可当它没配过）
     *
     * 注意：**保留用户选的顺序**，不做任何「按枚举顺序重排」的额外归整 ——
     * 用户把「成绩」拖到第一位是明确意图，不是脏数据。
     */
    fun resolve(stored: List<String>?): List<StartupPage> {
        if (stored == null) return default

        val seen = LinkedHashSet<StartupPage>(stored.size)
        for (route in stored) {
            val page = StartupPage.entries.firstOrNull { it.route == route } ?: continue
            seen.add(page)
        }
        val resolved = seen.toList()

        if (resolved.size < MIN_ITEMS || pinned !in resolved) return default
        return resolved
    }

    /**
     * 把 [from] 位置的项移动到 [to] 位置，其余项顺次平移。
     *
     * 越界 / `from == to` 一律**原样返回**，不抛异常：
     * 调用点全在 UI 按钮的 onClick 里，抛出去就是闪退。
     */
    fun move(items: List<StartupPage>, from: Int, to: Int): List<StartupPage> {
        if (from == to) return items
        if (from !in items.indices || to !in items.indices) return items
        val result = items.toMutableList()
        val moved = result.removeAt(from)
        result.add(to, moved)
        return result
    }

    /** 移除操作的判定结果。返回枚举而不是 Boolean，界面才能给出具体原因。 */
    sealed interface RemoveCheck {
        /** 可以移除。 */
        data object Ok : RemoveCheck
        /** 「我的」不可移除：它是改回导航的唯一入口。 */
        data object CannotRemoveSettings : RemoveCheck
        /** 已经只剩 [MIN_ITEMS] 项，再删底栏就不成形了。 */
        data object TooFewItems : RemoveCheck
        /** 这一项本来就不在列表里。 */
        data object NotPresent : RemoveCheck
    }

    /**
     * 判断 [page] 能否从 [items] 中移除。
     *
     * 顺序有讲究：先判「在不在列表里」，再判「是不是 pinned」，最后判「项数够不够」——
     * 这样对同一项能给出最贴切的那条原因（把不存在的项报成「太重」是误导）。
     */
    fun canRemove(items: List<StartupPage>, page: StartupPage): RemoveCheck {
        if (page !in items) return RemoveCheck.NotPresent
        if (page == pinned) return RemoveCheck.CannotRemoveSettings
        if (items.size <= MIN_ITEMS) return RemoveCheck.TooFewItems
        return RemoveCheck.Ok
    }

    /**
     * [move] 的边界封装：第一项不能上移、最后一项不能下移。
     * 返回 null 表示「这个方向不可用」，界面据此禁用按钮。
     */
    fun moveUp(items: List<StartupPage>, index: Int): List<StartupPage>? {
        if (index <= 0 || index >= items.size) return null
        return move(items, index, index - 1)
    }

    /** 见 [moveUp]。 */
    fun moveDown(items: List<StartupPage>, index: Int): List<StartupPage>? {
        if (index < 0 || index >= items.size - 1) return null
        return move(items, index, index + 1)
    }
}
