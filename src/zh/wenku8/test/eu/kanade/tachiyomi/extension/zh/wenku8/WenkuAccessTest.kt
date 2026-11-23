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
import org.junit.Assert.assertThrows
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
    fun `redirected GBK page is decoded and only total ranking is parsed`() = runBlocking {
        val visited = mutableListOf<String>()
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { url ->
            visited += url.encodedPath
            if (url.encodedPath == "/top.php") response(url, 302, location = "/rank")
            else response(url, 200, ranking.toByteArray(charset("GBK")))
        }
        val page = pages.get("/top.php")
        assertEquals(listOf("/top.php", "/rank"), visited)
        assertEquals(listOf(WenkuBook("完整书名", "/book/42.htm")), WenkuParser.totalRanking(page, origin.host))
    }

    @Test
    fun `external redirect is stopped before requesting it`() {
        val seen = mutableListOf<String>()
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { url ->
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
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { url ->
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
            val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { url ->
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
        val pages = WenkuPages(OkHttpClient.Builder().cookieJar(jar).build(), headersOf(), origin) { url ->
            requests++
            response(url, 302, location = "http://www.wenku8.net/login.php")
        }
        val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
        assertEquals(AccessFailure.SESSION_EXPIRED, error.failure)
        assertEquals(1, requests)
    }

    @Test
    fun `http challenge rate limit and network failures remain distinct`() {
        for ((status, html, expected) in listOf(
            Triple(403, "<title>Forbidden</title>", AccessFailure.FORBIDDEN),
            Triple(403, "<title>Attention Required!</title>", AccessFailure.CHALLENGE),
            Triple(200, "<title>Just a moment...</title>", AccessFailure.CHALLENGE),
            Triple(429, "<title>Just a moment...</title>", AccessFailure.RATE_LIMITED),
        )) {
            val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { url -> response(url, status, html.toByteArray()) }
            val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
            assertEquals(expected, error.failure)
        }
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { throw IOException("offline") }
        val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
        assertEquals(AccessFailure.NETWORK, error.failure)
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
            val pages = WenkuPages(OkHttpClient.Builder().cookieJar(jar).build(), headersOf(), origin) { url -> response(url, 200, login) }
            val error = assertThrows(WenkuAccessException::class.java) { runBlocking { pages.get("/top.php") } }
            assertEquals(if (hasCookie) AccessFailure.SESSION_EXPIRED else AccessFailure.LOGIN_REQUIRED, error.failure)
        }
    }

    @Test
    fun `unexpected html is an error rather than an empty ranking`() {
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { url -> response(url, 200, "<h1>changed</h1>".toByteArray()) }
        val error = assertThrows(WenkuAccessException::class.java) {
            runBlocking { WenkuParser.totalRanking(pages.get("/top.php"), origin.host) }
        }
        assertEquals(AccessFailure.STRUCTURE, error.failure)
    }
}
