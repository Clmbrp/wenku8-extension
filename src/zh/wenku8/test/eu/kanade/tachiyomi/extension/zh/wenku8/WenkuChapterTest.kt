package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class WenkuChapterTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val origin = "https://www.wenku8.net/".toHttpUrl()
    private val path = "/novel/0/42/101.htm"

    @Test
    fun `chapter decodes entities and preserves logical breaks and Chinese punctuation`() {
        val chapter = parse(
            """<div id="title">第一卷 &amp; 序章</div>
                <div id="content"><ul id="contentdp">首部导航</ul>
                &nbsp;&nbsp;你好，<em>世界</em>！ &lt;书名&gt; &amp; &#x1F319;<br>
                换行　「保留」<br><br>新段落。
                <p>另一个自然段；<a href="#note">注释</a>。</p>
                <div id="adv900">广告</div><script>无关脚本</script>
                <a href="102.htm">下一页</a><ul id="contentdp">尾部导航</ul></div>""",
        )
        assertEquals("第一卷 & 序章", chapter.title)
        assertEquals(
            "\u00a0\u00a0你好，世界！ <书名> & 🌙\n换行　「保留」\n\n新段落。\n\n另一个自然段；注释。",
            chapter.textForReader().body,
        )
    }

    @Test
    fun `mixed chapter keeps illustration locations and text in DOM order`() {
        val chapter = parse(
            """<div id="title">示例卷</div><div id="content">第一段。<br>
                <div class="divimage"><a href="../images/figure.jpg"><img src="../images/figure.jpg"></a></div>
                第二段。<img src="//images.example.invalid/figure.jpg"><p>第三段。</p>
                <img src="data:image/png;base64,abc"></div>""",
        )
        assertEquals(
            listOf(
                WenkuContentBlock.TextBlock("第一段。"),
                WenkuContentBlock.ImageBlock("https://www.wenku8.net/novel/0/images/figure.jpg"),
                WenkuContentBlock.TextBlock("第二段。"),
                WenkuContentBlock.ImageBlock("https://images.example.invalid/figure.jpg"),
                WenkuContentBlock.TextBlock("第三段。"),
            ),
            chapter.blocks,
        )
        assertEquals("第一段。\n\n第二段。\n\n第三段。", chapter.textForReader().body)
    }

    @Test
    fun `image only chapter retains metadata and explains the current reading limit`() {
        val chapter = parse("""<div id="title">插图</div><div id="content"><img src="figure.jpg"></div>""")
        assertEquals(listOf(WenkuContentBlock.ImageBlock("https://www.wenku8.net/novel/0/42/figure.jpg")), chapter.blocks)
        val error = assertThrows(WenkuAccessException::class.java) { chapter.textForReader() }
        assertTrue(error.message.orEmpty().contains("仅含插图"))
    }

    @Test
    fun `unexpected and empty documents never become rendered pages`() {
        for (html in listOf(
            "<div id='content'>通用错误页</div>",
            "<div id='title'>章节</div>",
            "<div id='title'>章节</div><div id='content'><ul id='contentdp'>下一章</ul><br>&nbsp;</div>",
        )) {
            assertEquals(AccessFailure.STRUCTURE, assertThrows(WenkuAccessException::class.java) { parse(html) }.failure)
        }
        val generic = Jsoup.parse("<div id='title'>通用页</div><div id='content'>页内提示</div>", origin.resolve(path).toString())
        assertEquals(AccessFailure.STRUCTURE, assertThrows(WenkuAccessException::class.java) { WenkuChapterParser.parse(generic) }.failure)
    }

    @Test
    fun `chapter is decoded with GBK before parsing its text`() = runBlocking {
        val html = """<meta charset="gbk"><div id="contentmain"><div id="title">第一卷</div>
             <div id="content">示例文字。<br><br>保留全角标点！</div></div>"""
        val pages = WenkuPages(OkHttpClient(), headersOf(), origin) { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .header("Content-Type", "text/html")
                .body(html.toByteArray(charset("GBK")).toResponseBody())
                .build()
        }
        assertEquals(WenkuChapterText("第一卷", "示例文字。\n\n保留全角标点！"), WenkuChapterParser.parse(pages.get(path)).textForReader())
    }

    @Test
    fun `pagination covers every short or long chapter line exactly once`() {
        assertEquals(listOf(WenkuLinePage(0, 2, 41)), wenkuPaginate(intArrayOf(0, 20, 41), 41, 100))
        val textLines = List(400) { "第 ${it + 1} 行——「𠮷、🌙」\n" }
        val lineTops = IntArray(textLines.size + 1) { it * 37 + it / 3 }
        val pages = wenkuPaginate(lineTops, 98, 287)
        assertTrue(pages.size > 10)
        assertEquals(textLines.joinToString(""), pages.joinToString("") { textLines.subList(it.start, it.end).joinToString("") })
        assertTrue(pages.first().height <= 98)
        assertTrue(pages.drop(1).all { it.height <= 287 })
        assertThrows(IOException::class.java) { wenkuPaginate(intArrayOf(0, 42), 41, 100) }
    }

    @Test
    fun `cache survives reopen, distinguishes revisions and does not put the text in URLs`() {
        val directory = temporary.newFolder()
        val original = WenkuChapterText("初稿", "第一段。\n\n第二段。")
        val first = WenkuTextCache(directory).remember(path, original)
        val revised = WenkuTextCache(directory).remember(path, original.copy(body = "修订后文字。"))
        val other = WenkuTextCache(directory).remember("/novel/0/42/102.htm", original)
        val file = directory.listFiles()!!.single { revised.key.revision in it.name }
        assertTrue(file.setLastModified(file.lastModified() + 2_000))
        val reopened = WenkuTextCache(directory, now = { file.lastModified() })
        assertEquals(first, reopened.read(first.key))
        assertEquals(revised, reopened.fresh(path))
        assertEquals(other, reopened.fresh(other.key.chapterPath))
        val ref = WenkuPageRef(first.key, 12)
        val stableUrl = ref.url()
        assertEquals(ref, WenkuPageRef.parse(stableUrl.toHttpUrl()))
        assertTrue(!stableUrl.contains("初稿") && !stableUrl.contains("第一段"))
        assertTrue(stableUrl != WenkuPageRef(other.key, 12).url())
        assertTrue(stableUrl != WenkuPageRef(revised.key, 12).url())
        assertNull(WenkuPageRef.parse(stableUrl.toHttpUrl().newBuilder().query("unused=1").build()))

        val expired = WenkuTextCache(directory, now = { file.lastModified() + 21 * 60_000 })
        assertNull(expired.fresh(path))
        assertEquals(revised, expired.read(revised.key))
        file.writeText("corrupt")
        assertNull(expired.read(revised.key))
    }

    @Test
    fun `reader propagates network login verification and sustained limiting before layout`() {
        val directory = temporary.newFolder()
        for ((status, html, failure) in listOf(
            Triple(0, "", AccessFailure.NETWORK),
            Triple(302, "", AccessFailure.LOGIN_REQUIRED),
            Triple(200, "<title>Just a moment...</title>", AccessFailure.CHALLENGE),
            Triple(429, "", AccessFailure.RATE_LIMITED),
            Triple(404, "", AccessFailure.HTTP),
            Triple(200, "<div id='content'>缺少正文的页面</div>", AccessFailure.STRUCTURE),
        )) {
            var requests = 0
            val policy = WenkuRequestPolicy(intervalMillis = 0, fallbackCooldownMillis = 0)
            val pages = WenkuPages(OkHttpClient(), headersOf(), origin, policy = policy) { request ->
                requests++
                assertEquals(path, request.url.encodedPath)
                if (status == 0) throw IOException("offline")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
                    .header("Content-Type", "text/html; charset=utf-8")
                    .apply { if (status == 302) header("Location", "/login.php") }
                    .body(html.toResponseBody())
                    .build()
            }
            val reader = WenkuReader(pages, origin, WenkuTextCache(directory)) { error("Missing chapters must never fetch images") }
            val error = assertThrows(WenkuAccessException::class.java) { runBlocking { reader.pageUrls(path) } }
            assertEquals(failure, error.failure)
            assertEquals(if (status == 429) 2 else 1, requests)
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun parse(html: String) = WenkuChapterParser.parse(Jsoup.parse("<div id='contentmain'>$html</div>", origin.resolve(path).toString()))
}
