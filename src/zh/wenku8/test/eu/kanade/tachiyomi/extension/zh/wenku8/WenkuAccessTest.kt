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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong

class WenkuAccessTest {
    private val origin = "https://www.wenku8.net/".toHttpUrl()
    private val popular = """<html><body><a href="/logout.php">退出登录</a><div id="content">
        <table class="grid"><tr><td><a href="/book/42.htm">样本书</a></td></tr></table></div></body></html>"""
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
    fun `offline check sends nothing and recovery reuses all visible cookies`() = runBlocking {
        val cookies = FakeCookieJar(origin)
        var hasNetwork = true
        var requests = 0
        val logs = mutableListOf<String>()
        val pages = WenkuPages(
            OkHttpClient.Builder().cookieJar(cookies).build(), headersOf(), origin,
            WenkuDiagnostics { logs += it }, WenkuRequestPolicy(intervalMillis = 0), hasNetwork = { hasNetwork },
        ) { request ->
            requests++
            assertEquals(setOf("jieqiUserInfo", "PHPSESSID", "cf_clearance"), cookies.loadForRequest(request.url).map { it.name }.toSet())
            response(request.url, 200, popular.encodeToByteArray())
        }
        val browse = WenkuBrowse(pages, origin)
        assertNull(browse.checkWebsiteAccess())
        hasNetwork = false
        assertEquals(AccessFailure.OFFLINE, browse.checkWebsiteAccess())
        assertEquals(1, requests)
        hasNetwork = true
        assertNull(browse.checkWebsiteAccess())
        assertEquals(2, requests)
        assertEquals(0, cookies.writes)
        assertTrue("transport from=OFFLINE to=HTTP" in logs)
        assertTrue(logs.any { "siteLogin=SIGNED_IN" in it })
        assertEquals("网站当前访问正常，无需重新验证。", wenkuWebsiteMessage(null))
    }

    @Test
    fun `DNS and timeout preserve login and clearance until the next real response`() = runBlocking {
        val cookies = FakeCookieJar(origin)
        var requests = 0
        val logs = mutableListOf<String>()
        val pages = WenkuPages(
            OkHttpClient.Builder().cookieJar(cookies).build(), headersOf(), origin,
            WenkuDiagnostics { logs += it }, WenkuRequestPolicy(intervalMillis = 0), hasNetwork = { true },
        ) { request ->
            when (++requests) {
                1 -> throw SocketTimeoutException("synthetic-timeout-detail")
                2 -> throw UnknownHostException("synthetic-dns-detail")
                else -> response(request.url, 200, popular.encodeToByteArray())
            }
        }
        val browse = WenkuBrowse(pages, origin)
        repeat(2) { assertEquals(AccessFailure.NETWORK, browse.checkWebsiteAccess()) }
        assertNull(browse.checkWebsiteAccess())
        assertTrue(cookies.loadForRequest(origin).any { it.name == "jieqiUserInfo" })
        assertEquals(0, cookies.writes)
        assertTrue("transport from=TIMEOUT to=DNS" in logs)
        assertTrue("transport from=DNS to=HTTP" in logs)
        assertFalse(logs.any { "synthetic-" in it })
    }

    @Test
    fun `server challenge and login redirect are classified independently of existing cookies`() = runBlocking {
        val cookies = FakeCookieJar(origin)
        val paths = mutableListOf<HttpUrl>()
        val pages = WenkuPages(OkHttpClient.Builder().cookieJar(cookies).build(), headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0)) { request ->
            paths += request.url
            when (paths.size) {
                1 -> response(request.url, 403).newBuilder().header("cf-mitigated", "challenge").build()
                2 -> response(request.url, 302, location = "http://www.wenku8.net/login.php")
                else -> response(request.url, 403)
            }
        }
        val browse = WenkuBrowse(pages, origin)
        assertEquals(AccessFailure.CHALLENGE, browse.checkWebsiteAccess())
        assertTrue(wenkuWebsiteMessage(AccessFailure.CHALLENGE).contains("保留现有账号登录"))
        assertEquals(AccessFailure.SESSION_EXPIRED, browse.checkWebsiteAccess())
        assertTrue(wenkuWebsiteMessage(AccessFailure.SESSION_EXPIRED).contains("HTTPS 登录页"))
        assertEquals(AccessFailure.FORBIDDEN, browse.checkWebsiteAccess())
        assertTrue(paths.all { it.isHttps && it.encodedPath.endsWith("toplist.php") })
        assertEquals(0, cookies.writes)
    }

    @Test
    fun `inline turnstile and ordinary titles do not count as interstitial challenges`() = runBlocking {
        var requests = 0
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0)) { request ->
            val body = if (++requests == 1) {
                "<form><input name='username'><input name='password'><input name='cf-turnstile-response'></form>"
            } else {
                popular.replace("<html>", "<html><head><title>Book: Just a moment</title></head>")
            }
            response(request.url, 200, body.encodeToByteArray())
        }
        val browse = WenkuBrowse(pages, origin)
        assertEquals(AccessFailure.LOGIN_REQUIRED, browse.checkWebsiteAccess())
        assertNull(browse.checkWebsiteAccess())
    }

    @Test
    fun `manual access check reports 429 without retry and regular requests honor the same backoff`() = runBlocking {
        val time = AtomicLong()
        val starts = mutableListOf<Long>()
        val policy = WenkuRequestPolicy(intervalMillis = 0, monotonicMillis = time::get, pause = { time.addAndGet(it) })
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin, policy = policy) { request ->
            starts += time.get()
            if (starts.size == 1) response(request.url, 429).newBuilder().header("Retry-After", "7").build()
            else response(request.url, 200, popular.encodeToByteArray())
        }
        val browse = WenkuBrowse(pages, origin)
        repeat(2) { assertEquals(AccessFailure.RATE_LIMITED, browse.checkWebsiteAccess()) }
        assertEquals(listOf(0L), starts)
        assertEquals("样本书", browse.popular(1).books.single().title)
        assertEquals(listOf(0L, 7_000L), starts)
    }

    private class FakeCookieJar(origin: HttpUrl) : CookieJar {
        var writes = 0
            private set
        private val cookies = listOf("jieqiUserInfo", "PHPSESSID", "cf_clearance").map {
            Cookie.Builder().name(it).value("synthetic").hostOnlyDomain(origin.host).secure().build()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            writes++
        }
    }

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
        val debug = WenkuDiagnostics(webViewCookies = { setOf("cf_clearance") }) { logs += it }
        val url = "https://www.wenku8.net/top.php?secret-query=value#secret-fragment".toHttpUrl()
        debug.session(url, setOf("cf_clearance"))
        debug.sent(Request.Builder().url(url).header("Cookie", "PHPSESSID=secret-session").build())
        debug.sent(Request.Builder().url(url).header("Cookie", "other=secret-other; jieqiUserInfo=secret-login; cf_clearance=secret-clearance")
            .header("User-Agent", "secret-user-agent").build(), "secret-user-agent")
        debug.redirect("http://www.wenku8.net/login.php?jumpurl=secret-redirect".toHttpUrl())
        debug.redirect("https://secret-host.example/secret-path".toHttpUrl())
        assertEquals(
            listOf(
                "session url=https://www.wenku8.net/top.php jarNames=cf_clearance webViewNames=cf_clearance cookieNamesMatch=true",
                "request url=https://www.wenku8.net/top.php sentNames=PHPSESSID sourceUserAgentMatches=null",
                "request url=https://www.wenku8.net/top.php sentNames=cf_clearance,jieqiUserInfo sourceUserAgentMatches=true",
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
