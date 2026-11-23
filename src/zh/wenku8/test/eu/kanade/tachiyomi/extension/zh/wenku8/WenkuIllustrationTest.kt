package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class WenkuIllustrationTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val origin = "https://www.wenku8.net/".toHttpUrl()
    private val chapterPath = "/novel/0/42/101.htm"

    @Test
    fun `reading sequence keeps alternating text and figures with a single title`() {
        val chapter = parse("""<p>甲 &amp; 乙</p><img src="one.jpg"><p>图注。</p>
            <img src="//cdn.example.org/two.webp"><img src="one.jpg"><p>结束。</p>""")
        assertEquals(
            listOf("text:示例:甲 & 乙", "image::one.jpg", "text::图注。", "image::two.webp", "image::one.jpg", "text::结束。"),
            describe(chapter),
        )
        assertEquals(
            listOf("image:示例:a.jpg", "image::b.png", "text::后记。"),
            describe(parse("""<img src="a.jpg"><img src="b.png"><p>后记。</p>""")),
        )
        assertEquals(listOf("text:示例:第一段。\n\n第二段。"), describe(parse("<p>第一段。</p><p>第二段。</p>")))
    }

    @Test
    fun `parser resolves encoded links and skips decorative and lazy fallback images`() {
        val chapter = parse(
            """<ul id="contentdp"><li><img src="nav.png"></li></ul>
                <div id="adv300"><img src="ad.jpg"></div>
                <img class="site-logo" src="logo.png"><img src="pixel.gif" width="1" height="1">
                <div class="divimage"><a href="../插图 1.jpg"><picture>
                    <source srcset="unused.webp"><img src="data:image/gif;base64,abc" data-src="../插图 1.jpg?q=a%20b&amp;p=1#unused">
                </picture></a><noscript><img src="../插图 1.jpg"></noscript></div>
                <img src="//pic.example.org/two.png"><img src="https://another.example.org/three.webp">""",
        )
        val urls = chapter.blocks.filterIsInstance<WenkuContentBlock.ImageBlock>().map { it.url }
        assertEquals(
            listOf(
                "https://www.wenku8.net/novel/0/%E6%8F%92%E5%9B%BE%201.jpg?q=a%20b&p=1",
                "https://pic.example.org/two.png",
                "https://another.example.org/three.webp",
            ),
            urls,
        )
        assertEquals(listOf("image:示例:%E6%8F%92%E5%9B%BE%201.jpg", "image::two.png", "image::three.webp"), describe(chapter))
    }

    @Test
    fun `pure illustration chapter gives stable pages with no prefetch and reuses cached HTML after reopen`() = runBlocking {
        val directory = temporary.newFolder()
        var htmlCalls = 0
        var imageCalls = 0
        val service = WenkuPages(OkHttpClient(), headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0)) { request ->
            htmlCalls++
            val html = """<div id="contentmain"><div id="title">卷首插图</div>
                <div id="content"><img src="https://cdn.example.org/a.jpg"><img src="https://cdn.example.org/b.jpg"></div></div>"""
            response(request, content = html.encodeToByteArray(), mime = "text/html; charset=utf-8")
        }
        val makeReader = {
            WenkuReader(service, origin, WenkuTextCache(directory)) {
                imageCalls++
                throw IOException("模拟插图失败")
            }
        }
        val reader = makeReader()
        val urls = reader.pageUrls(chapterPath)
        assertEquals(2, urls.size)
        assertTrue(urls.all { "/v3/" in it && WenkuPageRef.parse(it.toHttpUrl())?.key?.version == 2 })
        assertEquals(urls, makeReader().pageUrls(chapterPath))
        assertEquals(1, htmlCalls)
        assertEquals(0, imageCalls)

        val intercepted = OkHttpClient.Builder().addInterceptor(reader::intercept).build()
        for (url in urls) {
            val error = assertThrows(IOException::class.java) {
                intercepted.newCall(Request.Builder().url(url).build()).execute().close()
            }
            assertEquals("模拟插图失败", error.message)
        }
        assertEquals(2, imageCalls)
        val stored = directory.listFiles()!!.single()
        assertTrue(stored.delete())
        val restored = OkHttpClient.Builder().addInterceptor(makeReader()::intercept).build()
        val recoveredError = assertThrows(IOException::class.java) {
            restored.newCall(Request.Builder().url(urls.last()).build()).execute().close()
        }
        assertEquals("模拟插图失败", recoveredError.message)
        assertEquals(3, imageCalls)
        assertEquals(urls, makeReader().pageUrls(chapterPath))
        assertEquals(2, htmlCalls)
    }

    @Test
    fun `legacy text snapshots still resolve while a new chapter load uses the ordered snapshot`() {
        val legacy = temporary.newFolder()
        val fresh = temporary.newFolder()
        val before = WenkuTextCache(legacy).remember(chapterPath, WenkuChapterText("示例", "甲。\n\n乙。"))
        val cache = WenkuTextCache(fresh, legacyDirectory = legacy)
        assertEquals(before, cache.read(before.key))
        assertNull(cache.fresh(chapterPath))
        val content = parse("""甲。<img src="a.jpg">乙。""")
        val mixed = cache.remember(chapterPath, content)
        assertEquals(before, cache.legacySnapshot(chapterPath, content))
        val reopened = WenkuTextCache(fresh, legacyDirectory = legacy)
        assertEquals(mixed, reopened.read(mixed.key))
        assertEquals(before, reopened.read(before.key))
        assertEquals(mixed, reopened.fresh(chapterPath))
        assertEquals(before.key, WenkuPageRef.parse(WenkuPageRef(before.key, 0).url().toHttpUrl())?.key)
        assertEquals(mixed.key, WenkuPageRef.parse(WenkuPageRef(mixed.key, 1).url().toHttpUrl())?.key)
    }

    @Test
    fun `illustration fetch accepts HTTPS across hosts without forwarding site headers`() = runBlocking {
        val requests = mutableListOf<Request>()
        val network = OkHttpClient()
        val bytes = byteArrayOf(10, 20, 30)
        val pages = WenkuPages(network, headersOf("Authorization", "test-only"), origin) { error("Unexpected same-site request") }
        val illustrations = WenkuIllustrations(network, pages, origin) { request ->
            requests += request
            if (request.url.host == "cdn.example.org") {
                response(request, status = 302, location = "https://other.example.org/%E5%9B%BE.png")
            } else {
                response(request, content = bytes)
            }
        }
        assertArrayEquals(bytes, illustrations.fetch("https://cdn.example.org/a.jpg"))
        assertEquals(listOf("cdn.example.org", "other.example.org"), requests.map { it.url.host })
        assertEquals("/%E5%9B%BE.png", requests.last().url.encodedPath)
        assertTrue(requests.all { it.header("Authorization") == null && it.header("Cookie") == null })
    }

    @Test
    fun `illustration failures reject rate limits inline error HTML and unsafe redirects`() {
        for (case in listOf("429", "html", "downgrade", "local")) {
            var count = 0
            val network = OkHttpClient()
            val pages = WenkuPages(network, headersOf(), origin) { error("Unexpected main-site request") }
            val illustrations = WenkuIllustrations(network, pages, origin) { request ->
                count++
                when (case) {
                    "429" -> response(request, status = 429)
                    "html" -> response(request, mime = "text/html", content = "<html>error</html>".encodeToByteArray())
                    "downgrade" -> response(request, status = 302, location = "http://cdn.example.org/image.jpg")
                    else -> response(request, status = 302, location = "https://127.0.0.1/image.jpg")
                }
            }
            val error = assertThrows(IOException::class.java) { runBlocking { illustrations.fetch("https://cdn.example.org/image.jpg") } }
            assertEquals(1, count)
            assertTrue(error.message.orEmpty().contains("插图"))
        }
    }

    @Test
    fun `same site illustration uses the HTML interval and advances the shared cooldown`() = runBlocking {
        val clock = AtomicLong()
        val policy = WenkuRequestPolicy(monotonicMillis = clock::get, pause = { clock.addAndGet(it) })
        val calls = mutableListOf<Long>()
        val network = OkHttpClient()
        val pages = WenkuPages(network, headersOf(), origin, policy = policy) { request ->
            calls += clock.get()
            if (request.url.encodedPath.endsWith(".jpg")) {
                response(request, status = 429).newBuilder().header("Retry-After", "7").build()
            } else {
                response(request, content = "<html><title>样本</title></html>".encodeToByteArray(), mime = "text/html")
            }
        }
        val illustrations = WenkuIllustrations(network, pages, origin) { error("Unexpected external request") }
        pages.get(chapterPath)
        val failure = runCatching { illustrations.fetch(origin.resolve("/image.jpg").toString()) }.exceptionOrNull()
        assertEquals(AccessFailure.RATE_LIMITED, (failure as? WenkuAccessException)?.failure)
        pages.get("/novel/0/42/102.htm")
        assertEquals(listOf(0L, 2_000L, 9_000L), calls)
    }

    @Test
    fun `oversize and unusually tall illustrations are sampled and fitted without distortion`() {
        val dimensions = wenkuFitImage(4_000, 12_000, 876, 1_980)
        assertEquals(WenkuImageSize(660, 1_980), dimensions)
        assertEquals(WenkuImageSize(876, 219), wenkuFitImage(8_000, 2_000, 876, 1_980))
        assertEquals(WenkuImageSize(100, 50), wenkuFitImage(100, 50, 876, 1_980))
        assertEquals(8, wenkuImageSampleSize(12_000, 16_000))
        assertEquals(1, wenkuImageSampleSize(1_024, 1_024))
        assertThrows(IOException::class.java) { wenkuImageSampleSize(-1, 2_048) }
        assertFalse(dimensions.height > 1_980)
    }

    private fun parse(content: String) = WenkuChapterParser.parse(
        Jsoup.parse("""<div id="contentmain"><div id="title">示例</div><div id="content">$content</div></div>""", origin.resolve(chapterPath).toString()),
    )

    private fun describe(chapter: WenkuChapterContent) = chapter.readerPages(
        textPages = { listOf("text:${it.title}:${it.body}") },
        illustrationPage = { image, heading -> "image:$heading:${image.url.toHttpUrl().encodedPath.substringAfterLast('/')}" },
    )

    private fun response(request: Request, status: Int = 200, content: ByteArray = byteArrayOf(), mime: String = "image/png", location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .apply { if (location != null) header("Location", location) }
            .body(content.toResponseBody(mime.toMediaType()))
            .build()
}
