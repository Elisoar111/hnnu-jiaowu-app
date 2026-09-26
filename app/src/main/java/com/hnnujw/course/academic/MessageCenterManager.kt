package com.hnnujw.course.academic

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.hnnujw.course.model.SchoolConfig
import com.hnnujw.course.widgetboard.CardWidgetUpdater
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 消息中心的本地缓存与编排：
 * - [load] 在已登录会话上抓取列表（成功则写缓存，并刷新未读计数）；
 * - [readCache] / [writeCache] 提供离线优先的本地列表；
 * - [unread] / [unreadCount] 未读数（入口红点）；
 * - [loadDetail] 抓取单条消息正文。
 */
object MessageCenterManager {

    /**
     * 账号键归一化：与项目内其它按账号落盘的存储保持一致（非法字符替换为 `_`）。
     *
     * 例：`hnnu::2024001` → `hnnu__2024001`。
     */
    internal fun normalizeAccountKey(accountKey: String): String =
        accountKey.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    /**
     * 账号键 → 当前缓存文件名。
     *
     * 旧实现是 `accountKey.hashCode().toString(36)`，有两个问题：
     * 1. 两个不同账号一旦 `hashCode()` 碰撞，就会互相读到对方的消息列表；
     * 2. 文件名是乱码串，排查线上问题时无法从文件名看出属于哪个账号。
     * 现统一为项目内其它缓存一致的归一化口径。
     */
    internal fun cacheFileName(accountKey: String): String =
        "message_center_" + normalizeAccountKey(accountKey) + ".json"

    /** 旧口径（`hashCode()`）的文件名，**仅用于读取并迁移历史缓存**。 */
    internal fun legacyCacheFileName(accountKey: String): String =
        "message_center_" + accountKey.hashCode().toString(36) + ".json"

    private fun cacheFile(context: Context, accountKey: String): File =
        File(context.cacheDir, cacheFileName(accountKey))

    private fun legacyCacheFile(context: Context, accountKey: String): File =
        File(context.cacheDir, legacyCacheFileName(accountKey))

    fun unreadCount(messages: List<AcademicMessage>): Int = messages.count { !it.read }

    /**
     * 当前账号的未读数，**Compose state**。
     *
     * 「我的」页的「消息中心」入口与底栏角标都直接订阅它，所以任何一次刷新
     * （进「我的」页、前台轮询、打开消息中心）都会当帧把红点更新掉。
     */
    var unread by mutableIntStateOf(0)
        private set

    /** 用一份已知的列表刷新未读计数。 */
    fun publishUnread(messages: List<AcademicMessage>) {
        unread = unreadCount(messages)
    }

    /** 退出登录 / 切账号时把红点清掉。 */
    fun clearUnread() {
        unread = 0
    }

    /** 缓存里没有数据时把红点清掉；有数据就用缓存先顶上（离线也能显示红点）。 */
    fun refreshUnreadFromCache(context: Context, accountKey: String) {
        if (accountKey.isBlank()) {
            unread = 0
            return
        }
        unread = unreadCount(readCache(context, accountKey).orEmpty())
    }

    fun readCache(context: Context, accountKey: String): List<AcademicMessage>? = runCatching {
        val file = cacheFile(context, accountKey)
        if (file.exists()) return@runCatching decodeMessages(file.readText())

        // 旧口径文件（`hashCode()` 命名）一次性迁移：读到内容后改写成新文件名并删掉旧的。
        // 迁移失败不影响本次读取 —— 缓存目录里的数据随时可以重新抓取。
        val legacy = legacyCacheFile(context, accountKey)
        if (!legacy.exists()) return@runCatching null
        val text = legacy.readText()
        runCatching {
            file.writeText(text)
            legacy.delete()
        }
        decodeMessages(text)
    }.getOrNull()

    /**
     * 缓存 JSON → 消息列表。
     *
     * **空数组 `[]` 解出的是空列表，不是 null** —— "共 0 条"是权威状态，与
     * "读不到缓存"是两件事（见 `widgetboard/WidgetBoardData.messagesCardData` 的契约注释：
     * 列表为空也返回非 null，卡片据此显示"共 0 条 / 没有未读"）。只有 JSON 本身
     * 损坏、根本解不出列表时才返回 null。
     *
     * 抽成纯函数是为了能被 JVM 单测盯住：这个区分一旦被写回 `ifEmpty { null }`，
     * 桌面卡片就会在"确实没有消息"的账号上错误地显示"还没有消息缓存"。
     */
    internal fun decodeMessages(json: String): List<AcademicMessage>? = runCatching {
        val arr = JSONArray(json)
        val list = mutableListOf<AcademicMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list += AcademicMessage(
                id = o.optString("id"),
                title = o.optString("title"),
                sender = o.optString("sender"),
                sendTime = o.optString("sendTime"),
                summary = o.optString("summary"),
                read = o.optBoolean("read"),
                detailUrl = o.optString("detailUrl"),
                content = o.optString("content")
            )
        }
        list
    }.getOrNull()

    internal fun encodeMessages(messages: List<AcademicMessage>): String {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(JSONObject().apply {
                put("id", m.id); put("title", m.title); put("sender", m.sender)
                put("sendTime", m.sendTime); put("summary", m.summary)
                put("read", m.read); put("detailUrl", m.detailUrl)
                put("content", m.content)
            })
        }
        return arr.toString()
    }

    fun writeCache(context: Context, accountKey: String, messages: List<AcademicMessage>) {
        runCatching {
            cacheFile(context, accountKey).writeText(encodeMessages(messages))
            // 新口径文件已落盘，旧口径文件不再需要（读取时新文件优先）。
            legacyCacheFile(context, accountKey).delete()
            notifyCardWidget(context)
        }
    }

    fun clearCache(context: Context, accountKey: String) {
        runCatching {
            cacheFile(context, accountKey).delete()
            legacyCacheFile(context, accountKey).delete()
        }
        notifyCardWidget(context)
    }

    /**
     * 通知桌面卡片重画一次。
     *
     * 列表缓存落在 `cacheDir` 的 JSON 文件里，而桌面组件的自动刷新只监听 SharedPreferences
     * （`CardWidgetUpdater.OBSERVED_PREFS`），看不见文件写入。少了这一句，桌面上的
     * 「未读消息」卡就要等系统 `updatePeriodMillis` 那个 30 分钟的兜底周期才更新 ——
     * 收到新消息后半小时桌面还是旧数字。
     *
     * 放在写入之后而不是读完之前：卡片渲染本来就只读缓存，读这一侧没有可通知的事。
     */
    private fun notifyCardWidget(context: Context) {
        runCatching { CardWidgetUpdater.refreshSoon(context) }
    }

    /** 标记单条为已读并写回缓存，同时把未读计数减一。 */
    fun markRead(context: Context, accountKey: String, messageId: String) {
        val current = readCache(context, accountKey).orEmpty()
        if (current.none { it.id == messageId && !it.read }) return
        val updated = current.map { if (it.id == messageId) it.copy(read = true) else it }
        writeCache(context, accountKey, updated)
        publishUnread(updated)
    }

    /**
     * 一键已读：把给定列表（缺省取缓存）里的所有消息标为已读。
     * 与 [markRead] 一样只作用于本地——服务端的已阅状态以正方为准。
     */
    fun markAllRead(context: Context, accountKey: String, messages: List<AcademicMessage>? = null) {
        if (accountKey.isBlank()) return
        val source = messages ?: readCache(context, accountKey).orEmpty()
        if (source.isEmpty()) return
        if (source.none { !it.read }) {
            publishUnread(source)
            return
        }
        val updated = source.map { if (it.read) it else it.copy(read = true) }
        writeCache(context, accountKey, updated)
        publishUnread(updated)
    }

    /**
     * 本地已读消息的主键集合。服务端抓回的列表会用它做合并：
     * 用户在应用里点过（或一键已读过的）消息，刷新后不会被服务端的"待阅"状态打回未读。
     */
    fun locallyReadIds(context: Context, accountKey: String): Set<String> =
        readCache(context, accountKey).orEmpty().filter { it.read }.map { it.id }.toHashSet()

    /** 抓取并刷新列表；成功时落缓存并刷新未读计数。 */
    suspend fun load(context: Context, school: SchoolConfig?, accountKey: String): MessageCenterResult {
        if (school == null || accountKey.isBlank()) {
            return MessageCenterResult.NeedLogin("尚未登录教务账号，无法加载消息")
        }
        return try {
            val transport = AcademicGatewayFactory.transportFor(school, accountKey)
            val result = ZfMessageCenter.fetchMessages(transport, school)
            if (result is MessageCenterResult.Success) {
                // 合并本地已读状态：应用里标过已读（含一键已读）的消息，
                // 刷新后不会被服务端的"待阅"桶打回未读。
                val locallyRead = locallyReadIds(context, accountKey)
                val merged = if (locallyRead.isEmpty()) {
                    result.messages
                } else {
                    result.messages.map { if (it.id in locallyRead) it.copy(read = true) else it }
                }
                writeCache(context, accountKey, merged)
                publishUnread(merged)
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 抓取失败时红点不该跟着消失：退回上一次缓存的未读数
            refreshUnreadFromCache(context, accountKey)
            MessageCenterResult.Failure(e.message ?: "加载消息失败", school.getFullBasePath().trimEnd('/'))
        }
    }

    /** 抓取单条消息详情（用于原生详情页）。 */
    suspend fun loadDetail(school: SchoolConfig?, accountKey: String, detailUrl: String): AcademicMessageDetail? {
        if (school == null || accountKey.isBlank() || detailUrl.isBlank()) return null
        return try {
            val transport = AcademicGatewayFactory.transportFor(school, accountKey)
            ZfMessageCenter.fetchMessageDetail(transport, detailUrl)
        } catch (e: CancellationException) {
            // 协程取消必须原样抛出：吞掉后调用方会拿上一个账号的结果去刷全局红点
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
