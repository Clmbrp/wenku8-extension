package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class WenkuWebViewRecoveryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val origin = "https://www.wenku8.net/".toHttpUrl()
    private val chapter = origin.resolve("/novel/0/42/101.htm")!!
    private val other = origin.resolve("/novel/0/43/102.htm")!!
    private val login = origin.resolve("/login.php")!!
    private val detail = origin.resolve("/book/42.htm")!!
    private val signedIn = """<a href="/logout.php">退出登录</a>"""
    private val popular = """$signedIn<div id="content"><table class="grid"><tr><td><a href="/book/42.htm">样本书</a></td></tr></table></div>"""
    private val body = """$signedIn<div id="contentmain"><div id="title">样本章</div><div id="content">正文。</div></div>"""

    @Test
    fun `a challenged chapter survives other requests and WebView dismissal until its real content loads`() = runBlocking {
        val recovery = WenkuWebViewRecovery(origin)
        var mode = 0
        var requests = 0
        var online = true
        var probeNeedsLogin = false
        val client = OkHttpClient.Builder().cookieJar(SignedInJar()).build()
        val pages = WenkuPages(client, headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0), hasNetwork = { online }, recovery = recovery) { request ->
            requests++
            when {
                request.url == chapter && mode == 0 -> challenge(request)
                request.url == chapter && mode == 1 -> response(request, 200, "<h1>不是正文</h1>")
                request.url == chapter -> response(request, 200, body)
                request.url == other && mode == 0 -> challenge(request)
                request.url == other -> response(request, 200, body)
                probeNeedsLogin -> response(request, 302).newBuilder().header("Location", "/login.php").build()
                else -> response(request, 200, popular)
            }
        }
        val reader = WenkuReader(
            pages, origin, WenkuTextCache(temporary.newFolder()),
            graphics = {
                object : WenkuPageGraphics {
                    override fun prepare(text: WenkuChapterText) = object : WenkuTextPages {
                        override val pageCount = 1
                        override fun png(index: Int) = error("not rendering pixels")
                    }

                    override fun illustration(data: ByteArray, title: String) = error("not an illustration")
                }
            },
            fetchIllustration = { error("not an illustration") },
        )
        assertEquals(AccessFailure.CHALLENGE, failure { reader.pageUrls(chapter.toString()) })
        assertEquals(chapter, recovery.entry())
        val browse = WenkuBrowse(pages, origin)
        assertNull(browse.checkWebsiteAccess())
        assertTrue(wenkuWebsiteMessage(null, recovery.hasPending()).startsWith("热门榜可访问。"))
        probeNeedsLogin = true
        assertEquals(AccessFailure.SESSION_EXPIRED, browse.checkWebsiteAccess())
        assertEquals(chapter, recovery.entry())
        assertEquals(AccessFailure.CHALLENGE, failure { reader.pageUrls(other.encodedPath) })
        assertEquals(chapter, recovery.entry())
        assertEquals(chapter, recovery.entry()) // The user opened WebView and backed out.
        mode = 1
        assertEquals(1, reader.pageUrls(other.encodedPath).size)
        assertEquals(AccessFailure.STRUCTURE, failure { reader.pageUrls(chapter.encodedPath) })
        assertEquals(chapter, recovery.entry())
        mode = 2
        val urls = reader.pageUrls(chapter.encodedPath)
        assertEquals(1, urls.size)
        assertEquals(origin, recovery.entry())
        val beforeOffline = requests
        online = false
        assertEquals(urls, reader.pageUrls(chapter.encodedPath))
        assertEquals(beforeOffline, requests)
    }

    @Test
    fun `confirmed login redirect opens HTTPS and retries the original detail without following HTTP`() = runBlocking {
        val recovery = WenkuWebViewRecovery(origin)
        val requests = mutableListOf<HttpUrl>()
        val client = OkHttpClient.Builder().cookieJar(SignedInJar()).build()
        val pages = WenkuPages(client, headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0), recovery = recovery) { request ->
            requests += request.url
            when (requests.size) {
                1 -> challenge(request)
                2 -> response(request, 302).newBuilder().header("Location", "http://www.wenku8.net/login.php?jumpurl=private").build()
                else -> response(request, 200, signedIn)
            }
        }
        assertEquals(AccessFailure.CHALLENGE, failure { pages.get(detail.toString()) })
        assertEquals(detail, recovery.entry())
        assertEquals(AccessFailure.SESSION_EXPIRED, failure { pages.get(detail.toString()) })
        assertEquals(login, recovery.entry())
        assertEquals(login, recovery.entry())
        pages.get(detail.toString())
        assertEquals(listOf(detail, detail, detail), requests)
        assertEquals(origin, recovery.entry())
    }

    @Test
    fun `ordinary denial Cloudflare block rate limit and timeout do not prepare a WebView target`() = runBlocking {
        val recovery = WenkuWebViewRecovery(origin)
        var attempts = 0
        val pages = WenkuPages(
            OkHttpClient(), headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0, fallbackCooldownMillis = 0),
            recovery = recovery,
        ) { request ->
            when (++attempts) {
                1 -> response(request, 403)
                2 -> response(request, 403, """<div id="cf-error-details"><h1 data-translate="block_headline">blocked</h1></div>""")
                3 -> response(request, 429)
                else -> throw SocketTimeoutException()
            }
        }
        for (kind in listOf(AccessFailure.FORBIDDEN, AccessFailure.CLOUDFLARE_BLOCK, AccessFailure.RATE_LIMITED, AccessFailure.NETWORK)) {
            assertEquals(kind, failure { pages.get(detail.toString(), retryOnRateLimit = false) })
            assertEquals(origin, recovery.entry())
        }
    }

    @Test
    fun `redirect records the actual allowlisted browser page and rejects unsafe or non-webpage destinations`() = runBlocking {
        val recovery = WenkuWebViewRecovery(origin)
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0), recovery = recovery) { request ->
            if (request.url == detail) response(request, 302).newBuilder().header("Location", chapter.encodedPath).build()
            else challenge(request)
        }
        for (url in listOf(
            "http://www.wenku8.net/book/42.htm", "https://external.example/book/42.htm", "https://user@www.wenku8.net/book/42.htm",
            "https://www.wenku8.net:444/book/42.htm", "https://wenku8-reader.invalid/novel/0/42/101.htm",
            "https://www.wenku8.net/files/42.jpg", "https://www.wenku8.net/cover/42.webp",
            "https://www.wenku8.net/cdn-cgi/challenge-platform/example", "https://www.wenku8.net/book/42.htm?__cf_chl_tk=test",
        )) {
            assertTrue(failure { pages.get(url) } in setOf(AccessFailure.STRUCTURE, AccessFailure.CHALLENGE))
            assertEquals(url, origin, recovery.entry())
        }
        assertEquals(AccessFailure.CHALLENGE, failure { pages.get(detail.toString()) })
        assertEquals(chapter, recovery.entry())
    }

    @Test
    fun `search challenge opens the clean search form without repeating the submission`() = runBlocking {
        val recovery = WenkuWebViewRecovery(origin)
        val requests = mutableListOf<Pair<String, HttpUrl>>()
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0), recovery = recovery) { request ->
            requests += request.method to request.url
            challenge(request)
        }
        val form = origin.resolve(WenkuBrowse.SEARCH_FORM)!!
        assertEquals(AccessFailure.CHALLENGE, failure { pages.searchForm("搜索样本", FormBody.Builder().add("searchkey", "搜索样本").build()) })
        assertEquals(form, recovery.entry())
        assertEquals(listOf("POST" to form), requests)
        val result = origin.resolve("${WenkuBrowse.SEARCH}?searchkey=private&page=2")!!
        assertEquals(AccessFailure.CHALLENGE, failure { pages.get(result.toString()) })
        assertEquals(form, recovery.entry())
        assertEquals(listOf("POST" to form, "GET" to result), requests)
    }

    @Test
    fun `cancelled requests do not create a target and unresolved entries expire`() = runBlocking {
        val clock = TestTimeSource()
        val recovery = WenkuWebViewRecovery(origin, clock)
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0), recovery = recovery) { request ->
            started.complete(Unit)
            never.await()
            challenge(request)
        }
        val cancelled = async { pages.get(chapter.toString()) }
        started.await()
        cancelled.cancelAndJoin()
        never.complete(Unit)
        assertEquals(origin, recovery.entry())
        assertTrue(recovery.prepareLogin())
        assertEquals(login, recovery.entry())
        clock += 10.minutes
        assertEquals(origin, recovery.entry())
        recovery.failed(chapter, chapter, AccessFailure.CHALLENGE, probe = false)
        clock += 11.minutes
        assertEquals(origin, recovery.entry())
    }

    private suspend fun failure(block: suspend () -> Any): AccessFailure =
        (runCatching { block() }.exceptionOrNull() as WenkuAccessException).failure

    private fun challenge(request: Request) = response(request, 403).newBuilder().header("cf-mitigated", "challenge").build()

    private fun response(request: Request, code: Int, body: String = ""): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).build()

    private class SignedInJar : CookieJar {
        override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name(WenkuSession.LOGIN_COOKIE).value("synthetic").hostOnlyDomain(url.host).secure().build())
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = error("Unexpected cookie write")
    }
}
