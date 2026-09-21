package com.hnnujw.course.secondclass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二课堂外部入口（深链 / 剪贴板）解析回归。
 *
 * 形态全部来自线上实测：
 * - 签到码 `qutuo://sign?activityId=X&userId=Y&sp=Z`（签到人出示）
 * - 等待签到码 `qutuo://waitSign?...`（学生亮自己的码）
 * - 组织者活动码 `https://ekta.hnnu.edu.cn/?sourceName=schActivityCode@Xj&activityid=7358`
 * - 网页活动页 `https://ekta.hnnu.edu.cn/#/activityDetail?id=7358`（hash 路由）
 */
class SecondClassDeepLinkTest {

    @Test
    fun parsesSignInCode() {
        val link = SecondClassDeepLink.fromUri("qutuo://sign?activityId=7358&userId=2505050101&sp=3")

        val payload = (link as SecondClassDeepLink.Scan).payload
        assertTrue(payload is SecondClassScanPayload.SignCode)
        assertEquals(7358, (payload as SecondClassScanPayload.SignCode).activityId)
        assertEquals("2505050101", payload.userId)
        assertEquals("3", payload.sp)
        assertEquals(false, payload.waitSign)
        assertEquals(2, payload.submitType)
    }

    @Test
    fun parsesWaitSignCode() {
        val link = SecondClassDeepLink.fromUri("qutuo://waitSign?activityId=7358&userId=2505050101&sp=1")

        val payload = (link as SecondClassDeepLink.Scan).payload as SecondClassScanPayload.SignCode
        assertTrue(payload.waitSign)
        assertEquals(1, payload.submitType)
    }

    @Test
    fun stripsYibanSuffix() {
        val link = SecondClassDeepLink.fromUri(
            "qutuo://sign?activityId=7358&userId=2505050101&sp=3?yiban=yiban_scan_result"
        )

        val payload = (link as SecondClassDeepLink.Scan).payload as SecondClassScanPayload.SignCode
        // 后缀粘在最后一个参数上也不能把 sp 弄脏
        assertEquals("3", payload.sp)
    }

    @Test
    fun parsesOrganizerWebCode() {
        val link = SecondClassDeepLink.fromUri(
            "https://ekta.hnnu.edu.cn/?sourceName=schActivityCode@Xj&activityid=7358"
        )

        assertEquals(SecondClassDeepLink.Activity(7358), link)
    }

    @Test
    fun parsesHashRoutedActivityPage() {
        // hash 路由：query 在 # 后面，android.net.Uri 取不到，必须手拆
        val link = SecondClassDeepLink.fromUri(
            "https://ekta.hnnu.edu.cn/#/activityDetail?id=7358&from=share"
        )

        assertEquals(SecondClassDeepLink.Activity(7358), link)
    }

    @Test
    fun rejectsUnknownAndSensitiveText() {
        // 剪贴板可能装着密码、口令等敏感内容 —— 一律不认，更不能展示出来
        assertEquals(SecondClassDeepLink.None, SecondClassDeepLink.fromUri("我的密码是 abc123"))
        assertEquals(SecondClassDeepLink.None, SecondClassDeepLink.fromUri("https://example.com/?activityId=1"))
        assertEquals(SecondClassDeepLink.None, SecondClassDeepLink.fromUri("https://ekta.hnnu.edu.cn/#/home"))
        assertEquals(SecondClassDeepLink.None, SecondClassDeepLink.fromUri(""))
        assertEquals(SecondClassDeepLink.None, SecondClassDeepLink.fromUri(null))
        // qutuo:// 但没有可用的 activityId —— 不是有效码
        assertEquals(SecondClassDeepLink.None, SecondClassDeepLink.fromUri("qutuo://sign?userId=x"))
    }

    @Test
    fun navigationAcceptsAndConsumes() {
        SecondClassDeepLinkNavigation.consume()
        assertNull(SecondClassDeepLinkNavigation.pending)

        SecondClassDeepLinkNavigation.accept(SecondClassDeepLink.Activity(7358))
        assertEquals(SecondClassDeepLink.Activity(7358), SecondClassDeepLinkNavigation.pending)

        SecondClassDeepLinkNavigation.consume()
        assertNull(SecondClassDeepLinkNavigation.pending)
    }
}
