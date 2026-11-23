package eu.kanade.tachiyomi.extension.zh.wenku8

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class WenkuDownloadTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val origin = "https://www.wenku8.net/".toHttpUrl()
    private val chapterPath = "/novel/0/42/101.htm"
    private val root = OkHttpClient()
    private val purple = Color(100, 40, 180).rgb

    @Test
    fun `native page objects expose complete PNG images for text illustrations and mixed chapters in order`() = runBlocking {
        val jpeg = image(purple, format = "jpeg")
        val illustrationColor = ImageIO.read(jpeg.inputStream()).getRGB(0, 0)
        for ((html, colors) in listOf(
            "<p>第一段。</p><p>第二段。</p>" to listOf(FIRST_TEXT, SECOND_TEXT),
            """<img src="https://cdn.example.org/example.jpg">""" to listOf(illustrationColor),
            """<p>第一段。</p><img src="https://cdn.example.org/example.jpg"><p>第二段。</p>""" to listOf(FIRST_TEXT, illustrationColor, SECOND_TEXT),
        )) {
            var downloads = 0
            val fixture = fixture(temporary.newFolder(), html) {
                assertEquals("https://cdn.example.org/example.jpg", it)
                downloads++
                jpeg
            }
            val reader = fixture.reader()
            val pages = wenkuMihonPages(reader.pageUrls(chapterPath))
            assertEquals(colors.indices.toList(), pages.map(Page::index))
            assertTrue(pages.all { it.imageUrl == it.url })
            assertEquals(0, downloads)
            val client = client(reader)
            assertEquals(colors, pages.map { pngColor(getPng(client, it)) })
            assertEquals(if (illustrationColor in colors) 1 else 0, downloads)
            assertEquals(1, fixture.htmlCalls)
        }
    }

    @Test
    fun `after memory and snapshot loss a download page recovers once and keeps the original style`() = runBlocking {
        val style = WenkuReadingStyle(bodySize = 45, customPaper = "#FAFAF8".toRgb())
        val fixture = fixture(temporary.newFolder(), "<p>第一段。</p><p>第二段。</p>") { error("no illustrations") }
        val original = fixture.reader { style }
        val pages = wenkuMihonPages(original.pageUrls(chapterPath))
        val recolored = client(fixture.reader { WenkuReadingStyle(dark = true) })
        assertEquals(SECOND_TEXT, pngColor(getPng(recolored, pages.last())))
        assertEquals(style, fixture.renderedStyles.last())
        assertEquals(1, fixture.htmlCalls)

        assertTrue(fixture.directory.listFiles()!!.single().delete())
        val recovered = client(fixture.reader())
        assertEquals(FIRST_TEXT, pngColor(getPng(recovered, pages.first())))
        assertEquals(SECOND_TEXT, pngColor(getPng(recovered, pages.last())))
        assertEquals(2, fixture.htmlCalls)
        assertEquals(style, fixture.renderedStyles.last())
    }

    @Test
    fun `cancelled illustration stays incomplete and retry rewrites partial PNG before offline archive reopen`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var calls = 0
        val illustration = image(purple)
        val fixture = fixture(
            temporary.newFolder(),
            """<p>第一段。</p><img src="https://cdn.example.org/example.png"><p>第二段。</p>""",
        ) {
            if (++calls == 1) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            illustration
        }
        val reader = fixture.reader()
        val pages = wenkuMihonPages(reader.pageUrls(chapterPath))
        val client = client(reader)
        val staging = temporary.newFolder()
        val target = File(staging, "chapter.cbz")
        val first = File(staging, "001.png").apply { writeBytes(getPng(client, pages[0])) }
        val partial = File(staging, "002.tmp").apply { writeBytes(byteArrayOf(-119, 80, 78)) }

        val canceledCall = client.newCall(Request.Builder().url(pages[1].imageUrl!!).build())
        val attempt = async(Dispatchers.IO) { runCatching { canceledCall.execute().close() } }
        withTimeout(5_000) {
            started.await()
            canceledCall.cancel()
            cancelled.await()
            assertTrue(attempt.await().exceptionOrNull() is IOException)
        }
        assertFalse(target.exists())
        assertEquals(3L, partial.length())

        val secondBytes = getPng(client, pages[1], existingSize = partial.length())
        partial.writeBytes(secondBytes)
        val second = File(staging, "002.png")
        assertTrue(partial.renameTo(second))
        val third = File(staging, "003.png").apply { writeBytes(getPng(client, pages[2])) }
        assertEquals(2, calls)
        assertEquals(1, fixture.htmlCalls)
        ZipOutputStream(target.outputStream()).use { archive ->
            // Deliberately write entries unsorted, like concurrent completion.
            for (file in listOf(third, first, second)) {
                archive.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(archive) }
                archive.closeEntry()
            }
        }

        // Reopening, then moving forwards/backwards needs only the archive, with no source client.
        repeat(2) {
            ZipFile(target).use { archive ->
                val names = archive.entries().toList().sortedBy { it.name }
                assertEquals(listOf("001.png", "002.png", "003.png"), names.map { it.name })
                assertEquals(listOf(FIRST_TEXT, purple, SECOND_TEXT), names.map {
                    archive.getInputStream(it).use { stream -> ImageIO.read(stream).getRGB(0, 0) }
                })
                assertEquals(FIRST_TEXT, archive.getInputStream(names.first()).use { ImageIO.read(it).getRGB(0, 0) })
            }
        }
    }

    @Test
    fun `a changed source revision cannot silently fill an old download with different pages`() = runBlocking {
        val fixture = fixture(temporary.newFolder(), "<p>第一段。</p>") { error("no illustrations") }
        val pages = wenkuMihonPages(fixture.reader().pageUrls(chapterPath))
        assertTrue(fixture.directory.listFiles()!!.single().delete())
        fixture.html = "<p>第二段。</p>"
        val error = assertThrows(IOException::class.java) { getPng(client(fixture.reader()), pages.single()) }
        assertTrue(error.message.orEmpty().contains("章节内容已更新"))
        assertEquals(2, fixture.htmlCalls)
    }

    private fun client(reader: WenkuReader): OkHttpClient = root.newBuilder().addInterceptor(reader::intercept).build()

    private fun getPng(client: OkHttpClient, page: Page, existingSize: Long = 0L): ByteArray {
        val request = Request.Builder().url(page.imageUrl!!).apply {
            if (existingSize > 0) header("Range", "bytes=$existingSize-")
        }.build()
        return client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("image/png", response.body.contentType().toString())
            assertEquals(null, response.header("Content-Range"))
            response.body.bytes().also {
                assertEquals(it.size.toLong(), response.body.contentLength())
                assertNotNull(ImageIO.read(it.inputStream()))
            }
        }
    }

    private fun fixture(directory: File, html: String, lookup: suspend (String) -> ByteArray): Fixture = Fixture(directory, html, lookup)

    private inner class Fixture(val directory: File, var html: String, val lookup: suspend (String) -> ByteArray) {
        var htmlCalls = 0
        val renderedStyles = mutableListOf<WenkuReadingStyle?>()
        private val pages = WenkuPages(root, headersOf(), origin, policy = WenkuRequestPolicy(intervalMillis = 0)) { request ->
            htmlCalls++
            val doc = """<div id="contentmain"><div id="title">样本</div><div id="content">$html</div></div>"""
            Response.Builder().request(request).code(200).protocol(Protocol.HTTP_1_1).message("test")
                .body(doc.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }

        fun reader(style: () -> WenkuReadingStyle = { WenkuReadingStyle() }): WenkuReader = WenkuReader(
            pages, origin, WenkuTextCache(directory), readStyle = style,
            graphics = { selected ->
                renderedStyles += selected
                object : WenkuPageGraphics {
                    override fun prepare(text: WenkuChapterText): WenkuTextPages {
                        val paragraphs = text.body.split("\n\n")
                        return object : WenkuTextPages {
                            override val pageCount = paragraphs.size
                            override fun png(index: Int): ByteArray = image(if ("第一段" in paragraphs[index]) FIRST_TEXT else SECOND_TEXT)
                        }
                    }

                    override fun illustration(data: ByteArray, title: String): ByteArray = image(ImageIO.read(data.inputStream()).getRGB(0, 0))
                }
            },
            fetchIllustration = lookup,
        )
    }

    private fun pngColor(bytes: ByteArray) = ImageIO.read(bytes.inputStream()).getRGB(0, 0)

    private fun image(color: Int, format: String = "png"): ByteArray {
        val bitmap = BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) bitmap.setRGB(x, y, color)
        return ByteArrayOutputStream().use {
            check(ImageIO.write(bitmap, format, it))
            it.toByteArray()
        }
    }

    companion object {
        private val FIRST_TEXT = Color.RED.rgb
        private val SECOND_TEXT = Color.BLUE.rgb
    }
}
