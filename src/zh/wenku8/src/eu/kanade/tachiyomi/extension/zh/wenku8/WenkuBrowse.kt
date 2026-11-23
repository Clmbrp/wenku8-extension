package eu.kanade.tachiyomi.extension.zh.wenku8

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

internal data class WenkuBookPage(val books: List<WenkuBook>, val hasNextPage: Boolean = false)

internal class WenkuBrowse(private val pages: WenkuPages, private val origin: HttpUrl) {
    suspend fun popular(page: Int): WenkuBookPage = ranking(page, "allvisit")

    suspend fun latest(page: Int): WenkuBookPage = ranking(page, "lastupdate")

    private suspend fun ranking(page: Int, sort: String): WenkuBookPage {
        require(page > 0)
        val document = pages.get("$TOPLIST?sort=$sort&page=$page")
        return listing(document, page, isSearch = false) {
            it.encodedPath == TOPLIST && it.queryParameter("sort") == sort
        }
    }

    suspend fun search(page: Int, query: String): WenkuBookPage {
        require(page > 0)
        val keyword = query.trim()
        if (keyword.isEmpty()) return WenkuBookPage(emptyList())
        if (!GBK.newEncoder().canEncode(keyword)) structure("搜索词含网站不支持的字符")

        val document = if (page == 1) {
            val body = FormBody.Builder(GBK)
                .add("searchtype", "articlename")
                .add("searchkey", keyword)
                .add("charset", "gbk")
                .add("Submit", "轻小说搜索")
                .build()
            pages.searchForm(body)
        } else {
            val encoded = URLEncoder.encode(keyword, GBK.name())
            pages.get("$SEARCH?searchtype=articlename&searchkey=$encoded&page=$page")
        }
        val url = document.location().toHttpUrlOrNull()
        if (page == 1 && url != null && url.hasWenkuOrigin(origin) && WenkuParser.BOOK_PATH.matches(url.encodedPath)) {
            val book = WenkuParser.details(document, origin)
            return WenkuBookPage(listOf(WenkuBook(book.title, url.encodedPath, book.coverUrl)))
        }
        return listing(document, page, isSearch = true) { target ->
            target.encodedPath == SEARCH && target.queryParameter("searchtype").let { it == null || it == "articlename" } &&
                target.gbkQueryParameter("searchkey").let { it == null || it.equals(keyword, ignoreCase = true) }
        }
    }

    private fun listing(
        document: Document,
        page: Int,
        isSearch: Boolean,
        matchesList: (HttpUrl) -> Boolean,
    ): WenkuBookPage {
        val url = document.location().toHttpUrlOrNull()?.takeIf { it.hasWenkuOrigin(origin) }
            ?: structure("列表地址不符合预期")
        val formResult = isSearch && page == 1 && url.encodedPath == SEARCH_FORM
        if (!formResult && !matchesList(url)) structure("网站返回了非预期的列表页面")
        val activePage = url.queryParameter("page")
        if (activePage != null && activePage.toIntOrNull() != page) structure("网站返回了非预期的页码")

        val tables = document.select("#content table.grid").ifEmpty {
            if (isSearch) emptyList() else document.select("table.grid")
        }
        val books = tables.firstNotNullOfOrNull { table -> booksIn(table).takeIf(List<WenkuBook>::isNotEmpty) }
        if (books == null) {
            if (isSearch && page == 1 && emptySearch(document, tables)) return WenkuBookPage(emptyList())
            structure(if (isSearch) "未识别到搜索结果，网站结构可能已变化" else "未识别到排行榜书目，网站结构可能已变化")
        }

        return WenkuBookPage(books, hasNextPage(document, page, matchesList))
    }

    private fun booksIn(table: Element): List<WenkuBook> = table.select("a[href]")
        .mapNotNull { link ->
            val url = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            if (!url.hasWenkuOrigin(origin) || !WenkuParser.BOOK_PATH.matches(url.encodedPath)) return@mapNotNull null
            url.encodedPath to link
        }
        .groupBy({ it.first }, { it.second })
        .mapNotNull { (path, links) ->
            val title = links.firstNotNullOfOrNull { it.attr("tiptitle").trim().ifBlank { null } }
                ?: links.firstNotNullOfOrNull { it.attr("title").trim().ifBlank { null } }
                ?: links.firstNotNullOfOrNull { link -> link.text().trim().takeIf { it.isNotEmpty() && it !in READING_LINK_TEXT } }
                ?: return@mapNotNull null
            val bookId = WenkuParser.BOOK_PATH.matchEntire(path)!!.groupValues[1]
            val cover = links.firstNotNullOfOrNull { link -> link.selectFirst("img[src]")?.let { WenkuCover.fromPage(it, bookId) } }
            if (cover == null) WenkuBook(title, path) else WenkuBook(title, path, cover)
        }

    private fun hasNextPage(document: Document, page: Int, matchesList: (HttpUrl) -> Boolean): Boolean {
        val pager = document.selectFirst("#pagelink, div.pagelink")
        if (pager == null) {
            if (page != 1) structure("缺少列表分页信息，无法确认当前页")
            return false
        }
        val total = pager.selectFirst("#pagestats")?.let {
            val stats = PAGE_STATS.matchEntire(it.text())
            val current = stats?.groupValues?.get(1)?.toIntOrNull()
            val last = stats?.groupValues?.get(2)?.toIntOrNull()
            if (current != page || last == null || last < page) structure("网站返回了非预期的分页信息")
            last
        }
        val selected = pager.selectFirst("strong")?.text()?.toIntOrNull()
        if (selected != null && selected != page) structure("网站返回了非预期的页码")

        // Mihon requests page + 1. Only advertise a next page if the site links to it.
        val links = pager.select("a[href]").mapNotNull { link ->
            val target = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            if (!target.hasWenkuOrigin(origin) || !matchesList(target)) return@mapNotNull null
            target.queryParameter("page")?.toIntOrNull()
        }
        val next = links.any { it > page && it - page == 1 }
        if ((total != null && (page < total) != next) || (!next && links.any { it > page })) {
            structure("网站分页导航与当前页不一致")
        }
        if (page > 1 && total == null && selected == null && !next) structure("无法确认网站返回的列表页码")
        return next
    }

    private fun emptySearch(document: Document, tables: List<Element>): Boolean {
        if (document.select("#pagelink a.next[href], div.pagelink a.next[href]").isNotEmpty()) return false
        if (tables.any { table ->
                table.selectFirst("caption")?.text()?.endsWith("搜索结果") == true &&
                    table.select("td").let { cells ->
                        cells.isNotEmpty() && cells.all { it.text().isBlank() && it.children().isEmpty() }
                    }
            }
        ) {
            return true
        }

        return document.select("#content, #msgbox, .msgbox, .blockcontent, table.grid td")
            .any { region -> region.allElements.any { NO_SEARCH_RESULTS.matches(it.ownText()) } }
    }

    private fun HttpUrl.gbkQueryParameter(name: String): String? {
        val encoded = encodedQuery?.split('&')?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "") ?: return null
        return try {
            URLDecoder.decode(encoded, GBK.name())
        } catch (_: IllegalArgumentException) {
            structure("网站返回了无法识别的搜索参数")
        }
    }

    private fun structure(message: String): Nothing = throw WenkuAccessException(AccessFailure.STRUCTURE, message)

    companion object {
        const val SEARCH_FORM = "/so.php"
        private const val TOPLIST = "/modules/article/toplist.php"
        private const val SEARCH = "/modules/article/search.php"
        private val GBK = charset("GBK")
        private val PAGE_STATS = Regex("""(\d+)\s*/\s*(\d+)""")
        private val READING_LINK_TEXT = setOf("我要阅读", "阅读", "加入书架", "推荐本书")
        private val NO_SEARCH_RESULTS = Regex(
            """(?:(?:对不起|抱歉)[，,！!：:\s]*)?(?:没有(?:找到|搜索到|检索到)|未(?:找到|搜索到|检索到)|找不到|无)(?:任何)?(?:符合(?:该|您(?:的)?)?(?:搜索|查询)?(?:条件|要求)的?|相关的?)?(?:小说|作品|文章|记录|(?:搜索|查询)?结果)[！!。.]?""",
        )
    }
}
