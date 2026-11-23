package eu.kanade.tachiyomi.extension.zh.wenku8

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor

internal sealed interface WenkuContentBlock {
    data class TextBlock(val text: String) : WenkuContentBlock
    data class ImageBlock(val url: String) : WenkuContentBlock
}

internal data class WenkuChapterContent(val title: String, val blocks: List<WenkuContentBlock>) {
    fun textForReader(): WenkuChapterText {
        val body = blocks.filterIsInstance<WenkuContentBlock.TextBlock>().joinToString("\n\n") { it.text }
        if (body.isBlank()) {
            throw WenkuAccessException(AccessFailure.STRUCTURE, "本章仅含插图，当前版本仅支持阅读文字")
        }
        return WenkuChapterText(title, body)
    }
}

internal data class WenkuChapterText(val title: String, val body: String)

internal object WenkuChapterParser {
    fun parse(document: Document): WenkuChapterContent {
        val shell = document.selectFirst("#contentmain")
        val content = (shell ?: document).selectFirst("#content")
            ?: throw WenkuAccessException(AccessFailure.STRUCTURE, "未找到章节正文区域")
        if (shell == null && content.selectFirst("ul#contentdp") == null) {
            throw WenkuAccessException(AccessFailure.STRUCTURE, "站点返回了非章节正文页面")
        }
        val title = (shell ?: document).selectFirst("#title")?.text()?.takeIf(String::isNotBlank)
            ?: throw WenkuAccessException(AccessFailure.STRUCTURE, "未找到章节标题，无法识别正文页面")
        val collector = ContentCollector()
        NodeTraversor.filter(collector, content)
        collector.flush()
        if (collector.blocks.isEmpty()) throw WenkuAccessException(AccessFailure.STRUCTURE, "该章节没有可识别的正文")
        return WenkuChapterContent(title, collector.blocks)
    }

    private class ContentCollector : NodeFilter {
        val blocks = mutableListOf<WenkuContentBlock>()
        private val text = StringBuilder()
        private var breaks = 0

        override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
            if (node is TextNode) append(node.wholeText)
            if (node !is Element || depth == 0) return NodeFilter.FilterResult.CONTINUE
            val tag = node.normalName()
            if (node.id() in SKIPPED_IDS || AD_ID.matches(node.id()) ||
                tag in SKIPPED_TAGS || node.classNames().any(SKIPPED_CLASSES::contains) ||
                (tag == "a" && node.text() in NAVIGATION_LABELS)
            ) {
                return NodeFilter.FilterResult.SKIP_ENTIRELY
            }
            when (tag) {
                "br" -> breaks = (breaks + 1).coerceAtMost(2)
                "img" -> {
                    val src = node.attr("src").ifBlank { node.attr("data-src") }.trim()
                    val url = node.baseUri().toHttpUrlOrNull()?.resolve(src)
                    if (src.isNotEmpty() && url != null && url.username.isEmpty() && url.password.isEmpty()) {
                        flush()
                        blocks += WenkuContentBlock.ImageBlock(url.toString())
                    }
                }
                else -> if (tag in PARAGRAPH_TAGS) breaks = 2
            }
            return NodeFilter.FilterResult.CONTINUE
        }

        override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
            if (depth > 0 && node is Element && node.normalName() in PARAGRAPH_TAGS) breaks = 2
            return NodeFilter.FilterResult.CONTINUE
        }

        private fun append(raw: String) {
            for (character in raw) {
                if (character in COLLAPSIBLE_SPACE) {
                    if (breaks == 0 && text.isNotEmpty() && text.last() != ' ') text.append(' ')
                    continue
                }
                if (breaks > 0) {
                    if (text.isNotEmpty()) {
                        if (text.last() == ' ') text.setLength(text.length - 1)
                        repeat(breaks) { text.append('\n') }
                    }
                    breaks = 0
                }
                text.append(character)
            }
        }

        fun flush() {
            val normalized = text.toString().trimEnd { it == ' ' || it == '\n' }
            if (normalized.isNotBlank()) blocks += WenkuContentBlock.TextBlock(normalized)
            text.clear()
            breaks = 0
        }
    }

    private const val COLLAPSIBLE_SPACE = " \t\r\n\u000C"
    private val PARAGRAPH_TAGS = setOf("p", "div", "blockquote", "li", "section", "article")
    private val SKIPPED_TAGS = setOf("nav", "script", "style", "iframe", "noscript", "button", "form")
    private val SKIPPED_IDS = setOf("contentdp", "footlink", "title")
    private val SKIPPED_CLASSES = setOf("ad", "ads", "advertisement", "chapter-nav")
    private val AD_ID = Regex("adv\\d+")
    private val NAVIGATION_LABELS = setOf("上一页", "下一页", "上一章", "下一章", "返回目录", "上一頁", "下一頁", "返回目錄")
}
