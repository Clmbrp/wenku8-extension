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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class WenkuReadingStyleTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val origin = "https://www.wenku8.net/".toHttpUrl()
    private val chapterPath = "/novel/0/42/101.htm"
    private val key = WenkuTextKey(chapterPath, "a".repeat(64), version = 2)
    private val standard = WenkuReadingStyle()

    @Test
    fun `default sizes colors and paragraph structure match the verified style`() {
        val style = WenkuReadingStyle.fromPreferences(emptyMap<String, Any>(), dark = false)
        assertEquals(standard, style)
        assertEquals(listOf(42, 52, 135, 0, 62, 1_000, 2_000), with(style) {
            listOf(bodySize, headingSize, lineSpacingPercent, paragraphGap, horizontalMargin, width, maxHeight)
        })
        assertEquals(0xFFFFFFFF.toInt(), style.backgroundColor)
        assertEquals(0xFF000000.toInt(), style.textColor)
        assertEquals(listOf(3, 9), wenkuParagraphBreaks("甲。\n\n乙\n丙。\n\n丁。"))
    }

    @Test
    fun `invalid values fall back and dark mode follows explicit host or system setting`() {
        val invalid = pref("body_size" to "99999", "heading_size" to 7, "paper" to "#GGHHII", "ink" to "WHITE", "line_spacing" to "NaN")
        assertEquals(standard, WenkuReadingStyle.fromPreferences(invalid, dark = false))
        assertTrue(WenkuNight.FOLLOW.resolve(true, false))
        assertFalse(WenkuNight.FOLLOW.resolve(false, true))
        assertTrue(WenkuNight.FOLLOW.resolve(null, true))
        assertTrue(WenkuNight.ON.resolve(false, false))
        assertFalse(WenkuNight.OFF.resolve(true, true))
        assertEquals(0xFF241F1A.toInt(), standard.copy(paper = WenkuPaper.IVORY, dark = true).backgroundColor)
        assertEquals(0xFFF0D8BE.toInt(), standard.copy(ink = WenkuInk.WARM, dark = true).textColor)
    }

    @Test
    fun `old preset preferences keep their values and existing page stamp`() {
        val saved = pref("body_size" to "56", "heading_size" to "58", "line_spacing" to "155", "paragraph_gap" to "18", "side_margin" to "80", "paper" to "IVORY", "ink" to "WARM")
        val style = WenkuReadingStyle.fromPreferences(saved, dark = false)
        val originalStamp = "56-58-155-18-80-IVORY-WARM-0"
        assertEquals(originalStamp, style.stamp())
        assertEquals(style, WenkuReadingStyle.fromStamp(originalStamp))
        assertEquals("56", saved[WENKU_STYLE_PREFIX + "body_size"])
    }

    @Test
    fun `custom integers validate boundaries and persist in the existing keys`() {
        val range = WenkuReadingStyle.BODY_RANGE
        assertEquals(47, wenkuInteger(" 47 ", range))
        assertEquals(range.first, wenkuInteger(range.first.toString(), range))
        assertEquals(range.last, wenkuInteger(range.last.toString(), range))
        for (invalid in listOf("29", "73", "12.5", "-45", "４９", "4 9", "9999999999", "")) assertNull(wenkuInteger(invalid, range))
        val settings = pref("body_size" to "47", "heading_size" to "63", "line_spacing" to "140", "paragraph_gap" to "27", "side_margin" to "71")
        val style = WenkuReadingStyle.fromPreferences(settings, dark = false)
        assertEquals(listOf(47, 63, 140, 27, 71), listOf(style.bodySize, style.headingSize, style.lineSpacingPercent, style.paragraphGap, style.horizontalMargin))
        assertEquals(style, WenkuPageRef.parse(WenkuPageRef(key, 0, style).url().toHttpUrl())?.style)
    }

    @Test
    fun `custom RGB is validated separately for light and dark and keeps text readable`() {
        assertEquals("#FAFAF8", wenkuHexColor(" #fafaf8 "))
        for (invalid in listOf("FAFAF8", "#FAF", "#FFFAFAF8", "#ZZ0000", "red", "")) assertNull(wenkuHexColor(invalid))
        val settings = pref("paper" to "IVORY", "ink" to "WARM").toMutableMap()
        settings[wenkuColorPreferenceKey("paper", dark = false)] = "#FAFAF8"
        settings[wenkuColorPreferenceKey("ink", dark = false)] = "#333333"
        settings[wenkuColorPreferenceKey("paper", dark = true)] = "#101122"
        val light = WenkuReadingStyle.fromPreferences(settings, dark = false)
        val dark = WenkuReadingStyle.fromPreferences(settings, dark = true)
        assertEquals("FAFAF8", light.backgroundColor.hexRgb())
        assertEquals("333333", light.textColor.hexRgb())
        assertEquals("101122", dark.backgroundColor.hexRgb())
        assertEquals("F0D8BE", dark.textColor.hexRgb())
        for (style in listOf(light, dark)) {
            val ref = WenkuPageRef(key, 0, style)
            assertEquals(ref, WenkuPageRef.parse(ref.url().toHttpUrl()))
        }
        assertNull(WenkuReadingStyle.fromStamp(dark.stamp().replace("-101122-X", "-NOPE-X")))
        val unreadable = light.copy(customInk = light.backgroundColor)
        assertNotEquals(unreadable.backgroundColor, unreadable.textColor)
        assertTrue(wenkuColorContrast(unreadable.backgroundColor, unreadable.textColor) >= WENKU_MIN_CONTRAST)
    }

    @Test
    fun `each style dimension changes the page cache key and can be recovered after restart`() {
        val variants = listOf(
            standard,
            WenkuReadingStyle.fromPreferences(pref("body_size" to "30"), dark = false),
            WenkuReadingStyle.fromPreferences(pref("body_size" to "72"), dark = false),
            standard.copy(headingSize = 88),
            standard.copy(lineSpacingPercent = 115),
            standard.copy(paragraphGap = 18),
            standard.copy(horizontalMargin = 100),
            standard.copy(paper = WenkuPaper.IVORY),
            standard.copy(ink = WenkuInk.SOFT),
            standard.copy(dark = true),
        )
        val pages = variants.map { WenkuPageRef(key, 1, it) }
        assertEquals(pages.size, pages.map(WenkuPageRef::url).toSet().size)
        for (page in pages) {
            assertEquals(page, WenkuPageRef.parse(page.url().toHttpUrl()))
        }
        val defaultUrl = pages.first().url()
        assertNotEquals(defaultUrl, WenkuPageRef(key.copy(chapterPath = "/novel/0/42/102.htm"), 1, standard).url())
        assertNull(WenkuPageRef.parse(defaultUrl.replace("/42-52-", "/999-52-").toHttpUrl()))
        assertNull(WenkuPageRef.parse(defaultUrl.replace("-WHITE-", "-MALFORMED-").toHttpUrl()))
        val old = WenkuPageRef(key, 1)
        assertEquals(old, WenkuPageRef.parse(old.url().toHttpUrl()))
    }

    @Test
    fun `larger and smaller line measures tile a long chapter exactly within the page height`() {
        val lineCount = 3_500
        fun paginate(lineHeight: Int, blankHeight: Int): Int {
            val lineTops = IntArray(lineCount + 1)
            for (line in 0 until lineCount) lineTops[line + 1] = lineTops[line] + if (line % 6 == 5) blankHeight else lineHeight
            val pages = wenkuPaginate(lineTops, standard.maxHeight - 52 - 105 - 20, standard.maxHeight - 40)
            assertEquals((0 until lineCount).toList(), pages.flatMap { it.start until it.end })
            assertTrue(pages.withIndex().all { (index, page) -> page.height + (if (index == 0) 177 else 40) <= standard.maxHeight })
            return pages.size
        }
        assertTrue(paginate(98, 75) > paginate(46, 20))
    }

    @Test
    fun `refresh evicts only verified outdated Wenku8 lists`() {
        val root = temporary.newFolder()
        val directory = makeHostCache(root)
        val old = hostEntry(directory, 'a', WenkuPageRef(key, 0), WenkuPageRef(key, 1))
        val current = hostEntry(directory, 'b', WenkuPageRef(key, 0, standard))
        val staleColor = hostEntry(directory, 'c', WenkuPageRef(key, 0, standard.copy(dark = true)))
        val mixedRevisions = hostEntry(directory, 'd', WenkuPageRef(key, 0), WenkuPageRef(key.copy(revision = "b".repeat(64)), 1))
        val pending = hostEntry(directory, 'e', WenkuPageRef(key, 0))
        File(directory, "${pending.name}.tmp").writeText("pending")
        val anotherSource = File(directory, "f".repeat(32) + ".0").apply { writeText("""[{"url":"https://another.example.org/image.png"}]""") }
        val image = File(directory, "0".repeat(32) + ".0").apply { writeBytes(byteArrayOf(-119, 80, 78, 71)) }
        val evicted = mutableListOf<String>()
        assertEquals(WenkuPageListRefreshResult(updated = 2, failed = 1), wenkuRefreshPageLists(root, standard, evicted::add))
        assertEquals(listOf(chapterPath, chapterPath), evicted)
        assertTrue(listOf(current, mixedRevisions, pending, anotherSource, image, File(directory, "journal")).all(File::exists))
        assertFalse(old.exists())
        assertFalse(staleColor.exists())
        File(directory, "journal").writeText("unknown format")
        assertTrue(wenkuRefreshPageLists(root, standard, evicted::add).incompatible)
        assertTrue(current.exists())
    }

    @Test
    fun `applying a style and reopening uses expired local chapter once with versioned page URLs`() = runBlocking {
        val directory = temporary.newFolder()
        val root = temporary.newFolder()
        val host = makeHostCache(root)
        val time = AtomicLong(System.currentTimeMillis())
        var style = standard
        var htmlCalls = 0
        val service = WenkuPages(OkHttpClient(), headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0)) { request ->
            htmlCalls++
            val html = """<div id="contentmain"><div id="title">卷首插图</div>
                <div id="content"><img src="https://cdn.example.org/first.jpg"><img src="https://cdn.example.org/last.jpg"></div></div>"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }
        val reader = WenkuReader(service, origin, WenkuTextCache(directory, time::get), readStyle = { style }) {
            throw IOException("模拟插图加载失败")
        }
        val original = reader.pageUrls(chapterPath)
        time.addAndGet(25 * 60_000L)
        hostEntry(host, 'a', *original.map { WenkuPageRef.parse(it.toHttpUrl())!! }.toTypedArray())
        style = standard.copy(bodySize = 62, dark = true)
        assertEquals(1, reader.refreshPageLists(root).updated)
        val styled = reader.pageUrls(chapterPath)
        assertEquals(1, htmlCalls)
        val styledPages = styled.map { WenkuPageRef.parse(it.toHttpUrl())!! }
        assertEquals(listOf(0, 1), styledPages.map(WenkuPageRef::index))
        assertTrue(styledPages.all { it.style == style })
        assertNotEquals(original, styled)

        val reopened = WenkuReader(service, origin, WenkuTextCache(directory), readStyle = { style }) {
            throw IOException("模拟插图加载失败")
        }
        val client = OkHttpClient.Builder().addInterceptor(reopened::intercept).build()
        val request = Request.Builder().url(styled.last()).build()
        val error = assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
        assertEquals("模拟插图加载失败", error.message)
        assertEquals(1, htmlCalls)
        style = WenkuReadingStyle.fromPreferences(emptyMap<String, Any>(), dark = false)
        assertEquals(original, reopened.pageUrls(chapterPath))
        assertEquals(1, htmlCalls)
    }

    private fun pref(vararg values: Pair<String, Any>) = values.toMap().mapKeys { (key, _) -> WENKU_STYLE_PREFIX + key }

    private fun makeHostCache(root: File): File = File(root, "chapter_disk_cache").apply {
        mkdir()
        File(this, "journal").writeText("libcore.io.DiskLruCache\n1\n1\n1\n\n")
    }

    private fun hostEntry(directory: File, name: Char, vararg pages: WenkuPageRef): File = File(directory, name.toString().repeat(32) + ".0").apply {
        writeText(pages.joinToString(prefix = "[", postfix = "]") { """{"index":${it.index},"url":"${it.url()}","imageUrl":"${it.url()}"}""" })
    }
}
