package eu.kanade.tachiyomi.extension.zh.wenku8

import okhttp3.HttpUrl
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val WENKU_WEBVIEW_ACTION = "轻小说文库来源页 → 右上角菜单 → 在 WebView 中打开"

/** One short-lived browser destination per source. Reading it never acknowledges recovery. */
internal class WenkuWebViewRecovery(private val origin: HttpUrl, private val clock: TimeSource = TimeSource.Monotonic) {
    private data class Pending(
        val request: HttpUrl?,
        val page: HttpUrl,
        val open: HttpUrl,
        val probe: Boolean,
        val since: TimeMark,
    ) {
        val isChapter = request?.encodedPath?.let(WENKU_CHAPTER_PATH::matches) == true || WENKU_CHAPTER_PATH.matches(page.encodedPath)
    }

    private val home = origin.newBuilder().encodedPath("/").query(null).fragment(null).build()
    private val login = home.resolve("/login.php")!!
    private var pending: Pending? = null

    @Synchronized
    fun entry(): HttpUrl = active()?.open ?: home

    @Synchronized
    fun hasPending(): Boolean = active() != null

    @Synchronized
    fun prepareLogin(): Boolean {
        active()?.let { return it.open == login }
        pending = Pending(null, login, login, false, clock.markNow())
        return true
    }

    @Synchronized
    fun failed(start: HttpUrl, at: HttpUrl, failure: AccessFailure, probe: Boolean) {
        if (failure != AccessFailure.CHALLENGE && failure != AccessFailure.LOGIN_REQUIRED && failure != AccessFailure.SESSION_EXPIRED) return
        val request = browserPage(start) ?: return
        val page = browserPage(at) ?: return
        active()?.let { current ->
            if (probe && !current.probe) return
            // Mihon uses the same chapter API for preloads and chapters explicitly opened by readers.
            if (current.isChapter && current.request != request) return
        }
        val open = if (failure == AccessFailure.CHALLENGE) page else login
        pending = Pending(request, page, open, probe, clock.markNow())
    }

    @Synchronized
    fun succeeded(start: HttpUrl, at: HttpUrl, probe: Boolean, signedIn: Boolean) {
        val current = active() ?: return
        if (current.request == null) {
            if (signedIn) pending = null
            return
        }
        if (probe && !current.probe) return
        val request = browserPage(start) ?: return
        if (request != current.request && request != current.page) return
        val page = browserPage(at) ?: return
        // A search can redirect to a single book. No query data is needed to recover the form.
        if (page == current.page || current.request.encodedPath == WenkuBrowse.SEARCH_FORM || (current.open == login && signedIn)) {
            pending = null
        }
    }

    private fun active(): Pending? {
        if (pending?.since?.elapsedNow()?.let { it >= 10.minutes } == true) pending = null
        return pending
    }

    private fun browserPage(url: HttpUrl): HttpUrl? {
        if (!url.hasWenkuOrigin(origin) || url.queryParameterNames.any { it.startsWith("__cf", ignoreCase = true) }) return null
        return when (val path = url.encodedPath) {
            WenkuBrowse.SEARCH, WenkuBrowse.SEARCH_FORM -> home.resolve(WenkuBrowse.SEARCH_FORM)
            WenkuBrowse.TOPLIST -> {
                val sort = url.queryParameter("sort")?.takeIf { it == "allvisit" || it == "lastupdate" } ?: return null
                val page = (url.queryParameter("page") ?: "1").toIntOrNull()?.takeIf { it > 0 } ?: return null
                home.resolve(WenkuBrowse.rankingPath(sort, page))
            }
            else -> if (path in LANDING_PATHS || WenkuParser.BOOK_PATH.matches(path) ||
                WenkuParser.CATALOG_PATH.matches(path) || WENKU_CHAPTER_PATH.matches(path)
            ) {
                home.resolve(path)
            } else {
                null
            }
        }
    }

    companion object {
        private val LANDING_PATHS = setOf("/", "/index.php", "/top.php")
    }
}
