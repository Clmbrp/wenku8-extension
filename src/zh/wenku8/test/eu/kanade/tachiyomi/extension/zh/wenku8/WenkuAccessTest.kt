package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WenkuAccessTest {
    private val origin = "https://www.wenku8.net/".toHttpUrl()
    private val ranking = """<html><head><meta charset="gbk"></head><body>
        <div class="blocktop"><div class="blockcaption">总热度榜</div><div class="blockcontent">
        <ul class="ultop"><li><a href="/book/42.htm" tiptitle="完整书名">截断</a></li></ul></div></div>
        <div class="blocktop"><div class="blockcaption">其他榜</div><div class="blockcontent">
        <ul class="ultop"><li><a href="/book/43.htm">其他</a></li></ul></div></div></body></html>"""

    private fun response(url: HttpUrl, status: Int, body: ByteArray = byteArrayOf(), location: String? = null): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("test")
            .header("Content-Type", "text/html")
            .apply { if (location != null) header("Location", location) }
            .body(body.toResponseBody("text/html".toMediaType()))
            .build()

    @Test
    fun `redirected GBK page is decoded from the HTML declaration`() = runBlocking {
        val visited = mutableListOf<String>()
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { request ->
            val url = request.url
            visited += url.encodedPath
            if (url.encodedPath == "/top.php") response(url, 302, location = "/rank")
            else response(url, 200, ranking.toByteArray(charset("GBK")))
        }
        val page = pages.get("/top.php")
        assertEquals(listOf("/top.php", "/rank"), visited)
        assertEquals("完整书名", page.selectFirst(".ultop a")?.attr("tiptitle"))
    }

    @Test
    fun `external redirect is stopped before requesting it`() {
        val seen = mutableListOf<String>()
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { request ->
            val url = request.url
            seen += url.host
            response(url, 302, location = "https://other.example/login")
        }
        val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
        assertEquals(AccessFailure.STRUCTURE, error.failure)
        assertEquals(listOf(origin.host), seen)
    }

    @Test
    fun `same host http login redirect asks for login without requesting http`() {
        val seen = mutableListOf<String>()
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { request ->
            val url = request.url
            seen += url.toString()
            response(url, 302, location = "http://www.wenku8.net/login.php?jumpurl=http%3A%2F%2Fwww.wenku8.net%2Findex.php")
        }
        val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
        assertEquals(AccessFailure.LOGIN_REQUIRED, error.failure)
        assertEquals(listOf("https://www.wenku8.net/top.php"), seen)
    }

    @Test
    fun `untrusted http redirects remain blocked`() {
        for (location in listOf("http://other.example/login.php", "http://www.wenku8.net/other", "http://www.wenku8.net:8080/login.php")) {
            val seen = mutableListOf<String>()
            val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { request ->
                val url = request.url
                seen += url.toString()
                response(url, 302, location = location)
            }
            val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
            assertEquals(AccessFailure.STRUCTURE, error.failure)
            assertEquals(listOf("https://www.wenku8.net/top.php"), seen)
        }
    }

    @Test
    fun `http login redirect with existing session reports possible expiry without requesting it`() {
        val jar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                Cookie.Builder().name("jieqiUserInfo").value("synthetic").hostOnlyDomain(url.host).build(),
            )
        }
        var requests = 0
        val pages = WenkuPages(OkHttpClient.Builder().cookieJar(jar).build(), headersOf(), origin) { request ->
            requests++
            response(request.url, 302, location = "http://www.wenku8.net/login.php")
        }
        val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
        assertEquals(AccessFailure.SESSION_EXPIRED, error.failure)
        assertEquals(1, requests)
    }

    @Test
    fun `session hint uses the protected path without making a login request`() {
        val lookedUpPaths = mutableListOf<String>()
        val jar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                lookedUpPaths += url.encodedPath
                return if (url.encodedPath == "/top.php") {
                    listOf(Cookie.Builder().name("jieqiUserInfo").value("synthetic").hostOnlyDomain(url.host).path("/top.php").secure().build())
                } else {
                    emptyList()
                }
            }
        }
        val visited = mutableListOf<HttpUrl>()
        val pages = WenkuPages(OkHttpClient.Builder().cookieJar(jar).build(), headersOf(), origin) { request ->
            val url = request.url
            visited += url
            response(url, 302, location = "/login.php?jumpurl=%2Ftop.php")
        }
        val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
        assertEquals(AccessFailure.SESSION_EXPIRED, error.failure)
        assertEquals(listOf("/top.php"), lookedUpPaths)
        assertEquals(listOf(origin.resolve("/top.php")), visited)
    }

    @Test
    fun `http challenge rate limit and network failures remain distinct`() {
        for ((status, html, expected) in listOf(
            Triple(403, "<title>Forbidden</title>", AccessFailure.FORBIDDEN),
            Triple(403, "<title>Attention Required!</title>", AccessFailure.FORBIDDEN),
            Triple(
                403,
                """<title>Attention Required! | Cloudflare</title><div id="cf-error-details"><h1 data-translate="block_headline">Sorry, you have been blocked</h1></div>""",
                AccessFailure.CLOUDFLARE_BLOCK,
            ),
            Triple(200, "<title>Just a moment...</title>", AccessFailure.CHALLENGE),
            Triple(429, "<title>Just a moment...</title>", AccessFailure.RATE_LIMITED),
        )) {
            val policy = WenkuRequestPolicy(intervalMillis = 0, fallbackCooldownMillis = 0)
            val pages = WenkuPages(OkHttpClient(), headersOf(), origin, policy = policy) { response(it.url, status, html.toByteArray()) }
            val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
            assertEquals(expected, error.failure)
        }
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { throw IOException("offline") }
        val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
        assertEquals(AccessFailure.NETWORK, error.failure)
    }

    @Test
    fun `explicit challenge header rejects even successful ranking markup`() {
        val logs = mutableListOf<String>()
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin, WenkuDiagnostics { logs += it }) {
            response(it.url, 200, ranking.toByteArray(charset("GBK"))).newBuilder().header("cf-mitigated", "challenge").build()
        }
        val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
        assertEquals(AccessFailure.CHALLENGE, error.failure)
        assertTrue(logs.any { "status=200 challengeHeader=true" in it })
        assertTrue("result stage=response kind=CHALLENGE" in logs)
    }

    @Test
    fun `debug trace reports cookie presence and excludes values and unknown paths`() {
        val logs = mutableListOf<String>()
        val debug = WenkuDiagnostics { logs += it }
        val url = "https://www.wenku8.net/top.php?secret-query=value#secret-fragment".toHttpUrl()
        debug.session(url, false)
        debug.sent(Request.Builder().url(url).header("Cookie", "PHPSESSID=secret-session").build())
        debug.sent(Request.Builder().url(url).header("Cookie", "other=secret-other; jieqiUserInfo=secret-login").build())
        debug.redirect("http://www.wenku8.net/login.php?jumpurl=secret-redirect".toHttpUrl())
        debug.redirect("https://secret-host.example/secret-path".toHttpUrl())
        assertEquals(
            listOf(
                "session url=https://www.wenku8.net/top.php loginCookieVisible=false",
                "request url=https://www.wenku8.net/top.php loginCookieSent=false",
                "request url=https://www.wenku8.net/top.php loginCookieSent=true",
                "redirect target=http://www.wenku8.net/login.php",
                "redirect target=https://<external>/<redacted>",
            ),
            logs,
        )
        assertFalse(logs.any { "secret-" in it })
    }

    @Test
    fun `login form distinguishes missing from possibly expired session`() {
        val login = "<form><input name='username'><input name='password'></form>".toByteArray()
        for (hasCookie in listOf(false, true)) {
            val jar = object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl): List<Cookie> = if (hasCookie) {
                    listOf(Cookie.Builder().name("jieqiUserInfo").value("synthetic").hostOnlyDomain(url.host).build())
                } else {
                    emptyList()
                }
            }
            val pages = WenkuPages(OkHttpClient.Builder().cookieJar(jar).build(), headersOf(), origin) { response(it.url, 200, login) }
            val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
            assertEquals(if (hasCookie) AccessFailure.SESSION_EXPIRED else AccessFailure.LOGIN_REQUIRED, error.failure)
        }
    }

    @Test
    fun `unexpected html is an error rather than an empty ranking`() {
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { response(it.url, 200, "<h1>changed</h1>".toByteArray()) }
        val error = assertThrows(WenkuAccessException::class.java) {
            runBlocking { WenkuBrowse(pages, origin).popular(1) }
        }
        assertEquals(AccessFailure.STRUCTURE, error.failure)
    }
}
