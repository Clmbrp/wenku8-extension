package eu.kanade.tachiyomi.extension.zh.wenku8

import android.net.ConnectivityManager
import android.util.Log
import android.webkit.CookieManager
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.BuildConfig
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.applicationContext
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

private const val HOST_CLOUDFLARE = "eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor"

@Source
abstract class Wenku8 :
    HttpSource(),
    ConfigurableSource {
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val webViewRecovery by lazy { WenkuWebViewRecovery(baseUrl.siteUrl()) }

    // The old wenku8_webview_entry preference no longer controls navigation.
    override fun getHomeUrl(): String = webViewRecovery.entry().toString()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        wenkuRemoveLegacyWebViewPreference(preferences)
        val website = wenkuPreferenceGroup(screen, "网站访问")
        website(wenkuWebsiteLoginPreference(screen.context, webViewRecovery::prepareLogin))
        website(
            wenkuWebsiteCheckPreference(screen.context) {
                wenkuWebsiteMessage(browse.checkWebsiteAccess(), webViewRecovery.hasPending())
            },
        )
        wenkuReadingPreferences(screen, preferences) { reader.refreshPageLists(applicationContext.cacheDir) }
    }

    private val diagnostics = if (BuildConfig.DEBUG) {
        WenkuDiagnostics(webViewCookies = { url ->
            runCatching {
                val cookieHeader = CookieManager.getInstance().getCookie(url.toString())
                WenkuSession.cookieNamesFromHeaders(listOfNotNull(cookieHeader))
            }.getOrNull()
        }) { Log.d(WenkuDiagnostics.TAG, it) }
    } else {
        null
    }

    private val hostCloudflare = super.client.interceptors.find { it.javaClass.name == HOST_CLOUDFLARE }

    // Keep the host's WebView handler on the paced main-site HTML client, away from image hosts.
    override val client = super.client.newBuilder().apply {
        interceptors().add(0) { chain ->
            if (chain.request().url.host == WenkuPageRef.HOST) reader.intercept(chain) else chain.proceed(chain.request())
        }
        hostCloudflare?.let { interceptors().remove(it) }
        interceptors().removeAll { it.javaClass.name == "okhttp3.logging.HttpLoggingInterceptor" }
        networkInterceptors().removeAll { it.javaClass.name == "okhttp3.logging.HttpLoggingInterceptor" }
        diagnostics?.let { addInterceptor(it::cover) }
    }.build()

    private val pages by lazy {
        WenkuPages(client, headers, baseUrl.siteUrl(), diagnostics, hasNetwork = {
            runCatching {
                applicationContext.getSystemService(ConnectivityManager::class.java)?.let { it.activeNetwork != null }
            }.getOrNull()
        }, hostCloudflare = hostCloudflare, recovery = webViewRecovery)
    }
    private val browse by lazy { WenkuBrowse(pages, baseUrl.siteUrl(), diagnostics) }
    private val reader: WenkuReader by lazy {
        val origin = baseUrl.siteUrl()
        val cacheDir = applicationContext.cacheDir
        val cache = WenkuTextCache(File(cacheDir, "wenku8-reader-v2-$id"), legacyDirectory = File(cacheDir, "wenku8-text-v1-$id"))
        WenkuReader(
            pages,
            origin,
            cache,
            readStyle = { wenkuReadingStyle(preferences, applicationContext) },
            fetchIllustration = WenkuIllustrations(client, pages, origin)::fetch,
        )
    }

    override suspend fun getPopularManga(page: Int): MangasPage = browse.popular(page).toMangasPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage = browse.latest(page).toMangasPage()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = browse.search(page, query).toMangasPage()

    private fun WenkuBookPage.toMangasPage(): MangasPage = MangasPage(
        books.map { book ->
            SManga.create().apply {
                title = book.title
                url = book.path
                thumbnail_url = book.coverUrl
            }
        },
        hasNextPage,
    )

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val details = WenkuParser.details(pages.get(manga.url), baseUrl.siteUrl())
        val updated = if (fetchDetails) {
            SManga.create().apply {
                url = manga.url
                title = details.title
                author = details.author
                description = details.description
                genre = details.genre
                thumbnail_url = details.coverUrl ?: manga.thumbnail_url
                status = when (details.status) {
                    "已完结", "全本", "已完結" -> SManga.COMPLETED
                    "连载中", "連載中" -> SManga.ONGOING
                    else -> SManga.UNKNOWN
                }
                initialized = true
            }
        } else {
            manga
        }
        val chapterList = if (fetchChapters) {
            val catalog = details.catalogPath ?: throw WenkuAccessException(AccessFailure.STRUCTURE, "未找到该书的章节目录入口")
            WenkuParser.catalog(pages.get(catalog), baseUrl.siteUrl(), catalog).asReversed().map { chapter ->
                SChapter.create().apply {
                    name = listOfNotNull(chapter.volume, chapter.title).joinToString(" ")
                    url = chapter.path
                }
            }
        } else {
            chapters
        }
        return SMangaUpdate(updated, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = wenkuMihonPages(reader.pageUrls(chapter.url))

    override suspend fun getImageUrl(page: Page): String {
        val url = page.url.toHttpUrlOrNull()
        if (url == null || WenkuPageRef.parse(url) == null) {
            throw WenkuAccessException(AccessFailure.STRUCTURE, "阅读页地址无效，请重新打开章节")
        }
        return page.url
    }
}

internal fun wenkuMihonPages(urls: List<String>): List<Page> = urls.mapIndexed { index, url -> Page(index, url, url) }
