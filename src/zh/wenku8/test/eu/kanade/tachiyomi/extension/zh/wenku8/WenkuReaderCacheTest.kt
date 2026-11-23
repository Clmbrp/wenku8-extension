package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class WenkuReaderCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val origin = "https://www.wenku8.net/".toHttpUrl()
    private val path = "/novel/0/42/101.htm"
    private val network = OkHttpClient()
    private val png = byteArrayOf(1, 2, 3)
    private val chapter = """<div id="contentmain"><div id="title">样本</div><div id="content"><img src="https://cdn.example.org/a.jpg"></div></div>"""

    @Test
    fun `two subscribers to the same illustration share the fetch and PNG render`() = runBlocking {
        val fetches = AtomicInteger()
        val renders = AtomicInteger()
        val firstFetch = CompletableDeferred<Unit>()
        val secondFetch = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val reader = reader(temporary.newFolder(), renders) {
            if (fetches.incrementAndGet() == 1) firstFetch.complete(Unit) else secondFetch.complete(Unit)
            release.await()
            png
        }
        val url = reader.pageUrls(path).single()
        val client = network.newBuilder().addInterceptor {
            if (it.request().header("second") != null) secondEntered.complete(Unit)
            reader.intercept(it)
        }.build()
        val first = async(Dispatchers.IO) { get(client, url) }
        try {
            withTimeout(5_000) { firstFetch.await() }
            val second = async(Dispatchers.IO) { get(client, url, second = true) }
            withTimeout(5_000) { secondEntered.await() }
            withTimeoutOrNull(500) { secondFetch.await() }
            release.complete(Unit)
            assertArrayEquals(png, first.await())
            assertArrayEquals(png, second.await())
            assertEquals(1, fetches.get())
            assertEquals(1, renders.get())
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `cancelling one shared subscriber lets another keep reading`() = runBlocking {
        val flights = WenkuFlights<String, ByteArray>()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            flights.await("same page") {
                entered.complete(Unit)
                release.await()
                png
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                flights.await("same page") { error("Reader started duplicate work") }
            }
            first.cancelAndJoin()
            release.complete(Unit)
            assertArrayEquals(png, withTimeout(5_000) { second.await() })
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `cancelling a queued illustration releases it before a network request`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val secondFetched = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetches = AtomicInteger()
        val fixture = chapter.replace("</div></div>", """<img src="https://cdn.example.org/b.jpg"></div></div>""")
        val reader = reader(temporary.newFolder(), AtomicInteger(), html = fixture) { url ->
            fetches.incrementAndGet()
            if (url.endsWith("a.jpg")) {
                firstEntered.complete(Unit)
                release.await()
            } else {
                secondFetched.complete(Unit)
            }
            png
        }
        val urls = reader.pageUrls(path)
        val client = network.newBuilder().addInterceptor {
            if (it.request().url.toString() == urls[1]) secondEntered.complete(Unit)
            reader.intercept(it)
        }.build()
        val first = async(Dispatchers.IO) { get(client, urls[0]) }
        try {
            withTimeout(5_000) { firstEntered.await() }
            val call = client.newCall(Request.Builder().url(urls[1]).build())
            val second = async(Dispatchers.IO) { runCatching { call.execute().close() }.exceptionOrNull() }
            withTimeout(5_000) { secondEntered.await() }
            assertNull(withTimeoutOrNull(300) { secondFetched.await() })
            call.cancel()
            assertTrue(withTimeout(5_000) { second.await() } is IOException)
            release.complete(Unit)
            assertArrayEquals(png, first.await())
            assertArrayEquals(png, get(client, urls[1]))
            assertEquals(2, fetches.get())
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `memory cache enforces both budgets and retains only one oversized layout`() {
        val cache = WenkuMemoryCache<Int, ByteArray>(3, 6) { it.size.toLong() }
        repeat(100) { cache.put(it, ByteArray(3)) }
        assertNull(cache[97])
        assertTrue(cache[98] != null)
        cache.put(100, ByteArray(3))
        assertNull(cache[99])
        assertTrue(cache[98] != null)
        cache.put(101, ByteArray(7))
        assertNull(cache[101])

        val byCount = WenkuMemoryCache<Int, ByteArray>(2, 100, keepOversized = true) { it.size.toLong() }
        repeat(100) { byCount.put(it, ByteArray(1)) }
        assertNull(byCount[97])
        assertTrue(byCount[98] != null)
        byCount.put(100, ByteArray(120))
        assertNull(byCount[98])
        assertNull(byCount[99])
        assertEquals(120, byCount[100]?.size)
        byCount.put(101, ByteArray(1))
        assertNull(byCount[100])
    }

    @Test
    fun `parsed reuse checks freshness and maintenance only removes plugin cache files`() {
        val root = temporary.newFolder()
        val pluginDirectory = File(root, "plugin-cache").apply { mkdir() }
        val legacyDirectory = File(root, "legacy-cache").apply { mkdir() }
        val downloads = File(root, "downloads").apply { mkdir() }
        val archive = File(downloads, "sample.cbz").apply { writeText("archive") }
        val unrelated = File(pluginDirectory, "unrelated.tmp").apply { writeText("unrelated") }
        val now = System.currentTimeMillis()
        val pending = File(pluginDirectory, "pending-123.tmp").apply {
            writeText("orphan")
            setLastModified(now - 48L * 60 * 60 * 1_000)
        }
        val active = File(pluginDirectory, "pending-456.tmp").apply { writeText("active") }
        val legacy = File(legacyDirectory, "pending-123.tmp").apply {
            writeText("legacy")
            setLastModified(now - 48L * 60 * 60 * 1_000)
        }
        val cache = WenkuTextCache(pluginDirectory, { now }, legacyDirectory)
        val snapshot = cache.remember(path, WenkuChapterText("标题", "样本文字。"))
        assertSame(snapshot, cache.read(snapshot.key))
        assertFalse(pending.exists())
        assertTrue(active.exists())
        assertTrue(unrelated.exists())
        assertTrue(legacy.exists())
        assertEquals("archive", archive.readText())
        val stored = pluginDirectory.listFiles()!!.single { it.extension == "txt" }
        stored.writeText("corrupt")
        assertNull(cache.read(snapshot.key))
    }

    @Test
    fun `disk snapshot churn stays within both limits and keeps the newest write`() {
        val directory = temporary.newFolder()
        val cache = WenkuTextCache(directory)
        fun churn(count: Int, body: String): WenkuCachedChapter {
            var newest = cache.remember(path, WenkuChapterText("标题", body))
            repeat(count) { index ->
                newest = cache.remember("/novel/0/42/${index + 200}.htm", WenkuChapterText("标题", body))
            }
            return newest
        }
        val newestSmall = churn(30, "短章。")
        assertEquals(24, directory.listFiles()?.size)
        assertEquals(newestSmall, cache.read(newestSmall.key))
        val newestLarge = churn(12, "长".repeat(800_000))
        assertTrue(directory.listFiles()!!.sumOf(File::length) <= 16L * 1_024 * 1_024)
        assertEquals(newestLarge, cache.read(newestLarge.key))
    }

    private fun reader(directory: File, renders: AtomicInteger, html: String = chapter, illustration: suspend (String) -> ByteArray): WenkuReader {
        val pages = WenkuPages(network, headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0)) { request ->
            Response.Builder().request(request).code(200).protocol(Protocol.HTTP_1_1).message("OK")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }
        return WenkuReader(
            pages, origin, WenkuTextCache(directory),
            graphics = {
                object : WenkuPageGraphics {
                    override fun prepare(text: WenkuChapterText): WenkuTextPages = error("Illustration-only fixture")
                    override fun illustration(data: ByteArray, title: String): ByteArray {
                        renders.incrementAndGet()
                        return data
                    }
                }
            },
            fetchIllustration = illustration,
        )
    }

    private fun get(client: OkHttpClient, url: String, second: Boolean = false): ByteArray {
        val request = Request.Builder().url(url).apply { if (second) header("second", "yes") }.build()
        return client.newCall(request).execute().use { it.body.bytes() }
    }
}
