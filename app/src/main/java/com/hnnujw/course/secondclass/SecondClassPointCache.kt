package com.hnnujw.course.secondclass

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「分类与学期统计」积分明细的本地缓存。
 *
 * 与 [SecondClassOverviewCache] 同一套"缓存优先、后台静默刷新"的做法，但**分开存**：
 * 概览缓存只有几十字节的模块汇总，会随桌面组件一起被高频读取；明细是几百条记录，
 * 混在同一个 prefs 里会让每次读概览都顺带反序列化整份明细。
 *
 * 按账号隔离 —— 明细里全是"我参加了什么"，串账号就是严重的隐私问题。
 * 键口径与 [SecondClassroomStore.normalize] 一致（两种账号键写法必须落到同一个键，
 * 否则会出现"写进一个键、读另一个键"的经典 bug）。
 */
object SecondClassPointCache {

    private const val TAG = "SecondClassPoint"
    private const val PREFS_NAME = "second_class_points"
    private const val KEY_PREFIX = "ledger_"
    private const val KEY_AT_PREFIX = "at_"

    /** 明细比概览变得更快（活动结束就加分），6 小时后重新拉一次。 */
    private const val FRESH_WINDOW_MS = 6L * 60 * 60 * 1000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun normalize(accountKey: String): String =
        accountKey.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun ledgerKey(key: String) = KEY_PREFIX + key
    private fun atKey(key: String) = KEY_AT_PREFIX + key

    /** 带时间戳的缓存结果。 */
    data class Cached(val ledger: SecondClassPointLedger, val updatedAt: Long)

    fun load(context: Context, accountKey: String): Cached? {
        val key = normalize(accountKey)
        val p = prefs(context)
        val at = p.getLong(atKey(key), 0L)
        if (at <= 0L) return null
        val raw = p.getString(ledgerKey(key), null) ?: return null
        val ledger = runCatching { decode(JSONObject(raw)) }.getOrElse { e ->
            // 解析失败说明结构变了（或写坏了）：丢弃，下次联网自然重建。
            // 保留一份坏缓存只会让界面永远停在空态。
            Log.w(TAG, "积分明细缓存解析失败，丢弃: ${e.message}")
            clearAccount(context, key)
            return null
        }
        return Cached(ledger, at)
    }

    fun save(context: Context, accountKey: String, ledger: SecondClassPointLedger) {
        val key = normalize(accountKey)
        runCatching {
            prefs(context).edit()
                .putString(ledgerKey(key), encode(ledger).toString())
                .putLong(atKey(key), System.currentTimeMillis())
                .apply()
        }.onFailure { Log.w(TAG, "积分明细缓存写入失败: ${it.message}") }
    }

    /** 超过这个时长才值得再拉一次；与 [isStale] 配对。 */
    fun isStale(updatedAt: Long): Boolean =
        updatedAt <= 0L || (System.currentTimeMillis() - updatedAt) > FRESH_WINDOW_MS

    fun clearAccount(context: Context, accountKey: String) {
        val key = normalize(accountKey)
        prefs(context).edit()
            .remove(ledgerKey(key))
            .remove(atKey(key))
            .apply()
    }

    // ── 序列化 ────────────────────────────────────────────────────────────
    //
    // 手写而不是用 kotlinx.serialization：本工程其余缓存（GradesCacheManager、
    // SecondClassOverviewCache）都是 org.json 手写，保持一致；且这层结构很浅，
    // 引入一个序列化插件只为这一个类是净负担。

    internal fun encode(ledger: SecondClassPointLedger): JSONObject = JSONObject()
        .put("profileScore", ledger.profileScore)
        .put("classifyHasMore", ledger.classifyHasMore)
        .put("termHasMore", ledger.termHasMore)
        .put("byClassify", encodeGroups(ledger.byClassify))
        .put("byTerm", encodeGroups(ledger.byTerm))

    internal fun decode(json: JSONObject): SecondClassPointLedger = SecondClassPointLedger(
        byClassify = decodeGroups(json.optJSONArray("byClassify")),
        byTerm = decodeGroups(json.optJSONArray("byTerm")),
        profileScore = json.optDouble("profileScore", 0.0),
        classifyHasMore = json.optBoolean("classifyHasMore", false),
        termHasMore = json.optBoolean("termHasMore", false),
    )

    private fun encodeGroups(groups: List<SecondClassPointGroup>): JSONArray {
        val arr = JSONArray()
        groups.forEach { group ->
            arr.put(
                JSONObject().apply {
                    put("name", group.name)
                    // siteTotal 是可空语义，必须显式写 null 才能与"站点给了 0"区分
                    put("siteTotal", group.siteTotal ?: JSONObject.NULL)
                    put("required", group.required)
                    put("termNumber", group.termNumber)
                    put("unit", group.unit)
                    put("records", encodeRecords(group.records))
                }
            )
        }
        return arr
    }

    private fun encodeRecords(records: List<SecondClassPointRecord>): JSONArray {
        val arr = JSONArray()
        records.forEach { record ->
            arr.put(
                JSONObject().apply {
                    put("name", record.name)
                    put("hours", record.hours)
                    put("amount", record.amount)
                    put("time", record.time)
                    put("classifyName", record.classifyName)
                    put("sourceType", record.sourceType)
                    put("relationId", record.relationId)
                }
            )
        }
        return arr
    }

    private fun decodeGroups(arr: JSONArray?): List<SecondClassPointGroup> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            val group = arr.optJSONObject(index) ?: return@mapNotNull null
            SecondClassPointGroup(
                name = group.optString("name", ""),
                siteTotal = group.opt("siteTotal")
                    ?.takeIf { it !== JSONObject.NULL }
                    ?.toString()?.toDoubleOrNull(),
                required = group.optDouble("required", 0.0),
                records = decodeRecords(group.optJSONArray("records")),
                termNumber = group.optString("termNumber", ""),
                unit = group.optString("unit", ""),
            )
        }
    }

    private fun decodeRecords(arr: JSONArray?): List<SecondClassPointRecord> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            val record = arr.optJSONObject(index) ?: return@mapNotNull null
            SecondClassPointRecord(
                name = record.optString("name", ""),
                hours = record.optDouble("hours", 0.0),
                amount = record.optDouble("amount", 0.0),
                time = record.optLong("time", 0L),
                classifyName = record.optString("classifyName", ""),
                sourceType = record.optInt("sourceType", 0),
                relationId = record.optString("relationId", ""),
            )
        }
    }
}
