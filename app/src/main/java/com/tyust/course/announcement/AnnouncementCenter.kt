package com.tyust.course.announcement

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 开发者公告中心：把每次发布的公告以列表形式呈现在「我的」页，
 * 与消息中心一样带红点（未读数）。
 *
 * 与 [MessageCenterManager] 的差别：
 * - 消息中心 = 教务系统里的站内消息，按账号隔离，需要登录；
 * - 公告中心 = 开发者面向全体用户的公告，**全局共享、无需登录**，数据来自
 *   仓库根目录的 `announcement.json`（Gitee Raw），发公告 = 改仓库，无需发版。
 *
 * 离线兜底：拉取成功后把原始 JSON 缓存进 prefs，之后断网也能渲染列表与红点。
 */
object AnnouncementCenter {

    private const val PREFS = "announcement_center"
    private const val KEY_CACHE = "cached_json"
    private const val KEY_LAST_FETCH = "last_fetch_ms"

    /** 节流：10 分钟内不重复请求（「我的」页每次可见都会调 refresh）。 */
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L

    /** 当前已知的全部公告（含已读）。离线时来自缓存。 */
    var announcements by mutableStateOf<List<AnnouncementManager.Announcement>>(emptyList())
        private set

    /** 未读公告数：「我的」页公告入口红点。 */
    var unreadCount by mutableIntStateOf(0)
        private set

    var isLoading by mutableStateOf(false)
        private set

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 启动 / 「我的」页可见时调用。默认节流；force = 用户在列表里手动刷新。 */
    suspend fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val prefs = prefs(app)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_FETCH, 0L) < MIN_INTERVAL_MS) {
            // 节流窗口内也要保证内存态有值（冷启动后的第一次进入）
            if (announcements.isEmpty()) loadFromCache(app)
            return
        }
        isLoading = true
        val fetched = try {
            AnnouncementManager.fetchAllAnnouncements()
        } finally {
            isLoading = false
        }
        if (fetched.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_CACHE, encode(fetched))
                .putLong(KEY_LAST_FETCH, now)
                .apply()
        }
        if (announcements.isEmpty() || fetched.isNotEmpty()) {
            announcements = fetched.ifEmpty { decode(prefs.getString(KEY_CACHE, null)) }
            recount(app)
        }
    }

    /** 列表页点开一条 → 已读，红点当帧减少。 */
    fun markRead(context: Context, id: String) {
        AnnouncementManager.markAsRead(context, id)
        recount(context)
    }

    val firstUnread: AnnouncementManager.Announcement?
        get() = announcements.firstOrNull { !isRead(it.id) }

    /** 供弹窗判断单条是否已读；快照在每次 recount 时随已读集合刷新。 */
    private val unreadIdsSnapshot = mutableSetOf<String>()

    fun isRead(id: String): Boolean = id !in unreadIdsSnapshot

    private fun recount(context: Context) {
        val ids = context.applicationContext.getSharedPreferences(AnnouncementManager.PREFS_NAME_OF, Context.MODE_PRIVATE)
            .getStringSet(AnnouncementManager.KEY_READ_IDS, emptySet()).orEmpty()
        unreadIdsSnapshot.clear()
        announcements.forEach { if (it.id !in ids) unreadIdsSnapshot.add(it.id) }
        unreadCount = unreadIdsSnapshot.size
    }

    private fun encode(list: List<AnnouncementManager.Announcement>): String =
        org.json.JSONArray().apply {
            list.forEach { a ->
                put(org.json.JSONObject()
                    .put("id", a.id).put("title", a.title).put("content", a.content)
                    .put("type", a.type).put("contentType", a.contentType).put("showOnce", a.showOnce))
            }
        }.toString()

    private fun decode(raw: String?): List<AnnouncementManager.Announcement> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                AnnouncementManager.Announcement(
                    id = o.optString("id"), title = o.optString("title"), content = o.optString("content"),
                    type = o.optString("type", "info"), contentType = o.optString("contentType", "text"),
                    showOnce = o.optBoolean("showOnce", true)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun loadFromCache(context: Context) {
        announcements = decode(prefs(context).getString(KEY_CACHE, null))
        recount(context)
    }
}
