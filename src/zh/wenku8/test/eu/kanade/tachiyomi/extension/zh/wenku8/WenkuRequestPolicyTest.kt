package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WenkuRequestPolicyTest {
    private val origin = "https://www.wenku8.net/".toHttpUrl()

    @Test
    fun `HTML endpoints and redirect hops share one dispatch interval`() = runBlocking {
        val clock = TestClock()
        val calls = mutableListOf<Pair<String, Long>>()
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pages = pages(clock.policy) { request ->
            val path = request.url.encodedPath
            calls += path to clock.now.get()
            if (path == "/top.php") {
                arrived.complete(Unit)
                release.await()
            }
            if (path == "/so.php") {
                reply(request, code = 302, location = "/modules/article/search.php")
            } else {
                reply(request)
            }
        }
        withTimeout(5_000) {
            val ranking = async(start = CoroutineStart.UNDISPATCHED) { pages.get("/top.php") }
            arrived.await()
            val book = async(start = CoroutineStart.UNDISPATCHED) { pages.get("/book/42.htm") }
            val form = FormBody.Builder().add("searchkey", "示例").build()
            val search = async(start = CoroutineStart.UNDISPATCHED) { pages.searchForm("示例", form) }
            release.complete(Unit)
            ranking.await()
            book.await()
            search.await()
        }
        assertEquals(setOf("/top.php", "/book/42.htm", "/so.php", "/modules/article/search.php"), calls.map { it.first }.toSet())
        assertEquals(4, calls.size)
        assertTrue(calls.zipWithNext().all { (first, next) -> next.second - first.second >= 2_000 })
    }

    @Test
    fun `search result requests use a separate floor while books use the normal interval`() = runBlocking {
        val clock = TestClock()
        val starts = mutableListOf<Long>()
        val pages = pages(clock.policy) { request ->
            starts += clock.now.get()
            reply(request)
        }
        pages.get("/modules/article/search.php?page=1")
        pages.get("/book/42.htm")
        pages.get("/modules/article/search.php?page=2")
        assertEquals(listOf(0L, 2_000L, 6_500L), starts)
    }

    @Test
    fun `identical concurrent GETs share one call even if first subscriber cancels`() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val pages = pages(TestClock().policy) { request ->
            requests.incrementAndGet()
            arrived.complete(Unit)
            release.await()
            reply(request)
        }
        withTimeout(5_000) {
            val first = async(start = CoroutineStart.UNDISPATCHED) { pages.get("/book/42.htm") }
            arrived.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { pages.get("https://www.wenku8.net/book/42.htm") }
            first.cancelAndJoin()
            release.complete(Unit)
            assertEquals("https://www.wenku8.net/book/42.htm", second.await().location())
            assertEquals(1, requests.get())
            pages.get("/book/42.htm")
        }
        assertEquals(2, requests.get())
    }

    @Test
    fun `cancellation drops queued work and cancels an active call with no subscribers`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val pages = pages(TestClock().policy) { request ->
            val path = request.url.encodedPath
            calls += path
            if (path == "/top.php") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
            reply(request)
        }
        withTimeout(5_000) {
            val active = async(start = CoroutineStart.UNDISPATCHED) { pages.get("/top.php") }
            started.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { pages.get("/book/42.htm") }
            queued.cancelAndJoin()
            active.cancelAndJoin()
            stopped.await()
            pages.get("/novel/0/42/index.htm")
        }
        assertEquals(listOf("/top.php", "/novel/0/42/index.htm"), calls)
    }

    @Test
    fun `429 keeps the load suspended through Retry-After and resumes without an error`() = runBlocking {
        val now = AtomicLong()
        val calls = mutableListOf<Pair<String, Long>>()
        val paused = CompletableDeferred<Long>()
        val resume = CompletableDeferred<Unit>()
        val policy = WenkuRequestPolicy(monotonicMillis = now::get, pause = { wait ->
            if (now.get() == 0L) {
                paused.complete(wait)
                resume.await()
                now.compareAndSet(0, 45_000)
            } else {
                now.addAndGet(wait)
            }
        })
        val pages = pages(policy) { request ->
            calls += request.url.encodedPath to now.get()
            if (calls.size == 1) {
                reply(request, code = 429, retryAfter = "45")
            } else {
                reply(request)
            }
        }
        withTimeout(5_000) {
            val source = async { pages.get("/top.php") }
            assertEquals(45_000L, paused.await())
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { pages.get("/book/42.htm") }
            assertFalse(source.isCompleted)
            assertFalse(waiting.isCompleted)
            assertEquals(listOf("/top.php" to 0L), calls)
            resume.complete(Unit)
            assertEquals("正常", source.await().title())
            assertEquals("正常", waiting.await().title())
        }
        assertEquals(3, calls.size)
        assertTrue(calls.drop(1).all { it.second >= 45_000L })
    }

    @Test
    fun `HTTP date Retry-After uses the server Date if supplied`() = runBlocking {
        val clock = TestClock()
        val starts = mutableListOf<Long>()
        val pages = pages(clock.policy) { request ->
            starts += clock.now.get()
            if (starts.size == 1) {
                reply(
                    request,
                    code = 429,
                    retryAfter = "Sun, 06 Nov 1994 08:50:37 GMT",
                    date = "Sun, 06 Nov 1994 08:49:37 GMT",
                )
            } else {
                reply(request)
            }
        }
        assertEquals("正常", pages.get("/top.php").title())
        assertEquals(listOf(0L, 60_000L), starts)
    }

    @Test
    fun `site frequency notice is distinguished from search structure and sets fallback cooldown`() = runBlocking {
        val clock = TestClock()
        var requests = 0
        val pages = pages(clock.policy) { request ->
            requests++
            if (requests == 1) {
                reply(request, """<div id="content"><div class="blockcontent">操作频繁，请稍候再试。</div></div>""")
            } else {
                reply(request, "<div id='content'>未识别结构</div>")
            }
        }
        val browse = WenkuBrowse(pages, origin)
        assertEquals(AccessFailure.STRUCTURE, failure { browse.search(1, "示例") }.failure)
        assertEquals(2, requests)
        assertEquals(30_000L, clock.now.get())
    }

    @Test
    fun `persistent 429 reports a final failure after a single retry`() = runBlocking {
        val clock = TestClock()
        val starts = mutableListOf<Long>()
        val pages = pages(clock.policy) { request ->
            starts += clock.now.get()
            reply(request, code = 429, retryAfter = "10")
        }
        assertEquals(AccessFailure.RATE_LIMITED, failure { pages.get("/top.php") }.failure)
        assertEquals(listOf(0L, 10_000L), starts)
    }

    @Test
    fun `challenge and login responses do not turn into cooldown or empty results`() = runBlocking {
        val clock = TestClock()
        val pages = pages(clock.policy) { request ->
            when (request.url.encodedPath) {
                "/so.php" -> reply(request, """<title>Just a moment...</title><p>访问过于频繁</p>""", code = 503)
                "/book/42.htm" -> reply(request, code = 302, location = "/login.php")
                else -> reply(request)
            }
        }
        assertEquals(AccessFailure.CHALLENGE, failure { WenkuBrowse(pages, origin).search(1, "示例") }.failure)
        assertEquals(AccessFailure.LOGIN_REQUIRED, failure { pages.get("/book/42.htm") }.failure)
        assertEquals("正常", pages.get("/top.php").title())
        assertEquals(4_000L, clock.now.get())
    }

    private class TestClock {
        val now = AtomicLong()
        val policy = WenkuRequestPolicy(monotonicMillis = now::get, pause = { now.addAndGet(it) })
    }

    private suspend fun failure(block: suspend () -> Any): WenkuAccessException = try {
        block()
        throw AssertionError("Expected a WenkuAccessException")
    } catch (e: WenkuAccessException) {
        e
    }

    private fun pages(policy: WenkuRequestPolicy, execute: suspend (Request) -> Response): WenkuPages =
        WenkuPages(OkHttpClient(), headersOf(), origin, policy = policy, executeRequest = execute)

    private fun reply(
        request: Request,
        html: String = "<title>正常</title>",
        code: Int = 200,
        retryAfter: String? = null,
        date: String? = null,
        location: String? = null,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .apply {
            retryAfter?.let { header("Retry-After", it) }
            date?.let { header("Date", it) }
            location?.let { header("Location", it) }
        }
        .header("Content-Type", "text/html; charset=utf-8")
        .body(html.toResponseBody())
        .build()
}
