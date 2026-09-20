package com.hnnujw.course.academic

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.hnnujw.course.model.SchoolConfig
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

    private fun cacheFile(context: Context, accountKey: String): File =
        File(context.cacheDir, "message_center_" + accountKey.hashCode().toString(36) + ".json")

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
        if (!file.exists()) return@runCatching null
        val arr = JSONArray(file.readText())
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
        list.ifEmpty { null }
    }.getOrNull()

    fun writeCache(context: Context, accountKey: String, messages: List<AcademicMessage>) {
        runCatching {
            val arr = JSONArray()
            for (m in messages) {
                arr.put(JSONObject().apply {
                    put("id", m.id); put("title", m.title); put("sender", m.sender)
                    put("sendTime", m.sendTime); put("summary", m.summary)
                    put("read", m.read); put("detailUrl", m.detailUrl)
                    put("content", m.content)
                })
            }
            cacheFile(context, accountKey).writeText(arr.toString())
        }
    }

    fun clearCache(context: Context, accountKey: String) {
        runCatching { cacheFile(context, accountKey).delete() }
    }

    /** 标记单条为已读并写回缓存，同时把未读计数减一。 */
    fun markRead(context: Context, accountKey: String, messageId: String) {
        val current = readCache(context, accountKey).orEmpty()
        if (current.none { it.id == messageId && !it.read }) return
        val updated = current.map { if (it.id == messageId) it.copy(read = true) else it }
        writeCache(context, accountKey, updated)
        publishUnread(updated)
    }

    /** 抓取并刷新列表；成功时落缓存并刷新未读计数。 */
    suspend fun load(context: Context, school: SchoolConfig?, accountKey: String): MessageCenterResult {
        if (school == null || accountKey.isBlank()) {
            return MessageCenterResult.NeedLogin("尚未登录教务账号，无法加载消息")
        }
        return try {
            val transport = AcademicGatewayFactory.transportFor(school, accountKey)
            val result = ZfMessageCenter.fetchMessages(transport, school)
            if (result is MessageCenterResult.Success) {
                writeCache(context, accountKey, result.messages)
                publishUnread(result.messages)
            }
            result
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
        } catch (_: Exception) {
            null
        }
    }
}
