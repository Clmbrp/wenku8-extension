package eu.kanade.tachiyomi.extension.zh.wenku8

import android.util.Log
import eu.kanade.tachiyomi.extension.BuildConfig
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source

@Source
abstract class Wenku8 : HttpSource() {
    override val supportsLatest = false

    override fun getHomeUrl(): String = "$baseUrl/login.php"

    // The host's Cloudflare interceptor may start an automated background WebView. This source
    // only allows user-initiated interaction in the host WebView. Preserve the host cookie jar.
    override val client = super.client.newBuilder().apply {
        interceptors().removeAll { it.javaClass.name == "eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor" }
        interceptors().removeAll { it.javaClass.name == "okhttp3.logging.HttpLoggingInterceptor" }
        networkInterceptors().removeAll { it.javaClass.name == "okhttp3.logging.HttpLoggingInterceptor" }
    }.build()

    private val diagnostics = if (BuildConfig.DEBUG) WenkuDiagnostics { Log.d(WenkuDiagnostics.TAG, it) } else null
    private val pages by lazy { WenkuPages(client, headers, baseUrl.siteUrl(), diagnostics) }

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page != 1) throw WenkuAccessException(AccessFailure.STRUCTURE, "总热度榜没有更多页面")
        val html = pages.get("/top.php")
        val books = try {
            WenkuParser.totalRanking(html, baseUrl.siteUrl().host)
        } catch (e: WenkuAccessException) {
            diagnostics?.result("ranking", e.failure.name)
            throw e
        }
        diagnostics?.result("ranking", "RANKING_HTML")
        return MangasPage(
            books.map { book ->
                SManga.create().apply {
                    title = book.title
                    url = book.path
                }
            },
            false,
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = notImplemented()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = notImplemented()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = notImplemented()

    override suspend fun getPageList(chapter: SChapter): List<Page> = notImplemented()

    override suspend fun getImageUrl(page: Page): String = notImplemented()

    private fun notImplemented(): Nothing = throw UnsupportedOperationException("轻小说文库尚未实现")
}
