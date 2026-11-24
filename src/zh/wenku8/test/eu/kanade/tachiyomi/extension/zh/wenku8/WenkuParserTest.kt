package eu.kanade.tachiyomi.extension.zh.wenku8

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WenkuParserTest {
    private val origin = "https://www.wenku8.net/".toHttpUrl()

    @Test
    fun `webview defaults to secure login and can open secure ranking`() {
        assertEquals("https://www.wenku8.net/login.php", origin.resolve(wenkuWebViewPath(null)).toString())
        assertEquals("https://www.wenku8.net/login.php", origin.resolve(wenkuWebViewPath("http://example.org")).toString())
        assertEquals("https://www.wenku8.net/modules/article/toplist.php?sort=allvisit&page=1", origin.resolve(wenkuWebViewPath("security")).toString())
    }

    @Test
    fun `detail resolves only the book's catalog and normalizes the site's cover to HTTPS`() {
        val doc = Jsoup.parse(
            """<div id="content"><table><tr><td><span><b>示例小说</b></span></td></tr>
              <tr><td>小说作者：示例作者</td><td>文章状态：已完结</td><td>文库分类：样本文库</td></tr></table>
              <table><tr><td><img src="http://img.wenku8.com/image/0/42/42s.jpg"></td>
              <td><span class="hottext"><b>作品Tags：奇幻</b></span><br>
              <span class="hottext">内容简介：</span><br><span>第一行<br>第二行</span></td></tr></table>
              <a href="https://example.org/novel/0/42/index.htm">外站目录</a>
              <a href="/novel/0/43/index.htm">其他书目录</a>
              <a href="/novel/0/42/index.htm">小说目录</a></div>""",
            origin.resolve("/book/42.htm").toString(),
        )
        assertEquals(
            WenkuDetails("示例小说", "示例作者", "第一行\n第二行", "样本文库, 奇幻", "已完结", "https://img.wenku8.com/image/0/42/42s.jpg", "/novel/0/42/index.htm"),
            WenkuParser.details(doc, origin),
        )
    }

    @Test
    fun `detail with missing author status and introduction remains usable`() {
        val doc = Jsoup.parse(
            """<div id="content"><table><tr><td><span><b>示例小说</b></span></td></tr></table>
              <a href="../novel/0/42/index.htm">小说目录</a></div>""",
            origin.resolve("/book/42.htm").toString(),
        )
        assertEquals(
            WenkuDetails("示例小说", null, null, null, null, "https://img.wenku8.com/image/0/42/42s.jpg", "/novel/0/42/index.htm"),
            WenkuParser.details(doc, origin),
        )
    }

    @Test
    fun `cover handles scheme relative and base relative URLs only for the known host and book`() {
        val doc = Jsoup.parse(
            """<base href="http://img.wenku8.com/image/0/42/">
              <img id="relative" src="42s.jpg">
              <img id="scheme-relative" src="//img.wenku8.com/image/0/42/42s.jpg">
              <img id="unknown-host" src="http://example.org/image/0/42/42s.jpg">
              <img id="other-book" src="43s.jpg">
              <img id="unexpected-port" src="http://img.wenku8.com:8080/image/0/42/42s.jpg">
              <img id="with-userinfo" src="http://example@img.wenku8.com/image/0/42/42s.jpg">""",
            origin.resolve("/book/42.htm").toString(),
        )
        val coverUrl = "https://img.wenku8.com/image/0/42/42s.jpg"
        assertEquals(coverUrl, WenkuCover.fromPage(doc.selectFirst("#relative")!!, "42"))
        assertEquals(coverUrl, WenkuCover.fromPage(doc.selectFirst("#scheme-relative")!!, "42"))
        for (selector in listOf("#unknown-host", "#other-book", "#unexpected-port", "#with-userinfo")) {
            assertNull(selector, WenkuCover.fromPage(doc.selectFirst(selector)!!, "42"))
        }
    }

    @Test
    fun `detail rejects an unexpected HTML document at a book URL`() {
        val doc = Jsoup.parse(
            """<div id="content"><table><tr><td><span><b>服务暂不可用</b></span></td></tr></table>
              <img src="http://img.wenku8.com/image/0/43/43s.jpg">
              <a href="/novel/0/43/index.htm">其他书目录</a></div>""",
            origin.resolve("/book/42.htm").toString(),
        )
        val error = assertThrows(WenkuAccessException::class.java) { WenkuParser.details(doc, origin) }
        assertEquals(AccessFailure.STRUCTURE, error.failure)
    }

    @Test
    fun `catalog keeps volume boundaries and illustration entries with relative links`() {
        val path = "/novel/0/42/index.htm"
        val doc = Jsoup.parse(
            """<table class="css"><tr><td class="vcss">上卷</td></tr>
              <tr><td class="ccss"><a href="101.htm">第一章</a></td><td class="ccss"><a href="102.htm">插图</a></td></tr>
              <tr><td class="vcss">下卷</td></tr>
              <tr><td class="ccss"><a href="95.htm">第二章</a></td><td class="ccss"></td></tr></table>""",
            origin.resolve(path).toString(),
        )
        assertEquals(
            listOf(
                WenkuChapter("第一章", "上卷", "/novel/0/42/101.htm"),
                WenkuChapter("插图", "上卷", "/novel/0/42/102.htm"),
                WenkuChapter("第二章", "下卷", "/novel/0/42/95.htm"),
            ),
            WenkuParser.catalog(doc, origin, path),
        )
        doc.selectFirst("td.ccss > a")!!.attr("href", "../43/101.htm")
        val error = assertThrows(WenkuAccessException::class.java) { WenkuParser.catalog(doc, origin, path) }
        assertEquals(AccessFailure.STRUCTURE, error.failure)
    }

    @Test
    fun `catalog supports a single volume and keeps one stable URL per chapter`() {
        val path = "/novel/0/42/index.htm"
        val doc = Jsoup.parse(
            """<table class="css"><tr><td class="vcss">全一卷</td></tr>
              <tr><td class="ccss"><a href="101.htm">序章</a></td>
              <td class="ccss"><a href="/novel/0/42/101.htm#start">重复链接</a></td>
              <td class="ccss"><a href="102.htm">正文</a></td></tr></table>""",
            origin.resolve(path).toString(),
        )
        val chapters = listOf(
            WenkuChapter("序章", "全一卷", "/novel/0/42/101.htm"),
            WenkuChapter("正文", "全一卷", "/novel/0/42/102.htm"),
        )
        assertEquals(chapters, WenkuParser.catalog(doc, origin, path))
        doc.select("td.vcss").remove()
        assertEquals(chapters.map { it.copy(volume = null) }, WenkuParser.catalog(doc, origin, path))
    }

    @Test
    fun `catalog rejects missing or empty chapter tables`() {
        val path = "/novel/0/42/index.htm"
        for (html in listOf("<p>服务暂不可用</p>", """<table class="css"><tr><td class="ccss"></td></tr></table>""")) {
            val doc = Jsoup.parse(html, origin.resolve(path).toString())
            val error = assertThrows(WenkuAccessException::class.java) { WenkuParser.catalog(doc, origin, path) }
            assertEquals(AccessFailure.STRUCTURE, error.failure)
        }
    }
}
