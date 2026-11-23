package eu.kanade.tachiyomi.extension.zh.wenku8

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

internal data class WenkuBookPage(val books: List<WenkuBook>, val next: HttpUrl? = null) {
    val hasNextPage: Boolean get() = next != null
}

internal class WenkuBrowse(
    private val pages: WenkuPages,
    private val origin: HttpUrl,
    private val diagnostics: WenkuDiagnostics? = null,
) {
    private data class SearchContinuation(val url: HttpUrl, val previousBook: WenkuBook)

    private val searchLinks = LinkedHashMap<Pair<String, Int>, SearchContinuation>(16, 0.75f, true)

    suspend fun popular(page: Int): WenkuBookPage = ranking(page, "allvisit")

    suspend fun latest(page: Int): WenkuBookPage = ranking(page, "lastupdate")

    private suspend fun ranking(page: Int, sort: String): WenkuBookPage {
        require(page > 0)
        val document = pages.get("$TOPLIST?sort=$sort&page=$page")
        return listing(document, page, isSearch = false) {
            it.encodedPath == TOPLIST && it.queryParameter("sort") == sort
        } ?: structure("未识别到排行榜书目，网站结构可能已变化")
    }

    suspend fun search(page: Int, query: String): WenkuBookPage {
        require(page > 0)
        val keyword = query.trim()
        if (keyword.isEmpty()) return WenkuBookPage(emptyList())
        if (!GBK.newEncoder().canEncode(keyword)) structure("搜索词含网站不支持的字符")

        val continuation = synchronized(searchLinks) { searchLinks[keyword to page] }
        return try {
            search(keyword, page, continuation)
        } catch (e: WenkuAccessException) {
            diagnostics?.search(page, e.failure.name, continuation != null)
            throw e
        }
    }

    private suspend fun search(keyword: String, page: Int, continuation: SearchContinuation?): WenkuBookPage {
        val document = if (page == 1) {
            val body = FormBody.Builder(GBK)
                .add("searchtype", "articlename")
                .add("searchkey", keyword)
                .add("charset", "gbk")
                .add("Submit", "轻小说搜索")
                .build()
            pages.searchForm(keyword, body)
        } else if (continuation != null) {
            pages.get(continuation.url.toString())
        } else {
            val encoded = URLEncoder.encode(keyword, GBK.name())
            pages.get("$SEARCH?searchtype=articlename&searchkey=$encoded&page=$page")
        }
        val url = document.location().toHttpUrlOrNull()
        if (page == 1 && url != null && url.hasWenkuOrigin(origin) && WenkuParser.BOOK_PATH.matches(url.encodedPath)) {
            val book = WenkuParser.details(document, origin)
            synchronized(searchLinks) { searchLinks.keys.removeAll { it.first == keyword } }
            diagnostics?.search(page, "DETAIL", false)
            return WenkuBookPage(listOf(WenkuBook(book.title, url.encodedPath, book.coverUrl)))
        }
        val matchesSearch = { target: HttpUrl ->
            target.encodedPath == SEARCH && target.queryParameter("searchtype").let { it == null || it == "articlename" } &&
                target.gbkQueryParameter("searchkey").let { it == null || it.equals(keyword, ignoreCase = true) }
        }
        val parsed = listing(document, page, isSearch = true, matchesSearch)
        val result = if (parsed == null && continuation != null) {
            diagnostics?.search(page, "RETRY_MISSING", true)
            listing(pages.get(continuation.url.toString()), page, isSearch = true, matchesSearch)
        } else {
            parsed
        } ?: structure("未识别到搜索结果，网站结构可能已变化")
        synchronized(searchLinks) {
            if (page == 1) searchLinks.keys.removeAll { it.first == keyword }
            val nextKey = keyword to (page + 1)
            val next = result.next
            if (next == null) {
                searchLinks.remove(nextKey)
            } else {
                searchLinks[nextKey] = SearchContinuation(next, result.books.first())
                if (searchLinks.size > MAX_SEARCH_LINKS) searchLinks.remove(searchLinks.keys.first())
            }
        }
        if (result.books.isEmpty() && continuation != null) {
            diagnostics?.search(page, "EMPTY_TAIL", true)
            // Mihon treats an empty append as an error. Its paging session filters this known prior URL.
            return WenkuBookPage(listOf(continuation.previousBook))
        }
        diagnostics?.search(page, if (result.books.isEmpty()) "EMPTY" else "LIST", continuation != null)
        return result
    }

    private fun listing(
        document: Document,
        page: Int,
        isSearch: Boolean,
        matchesList: (HttpUrl) -> Boolean,
    ): WenkuBookPage? {
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
            if (isSearch && emptySearch(document, tables)) return WenkuBookPage(emptyList())
            return null
        }

        return WenkuBookPage(books, nextPage(document, url, page, matchesList))
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

    private fun nextPage(document: Document, currentUrl: HttpUrl, page: Int, matchesList: (HttpUrl) -> Boolean): HttpUrl? {
        val pager = document.selectFirst("#pagelink, div.pagelink")
        if (pager == null) {
            if (page != 1 && currentUrl.queryParameter("page") == null) structure("缺少列表分页信息，无法确认当前页")
            return null
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
        if (total == page) return null

        // Mihon requests page + 1. Only advertise a next page if the site links to it.
        val links = pager.select("a[href]").sortedByDescending { it.hasClass("next") }.mapNotNull { link ->
            val target = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            if (!target.hasWenkuOrigin(origin) || !matchesList(target)) return@mapNotNull null
            val targetPage = target.queryParameter("page")?.toIntOrNull() ?: return@mapNotNull null
            targetPage to target
        }
        val next = links.firstOrNull { (targetPage, _) -> targetPage > page && targetPage - page == 1 }?.second
        if (next == null && (total != null || links.any { it.first > page })) {
            structure("网站分页导航与当前页不一致")
        }
        if (page > 1 && selected == null && next == null && currentUrl.queryParameter("page") == null) {
            structure("无法确认网站返回的列表页码")
        }
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
        const val SEARCH = "/modules/article/search.php"
        private const val MAX_SEARCH_LINKS = 64
        private val GBK = charset("GBK")
        private val PAGE_STATS = Regex("""(\d+)\s*/\s*(\d+)""")
        private val READING_LINK_TEXT = setOf("我要阅读", "阅读", "加入书架", "推荐本书")
        private val NO_SEARCH_RESULTS = Regex(
            """(?:(?:对不起|抱歉)[，,！!：:\s]*)?(?:没有(?:找到|搜索到|检索到)|未(?:找到|搜索到|检索到)|找不到|无)(?:任何)?(?:符合(?:该|您(?:的)?)?(?:搜索|查询)?(?:条件|要求)的?|相关的?)?(?:小说|作品|文章|记录|(?:搜索|查询)?结果)[！!。.]?""",
        )
    }
}
