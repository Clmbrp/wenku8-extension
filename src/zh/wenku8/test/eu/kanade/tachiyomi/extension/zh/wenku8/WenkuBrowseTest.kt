package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WenkuBrowseTest {
    private val origin = "https://www.wenku8.net/".toHttpUrl()
    private val top = "/modules/article/toplist.php"
    private val search = "/modules/article/search.php?searchtype=articlename&searchkey=%BB%AF"
    private val detail = """<div id="content"><table><tr><td><span><b>示例书名</b></span></td></tr>
        <tr><td>小说作者：示例作者</td></tr></table></div>"""

    @Test
    fun `popular uses each page number and preserves ranking order through the last page`() = runBlocking {
        val requested = mutableListOf<String>()
        val browse = browse { request ->
            assertEquals("GET", request.method)
            assertEquals(top, request.url.encodedPath)
            assertEquals("allvisit", request.url.queryParameter("sort"))
            val page = request.url.queryParameter("page")!!
            requested += page
            val entries = when (page) {
                "1" ->
                """<a href="/book/1973.htm"><img src="http://img.wenku8.com/image/1/1973/1973s.jpg"></a>
                  <b><a href="/book/1973.htm" tiptitle="第一本完整书名">第一本</a></b>
                  <a href="/book/1973.htm#start">我要阅读</a><a href="/book/42.htm">第二本</a>"""
                "2" -> """<a href="/book/7.htm">第三本</a>"""
                else -> """<a href="/book/11.htm">第四本</a>"""
            }
            reply(request, grid(entries, pager(page.toInt(), 3, "$top?sort=allvisit")))
        }
        val first = browse.popular(1)
        val second = browse.popular(2)
        val last = browse.popular(3)
        assertEquals(listOf("1", "2", "3"), requested)
        assertEquals(listOf("第一本完整书名", "第二本", "第三本", "第四本"), (first.books + second.books + last.books).map { it.title })
        assertEquals("https://img.wenku8.com/image/1/1973/1973s.jpg", first.books.first().coverUrl)
        assertTrue(first.hasNextPage)
        assertTrue(second.hasNextPage)
        assertFalse(last.hasNextPage)
    }

    @Test
    fun `latest uses update sort and can start directly on a later page`() = runBlocking {
        val browse = browse { request ->
            assertEquals("$top?sort=lastupdate&page=2", "${request.url.encodedPath}?${request.url.encodedQuery}")
            reply(request, grid("""<a href="../..//book/43.htm">不安全路径</a><b><a href="/book/42.htm">最新书目</a></b>""", pager(2, 2, "$top?sort=lastupdate")))
        }
        assertEquals(WenkuBookPage(listOf(WenkuBook("最新书目", "/book/42.htm"))), browse.latest(2))
    }

    @Test
    fun `partial title is posted in GBK and page 2 fetches the encoded result URL by GET`() = runBlocking {
        val methods = mutableListOf<String>()
        val browse = browse { request ->
            methods += request.method
            if (request.url.encodedPath == "/so.php") {
                val form = request.body as FormBody
                assertEquals("articlename", form.encodedValue(0))
                assertEquals("%BB%AF", form.encodedValue(1))
                assertEquals("gbk", form.encodedValue(2))
                reply(request, code = 302, location = search)
            } else {
                assertTrue(request.url.encodedQuery!!.contains("searchkey=%BB%AF"))
                val page = request.url.queryParameter("page")?.toInt() ?: 1
                reply(request, grid("""<b><a href="/book/${41 + page}.htm" title="变化示例">书目</a></b>""", pager(page, 2, search)))
            }
        }
        val first = browse.search(1, "化")
        val last = browse.search(2, "化")
        assertEquals(listOf("POST", "GET", "GET"), methods)
        assertEquals("变化示例", first.books.single().title)
        assertEquals("/book/42.htm", first.books.single().path)
        assertTrue(first.hasNextPage)
        assertEquals("/book/43.htm", last.books.single().path)
        assertFalse(last.hasNextPage)
    }

    @Test
    fun `search follows the complete next link from the successful first page`() = runBlocking {
        val next = "$search&searchid=123&page=2"
        val browse = browse { request ->
            when {
                request.method == "POST" -> reply(request, code = 302, location = search)
                request.url.queryParameter("page") == null -> reply(
                    request,
                    grid("""<a href="/book/42.htm">首屏结果</a>""", pager(1, 2, "$search&searchid=123")),
                )
                request.url == origin.resolve(next) -> reply(
                    request,
                    grid("""<a href="/book/43.htm">下一页结果</a>""", pager(2, 2, "$search&searchid=123")),
                )
                else -> reply(request, "<div id='content'>搜索会话已失效</div>")
            }
        }
        assertTrue(browse.search(1, "化").hasNextPage)
        assertEquals(WenkuBookPage(listOf(WenkuBook("下一页结果", "/book/43.htm"))), browse.search(2, "化"))
    }

    @Test
    fun `advertised search page recovers from one successful HTTP response without books`() = runBlocking {
        var secondPageRequests = 0
        val browse = browse { request ->
            when {
                request.method == "POST" -> reply(request, code = 302, location = search)
                request.url.queryParameter("page") == null -> reply(
                    request,
                    grid("""<a href="/book/42.htm">首屏结果</a>""", pager(1, 2, search)),
                )
                ++secondPageRequests == 1 -> reply(request, "<div id='content'>暂时没有可解析的列表</div>")
                else -> reply(request, grid("""<a href="/book/43.htm">下一页结果</a>""", pager(2, 2, search)))
            }
        }
        assertTrue(browse.search(1, "化").hasNextPage)
        assertEquals(WenkuBookPage(listOf(WenkuBook("下一页结果", "/book/43.htm"))), browse.search(2, "化"))
        assertEquals(2, secondPageRequests)
    }

    @Test
    fun `persistently unrecognized advertised results still report a structure error`() = runBlocking {
        var secondPageRequests = 0
        val browse = browse { request ->
            when {
                request.method == "POST" -> reply(request, code = 302, location = search)
                request.url.queryParameter("page") == null -> reply(
                    request,
                    grid("""<a href="/book/42.htm">首屏结果</a>""", pager(1, 2, search)),
                )
                else -> {
                    secondPageRequests++
                    reply(request, "<div id='content'>未识别结构</div>")
                }
            }
        }
        assertTrue(browse.search(1, "化").hasNextPage)
        val error = assertThrows(WenkuAccessException::class.java) { runBlocking { browse.search(2, "化") } }
        assertEquals(AccessFailure.STRUCTURE, error.failure)
        assertEquals(2, secondPageRequests)
    }

    @Test
    fun `an advertised search page that is explicitly empty can end pagination`() = runBlocking {
        val browse = browse { request ->
            when {
                request.method == "POST" -> reply(request, code = 302, location = search)
                request.url.queryParameter("page") == null -> reply(
                    request,
                    grid("""<a href="/book/42.htm">首屏结果</a>""", pager(1, 2, search)),
                )
                else -> reply(request, """<div id="content">对不起，没有找到符合您要求的小说！</div>""")
            }
        }
        val first = browse.search(1, "化")
        val last = browse.search(2, "化")
        assertFalse(last.hasNextPage)
        assertEquals(first.books, last.books)
        assertEquals(emptyList<WenkuBook>(), last.books.filterNot { it in first.books })
    }

    @Test
    fun `search ignores a stale next link when the pager confirms the last page`() = runBlocking {
        val browse = browse { request ->
            val nav = """<div id="pagelink"><em id="pagestats">2/2</em><strong>2</strong>
                <a class="next" href="$search&page=3">下一页</a></div>"""
            reply(request, grid("""<a href="/book/43.htm">最后一页结果</a>""", nav))
        }
        val last = browse.search(2, "化")
        assertEquals("/book/43.htm", last.books.single().path)
        assertFalse(last.hasNextPage)
    }

    @Test
    fun `search handles the full-title redirect as a single book`() = runBlocking {
        val requests = mutableListOf<Pair<String, String>>()
        val browse = browse { request ->
            requests += request.method to request.url.encodedPath
            when (request.url.encodedPath) {
                "/so.php" -> {
                    assertEquals("%BB%AF%CE%EF%D3%EF", (request.body as FormBody).encodedValue(1))
                    reply(request, code = 302, location = search.replace("%BB%AF", "%BB%AF%CE%EF%D3%EF"))
                }
                "/modules/article/search.php" -> reply(request, code = 302, location = "/book/6.htm")
                else -> reply(request, detail)
            }
        }
        assertEquals(WenkuBookPage(listOf(WenkuBook("示例书名", "/book/6.htm"))), browse.search(1, "化物语"))
        assertEquals(listOf("POST" to "/so.php", "GET" to "/modules/article/search.php", "GET" to "/book/6.htm"), requests)
    }

    @Test
    fun `search distinguishes an explicit empty result from unexpected HTML`() {
        val empty = browse { reply(it, """<div id="content"><div class="blockcontent">对不起，没有找到符合您要求的小说！</div></div>""") }
        val emptyTable = browse { reply(it, """<div id="content"><table class="grid"><caption>“化”搜索结果</caption><tr><td></td></tr></table></div>""") }
        val changed = browse { reply(it, """<div id="content"><div>搜索暂不可用</div><table class="grid"><tr><td></td></tr></table></div>""") }
        for (browse in listOf(empty, emptyTable)) {
            assertEquals(WenkuBookPage(emptyList()), runBlocking { browse.search(1, "化") })
        }
        val error = assertThrows(WenkuAccessException::class.java) { runBlocking { changed.search(1, "化") } }
        assertEquals(AccessFailure.STRUCTURE, error.failure)
    }

    @Test
    fun `pagination rejects a repeated first page or an untrusted next link`() {
        val entries = """<b><a href="/book/42.htm">示例</a></b>"""
        val repeated = browse { reply(it, grid(entries, pager(1, 2, "$top?sort=allvisit"))) }
        val external = browse { reply(it, grid(entries, pager(1, 2, "https://other.example$top?sort=allvisit"))) }
        for ((browse, page) in listOf(repeated to 2, external to 1)) {
            val error = assertThrows(WenkuAccessException::class.java) { runBlocking { browse.popular(page) } }
            assertEquals(AccessFailure.STRUCTURE, error.failure)
        }
    }

    @Test
    fun `search challenge login and untrusted redirects preserve access boundaries`() {
        val paths = mutableListOf<String>()
        for ((code, location, html, result) in listOf(
            Failure(403, null, "<title>Just a moment...</title>", AccessFailure.CHALLENGE),
            Failure(302, "http://www.wenku8.net/login.php", "", AccessFailure.LOGIN_REQUIRED),
            Failure(302, "https://other.example/search", "", AccessFailure.STRUCTURE),
            Failure(307, "/modules/article/search.php", "", AccessFailure.STRUCTURE),
        )) {
            paths.clear()
            val browse = browse { request ->
                paths += request.url.encodedPath
                reply(request, html, code, location)
            }
            val error = assertThrows(WenkuAccessException::class.java) { runBlocking { browse.search(1, "化") } }
            assertEquals(result, error.failure)
            assertEquals(listOf("/so.php"), paths)
        }
    }

    private data class Failure(val code: Int, val location: String?, val html: String, val expected: AccessFailure)

    private fun browse(respond: (Request) -> Response): WenkuBrowse =
        WenkuBrowse(WenkuPages(OkHttpClient(), headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0, searchIntervalMillis = 0), executeRequest = { respond(it) }), origin)

    private fun reply(request: Request, html: String = "", code: Int = 200, location: String? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .header("Content-Type", "text/html; charset=gbk")
            .apply { location?.let { header("Location", it) } }
            .body(html.toByteArray(charset("GBK")).toResponseBody())
            .build()

    private fun grid(entries: String, pager: String): String =
        """<div id="content"><table class="grid"><tr><td>$entries</td></tr></table>$pager</div>"""

    private fun pager(page: Int, last: Int, prefix: String): String {
        val next = if (page < last) """<a class="next" href="$prefix&page=${page + 1}">&gt;</a>""" else ""
        return """<div id="pagelink"><em id="pagestats">$page/$last</em><strong>$page</strong>$next</div>"""
    }
}
