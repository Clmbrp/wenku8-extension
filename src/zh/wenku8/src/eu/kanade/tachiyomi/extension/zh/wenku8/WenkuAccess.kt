package eu.kanade.tachiyomi.extension.zh.wenku8

import eu.kanade.tachiyomi.network.await
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit

internal enum class AccessFailure {
    LOGIN_REQUIRED,
    SESSION_EXPIRED,
    CHALLENGE,
    CLOUDFLARE_BLOCK,
    FORBIDDEN,
    RATE_LIMITED,
    NETWORK,
    STRUCTURE,
    HTTP,
}

internal class WenkuAccessException(val failure: AccessFailure, message: String) : Exception(message)

/** Only observes the cookie's presence; the host owns its contents and lifecycle. */
internal class WenkuSession(private val cookieJar: CookieJar) {
    fun hasLoginCookie(url: HttpUrl): Boolean = cookieJar.loadForRequest(url).any { it.name == LOGIN_COOKIE }

    companion object {
        const val LOGIN_COOKIE = "jieqiUserInfo"
    }
}

internal object WenkuErrors {
    fun loginRequired(hadLoginCookie: Boolean): Nothing {
        val kind = if (hadLoginCookie) AccessFailure.SESSION_EXPIRED else AccessFailure.LOGIN_REQUIRED
        val message = if (hadLoginCookie) {
            "站点仍要求登录，会话可能已失效。请使用来源 WebView 的 HTTPS 账号登录入口。"
        } else {
            "站点要求登录，请使用来源 WebView 的 HTTPS 账号登录入口。"
        }
        throw WenkuAccessException(kind, message)
    }

    fun checkStatus(code: Int, challenge: Boolean) {
        if (code == 429) throw WenkuAccessException(AccessFailure.RATE_LIMITED, "访问过于频繁，请稍后再试")
        if (challenge) {
            throw WenkuAccessException(AccessFailure.CHALLENGE, "网站要求安全验证。请在来源设置切换到「站点安全验证」，打开来源 WebView 手动处理。")
        }
        if (code == 403) throw WenkuAccessException(AccessFailure.FORBIDDEN, "网站拒绝访问（403）；请检查站点限制或在 WebView 中查看")
        if (code !in 200..299) throw WenkuAccessException(AccessFailure.HTTP, "网站返回 HTTP $code")
    }

    fun isChallenge(document: Document): Boolean = document.title().contains("just a moment", ignoreCase = true) ||
        document.selectFirst("#challenge-form, input[name=cf-turnstile-response], .cf-challenge") != null

    fun isCloudflareBlock(document: Document): Boolean = document.selectFirst("#cf-error-details [data-translate=block_headline]") != null ||
        (
            document.title().contains("Cloudflare", ignoreCase = true) &&
                document.select("h1, h2").any { it.text().equals("Sorry, you have been blocked", ignoreCase = true) }
            )

    fun checkPage(document: Document, hadLoginCookie: Boolean) {
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
    private val diagnostics: WenkuDiagnostics? = null,
    private val executeRequest: (suspend (Request) -> Response)? = null,
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (diagnostics != null) {
                addNetworkInterceptor { chain ->
                    val outbound = chain.request()
                    diagnostics.sent(outbound)
                    chain.proceed(outbound)
                }
            }
        }
        .build()
    private val session = WenkuSession(client.cookieJar)

    suspend fun get(path: String): Document = load(path)

    suspend fun searchForm(body: RequestBody): Document = load(WenkuBrowse.SEARCH_FORM, body)

    private suspend fun load(path: String, form: RequestBody? = null): Document {
        var url = origin.resolve(path) ?: throw WenkuAccessException(AccessFailure.STRUCTURE, "页面地址无效")
        var body = form
        var stage = "redirect"
        try {
            repeat(6) { count ->
                stage = "redirect"
                if (!url.hasWenkuOrigin(origin)) {
                    throw WenkuAccessException(AccessFailure.STRUCTURE, "页面跳转到未验证的站点")
                }
                stage = "session"
                val hadLoginCookie = session.hasLoginCookie(url)
                diagnostics?.session(url, hadLoginCookie)
                stage = "request"
                val request = Request.Builder().url(url).headers(headers)
                val currentBody = body
                if (currentBody == null) {
                    request.cacheControl(CacheControl.FORCE_NETWORK)
                } else {
                    request.post(currentBody)
                }
                val outbound = request.build()
                val response = executeRequest?.invoke(outbound) ?: client.newCall(outbound).await()
                response.use {
                    val challengeHeader = response.header("cf-mitigated")?.equals("challenge", true) == true
                    diagnostics?.response(url, response.code, challengeHeader)
                    stage = "response"
                    if (challengeHeader || response.code == 429) WenkuErrors.checkStatus(response.code, challengeHeader)
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        stage = "redirect"
                        val location = response.header("Location")
                        val next = location?.let(url::resolve)
                            ?: throw WenkuAccessException(AccessFailure.STRUCTURE, "页面跳转地址无效")
                        diagnostics?.redirect(next)
                        // A same-site login redirect already identifies the response. Avoid a second GET.
                        val isLoginTarget = next.host == origin.host && next.encodedPath == "/login.php" &&
                            ((next.scheme == "http" && next.port == 80) || (next.scheme == "https" && next.port == origin.port))
                        if (isLoginTarget) {
                            WenkuErrors.loginRequired(hadLoginCookie)
                        }
                        if (body != null) {
                            if (response.code == 307 || response.code == 308) {
                                throw WenkuAccessException(AccessFailure.STRUCTURE, "搜索表单返回了未预期的重定向")
                            }
                            body = null
                        }
                        url = next
                        if (count == 5) throw WenkuAccessException(AccessFailure.STRUCTURE, "页面跳转次数过多")
                        return@repeat
                    }
                    if (response.code != 403) WenkuErrors.checkStatus(response.code, false)
                    stage = "page"
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
                    if (WenkuErrors.isCloudflareBlock(document)) {
                        throw WenkuAccessException(AccessFailure.CLOUDFLARE_BLOCK, "Cloudflare 拒绝了插件的请求（HTTP ${response.code}）。网页已登录时，插件请求仍可能被拦截。")
                    }
                    WenkuErrors.checkStatus(response.code, WenkuErrors.isChallenge(document))
                    WenkuErrors.checkPage(document, hadLoginCookie)
                    diagnostics?.result("page", "HTML")
                    return document
                }
            }
        } catch (e: IOException) {
            diagnostics?.result(stage, AccessFailure.NETWORK.name)
            throw WenkuAccessException(AccessFailure.NETWORK, "网络连接失败或超时，请检查网络后重试")
        } catch (e: WenkuAccessException) {
            diagnostics?.result(stage, e.failure.name)
            throw e
        }
        throw WenkuAccessException(AccessFailure.STRUCTURE, "页面跳转次数过多")
    }

    companion object {
        private const val MAX_HTML_BYTES = 2L * 1024 * 1024
    }
}

internal fun String.siteUrl(): HttpUrl = toHttpUrl()

internal fun HttpUrl.hasWenkuOrigin(origin: HttpUrl): Boolean = isHttps && host == origin.host && port == origin.port &&
    username.isEmpty() && password.isEmpty()
