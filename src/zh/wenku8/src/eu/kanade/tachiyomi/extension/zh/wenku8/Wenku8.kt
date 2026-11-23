package eu.kanade.tachiyomi.extension.zh.wenku8

import android.util.Log
import androidx.preference.ListPreference
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
import keiyoushi.utils.getPreferencesLazy

private const val WEBVIEW_ENTRY = "wenku8_webview_entry"
private const val VALIDATION_ENTRY = "security"

internal fun wenkuWebViewPath(mode: String?): String = if (mode == VALIDATION_ENTRY) "/top.php" else "/login.php"

@Source
abstract class Wenku8 :
    HttpSource(),
    ConfigurableSource {
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun getHomeUrl(): String = baseUrl + wenkuWebViewPath(preferences.getString(WEBVIEW_ENTRY, null))

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            ListPreference(screen.context).apply {
                key = WEBVIEW_ENTRY
                title = "来源 WebView 打开页面"
                entries = arrayOf("账号登录（HTTPS）", "站点安全验证（HTTPS 榜单）")
                entryValues = arrayOf("login", VALIDATION_ENTRY)
                setDefaultValue("login")
                summary = "%s\n若热门榜提示验证，切换到站点安全验证，打开来源 WebView 并手动完成站点显示的验证。" +
                    "关闭后切回账号登录，从 HTTPS 登录页登录，再返回刷新热门榜。若出现 HTTP 登录页，请关闭页面，勿输入账号密码。"
            },
        )
    }

    private val diagnostics = if (BuildConfig.DEBUG) WenkuDiagnostics { Log.d(WenkuDiagnostics.TAG, it) } else null

    // The host's Cloudflare interceptor may start an automated background WebView. This source
    // only allows user-initiated interaction in the host WebView. Preserve the host cookie jar.
    override val client = super.client.newBuilder().apply {
        interceptors().removeAll { it.javaClass.name == "eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor" }
        interceptors().removeAll { it.javaClass.name == "okhttp3.logging.HttpLoggingInterceptor" }
        networkInterceptors().removeAll { it.javaClass.name == "okhttp3.logging.HttpLoggingInterceptor" }
        diagnostics?.let { addInterceptor(it::cover) }
    }.build()

    private val pages by lazy { WenkuPages(client, headers, baseUrl.siteUrl(), diagnostics) }
    private val browse by lazy { WenkuBrowse(pages, baseUrl.siteUrl()) }

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

    override suspend fun getPageList(chapter: SChapter): List<Page> = notImplemented()

    override suspend fun getImageUrl(page: Page): String = notImplemented()

    private fun notImplemented(): Nothing = throw UnsupportedOperationException("轻小说文库尚未实现")
}
