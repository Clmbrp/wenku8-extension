package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        data class Illustration(val url: String, val title: String) :
            RenderPage,
            PageWork
    }

    private sealed interface PageWork
    private data class Rendered(val png: ByteArray) : PageWork
    private data class Layout(val pages: List<RenderPage>, val weight: Long)

    private val renderLock = Mutex()
    private val illustrationSlot = Mutex()
    private val chapterLoads = WenkuFlights<String, WenkuCachedChapter>()
    private val snapshotLoads = WenkuFlights<WenkuTextKey, WenkuCachedChapter>()
    private val pageLoads = WenkuFlights<WenkuPageRef, ByteArray>()
    private val restyles = WenkuMemoryCache<String, Unit>(24, 24) { 1L }
    private val layouts = WenkuMemoryCache<WenkuRenderKey, Layout>(2, 1_024 * 1_024, keepOversized = true) { it.weight }
    private val images = WenkuMemoryCache<WenkuPageRef, ByteArray>(8, 4 * 1_024 * 1_024) { it.size.toLong() }

    suspend fun pageUrls(chapterUrl: String): List<String> = withContext(Dispatchers.IO) {
        val url = origin.resolve(chapterUrl)?.takeIf { it.hasWenkuOrigin(origin) && WENKU_CHAPTER_PATH.matches(it.encodedPath) }
            ?: throw WenkuAccessException(AccessFailure.STRUCTURE, "章节地址不符合预期")
        val path = url.encodedPath
        val content = loadChapter(path, allowExpired = restyles.remove(path) != null)
        val style = readStyle()
        val pageCount = renderLock.withLock { prepareLocked(content, style).size }
        List(pageCount) { WenkuPageRef(content.key, it, style).url() }
    }

    fun refreshPageLists(appCache: File): WenkuPageListRefreshResult = wenkuRefreshPageLists(appCache, readStyle()) { path ->
        if (cache.hasSnapshot(path)) restyles.put(path, Unit)
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
        if (call.isCanceled()) throw IOException("阅读页加载已取消")
        return images[page] ?: runForPage(call) { pageLoads.await(page) { images[page] ?: generate(page) } }
    }

    private suspend fun generate(page: WenkuPageRef): ByteArray = when (val entry = renderOrLocate(page)) {
        is Rendered -> entry.png
        is RenderPage.Illustration -> illustrationSlot.withLock {
            val data = fetchIllustration(entry.url)
            renderLock.withLock { renderLocked(page) { graphics(page.style).illustration(data, entry.title) } }
        }
    }

    private suspend fun renderOrLocate(page: WenkuPageRef): PageWork {
        // A queued render acquires the lock before taking a reference to a text layout.
        renderLock.withLock { layouts[page.renderKey]?.let { return renderOrLocateLocked(page, it.pages) } }
        val content = snapshotLoads.await(page.key) { cache.read(page.key) ?: recover(page.key) }
        return renderLock.withLock { renderOrLocateLocked(page, prepareLocked(content, page.style)) }
    }

    private suspend fun renderOrLocateLocked(page: WenkuPageRef, plan: List<RenderPage>): PageWork = when (val entry = plan.getOrNull(page.index)) {
        is RenderPage.Text -> Rendered(renderLocked(page) { entry.chapter.png(entry.index) })
        is RenderPage.Illustration -> entry
        null -> throw IOException("阅读页不存在，请重新打开章节")
    }

    private suspend fun renderLocked(page: WenkuPageRef, render: () -> ByteArray): ByteArray {
        currentCoroutineContext().ensureActive()
        val png = render()
        currentCoroutineContext().ensureActive()
        images.put(page, png)
        return png
    }

    private suspend fun prepareLocked(content: WenkuCachedChapter, style: WenkuReadingStyle?): List<RenderPage> {
        val renderKey = WenkuRenderKey(content.key, style)
        layouts[renderKey]?.let { return it.pages }
        val context = currentCoroutineContext()
        context.ensureActive()
        val renderer = graphics(style)
        val chapter = try {
            content.content.readerPages<RenderPage>(
                textPages = {
                    context.ensureActive()
                    val layout = renderer.prepare(it)
                    List(layout.pageCount) { index -> RenderPage.Text(layout, index) }
                },
                illustrationPage = { image, title -> RenderPage.Illustration(image.url, title) },
            )
        } catch (error: OutOfMemoryError) {
            throw IOException("设备内存不足，无法排版该章节", error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            throw IOException("无法排版该章节", error)
        }
        context.ensureActive()
        layouts.put(renderKey, Layout(chapter, content.content.payloadWeight() + 32L * chapter.size))
        return chapter
    }

    private suspend fun loadChapter(path: String, allowExpired: Boolean = false): WenkuCachedChapter {
        if (allowExpired) cache.fresh(path, allowExpired = true)?.let { return it }
        return chapterLoads.await(path) { cache.fresh(path) ?: fetch(path) }
    }

    private suspend fun fetch(path: String): WenkuCachedChapter {
        val document = pages.get(path)
        if (document.location().toHttpUrlOrNull()?.encodedPath != path) {
            throw WenkuAccessException(AccessFailure.STRUCTURE, "站点返回了其他页面，无法识别本章正文")
        }
        currentCoroutineContext().ensureActive()
        val content = WenkuChapterParser.parse(document)
        currentCoroutineContext().ensureActive()
        pages.confirmChapter(document)
        return cache.remember(path, content)
    }

    /** Mihon may keep a page list longer than the source's copy of the chapter. */
    private suspend fun recover(key: WenkuTextKey): WenkuCachedChapter {
        val content = loadChapter(key.chapterPath)
        val snapshot = if (key.version == 1 && content.key.version == 2) cache.legacySnapshot(key.chapterPath, content.content) else content
        if (snapshot.key != key) throw IOException("章节内容已更新，请刷新章节页面列表")
        return snapshot
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
    }
}
