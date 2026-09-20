package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AcademicCoreTest {
    @Test fun detectorRecognizesTheZhengfangLoginPageAndRejectsGenericJsxsd() {
        assertEquals(AcademicSystem.ZF, SystemDetector.classify(fixture("zf-login.html")))
        assertNull(SystemDetector.classify("<a href='/jsxsd/'>Welcome</a>"))
    }

    @Test fun cookiesMatchDomainPathSecureExpiryAndStayAccountScoped() {
        val store = AcademicSessionStore()
        val a = store.session("school", "a", "https://jw.example.edu.cn/jsxsd")
        val b = store.session("school", "b", "https://jw.example.edu.cn/jsxsd")
        val url = "https://jw.example.edu.cn/jsxsd/index".toHttpUrl()
        a.cookies.saveFromResponse(url, listOf(Cookie.parse(url, "sid=A; Path=/jsxsd; Secure")!!,
            Cookie.parse(url, "root=ROOT; Path=/; Secure")!!))
        assertEquals(2, a.cookies.loadForRequest(url).size)
        assertTrue(b.cookies.loadForRequest(url).isEmpty())
        assertTrue(a.cookies.loadForRequest("http://jw.example.edu.cn/jsxsd/index".toHttpUrl()).isEmpty())
        assertEquals(1, a.cookies.loadForRequest("https://jw.example.edu.cn/other".toHttpUrl()).size)
        assertTrue(a.cookies.loadForRequest("https://other.example.edu.cn/jsxsd/index".toHttpUrl()).isEmpty())
        val epoch = a.epoch
        a.invalidate()
        assertNotEquals(epoch, a.epoch)
        assertTrue(a.cookies.loadForRequest(url).isEmpty())
    }

    @Test fun aspNetPostIncludesOnlyClickedSubmitAndCheckedControls() {
        // 通用表单序列化样本：隐藏域 + 下拉默认项 + 未勾选复选框 + 两个提交按钮。
        val html = """
            <form action="list.aspx?xh=10001&amp;gnmkdm=test" method="post">
            <input name="__VIEWSTATE" value="fresh&amp;state" type="hidden">
            <select name="mode"><option value="1">One</option><option value="2" selected>Two</option></select>
            <input name="row1" value="section-A" type="checkbox"><input name="Button1" type="submit" value=" Submit ">
            <input name="Button2" type="submit" value="Search"></form>
        """.trimIndent()
        val page = AcademicHtml.parse(html, "https://jw.example.edu.cn/list.aspx")
        val fields = AcademicHtml.formFields(page.selectFirst("form")!!, "Button1" to " Submit ")
        assertTrue(fields.contains("__VIEWSTATE" to "fresh&state"))
        assertTrue(fields.contains("Button1" to " Submit "))
        assertFalse(fields.any { it.first == "Button2" || it.first == "row1" })
        assertTrue(fields.contains("mode" to "2"))
    }

    @Test fun redirectCookiesAreKeptButUnapprovedHostsAreNeverRequested() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val school = testSchool(server, AcademicSystem.ZF)
            val session = AcademicSessionStore().session(school.id, "a", school.fullBasePath)
            val http = AcademicHttpTransport(school, session)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/jsxsd/home")
                .addHeader("Set-Cookie", "sid=step1; Path=/jsxsd"))
            server.enqueue(MockResponse().setBody("ready"))
            assertEquals("ready", http.get(http.appUrl("start")).text)
            server.takeRequest()
            assertEquals("sid=step1", server.takeRequest().getHeader("Cookie"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://untrusted.invalid/login"))
            try {
                http.get(http.appUrl("start"))
                fail("An unapproved redirect must stop before credentials leave the school")
            } catch (e: AcademicException) { assertEquals(AcademicStatus.UNTRUSTED_URL, e.status) }
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    companion object {
        fun fixture(name: String): String = AcademicCoreTest::class.java.getResource("/academic/$name")!!.readText()
        fun testSchool(server: MockWebServer, system: AcademicSystem): SchoolConfig =
            SchoolConfig("test", "Test", server.url("/").host + ":" + server.url("/").port, "http").apply {
                basePath = "/jsxsd"
                academicSystem = system.id
            }
    }
}
