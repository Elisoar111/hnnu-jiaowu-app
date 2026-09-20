package com.hnnujw.course.manager

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 课表设置管理器 - 管理节次时间、第一周日期、自定义课程
 */
class ScheduleSettingsManager internal constructor(private var prefs: SharedPreferences? = null) {
    var revision by mutableIntStateOf(0)
        private set
    
    companion object {
        private const val PREFS_NAME = "schedule_settings"
        private const val KEY_SEMESTER_START = "semester_start"
        private const val KEY_PERIOD_TIMES = "period_times"
        private const val KEY_CUSTOM_COURSES = "custom_courses"
        private const val KEY_PERIOD_COUNT = "period_count"
        private const val KEY_FONT_SCALE = "font_scale"

        /**
         * 课表字号倍率（只作用于课表网格内的文字，不碰系统字体与其它页面）。
         *
         * 上下限是**排版**定出来的，不是审美：单元格高度由课程卡片文字实测决定
         * （见 ScheduleScreen.rememberCoursePeriodHeight），放大到 1.3 倍时一屏仍能
         * 放下 12 节课；再大就会出现"一行字都排不下 → 节点在、像素全无"的老问题。
         */
        const val MIN_FONT_SCALE = 0.85f
        const val DEFAULT_FONT_SCALE = 1.0f
        const val MAX_FONT_SCALE = 1.30f

        @Volatile
        private var instance: ScheduleSettingsManager? = null
        
        fun getInstance(): ScheduleSettingsManager {
            return instance ?: synchronized(this) {
                instance ?: ScheduleSettingsManager().also { instance = it }
            }
        }
    }
    
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun accountStorageKey(): String {
        return UserManager.getInstance().currentAccountStorageKey.ifBlank { "default" }
    }

    private fun scopedKey(key: String): String {
        return "${key}_${accountStorageKey()}"
    }

    private fun getScopedInt(key: String, defaultValue: Int): Int {
        val scoped = scopedKey(key)
        val p = prefs ?: return defaultValue
        if (!p.contains(scoped) && p.contains(key)) {
            val value = p.getInt(key, defaultValue)
            p.edit().putInt(scoped, value).remove(key).apply()
            return value
        }
        return p.getInt(scoped, defaultValue)
    }

    private fun getScopedLong(key: String, defaultValue: Long): Long {
        val scoped = scopedKey(key)
        val p = prefs ?: return defaultValue
        if (!p.contains(scoped) && p.contains(key)) {
            val value = p.getLong(key, defaultValue)
            p.edit().putLong(scoped, value).remove(key).apply()
            return value
        }
        return p.getLong(scoped, defaultValue)
    }

    private fun getScopedFloat(key: String, defaultValue: Float): Float {
        val scoped = scopedKey(key)
        val p = prefs ?: return defaultValue
        if (!p.contains(scoped) && p.contains(key)) {
            val value = p.getFloat(key, defaultValue)
            p.edit().putFloat(scoped, value).remove(key).apply()
            return value
        }
        return p.getFloat(scoped, defaultValue)
    }

    private fun getScopedString(key: String): String? {
        val scoped = scopedKey(key)
        val p = prefs ?: return null
        if (!p.contains(scoped) && p.contains(key)) {
            val value = p.getString(key, null)
            p.edit().putString(scoped, value).remove(key).apply()
            return value
        }
        return p.getString(scoped, null)
    }
    
    // ============ 节次数量 ============
    
    var periodCount: Int
        get() = getScopedInt(KEY_PERIOD_COUNT, 12)
        set(value) {
            prefs?.edit()?.putInt(scopedKey(KEY_PERIOD_COUNT), value)?.remove(KEY_PERIOD_COUNT)?.apply()
            revision++
        }
    
    // ============ 第一周日期 ============
    
    var semesterStartDate: Long
        get() = getScopedLong(KEY_SEMESTER_START, 0L)
        set(value) {
            if (semesterStartDate == value) return
            prefs?.edit()?.putLong(scopedKey(KEY_SEMESTER_START), value)?.remove(KEY_SEMESTER_START)?.apply()
            revision++
        }
    
    /**
     * 根据第一周日期计算当前是第几周
     */
    fun calculateCurrentWeek(): Int {
        val startDate = semesterStartDate
        if (startDate == 0L) return 1
        
        val now = System.currentTimeMillis()
        val diffDays = (now - startDate) / (1000 * 60 * 60 * 24)
        val week = (diffDays / 7).toInt() + 1
        return week.coerceIn(1, 25)
    }
    
    /**
     * 获取学期开始日期的 Calendar 对象（用于 iCal 导出）
     */
    fun getSemesterStartCalendar(): Calendar {
        val calendar = Calendar.getInstance()
        val startDate = semesterStartDate
        if (startDate > 0L) {
            calendar.timeInMillis = startDate
        } else {
            // 默认使用当前学期的开始日期（假设9月第一个周一）
            calendar.set(Calendar.MONTH, Calendar.SEPTEMBER)
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            // 找到第一个周一
            while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        return calendar
    }
    
    // ============ 课表字号 ============
    
    /**
     * 课表内文字的缩放倍率，默认 1.0（= 系统字号下的原样）。
     *
     * 实现上不是逐个 Text 改字号，而是在 `ScheduleGrid` 里把 [androidx.compose.ui.unit.Density]
     * 的 `fontScale` 乘上这个值：dp 不受影响（网格几何、单元格高度全不变），
     * 只有 sp 换算出的像素变大 —— 这正是"只放大课表的字、不动布局"。
     */
    var scheduleFontScale: Float
        get() = getScopedFloat(KEY_FONT_SCALE, DEFAULT_FONT_SCALE)
            .coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        set(value) {
            val clamped = value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
            if (scheduleFontScale == clamped) return
            prefs?.edit()?.putFloat(scopedKey(KEY_FONT_SCALE), clamped)?.remove(KEY_FONT_SCALE)?.apply()
            revision++
        }
    
    // ============ 节次时间 ============
    
    data class PeriodTime(
        val period: Int,
        val startTime: String,
        val endTime: String
    )
    
    fun getPeriodTimes(): List<PeriodTime> {
        val json = getScopedString(KEY_PERIOD_TIMES)
        if (json != null) {
            try {
                val array = JSONArray(json)
                val list = mutableListOf<PeriodTime>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(PeriodTime(
                        period = obj.getInt("period"),
                        startTime = obj.getString("start"),
                        endTime = obj.getString("end")
                    ))
                }
                return list
            } catch (e: Exception) {
                // ignore
            }
        }
        return getDefaultPeriodTimes()
    }
    
    fun savePeriodTimes(times: List<PeriodTime>) {
        val array = JSONArray()
        times.forEach { pt ->
            val obj = JSONObject()
            obj.put("period", pt.period)
            obj.put("start", pt.startTime)
            obj.put("end", pt.endTime)
            array.put(obj)
        }
        prefs?.edit()
            ?.putString(scopedKey(KEY_PERIOD_TIMES), array.toString())
            ?.remove(KEY_PERIOD_TIMES)
            ?.apply()
    }
    
    fun getDefaultPeriodTimes(): List<PeriodTime> {
        return listOf(
            PeriodTime(1, "08:00", "08:45"),
            PeriodTime(2, "08:55", "09:40"),
            PeriodTime(3, "10:00", "10:45"),
            PeriodTime(4, "10:55", "11:40"),
            PeriodTime(5, "14:00", "14:45"),
            PeriodTime(6, "14:55", "15:40"),
            PeriodTime(7, "16:00", "16:45"),
            PeriodTime(8, "16:55", "17:40"),
            PeriodTime(9, "19:00", "19:45"),
            PeriodTime(10, "19:55", "20:40"),
            PeriodTime(11, "20:50", "21:35"),
            PeriodTime(12, "21:45", "22:30")
        )
    }
    
    // ============ 自定义课程 ============
    
    data class CustomCourse(
        val id: String,
        val name: String,
        val location: String,
        val teacher: String,
        val day: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val weeks: String
    )
    
    private fun customCoursesKey(accountKey: String): String {
        return "${KEY_CUSTOM_COURSES}_$accountKey"
    }
    
    fun getCustomCourses(accountKey: String = accountStorageKey()): List<CustomCourse> {
        val scoped = customCoursesKey(accountKey)
        val json = prefs?.getString(scoped, null)
            ?: prefs?.getString(KEY_CUSTOM_COURSES, null)?.also { legacy ->
                prefs?.edit()
                    ?.putString(scoped, legacy)
                    ?.remove(KEY_CUSTOM_COURSES)
                    ?.apply()
            }
            ?: return emptyList()
        try {
            val array = JSONArray(json)
            val list = mutableListOf<CustomCourse>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(CustomCourse(
                    id = obj.optString("id", ""),
                    name = obj.getString("name"),
                    location = obj.optString("location", ""),
                    teacher = obj.optString("teacher", ""),
                    day = obj.getInt("day"),
                    startPeriod = obj.getInt("startPeriod"),
                    endPeriod = obj.getInt("endPeriod"),
                    weeks = obj.optString("weeks", "1-16周")
                ))
            }
            val seen = mutableSetOf<String>()
            val repaired = list.map { course ->
                if (course.id.isNotBlank() && seen.add(course.id)) course
                else course.copy(id = java.util.UUID.randomUUID().toString()).also { seen.add(it.id) }
            }
            if (repaired != list) saveCustomCourses(repaired, accountKey)
            return repaired
        } catch (e: Exception) {
            return emptyList()
        }
    }
    
    fun addCustomCourse(course: CustomCourse, accountKey: String = accountStorageKey()) {
        updateCustomCourse(course, accountKey)
    }
    
    fun updateCustomCourse(course: CustomCourse, accountKey: String = accountStorageKey()) {
        require(accountKey.isNotBlank() && course.id.isNotBlank())
        val existing = getCustomCourses(accountKey)
        val courses = if (existing.any { it.id == course.id }) existing.map { if (it.id == course.id) course else it }
            else existing + course
        saveCustomCourses(courses, accountKey)
    }
    
    fun removeCustomCourse(courseId: String, accountKey: String = accountStorageKey()) {
        saveCustomCourses(getCustomCourses(accountKey).filter { it.id != courseId }, accountKey)
    }

    private fun saveCustomCourses(courses: List<CustomCourse>, accountKey: String) {
        val array = JSONArray()
        courses.forEach { c ->
            val obj = JSONObject()
            obj.put("id", c.id)
            obj.put("name", c.name)
            obj.put("location", c.location)
            obj.put("teacher", c.teacher)
            obj.put("day", c.day)
            obj.put("startPeriod", c.startPeriod)
            obj.put("endPeriod", c.endPeriod)
            obj.put("weeks", c.weeks)
            array.put(obj)
        }
        prefs?.edit()
            ?.putString(customCoursesKey(accountKey), array.toString())
            ?.remove(KEY_CUSTOM_COURSES)
            ?.apply()
        revision++
    }
}
