package eu.kanade.tachiyomi.extension.zh.wenku8

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

internal data class WenkuBook(val title: String, val path: String)

internal object WenkuParser {
    /** The known total ranking, not every category on the overview page. */
    fun totalRanking(document: Document, host: String): List<WenkuBook> {
        val group = document.select("div.blocktop").firstOrNull {
            it.selectFirst(".blockcaption")?.text()?.trim() == "总热度榜"
        } ?: throw WenkuAccessException(AccessFailure.STRUCTURE, "未找到总热度榜，网站结构可能已变化")
        val anchors = group.select(".blockcontent > ul.ultop > li > a[href]")
        if (anchors.isEmpty()) throw WenkuAccessException(AccessFailure.STRUCTURE, "榜单没有可识别的书籍，网站结构可能已变化")
        return anchors.map { link ->
            val url = link.absUrl("href").toHttpUrlOrNull()
                ?: throw WenkuAccessException(AccessFailure.STRUCTURE, "榜单书籍地址无效")
            if (url.scheme != "https" || url.host != host || url.port != 443 || !BOOK_PATH.matches(url.encodedPath)) {
                throw WenkuAccessException(AccessFailure.STRUCTURE, "榜单书籍地址不符合预期")
            }
            val title = link.attr("tiptitle").trim().ifBlank { link.text().trim() }
            if (title.isBlank()) throw WenkuAccessException(AccessFailure.STRUCTURE, "榜单书籍缺少标题")
            WenkuBook(title, url.encodedPath)
        }.distinctBy { it.path }
    }

    private val BOOK_PATH = Regex("/book/\\d+\\.htm")
}
