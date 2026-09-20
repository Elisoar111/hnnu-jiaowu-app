package com.hnnujw.course.model

import com.hnnujw.course.academic.AcademicGatewayFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备用入口（域名不可达时改用 IP 直连）的行为契约。
 *
 * 设计约束：备用入口是**同一个 SchoolConfig 里的另一个 host**，不是第二所学校。
 * 设备绑定额度按 (schoolId, 学号) 计，拆成两所学校会让同一个学生白占两个名额。
 * 下面的 `switchingToTheAlternateSiteKeepsTheSchoolId` 就是锁住这一条。
 */
class SchoolConfigAlternateSiteTest {

    private fun hnnu() = SchoolConfig("hnnu", "淮南师范学院", "jwgl.hnnu.edu.cn", "https").apply {
        basePath = "/jwglxt"
        alternateDomain = "211.70.176.172"
        alternateProtocol = "http"
        allowedAcademicHosts.add("jwgl.hnnu.edu.cn")
        allowedAcademicHosts.add("211.70.176.172")
    }

    @Test fun theDefaultSiteIsThePrimaryDomain() {
        val school = hnnu()
        assertFalse(school.useAlternate)
        assertTrue(school.hasAlternate())
        assertEquals("https://jwgl.hnnu.edu.cn", school.getBaseUrl())
        assertEquals("jwgl.hnnu.edu.cn", school.activeHost)
    }

    @Test fun switchingToTheAlternateSiteKeepsTheSchoolId() {
        val school = hnnu()
        school.useAlternate = true
        assertEquals("http://211.70.176.172", school.getBaseUrl())
        assertEquals("211.70.176.172", school.activeHost)
        // 关键：只换 host，学校身份不变 → 不额外占用设备绑定名额
        assertEquals("hnnu", school.id)
    }

    @Test fun theLoginPageFollowsTheSelectedSite() {
        val school = hnnu()
        assertEquals(
            "https://jwgl.hnnu.edu.cn/jwglxt/xtgl/login_slogin.html",
            AcademicGatewayFactory.loginUrl(school)
        )
        school.useAlternate = true
        assertEquals(
            "http://211.70.176.172/jwglxt/xtgl/login_slogin.html",
            AcademicGatewayFactory.loginUrl(school)
        )
    }

    @Test fun aBlankAlternateDomainFallsBackToThePrimarySite() {
        val school = hnnu().apply {
            alternateDomain = "   "
            useAlternate = true
        }
        assertFalse(school.hasAlternate())
        assertEquals("https://jwgl.hnnu.edu.cn", school.getBaseUrl())
        assertEquals("jwgl.hnnu.edu.cn", school.activeHost)
    }

    @Test fun theChoiceAndTheAlternateHostSurviveAJsonRoundTrip() {
        val school = hnnu().apply {
            useAlternate = true
            description = "淮南师范学院"
        }
        val restored = SchoolConfig.fromJson(school.toJson())
        assertEquals("211.70.176.172", restored.alternateDomain)
        assertEquals("http", restored.alternateProtocol)
        assertTrue(restored.useAlternate)
        assertEquals("http://211.70.176.172", restored.getBaseUrl())
        // description 原先漏了持久化，恢复出来会丢标签
        assertEquals("淮南师范学院", restored.description)
        assertTrue(restored.allowedAcademicHosts.contains("211.70.176.172"))
    }

    @Test fun savedConfigsWithoutTheAlternateKeysStillLoadOnThePrimarySite() {
        val legacy = org.json.JSONObject()
            .put("id", "hnnu")
            .put("name", "淮南师范学院")
            .put("domain", "jwgl.hnnu.edu.cn")
            .put("protocol", "https")
            .put("basePath", "/jwglxt")
        val restored = SchoolConfig.fromJson(legacy)
        assertEquals("", restored.alternateDomain)
        assertFalse(restored.useAlternate)
        assertFalse(restored.hasAlternate())
        assertEquals("https://jwgl.hnnu.edu.cn", restored.getBaseUrl())
    }

    /**
     * 备用站协议通常是 HTTP（如 IP 直连），主站是 HTTPS。
     * 协议校验要用「当前实际使用」的协议，否则「HTTPS 不能降级」检查会把备用站误判为 untrusted。
     * 这一条是修掉登录页底「Academic redirect is outside the configured school hosts」红字的根因。
     */
    @Test fun activeProtocolFollowsTheSelectedSite() {
        val school = hnnu()
        assertEquals("https", school.getActiveProtocol())
        school.useAlternate = true
        assertEquals("http", school.getActiveProtocol())
    }

    @Test fun activeProtocolSurvivesAJsonRoundTrip() {
        val school = hnnu().apply { useAlternate = true }
        val restored = SchoolConfig.fromJson(school.toJson())
        assertTrue(restored.useAlternate)
        assertEquals("http", restored.getActiveProtocol())
        val primary = SchoolConfig.fromJson(hnnu().toJson())
        assertFalse(primary.useAlternate)
        assertEquals("https", primary.getActiveProtocol())
    }
}
