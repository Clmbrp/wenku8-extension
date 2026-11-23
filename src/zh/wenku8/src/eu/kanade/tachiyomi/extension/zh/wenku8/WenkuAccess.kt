package eu.kanade.tachiyomi.extension.zh.wenku8

import keiyoushi.network.get
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit

internal enum class AccessFailure {
    LOGIN_REQUIRED,
    SESSION_EXPIRED,
    CHALLENGE,
    FORBIDDEN,
    RATE_LIMITED,
    NETWORK,
    STRUCTURE,
    HTTP,
}

internal class WenkuAccessException(val failure: AccessFailure, message: String) : Exception(message)

/** Only observes the cookie's presence; the host owns its contents and lifecycle. */
internal class WenkuSession(private val cookieJar: CookieJar) {
    fun hasLoginCookie(url: HttpUrl): Boolean = cookieJar.loadForRequest(url).any { it.name == "jieqiUserInfo" }
}

internal object WenkuErrors {
    fun loginRequired(hadLoginCookie: Boolean): Nothing {
        val kind = if (hadLoginCookie) AccessFailure.SESSION_EXPIRED else AccessFailure.LOGIN_REQUIRED
        val message = if (hadLoginCookie) "登录可能已失效，请在来源的 WebView 中重新登录" else "请在来源的 WebView 中登录"
        throw WenkuAccessException(kind, message)
    }

    fun checkStatus(code: Int, challenge: Boolean) {
        if (code == 429) throw WenkuAccessException(AccessFailure.RATE_LIMITED, "访问过于频繁，请稍后再试")
        if (challenge) throw WenkuAccessException(AccessFailure.CHALLENGE, "网站要求验证，请在来源的 WebView 中手动处理")
        if (code == 403) throw WenkuAccessException(AccessFailure.FORBIDDEN, "网站拒绝访问（403）；请检查站点限制或在 WebView 中查看")
        if (code !in 200..299) throw WenkuAccessException(AccessFailure.HTTP, "网站返回 HTTP $code")
    }

    fun isChallenge(document: Document): Boolean {
        val title = document.title().lowercase()
        return title.contains("just a moment") || title.contains("attention required") ||
            document.selectFirst("#challenge-form, input[name=cf-turnstile-response], .cf-challenge") != null
    }

    fun checkPage(document: Document, hadLoginCookie: Boolean) {
        checkStatus(200, isChallenge(document))
        val isLogin = document.location().toHttpUrl().encodedPath == "/login.php" ||
            document.select("form").any {
                it.selectFirst("input[name=password]") != null && it.selectFirst("input[name=username]") != null
            }
        if (isLogin) {
            loginRequired(hadLoginCookie)
        }
    }
}

/** Requests only the configured origin. Redirects are checked before the next request. */
internal class WenkuPages(
    client: OkHttpClient,
    private val headers: Headers,
    private val origin: HttpUrl,
    private val request: (suspend (HttpUrl) -> Response)? = null,
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val session = WenkuSession(client.cookieJar)

    suspend fun get(path: String): Document {
        var url = origin.resolve(path) ?: throw WenkuAccessException(AccessFailure.STRUCTURE, "页面地址无效")
        val hadLoginCookie = session.hasLoginCookie(origin)
        try {
            repeat(6) { count ->
                if (url.scheme != "https" || url.host != origin.host || url.port != origin.port) {
                    throw WenkuAccessException(AccessFailure.STRUCTURE, "页面跳转到未验证的站点")
                }
                (request?.invoke(url) ?: client.get(url, headers, cacheControl = CacheControl.FORCE_NETWORK, ensureSuccess = false)).use { response ->
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        val location = response.header("Location")
                        val next = location?.let(url::resolve)
                            ?: throw WenkuAccessException(AccessFailure.STRUCTURE, "页面跳转地址无效")
                        // The site can point unauthenticated users at an HTTP login URL. Never
                        // follow it: direct the user to the source's HTTPS WebView entry instead.
                        if (next.scheme == "http" && next.host == origin.host && next.port == 80 && next.encodedPath == "/login.php") {
                            WenkuErrors.loginRequired(hadLoginCookie)
                        }
                        url = next
                        if (count == 5) throw WenkuAccessException(AccessFailure.STRUCTURE, "页面跳转次数过多")
                        return@repeat
                    }
                    val challengeHeader = response.header("cf-mitigated")?.equals("challenge", true) == true
                    if (response.code != 403) WenkuErrors.checkStatus(response.code, challengeHeader)
                    if (challengeHeader) WenkuErrors.checkStatus(response.code, true)
                    val type = response.header("Content-Type")?.toMediaTypeOrNull()
                    if (type != null && type.type != "text" && type.toString().substringBefore(';') != "application/xhtml+xml") {
                        WenkuErrors.checkStatus(response.code, false)
                        throw WenkuAccessException(AccessFailure.STRUCTURE, "网站返回了非 HTML 内容")
                    }
                    val source = response.body.source()
                    if (source.request(MAX_HTML_BYTES + 1)) {
                        throw WenkuAccessException(AccessFailure.STRUCTURE, "页面过大，无法解析")
                    }
                    val bytes = source.readByteArray()
                    val charset = type?.charset(null)?.name()
                    val document = Jsoup.parse(bytes.inputStream(), charset, url.toString())
                    WenkuErrors.checkStatus(response.code, WenkuErrors.isChallenge(document))
                    WenkuErrors.checkPage(document, hadLoginCookie)
                    return document
                }
            }
        } catch (e: IOException) {
            throw WenkuAccessException(AccessFailure.NETWORK, "网络连接失败或超时，请检查网络后重试")
        }
        throw WenkuAccessException(AccessFailure.STRUCTURE, "页面跳转次数过多")
    }

    companion object {
        private const val MAX_HTML_BYTES = 2L * 1024 * 1024
    }
}

internal fun String.siteUrl(): HttpUrl = toHttpUrl()
