package com.hnnujw.course.manager

import android.content.Context
import android.content.SharedPreferences

enum class StartupPage(val route: String, val label: String) {
    Schedule("schedule", "课表"),
    Xuegong("xuegong", "学工"),
    Grades("grades", "成绩"),
    SecondClass("secondclass", "二课"),
    Settings("settings", "我的");

    companion object {
        fun decode(value: String?): StartupPage = entries.firstOrNull { it.route == value } ?: Schedule
    }
}

/** App preference shared across accounts; activity restoration still retains its current page. */
class StartupPagePreferences(private val preferences: SharedPreferences) {
    fun read(): StartupPage = StartupPage.decode(runCatching { preferences.getString(KEY, null) }.getOrNull())

    fun write(page: StartupPage) {
        preferences.edit().putString(KEY, page.route).apply()
    }

    // ── 底部导航栏布局（第 14 轮：用户可编辑底栏） ─────────────────────
    //
    // 存**有序 route 列表**，逗号分隔。之所以不用 JSON：这是 5 个短字符串的
    // 纯顺序信息，org.json 的解析异常分支反而更容易出错；逗号分隔 + route 白名单
    // （见 [NavBarLayout.resolve]）已经足够，且读出来是一行 string 便于排查。
    //
    // 关键：**磁盘上的东西一律不可信**。这里读原始串，一切容错（未知项丢弃、
    // 去重、过短回退默认）都交给纯函数 [NavBarLayout.resolve]，本类只负责
    // 「存/取」这一层，不做判断 —— 判断才需要单测，存取不需要。

    /** 读回用户自定义的底栏布局（已归一）；从未存过或数据损坏时返回默认顺序。 */
    fun readNavBar(): List<StartupPage> =
        NavBarLayout.resolve(readNavBarRoutes())

    /** 原始 route 列表；未存过返回 null，让 [NavBarLayout.resolve] 走「默认」分支。 */
    fun readNavBarRoutes(): List<String>? {
        val raw = runCatching { preferences.getString(KEY_NAV_BAR, null) }.getOrNull() ?: return null
        if (raw.isBlank()) return null
        return raw.split(NAV_BAR_SEPARATOR)
    }

    /** 写入用户自定义布局。传空列表 = 清除自定义，回到默认。 */
    fun writeNavBar(pages: List<StartupPage>) {
        if (pages.isEmpty()) {
            preferences.edit().remove(KEY_NAV_BAR).apply()
            return
        }
        preferences.edit()
            .putString(KEY_NAV_BAR, pages.joinToString(NAV_BAR_SEPARATOR) { it.route })
            .apply()
    }

    /** 清除自定义布局，「恢复默认导航」走这里。 */
    fun clearNavBar() {
        preferences.edit().remove(KEY_NAV_BAR).apply()
    }

    companion object {
        private const val KEY = "startup_page"
        private const val KEY_NAV_BAR = "nav_bar_pages"
        private const val NAV_BAR_SEPARATOR = ","

        fun from(context: Context) = StartupPagePreferences(context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE))
    }
}
