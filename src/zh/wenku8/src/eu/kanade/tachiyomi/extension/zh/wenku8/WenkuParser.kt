package eu.kanade.tachiyomi.extension.zh.wenku8

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal data class WenkuBook(
    val title: String,
    val path: String,
    val coverUrl: String? = WenkuCover.forBook(path.substringAfterLast('/').substringBefore('.')),
)

internal object WenkuCover {
    private const val HOST = "img.wenku8.com"
    private val PATH = Regex("/image/\\d+/(\\d+)/(\\d+)s\\.jpg")

    fun forBook(bookId: String): String? {
        val id = bookId.toLongOrNull() ?: return null
        return "https://$HOST/image/${id / 1000}/$id/${id}s.jpg"
    }

    fun isKnownUrl(url: HttpUrl): Boolean = url.host == HOST &&
        url.port == HttpUrl.defaultPort(url.scheme) && url.username.isEmpty() && url.password.isEmpty() &&
        PATH.matches(url.encodedPath)

    fun fromPage(image: Element, bookId: String): String? {
        val src = image.attr("src").trim().takeIf(String::isNotEmpty) ?: return null
        val url = image.baseUri().toHttpUrlOrNull()?.resolve(src)?.takeIf(::isKnownUrl) ?: return null
        val match = PATH.matchEntire(url.encodedPath) ?: return null
        if (match.groupValues[1] != bookId || match.groupValues[2] != bookId) return null
        return "https://$HOST${url.encodedPath}"
    }
}

internal data class WenkuDetails(
    val title: String,
    val author: String?,
    val description: String?,
    val genre: String?,
    val status: String?,
    val coverUrl: String?,
    val catalogPath: String?,
)

internal data class WenkuChapter(val title: String, val volume: String?, val path: String)

internal object WenkuParser {
    fun details(document: Document, origin: HttpUrl): WenkuDetails {
        val page = document.location().toHttpUrlOrNull()?.takeIf { it.hasWenkuOrigin(origin) }
            ?: structure("书籍详情地址不符合预期")
        val id = BOOK_PATH.matchEntire(page.encodedPath)?.groupValues?.get(1)
            ?: structure("书籍详情地址不符合预期")
        val content = document.selectFirst("#content") ?: structure("未找到书籍详情")
        val catalogPath = content.select("a[href]").firstNotNullOfOrNull { link ->
            val url = link.absUrl("href").toHttpUrlOrNull() ?: return@firstNotNullOfOrNull null
            val match = CATALOG_PATH.matchEntire(url.encodedPath)
            url.encodedPath.takeIf { url.hasWenkuOrigin(origin) && match?.groupValues?.get(2) == id }
        }
        val cover = content.select("img[src]").firstNotNullOfOrNull { WenkuCover.fromPage(it, id) }
        val tables = content.select("table")
        val metadata = tables.firstOrNull { table ->
            table.select("td").any { it.field("小说作者") != null || it.field("文章状态") != null || it.field("文库分类") != null }
        } ?: tables.firstOrNull()?.takeIf { cover != null || catalogPath != null }
            ?: structure("未找到书籍元数据")
        val fields = metadata.select("td")
        val title = metadata.selectFirst("span > b")?.text()?.takeIf(String::isNotBlank)
            ?: structure("书籍详情缺少标题")
        val tags = content.select("span.hottext b").firstNotNullOfOrNull {
            it.text().takeIf { text -> text.startsWith("作品Tags") }?.substringAfter('：', "")?.trim()?.takeIf(String::isNotEmpty)
        }
        val genre = listOfNotNull(fields.firstNotNullOfOrNull { it.field("文库分类") }, tags)
            .joinToString(", ").ifBlank { null }
        val intro = content.select("span.hottext").firstOrNull { it.ownText().trim().trimEnd('：', ':') == "内容简介" }
        val description = intro?.let { label ->
            val text = Element("div")
            generateSequence(label.nextSibling()) { it.nextSibling() }.forEach { text.appendChild(it.clone()) }
            text.wholeText().trim().ifBlank { null }
        }
        return WenkuDetails(
            title = title,
            author = fields.firstNotNullOfOrNull { it.field("小说作者") },
            description = description,
            genre = genre,
            status = fields.firstNotNullOfOrNull { it.field("文章状态") },
            coverUrl = cover ?: WenkuCover.forBook(id),
            catalogPath = catalogPath,
        )
    }

    /** Returns chapter entries in the same order as the website's catalog. */
    fun catalog(document: Document, origin: HttpUrl, catalogPath: String): List<WenkuChapter> {
        val table = document.selectFirst("table.css") ?: structure("未找到章节目录")
        val prefix = catalogPath.substringBeforeLast('/') + "/"
        val chapters = mutableListOf<WenkuChapter>()
        var volume: String? = null
        for (cell in table.select("td.vcss, td.ccss")) {
            if (cell.hasClass("vcss")) {
                volume = cell.text().takeIf(String::isNotBlank)
                continue
            }
            for (link in cell.select("> a[href]")) {
                val url = link.absUrl("href").toHttpUrlOrNull()
                if (url == null || !url.hasWenkuOrigin(origin) || !url.encodedPath.startsWith(prefix) ||
                    !CHAPTER_FILE.matches(url.encodedPath.removePrefix(prefix))
                ) {
                    structure("目录中的章节地址不符合预期")
                }
                val name = link.text().takeIf(String::isNotBlank) ?: structure("目录中的章节缺少标题")
                chapters += WenkuChapter(name, volume, url.encodedPath)
            }
        }
        if (chapters.isEmpty()) structure("目录中没有可识别的章节")
        return chapters.distinctBy { it.path }
    }

    private fun Element.field(label: String): String? {
        val text = ownText()
        return text.substringAfter('：', "").trim().takeIf { text.startsWith("$label：") && it.isNotEmpty() }
    }

    private fun structure(message: String): Nothing = throw WenkuAccessException(AccessFailure.STRUCTURE, message)

    val BOOK_PATH = Regex("/book/(\\d+)\\.htm")
    private val CATALOG_PATH = Regex("/novel/(\\d+)/(\\d+)/index\\.htm")
    private val CHAPTER_FILE = Regex("\\d+\\.htm")
}
