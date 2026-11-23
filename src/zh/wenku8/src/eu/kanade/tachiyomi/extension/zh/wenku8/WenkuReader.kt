package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal interface WenkuTextPages {
    val pageCount: Int
    fun png(index: Int): ByteArray
}

internal interface WenkuPageGraphics {
    fun prepare(text: WenkuChapterText): WenkuTextPages
    fun illustration(data: ByteArray, title: String): ByteArray
}

internal class WenkuReader(
    private val pages: WenkuPages,
    private val origin: HttpUrl,
    private val cache: WenkuTextCache,
    private val readStyle: () -> WenkuReadingStyle = { WenkuReadingStyle() },
    private val graphics: (WenkuReadingStyle?) -> WenkuPageGraphics = { style -> WenkuTextRenderer(style ?: WenkuReadingStyle(), legacy = style == null) },
    private val fetchIllustration: suspend (String) -> ByteArray,
) {
    private sealed interface RenderPage {
        data class Text(val chapter: WenkuTextPages, val index: Int) : RenderPage
        data class Illustration(val url: String, val title: String) : RenderPage
    }

    private val renderLock = Any()
    private val illustrationSlots = Semaphore(2)
    private val restyles = mutableSetOf<String>()
    private val layouts = LinkedHashMap<WenkuRenderKey, List<RenderPage>>(4, 0.75f, true)
    private val images = LinkedHashMap<WenkuPageRef, ByteArray>(16, 0.75f, true)
    private var imageBytes = 0

    suspend fun pageUrls(chapterUrl: String): List<String> = withContext(Dispatchers.IO) {
        val url = origin.resolve(chapterUrl)?.takeIf { it.hasWenkuOrigin(origin) && WENKU_CHAPTER_PATH.matches(it.encodedPath) }
            ?: throw WenkuAccessException(AccessFailure.STRUCTURE, "章节地址不符合预期")
        val path = url.encodedPath
        val restyle = synchronized(restyles) { restyles.remove(path) }
        val content = cache.fresh(path, allowExpired = restyle) ?: fetch(path)
        val style = readStyle()
        val chapter = prepare(content, style)
        List(chapter.size) { WenkuPageRef(content.key, it, style).url() }
    }

    fun refreshPageLists(appCache: File): WenkuPageListRefreshResult = wenkuRefreshPageLists(appCache, readStyle()) { path ->
        synchronized(restyles) { restyles.add(path) }
    }

    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val page = WenkuPageRef.parse(request.url) ?: throw IOException("阅读页地址无效，请重新打开章节")
        val data = try {
            image(page, chain.call())
        } catch (error: WenkuAccessException) {
            throw IOException(error.message, error)
        } catch (error: TimeoutCancellationException) {
            throw IOException("阅读页恢复超时，请稍后重试", error)
        } catch (error: CancellationException) {
            throw IOException("阅读页加载已取消", error)
        } catch (error: OutOfMemoryError) {
            throw IOException("设备内存不足，无法生成阅读页", error)
        } catch (error: RuntimeException) {
            throw IOException("无法生成章节阅读图片", error)
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            // Mihon overwrites a partial download for 200. Every response here is a complete PNG.
            .code(200)
            .message("OK")
            .body(data.toResponseBody(PNG))
            .build()
    }

    private fun image(page: WenkuPageRef, call: Call): ByteArray {
        synchronized(renderLock) { images[page]?.let { return it } }
        val plan = synchronized(renderLock) { layouts[page.renderKey] }
            ?: prepare(cache.read(page.key) ?: recover(page.key, call), page.style)
        val entry = plan.getOrNull(page.index) ?: throw IOException("阅读页不存在，请重新打开章节")
        return when (entry) {
            is RenderPage.Text -> renderImage(page, call) { entry.chapter.png(entry.index) }
            is RenderPage.Illustration -> runForPage(call) {
                illustrationSlots.withPermit {
                    synchronized(renderLock) { images[page] }?.let { return@withPermit it }
                    val data = fetchIllustration(entry.url)
                    renderImage(page, call) { graphics(page.style).illustration(data, entry.title) }
                }
            }
        }
    }

    private fun renderImage(page: WenkuPageRef, call: Call, render: () -> ByteArray): ByteArray = synchronized(renderLock) {
        images[page]?.let { return@synchronized it }
        if (call.isCanceled()) throw IOException("阅读页加载已取消")
        val png = render()
        if (call.isCanceled()) throw IOException("阅读页加载已取消")
        if (png.size <= MAX_IMAGE_BYTES) {
            images[page] = png
            imageBytes += png.size
            while (images.size > MAX_IMAGES || imageBytes > MAX_IMAGE_BYTES) {
                val oldest = images.entries.iterator()
                imageBytes -= oldest.next().value.size
                oldest.remove()
            }
        }
        png
    }

    private fun prepare(content: WenkuCachedChapter, style: WenkuReadingStyle?): List<RenderPage> = synchronized(renderLock) {
        val renderKey = WenkuRenderKey(content.key, style)
        layouts[renderKey]?.let { return@synchronized it }
        val renderer = graphics(style)
        val chapter = try {
            content.content.readerPages<RenderPage>(
                textPages = {
                    val layout = renderer.prepare(it)
                    List(layout.pageCount) { index -> RenderPage.Text(layout, index) }
                },
                illustrationPage = { image, title -> RenderPage.Illustration(image.url, title) },
            )
        } catch (error: OutOfMemoryError) {
            throw IOException("设备内存不足，无法排版该章节", error)
        } catch (error: RuntimeException) {
            throw IOException("无法排版该章节", error)
        }
        layouts[renderKey] = chapter
        if (layouts.size > MAX_LAYOUTS) layouts.remove(layouts.keys.first())
        chapter
    }

    private suspend fun fetch(path: String, legacyText: Boolean = false): WenkuCachedChapter {
        val document = pages.get(path)
        if (document.location().toHttpUrlOrNull()?.encodedPath != path) {
            throw WenkuAccessException(AccessFailure.STRUCTURE, "站点返回了其他页面，无法识别本章正文")
        }
        val content = WenkuChapterParser.parse(document)
        val snapshot = cache.remember(path, content)
        return if (legacyText && snapshot.key.version == 2) cache.legacySnapshot(path, content) else snapshot
    }

    /** Mihon may keep a page list longer than the source's copy of the chapter. */
    private fun recover(key: WenkuTextKey, call: Call): WenkuCachedChapter = runForPage(call) {
        val content = fetch(key.chapterPath, legacyText = key.version == 1)
        if (content.key != key) throw IOException("章节内容已更新，请刷新章节页面列表")
        content
    }

    private fun <T> runForPage(call: Call, block: suspend () -> T): T = runBlocking {
        if (call.isCanceled()) throw IOException("阅读页加载已取消")
        val watcher = launch {
            while (!call.isCanceled()) delay(200)
            this@runBlocking.cancel()
        }
        try {
            withTimeout(45_000L) { block() }
        } finally {
            watcher.cancel()
        }
    }

    companion object {
        private val PNG = "image/png".toMediaType()
        private const val MAX_LAYOUTS = 3
        private const val MAX_IMAGES = 16
        private const val MAX_IMAGE_BYTES = 8 * 1_024 * 1_024
    }
}
