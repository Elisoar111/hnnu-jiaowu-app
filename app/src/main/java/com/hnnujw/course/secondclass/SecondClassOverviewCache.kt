package com.hnnujw.course.secondclass

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 第二课堂「我的积分」的本地缓存。
 *
 * 工作台上的积分卡片要在**打开 App 的当帧**就有东西可看，而二课概览必须联网。
 * 所以卡片只读这里、由 `WidgetBoardRoute` 在后台拉一次并写回来 —— 与
 * [com.hnnujw.course.manager.GradesCacheManager] 同一套"缓存优先、后台静默刷新"
 * 的做法，失败时保留旧数据而不是把卡片打回空白。
 *
 * 按账号隔离（二课 token 就是按账号存的，混用会读到别人的积分）。
 */
object SecondClassOverviewCache {
    private const val TAG = "SecondClassOverview"
    private const val PREFS_NAME = "second_class_overview"
    private const val KEY_MODULES_PREFIX = "modules_"
    private const val KEY_AT_PREFIX = "at_"
    private const val KEY_UNIT_PREFIX = "unit_"

    /** 半天。积分不会一天变好几次，超过这个时长才值得再拉一次。 */
    private const val FRESH_WINDOW_MS = 12L * 60 * 60 * 1000

    /**
     * @param hourUnit 积分单位（站点下发的 `hourUnit`，如"学时"/"学分"/"分数"）。
     *
     * 必须与模块一起缓存：卡片要显示 `mine / required` 这类数值，而单位只存在于
     * [SecondClassProfile.hourUnit] 里，[SecondClassModule] 没有这个字段。不缓存它，
     * 渲染层就只能写死"学时" —— 那会与同一张卡片上的"总积分"自相矛盾，且在
     * 学分制/分数制的学校上是错的。
     */
    data class Cached(
        val modules: List<SecondClassModule>,
        val updatedAt: Long,
        val hourUnit: String = "",
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun normalize(accountKey: String): String =
        accountKey.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun modulesKey(key: String) = KEY_MODULES_PREFIX + key
    private fun atKey(key: String) = KEY_AT_PREFIX + key
    private fun unitKey(key: String) = KEY_UNIT_PREFIX + key

    /** 读取缓存；没写过或 JSON 损坏时返回 null（卡片显示"尚未同步"）。 */
    fun load(context: Context, accountKey: String): Cached? {
        val key = normalize(accountKey)
        val p = prefs(context)
        val at = p.getLong(atKey(key), 0L)
        if (at <= 0L) return null
        val raw = p.getString(modulesKey(key), null) ?: return null
        val modules = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SecondClassModule(
                    name = o.optString("name"),
                    mine = o.optDouble("mine", 0.0),
                    required = o.optDouble("required", 0.0),
                    average = o.optDouble("average", 0.0),
                )
            }
        }.getOrElse { e ->
            Log.w(TAG, "积分缓存解析失败，丢弃: ${e.message}")
            clearAccount(context, key)
            return null
        }
        // 老缓存（本次改动之前写的）没有 unit_ 键：缺省空串 → 渲染层不显示单位，
        // 下次联网刷新时自然补齐，不需要为它做迁移。
        return Cached(modules, at, p.getString(unitKey(key), "").orEmpty())
    }

    fun save(context: Context, accountKey: String, modules: List<SecondClassModule>, hourUnit: String) {
        val key = normalize(accountKey)
        val arr = JSONArray()
        modules.forEach { m ->
            arr.put(
                JSONObject().apply {
                    put("name", m.name)
                    put("mine", m.mine)
                    put("required", m.required)
                    put("average", m.average)
                }
            )
        }
        runCatching {
            prefs(context).edit()
                .putString(modulesKey(key), arr.toString())
                .putString(unitKey(key), hourUnit)
                .putLong(atKey(key), System.currentTimeMillis())
                .apply()
        }.onFailure { Log.w(TAG, "积分缓存写入失败: ${it.message}") }
    }

    fun isStale(updatedAt: Long): Boolean =
        updatedAt <= 0L || (System.currentTimeMillis() - updatedAt) > FRESH_WINDOW_MS

    fun clearAccount(context: Context, accountKey: String) {
        val key = normalize(accountKey)
        prefs(context).edit()
            .remove(modulesKey(key))
            .remove(atKey(key))
            .remove(unitKey(key))
            .apply()
    }
}
