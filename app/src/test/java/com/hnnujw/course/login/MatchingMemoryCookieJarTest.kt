package com.hnnujw.course.login

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingMemoryCookieJarTest {
    @Test
    fun doesNotSendSecureCookieOverHttp() {
        val jar = MatchingMemoryCookieJar()
        val https = "https://jw.example.edu.cn/jwglxt/".toHttpUrl()
        jar.saveFromResponse(
            https,
            listOf(
                Cookie.Builder()
                    .name("JSESSIONID")
                    .value("test-session")
                    .hostOnlyDomain("jw.example.edu.cn")
                    .path("/jwglxt")
                    .secure()
                    .build()
            )
        )

        assertTrue(
            jar.loadForRequest("http://jw.example.edu.cn/jwglxt/".toHttpUrl()).isEmpty()
        )
    }

    @Test
    fun serializesOnlyCookiesMatchingTheTeachingHost() {
        val jar = MatchingMemoryCookieJar()
        jar.saveFromResponse(
            "https://sso.example.edu.cn/login".toHttpUrl(),
            listOf(
                Cookie.Builder().name("SESSION").value("sso")
                    .hostOnlyDomain("sso.example.edu.cn").path("/").secure().build()
            )
        )
        jar.saveFromResponse(
            "https://jw.example.edu.cn/jwglxt/".toHttpUrl(),
            listOf(
                Cookie.Builder().name("JSESSIONID").value("jw")
                    .hostOnlyDomain("jw.example.edu.cn").path("/jwglxt").secure().build()
            )
        )

        assertEquals(
            "JSESSIONID=jw",
            jar.cookieHeaderFor("https://jw.example.edu.cn/jwglxt/".toHttpUrl())
        )
    }

    @Test
    fun replacesSameNameDomainAndPathAndRemovesExpiredCookie() {
        val jar = MatchingMemoryCookieJar()
        val url = "https://jw.example.edu.cn/jwglxt/".toHttpUrl()

        jar.saveFromResponse(url, listOf(sessionCookie("old")))
        jar.saveFromResponse(url, listOf(sessionCookie("new")))
        assertEquals("JSESSIONID=new", jar.cookieHeaderFor(url))

        jar.saveFromResponse(
            url,
            listOf(sessionCookie("deleted", expiresAt = System.currentTimeMillis() - 1L))
        )
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun canSerializeOnlyCookiesSetByTeachingHost() {
        val jar = MatchingMemoryCookieJar()
        val teachingUrl = "https://jw.example.edu.cn/jwglxt/".toHttpUrl()
        jar.saveFromResponse(
            "https://sso.example.edu.cn/login".toHttpUrl(),
            listOf(
                Cookie.Builder().name("SESSION").value("parent-domain-sso")
                    .domain("example.edu.cn").path("/").secure().build()
            )
        )
        jar.saveFromResponse(teachingUrl, listOf(sessionCookie("teaching-session")))

        assertTrue(jar.cookieHeaderFor(teachingUrl).contains("SESSION=parent-domain-sso"))
        assertEquals(
            "JSESSIONID=teaching-session",
            jar.cookieHeaderFor(teachingUrl, setByHost = "jw.example.edu.cn")
        )
    }

    private fun sessionCookie(value: String, expiresAt: Long? = null): Cookie {
        val builder = Cookie.Builder()
            .name("JSESSIONID")
            .value(value)
            .hostOnlyDomain("jw.example.edu.cn")
            .path("/jwglxt")
            .secure()
        expiresAt?.let(builder::expiresAt)
        return builder.build()
    }
}
