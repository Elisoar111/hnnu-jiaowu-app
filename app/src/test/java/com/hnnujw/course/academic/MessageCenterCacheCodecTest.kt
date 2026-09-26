package com.hnnujw.course.academic

import com.hnnujw.course.widgetboard.messagesCardData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息中心缓存编解码的回归测试。
 *
 * 盯住的核心区分：**「共 0 条」和「读不到缓存」不是同一件事**。
 * 缓存文件里写的是 `[]` 时必须解成空列表（卡片显示"共 0 条 / 没有未读"），
 * 而不是 null（卡片显示"还没有消息缓存"）—— 这个区分曾经被 `ifEmpty { null }`
 * 抹平，导致确实没有消息的账号看到错误的空态。
 */
class MessageCenterCacheCodecTest {

    private fun message(id: String, read: Boolean) = AcademicMessage(
        id = id,
        title = "标题 $id",
        sender = "教务处",
        sendTime = "2026-09-23 10:00:00",
        summary = "摘要",
        read = read,
        detailUrl = "https://example.invalid/m/$id",
        content = "正文",
    )

    @Test
    fun anAuthoritativeEmptyListIsNotDegradedToNoCache() {
        val decoded = MessageCenterManager.decodeMessages("[]")

        assertNotNull(decoded)
        assertTrue(decoded!!.isEmpty())

        // 卡片据此走"共 0 条 / 没有未读"分支，而不是"还没有消息缓存"
        val card = messagesCardData(decoded)
        assertTrue(card.loaded)
        assertEquals(0, card.total)
        assertEquals(0, card.unread)
    }

    @Test
    fun corruptJsonIsReportedAsNoCache() {
        assertNull(MessageCenterManager.decodeMessages("{ 这不是 JSON"))

        // 真正读不到时 loaded=false，卡片才该提示"还没有消息缓存"
        assertFalse(messagesCardData(MessageCenterManager.decodeMessages("{ 这不是 JSON")).loaded)
    }

    @Test
    fun encodeDecodeRoundTripKeepsEveryField() {
        val messages = listOf(message("a", read = false), message("b", read = true))

        val decoded = MessageCenterManager.decodeMessages(MessageCenterManager.encodeMessages(messages))

        assertEquals(messages, decoded)
    }

    @Test
    fun unreadCountIgnoresReadMessages() {
        val messages = listOf(message("a", read = false), message("b", read = true), message("c", read = false))

        assertEquals(2, MessageCenterManager.unreadCount(messages))
        assertEquals(2, messagesCardData(messages).unread)
        assertEquals(3, messagesCardData(messages).total)
    }

    // ---- 缓存文件名口径（§4.3）------------------------------------------------

    @Test
    fun cacheFileNameIsReadableAndNormalizedLikeEveryOtherStore() {
        assertEquals("message_center_hnnu__2024001.json", MessageCenterManager.cacheFileName("hnnu::2024001"))
        // 与 SecondClassroomStore / ScheduleSettingsManager 用的是同一套归一化口径
        assertEquals(
            MessageCenterManager.cacheFileName("hnnu::2024001"),
            "message_center_" + MessageCenterManager.normalizeAccountKey("hnnu::2024001") + ".json",
        )
    }

    @Test
    fun blankAccountKeyFallsBackToDefaultSlot() {
        assertEquals("default", MessageCenterManager.normalizeAccountKey(""))
        assertEquals("default", MessageCenterManager.normalizeAccountKey("   "))
        assertEquals("message_center_default.json", MessageCenterManager.cacheFileName(""))
    }

    /**
     * §4.3 的核心断言：旧口径**确实会碰撞**，而新口径不会。
     *
     * `"Aa"` 与 `"BB"` 是 `String.hashCode()` 的经典碰撞对（都等于 2112），
     * 因此旧实现下两个不同账号会映射到**同一个缓存文件**，互相读到对方的消息列表。
     * 这不再是"理论上可能"，而是一个确定可复现的用例。
     */
    @Test
    fun legacyHashBasedFileNameCollidesButTheNewOneDoesNot() {
        assertEquals("Aa".hashCode(), "BB".hashCode())

        // 旧口径：两个不同账号 → 同一个文件（缺陷）
        assertEquals(
            MessageCenterManager.legacyCacheFileName("Aa"),
            MessageCenterManager.legacyCacheFileName("BB"),
        )

        // 新口径：两个不同账号 → 两个文件（已修）
        assertFalse(
            MessageCenterManager.cacheFileName("Aa") == MessageCenterManager.cacheFileName("BB"),
        )
    }

    @Test
    fun legacyFileNameIsKeptOnlyForMigration() {
        // 迁移读取仍要能找到老文件，所以旧口径函数必须原样保留
        assertEquals(
            "message_center_" + "hnnu::2024001".hashCode().toString(36) + ".json",
            MessageCenterManager.legacyCacheFileName("hnnu::2024001"),
        )
    }
}
