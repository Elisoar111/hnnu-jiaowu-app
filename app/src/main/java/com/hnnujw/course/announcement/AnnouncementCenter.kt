package com.hnnujw.course.announcement

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hnnujw.course.BuildConfig

/**
 * 开发者公告中心：公告的全量数据源与未读计数。
 *
 * 与 [com.hnnujw.course.academic.MessageCenterManager] 的差别：
 * - 消息中心 = 教务系统里的站内消息，按账号隔离、需要登录、要联网；
 * - 公告中心 = 开发者面向全体用户的公告，**全局共享、无需登录、不联网**，随包内置在
 *   `assets/announcement.json`（见 [AnnouncementManager]），发公告 = 改文件 + 出新版。
 *
 * **版本锁定**：公告按 [BuildConfig.VERSION_CODE] 过滤。只有显式声明了版本范围、
 * 且当前安装版本落在这个范围内的公告，才会弹启动弹窗、才计入红点；其余的（含所有
 * 没声明版本范围的历史公告）留在公告中心的「已读 / 历史」里可查，但不再打扰用户。
 * 这条规则的由来：老版本那条"请手动下载新版"的迁移公告没有版本字段，于是一直挂在
 * 所有新版本的未读里，每次进「我的」都提示一次。
 */
object AnnouncementCenter {

    /** 当前安装版本的 versionCode。编译期常量，不需要跟着 context 初始化。 */
    val appVersionCode: Int = BuildConfig.VERSION_CODE

    /** 当前已知的全部公告（含已读与历史归档）。 */
    var announcements by mutableStateOf<List<AnnouncementManager.Announcement>>(emptyList())
        private set

    /** 未读**且适用于当前版本**的公告数：「我的」页公告入口红点。 */
    var unreadCount by mutableIntStateOf(0)
        private set

    var isLoading by mutableStateOf(false)
        private set

    /**
     * 已读 id 快照。
     *
     * 必须是 Compose State，不能是普通 MutableSet：列表里的「已读 / 未读」和
     * 启动弹窗的 [firstUnread] 都读它，而这两个场景下 [announcements] 常常没变
     * （同一批公告，只是已读集合动了），用普通集合拿不到重组。
     */
    private var readIds by mutableStateOf<Set<String>>(emptySet())

    /** 内置公告一个进程只解析一次（内容随版本固定，运行期不会变）。 */
    private var loaded = false

    /**
     * 载入内置公告。冷启动 / 页面可见时调用；已载入过则直接返回。
     *
     * 读 assets 只有几 KB，但仍然切到 IO：调用点在组合期间，主线程不碰文件。
     * 读取失败（打包漏了 assets 文件）时保持未载入状态，下次调用会重试。
     */
    suspend fun load(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        isLoading = true
        val items = try {
            AnnouncementManager.loadAnnouncements(app)
        } finally {
            isLoading = false
        }
        if (items == null) return
        loaded = true
        announcements = order(items)
        recount(app)
    }

    /** 公告中心点开一条 → 已读，红点当帧减少。 */
    fun markRead(context: Context, id: String) {
        AnnouncementManager.markAsRead(context, id)
        recount(context)
    }

    /**
     * 公告中心「一键已读」：把所有未读且适用于当前版本的公告全部标为已读。
     * 历史归档（不适用当前版本）本就不算未读，不需要动。
     */
    fun markAllRead(context: Context) {
        val unreadIds = announcements.filter { isUnread(it) }.map { it.id }
        if (unreadIds.isEmpty()) return
        unreadIds.forEach { AnnouncementManager.markAsRead(context, it) }
        recount(context)
    }

    /**
     * 需要弹启动弹窗的第一条公告。
     * 排序规则同列表（新的在前），所以弹的永远是最新那条该看的公告。
     */
    val firstUnread: AnnouncementManager.Announcement?
        get() = announcements.firstOrNull { isUnread(it) }

    /** 这条公告 id 是否已被标记过已读（纯记录层面，不看版本范围）。 */
    fun hasBeenRead(id: String): Boolean = id in readIds

    /**
     * 单条公告此刻是否算「未读」= 适用于当前版本 且 还没读过。
     *
     * 判定本体在 [AnnouncementManager.Announcement.isUnreadIn]（纯函数，有单测）。
     * 这里只负责把当前进程的已读集合与版本号喂进去 —— 别再把判定逻辑复制一份到
     * 这个类里，之前就是因为在这里手写了 `!isRead(...)` 而把语义写反，导致
     * `showOnce = true` 的公告永远不显示。
     */
    fun isUnread(announcement: AnnouncementManager.Announcement): Boolean =
        announcement.isUnreadIn(readIds, appVersionCode)

    /** 这条公告是否适用于当前安装版本。 */
    fun isApplicable(announcement: AnnouncementManager.Announcement): Boolean =
        announcement.appliesTo(appVersionCode)

    /** 是否属于"历史归档"（不适用于当前版本）——列表里给它一个标记，避免用户困惑。 */
    fun isArchived(announcement: AnnouncementManager.Announcement): Boolean =
        !isApplicable(announcement)

    /** 未读且适用的公告，新的在前。公告中心「未读」分段的数据源。 */
    val unread: List<AnnouncementManager.Announcement>
        get() = announcements.filter { isUnread(it) }

    /**
     * 「已读 / 历史」分段的数据源：不需要用户再处理的那些 —— 已读的，
     * 加上不适用于当前版本的历史公告。历史公告不是"已读"，但它在语义上同属
     * "不需要你处理"的一类，放在一起才不会既占着未读又无处可查。
     */
    val readOrArchived: List<AnnouncementManager.Announcement>
        get() = announcements.filterNot { isUnread(it) }

    /**
     * 列表排序：按发布日期倒序（新的在最上）；没有日期的历史公告落到最后。
     * `sortedWith` 是稳定排序，同一天发布的公告保持文件里的原始顺序。
     */
    private fun order(list: List<AnnouncementManager.Announcement>) =
        list.sortedWith(compareByDescending { it.createdAt })

    private fun recount(context: Context) {
        readIds = AnnouncementManager.readIds(context)
        unreadCount = announcements.count { isUnread(it) }
    }
}
