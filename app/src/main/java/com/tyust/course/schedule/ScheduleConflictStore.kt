package com.tyust.course.schedule

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tyust.course.manager.UserManager
import org.json.JSONObject

/**
 * 课表时间冲突的「本节上哪门」选择。
 *
 * key = "$day:$period"（冲突重叠覆盖到的每一节），value = 用户选择要上的课程 id。
 * 只在课程详情弹层里写入；课表渲染时，一门课只要覆盖到任何一个「选了别人」的节次
 * 就整体让位隐藏 —— 两门课完全同段时，表现就是经典的"只显示选中的那门"。
 *
 * 为什么 key 只到「节」而不是整门课：两门冲突课的跨度可能不同
 * （A 占 1-2 节、B 占 2-3 节，只在第 2 节撞车），按节记录能把冲突精确限制在
 * 真正重叠的那几节上，A 在第 1 节的独立时段不受影响。
 *
 * 状态是进程内 Compose state：课表页与详情弹层直接观察，选择后当帧生效。
 * 持久化按账号隔离存 SharedPreferences（文件名带账号 storage key），
 * 切账号 / 冷启动时由课表路由调用 [refresh] 重载。
 */
object ScheduleConflictStore {

    var choices: Map<String, String> by mutableStateOf(emptyMap())
        private set

    private var prefs: SharedPreferences? = null
    private var loadedKey: String? = null

    /** 切账号或冷启动后重载当前账号的选择。同一账号重复调用是空操作。 */
    fun refresh(context: Context) {
        val key = if (UserManager.getInstance().isDemoMode) ""
        else UserManager.getInstance().currentAccountStorageKey
        if (loadedKey == key) return
        loadedKey = key
        prefs = context.applicationContext.getSharedPreferences(
            if (key.isBlank()) "schedule_conflict_demo" else "schedule_conflict_$key",
            Context.MODE_PRIVATE
        )
        choices = parse(prefs?.getString("choices", null))
    }

    /** 在 [day] 天的第 [startPeriod]–[endPeriod] 节都选择上 [courseId] 这门课。 */
    fun choose(day: Int, startPeriod: Int, endPeriod: Int, courseId: String) {
        val updated = choices.toMutableMap()
        for (p in startPeriod..endPeriod) updated["$day:$p"] = courseId
        write(updated)
    }

    /** 清除 [day] 天第 [startPeriod]–[endPeriod] 节的选择（恢复显示全部冲突课程）。 */
    fun clear(day: Int, startPeriod: Int, endPeriod: Int) {
        val updated = choices.toMutableMap()
        for (p in startPeriod..endPeriod) updated.remove("$day:$p")
        write(updated)
    }

    /** 该时段是否存在任何选择（决定"恢复显示全部"按钮的可见性）。 */
    fun hasChoiceIn(day: Int, startPeriod: Int, endPeriod: Int): Boolean =
        choices.keys.any { key ->
            key.startsWith("$day:") && key.removePrefix("$day:").toIntOrNull() in startPeriod..endPeriod
        }

    private fun write(map: Map<String, String>) {
        choices = map
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs?.edit()?.putString("choices", obj.toString())?.apply()
    }

    private fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val out = linkedMapOf<String, String>()
            obj.keys().forEach { k -> out[k] = obj.optString(k) }
            out
        }.getOrDefault(emptyMap())
    }
}
