package com.hnnujw.course.widgetboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * App 内组件的尺寸档位。
 *
 * 网格固定 4 列（见 `WidgetBoardScreen`），[columns] 是占几列。
 *
 * [rows] **不参与真正的二维占格** —— LazyVerticalGrid 的一行高度由该行最高的
 * 条目决定，所以"高卡"自然会把整行撑起来，这正是桌面组件那套"小/中/大"的
 * 心智模型。真去做二维占格需要一整套碰撞 / 重排算法，收益却只是让半高卡片
 * 能错位贴在一起，不值得。
 *
 * [heightDp] 交给卡片当固定高度：同一行里两张卡的**行数相同、高度就必须相同**，
 * 否则一张高一张矮，视觉上会散。
 */
enum class WidgetSize(val columns: Int, val rows: Int, val label: String) {
    Small(2, 1, "小"),
    Wide(4, 1, "宽"),
    Medium(2, 2, "中"),
    Large(4, 2, "大");

    /** 卡片在网格里的固定高度（dp）。 */
    val heightDp: Int get() = if (rows >= 2) 196 else 96

    /** 内容是否够地方铺列表。 */
    val roomy: Boolean get() = rows >= 2

    companion object {
        fun fromName(raw: String?): WidgetSize = entries.firstOrNull { it.name == raw } ?: Small
    }
}

/** 工作台上的一张卡片：一个数据源 + 一个尺寸。 */
data class WidgetInstance(
    val id: String,
    val sourceId: String,
    val size: WidgetSize,
)

data class WidgetBoardLayout(val instances: List<WidgetInstance>)

/**
 * 工作台布局的持久化。
 *
 * 布局**不按账号隔离**：卡片摆在哪、多大，是这台设备上的个人习惯，跟登的是谁
 * 无关（与 [com.hnnujw.course.schedule.ScheduleDisplayStore] 同一条理由）。
 * 卡片里**装的数据**仍然按账号隔离 —— 换账号后读到的是新账号的课表 / 成绩，
 * 只是摆放位置不跟着变。
 */
object WidgetBoardStore {
    private const val PREFS_NAME = "widget_board"
    private const val KEY_LAYOUT = "layout"

    fun load(context: Context): WidgetBoardLayout =
        load(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun save(context: Context, layout: WidgetBoardLayout) =
        save(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), layout)

    /**
     * 编解码部分只依赖 [SharedPreferences]，不碰 Context —— 与
     * [com.hnnujw.course.schedule.ScheduleCacheStore] 同一条约定：
     * 这样布局的往返与容错能在纯 JVM 单测里验，不用拉起 Robolectric。
     */
    fun load(prefs: SharedPreferences): WidgetBoardLayout {
        val raw = prefs.getString(KEY_LAYOUT, null)
        // 从没保存过 → 默认摆法。
        if (raw.isNullOrBlank()) return defaultLayout()
        val parsed = runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sourceId = o.optString("source")
                    // 卡片下架过（或用户装过更老的版本）时，布局里可能留着已经不存在的
                    // id。必须在这里丢掉，否则会渲染出一个永远空白的格子。
                    if (InAppWidgetRegistry.find(sourceId) == null) continue
                    add(
                        WidgetInstance(
                            id = o.optString("id").ifBlank { "$sourceId-$i" },
                            sourceId = sourceId,
                            size = WidgetSize.fromName(o.optString("size")),
                        )
                    )
                }
            }
        }.getOrNull() ?: return defaultLayout()
        // 空列表是**合法状态**（用户把卡片全删了，页面显示空态引导），
        // 绝不能当成"解析失败"而回退到默认布局 —— 那会让用户删不掉卡片。
        return WidgetBoardLayout(parsed)
    }

    fun save(prefs: SharedPreferences, layout: WidgetBoardLayout) {
        val arr = JSONArray()
        layout.instances.forEach { inst ->
            arr.put(
                JSONObject().apply {
                    put("id", inst.id)
                    put("source", inst.sourceId)
                    put("size", inst.size.name)
                }
            )
        }
        prefs.edit().putString(KEY_LAYOUT, arr.toString()).apply()
    }

    /**
     * 首次进入的默认摆法。刻意排成三行都占满的样子：
     * 整宽课表 → 整宽倒计时 → 两张半宽数据卡，一眼就能看懂"卡片可以这样拼"。
     */
    fun defaultLayout(): WidgetBoardLayout = WidgetBoardLayout(
        listOf(
            WidgetInstance("board-schedule", "schedule.today", WidgetSize.Large),
            WidgetInstance("board-exam", "exam.countdown", WidgetSize.Wide),
            WidgetInstance("board-grades", "grades.overview", WidgetSize.Small),
            WidgetInstance("board-messages", "message.unread", WidgetSize.Small),
        )
    )
}
