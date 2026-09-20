package com.hnnujw.course.announcement

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 公告管理器（支持多条公告）
 *
 * **数据源随包内置**：`app/src/main/assets/announcement.json`。公告跟着安装版本走 ——
 * 改公告就改这个文件、重新出包；应用运行期不联网、不落缓存，断网和首次冷启动都能
 * 读到本版公告。
 *
 * 为什么不从线上拉（曾经从 Gitee Raw 取）：那样等于把"公告能不能被看到"押在一次
 * 外部发布动作上 —— 文件没推上去、或线上还停着旧版本，用户就什么都看不到；而
 * "没拉到"和"线上真没公告"在界面上长得一模一样，出了问题极难自查。内置后公告与
 * 代码同源、同一次构建、同一个版本号，不会再出现"代码更新了、公告还是旧的"。
 *
 * 两条贯穿全链路的规则：
 * 1. **公告要锁定版本**。每条公告声明自己适用于哪些 versionCode（`minVersionCode` /
 *    `maxVersionCode`），客户端只对命中的版本弹窗、计未读。没有声明版本范围的历史
 *    公告一律按"历史归档"处理，不再打扰新版本用户。
 * 2. **已读记录有序**。旧实现把已读 id 塞进无序的 `StringSet`，裁剪时 `takeLast(100)`
 *    在无序集合上等于随机丢 id，丢掉的那条公告过一阵子会重新变成未读（公告回潮）。
 *    现在存成 `id<TAB>时间戳` 的有序文本，裁剪丢的一定是最旧的那批。
 */
object AnnouncementManager {
    private const val TAG = "AnnouncementManager"
    internal const val PREFS_NAME_OF = "announcement_prefs"

    /** 随包内置的公告文件。改它 = 改公告，需要重新出包。 */
    internal const val ASSET_NAME = "announcement.json"

    /** 旧格式：无序 StringSet。只在迁移时读一次，读完就删。 */
    internal const val KEY_READ_IDS_LEGACY = "read_announcement_ids"

    /** 新格式：每行一条 `id<TAB>时间戳`（毫秒），越靠后越新。 */
    internal const val KEY_READ_RECORDS = "read_announcement_records"

    /** 已读记录上限。超出时丢弃时间戳最早的那批。 */
    private const val MAX_READ_RECORDS = 200

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME_OF, Context.MODE_PRIVATE)
    }

    /**
     * 公告数据类。
     *
     * @param createdAt 发布日期（ISO `YYYY-MM-DD`）。老公告没有这个字段，留空即可。
     * @param minVersionCode 适用版本下限（含）。0 = 不限。
     * @param maxVersionCode 适用版本上限（含）。0 = 不限。
     * @param hasVersionRange 文件里是否**显式**写过版本范围。
     */
    data class Announcement(
        val id: String,
        val title: String,
        val content: String,
        val type: String,  // info, warning, important
        val contentType: String = "text",  // text, markdown
        val showOnce: Boolean = true,
        val createdAt: String = "",
        val minVersionCode: Int = 0,
        val maxVersionCode: Int = 0,
        val hasVersionRange: Boolean = false
    ) {
        /**
         * 这条公告是否适用于 [versionCode]。
         *
         * 没有显式版本范围的公告返回 false：它们是写给"当时那个版本"看的，比如
         * "请手动下载新版"那条迁移公告，对新版本用户来说既过时又无意义。这类公告
         * 仍然保留在公告中心的历史列表里可查。
         */
        fun appliesTo(versionCode: Int): Boolean {
            if (!hasVersionRange) return false
            if (minVersionCode != 0 && versionCode < minVersionCode) return false
            if (maxVersionCode != 0 && versionCode > maxVersionCode) return false
            return true
        }

        /**
         * 这条公告此刻是否算「未读」= 适用于当前版本 且 还没读过。
         *
         * `showOnce = false` 的公告例外：它的语义就是"每次都要看到"，所以永远算未读，
         * 不理会已读记录。
         *
         * 写成不依赖 Android / Compose 的纯函数是有原因的：这里曾经把"是否已读"判断
         * 写反（`id !in readIds` 被当成"已读"来用），于是 `showOnce = true` 的公告
         * 永远算已读 —— 启动不弹窗、红点不亮、「未读」分段永远为空。当时公告逻辑
         * 一个测试都没有，269 个用例全绿也没兜住。现在由 [AnnouncementLogicTest] 盯着。
         *
         * @param readIds 已经标记过已读的公告 id
         * @param versionCode 当前安装版本的 versionCode
         */
        fun isUnreadIn(readIds: Set<String>, versionCode: Int): Boolean =
            appliesTo(versionCode) && (!showOnce || id !in readIds)
    }

    /** 已读记录：id + 首次标记已读的时间。 */
    data class ReadRecord(val id: String, val timeMs: Long)

    /**
     * 读取随包内置的公告。
     *
     * @return `null` = 读取 / 解析失败（只可能是打包漏了 assets 里的文件）；空列表 =
     *         文件里确实一条公告都没有。调用方对两者的处理可以一样（都显示"暂无公告"），
     *         但保留这个区分便于日志定位。
     */
    suspend fun loadAnnouncements(context: Context): List<Announcement>? {
        val app = context.applicationContext
        return withContext(Dispatchers.IO) {
            try {
                val json = app.assets.open(ASSET_NAME)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                if (json.isBlank()) {
                    Log.e(TAG, "内置公告文件为空: $ASSET_NAME")
                    return@withContext null
                }
                parseAnnouncements(json)
            } catch (e: Exception) {
                Log.e(TAG, "读取内置公告失败: ${e.message}")
                null
            }
        }
    }

    /**
     * 解析公告 JSON（支持 `{announcements:[...]}`、顶层数组、单条三种格式）。
     * 解析不出来返回 `null`。
     */
    internal fun parseAnnouncements(json: String): List<Announcement>? {
        return try {
            val trimmedJson = json.trim()
            when {
                // 标准格式：包含 announcements 数组
                trimmedJson.startsWith("{") && trimmedJson.contains("\"announcements\"") -> {
                    val obj = JSONObject(trimmedJson)
                    val arr = obj.optJSONArray("announcements") ?: return emptyList()
                    parseAnnouncementArray(arr)
                }
                // 数组格式
                trimmedJson.startsWith("[") -> parseAnnouncementArray(JSONArray(trimmedJson))
                // 单条公告格式（兼容旧格式）
                trimmedJson.startsWith("{") -> {
                    val announcement = parseSingleAnnouncement(JSONObject(trimmedJson))
                    if (announcement != null) listOf(announcement) else emptyList()
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析公告失败: ${e.message}")
            null
        }
    }

    private fun parseAnnouncementArray(arr: JSONArray): List<Announcement> {
        val result = mutableListOf<Announcement>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseSingleAnnouncement(obj)?.let { result.add(it) }
        }
        return result
    }

    private fun parseSingleAnnouncement(obj: JSONObject): Announcement? {
        return try {
            val id = obj.optString("id", "")
            val title = obj.optString("title", "")
            val content = obj.optString("content", "")

            // 跳过空公告
            if (id.isEmpty() || title.isEmpty() || content.isEmpty()) {
                return null
            }

            Announcement(
                id = id,
                title = title,
                content = content,
                type = obj.optString("type", "info"),
                contentType = obj.optString("contentType", "text"),
                showOnce = obj.optBoolean("showOnce", true),
                // 两个写法都认：文件里既有历史条目用的 created_at，也有新条目用的 createdAt。
                createdAt = obj.optString("createdAt", obj.optString("created_at", "")),
                minVersionCode = obj.optInt("minVersionCode", 0).coerceAtLeast(0),
                maxVersionCode = obj.optInt("maxVersionCode", 0).coerceAtLeast(0),
                hasVersionRange = obj.has("minVersionCode") || obj.has("maxVersionCode")
            )
        } catch (e: Exception) {
            null
        }
    }

    // ── 已读记录 ────────────────────────────────────────────────────────

    /**
     * 读取已读记录（按时间从旧到新）。
     *
     * 顺带把旧格式（无序 StringSet）迁移过来：旧记录没有时间戳，统一补 0，
     * 于是它们天然排在"最旧"的一端，被裁剪时优先丢弃——这正是我们想要的，
     * 因为旧记录本来就没有可信的先后顺序。
     */
    fun readRecords(context: Context): List<ReadRecord> {
        val store = getPrefs(context)
        val raw = migrateLegacyReadIds(store) ?: store.getString(KEY_READ_RECORDS, null).orEmpty()
        // 同一个 id 可能同时存在于新旧两份记录里，按时间取较晚的一次。
        return parseReadRecords(raw)
            .groupBy { it.id }
            .map { (id, records) -> ReadRecord(id, records.maxOf { it.timeMs }) }
            .sortedBy { it.timeMs }
    }

    /** 已读公告 id 集合（红点 / 列表状态判定用）。 */
    fun readIds(context: Context): Set<String> =
        readRecords(context).mapTo(HashSet()) { it.id }

    /** 标记公告已读；重复标记会刷新时间戳，让它更不容易被裁剪掉。 */
    fun markAsRead(context: Context, announcementId: String) {
        if (announcementId.isBlank()) return
        val records = readRecords(context)
            .filterNot { it.id == announcementId }
            .toMutableList()
        records.add(ReadRecord(announcementId, System.currentTimeMillis()))
        val trimmed = records.sortedBy { it.timeMs }.takeLast(MAX_READ_RECORDS)
        getPrefs(context).edit()
            .putString(KEY_READ_RECORDS, encodeReadRecords(trimmed))
            .apply()
    }

    private fun migrateLegacyReadIds(store: SharedPreferences): String? {
        val legacy = store.getStringSet(KEY_READ_IDS_LEGACY, null) ?: return null
        val current = store.getString(KEY_READ_RECORDS, null).orEmpty()
        val known = parseReadRecords(current).mapTo(HashSet()) { it.id }
        val merged = StringBuilder(current)
        legacy.sorted().forEach { id ->
            if (id.isNotBlank() && id !in known) {
                if (merged.isNotEmpty()) merged.append('\n')
                merged.append(id).append('\t').append(0L)
            }
        }
        store.edit()
            .putString(KEY_READ_RECORDS, merged.toString())
            .remove(KEY_READ_IDS_LEGACY)
            .apply()
        return merged.toString()
    }

    internal fun parseReadRecords(raw: String): List<ReadRecord> =
        raw.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val separator = trimmed.lastIndexOf('\t')
                val id = if (separator < 0) trimmed else trimmed.substring(0, separator)
                val time = if (separator < 0) 0L else trimmed.substring(separator + 1).toLongOrNull() ?: 0L
                if (id.isBlank()) null else ReadRecord(id, time)
            }
            .toList()

    internal fun encodeReadRecords(records: List<ReadRecord>): String =
        records.joinToString("\n") { "${it.id}\t${it.timeMs}" }
}
