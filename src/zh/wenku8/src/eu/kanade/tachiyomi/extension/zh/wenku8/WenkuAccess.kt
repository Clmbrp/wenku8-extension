package eu.kanade.tachiyomi.extension.zh.wenku8

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

internal enum class AccessFailure {
    LOGIN_REQUIRED,
    SESSION_EXPIRED,
    CHALLENGE,
    CLOUDFLARE_BLOCK,
    FORBIDDEN,
    RATE_LIMITED,
    OFFLINE,
    NETWORK,
    STRUCTURE,
    HTTP,
}

internal class WenkuAccessException(val failure: AccessFailure, message: String) : Exception(message)

/** Only observes the cookie's presence; the host owns its contents and lifecycle. */
internal class WenkuSession(private val cookieJar: CookieJar) {
    fun cookieNames(url: HttpUrl): Set<String> = cookieJar.loadForRequest(url).mapNotNullTo(mutableSetOf()) { it.name.takeIf(TRACKED::contains) }

    companion object {
        const val LOGIN_COOKIE = "jieqiUserInfo"
        private val TRACKED = setOf(LOGIN_COOKIE, "PHPSESSID", "cf_clearance")

        fun cookieNamesFromHeaders(headers: List<String>): Set<String> = headers.asSequence()
            .flatMap { it.splitToSequence(';') }
            .map { it.substringBefore('=', "").trim() }
            .filter(TRACKED::contains)
            .toSet()
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
        if (challenge) challengeRequired()
        if (code == 403) throw WenkuAccessException(AccessFailure.FORBIDDEN, "网站拒绝访问（403）；请检查站点限制或在 WebView 中查看")
        if (code !in 200..299) throw WenkuAccessException(AccessFailure.HTTP, "网站返回 HTTP $code")
    }

    fun challengeRequired(): Nothing = throw WenkuAccessException(
        AccessFailure.CHALLENGE,
        "网站仍要求安全验证。请从「站点安全验证」打开来源 WebView，保持登录并按网页提示操作。",
    )

    fun isChallenge(document: Document): Boolean = CHALLENGE_TITLE.matches(document.title()) ||
        document.selectFirst("#challenge-form, #cf-challenge-running, .cf-challenge") != null

    fun showsSignedIn(document: Document, origin: HttpUrl): Boolean = document.select("a[href*=logout.php]").any { link ->
        link.absUrl("href").toHttpUrlOrNull()?.let { it.hasWenkuOrigin(origin) && it.encodedPath == "/logout.php" } == true
    }

    fun isCloudflareBlock(document: Document): Boolean = document.selectFirst("#cf-error-details [data-translate=block_headline]") != null ||
        (
            document.title().contains("Cloudflare", ignoreCase = true) &&
                document.select("h1, h2").any { it.text().equals("Sorry, you have been blocked", ignoreCase = true) }
            )

    fun isRateLimitNotice(document: Document): Boolean = RATE_LIMIT_NOTICE.matches(document.title()) ||
        document.select("#msgbox, .msgbox, .blockcontent, #content, h1, h2, #content > div, #content p, #content td, body > p")
            .any { RATE_LIMIT_NOTICE.matches(it.ownText()) }

    fun checkPage(document: Document, hadLoginCookie: Boolean) {
        val isLogin = document.location().toHttpUrl().encodedPath == "/login.php" ||
            document.select("form").any {
                it.selectFirst("input[name=password]") != null && it.selectFirst("input[name=username]") != null
            }
        if (isLogin) {
            loginRequired(hadLoginCookie)
        }
    }

    private const val ACTION = "(?:访问|操作|搜索|请求)"
    private val CHALLENGE_TITLE = Regex("Just a moment(?:\\.{1,3}|…)?", RegexOption.IGNORE_CASE)
    private const val TOO_FREQUENT = """(?:(?:您(?:的)?)?$ACTION(?:过于|太)?[频頻]繁|请(?:勿|不要)[频頻]繁$ACTION)"""
    private const val SEARCH_INTERVAL = """(?:两次)?搜索(?:的)?(?:时间)?间隔(?:太短|过短|不得少于\s*\d+\s*秒)"""
    private const val WAIT_SECONDS = """等待\s*\d+\s*秒(?:后)?再(?:试|$ACTION)"""
    private const val WAIT_NOTICE = """(?:稍[后候](?:再(?:试|$ACTION))?|$WAIT_SECONDS)"""
    private val RATE_LIMIT_NOTICE = Regex(
        """(?:(?:对不起|抱歉)[，,！!：:\s]*)?(?:(?:$TOO_FREQUENT|$SEARCH_INTERVAL)(?:[，,！!。\s]+请?$WAIT_NOTICE)?|请$WAIT_SECONDS)[！!。.]?""",
    )
}

/** Requests only the configured origin. Redirects are checked before the next request. */
internal class WenkuPages(
    client: OkHttpClient,
    private val headers: Headers,
    private val origin: HttpUrl,
    private val diagnostics: WenkuDiagnostics? = null,
    private val policy: WenkuRequestPolicy = WenkuRequestPolicy(),
    private val hasNetwork: () -> Boolean? = { null },
    hostCloudflare: Interceptor? = null,
    private val awaitCall: suspend (Call) -> Response = { it.await() },
    private val executeRequest: (suspend (Request) -> Response)? = null,
) {
    private val expectedUserAgent = headers["User-Agent"]
    private val client = client.newBuilder()
        // Serve chapter recovery even when the reader's image dispatcher is full.
        .dispatcher(Dispatcher())
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (diagnostics != null) {
                addNetworkInterceptor { chain ->
                    val outbound = chain.request()
                    diagnostics.sent(outbound, expectedUserAgent)
                    chain.proceed(outbound)
                }
            }
        }
        .build()
    private val htmlRecoveryClient = hostCloudflare?.let { host ->
        client.newBuilder().apply {
            interceptors().add(0, host)
            // The host may consume a challenge and throw IOException instead of returning it.
            interceptors().add(
                1,
                Interceptor { chain ->
                    val request = chain.request()
                    chain.proceed(request).also { response ->
                        val challenged = response.header("cf-mitigated").equals("challenge", ignoreCase = true)
                        request.tag(ChallengeAttempt::class.java)?.challenged = challenged
                        if (challenged) diagnostics?.result("hostWebView", "CHALLENGE_RECEIVED")
                    }
                },
            )
        }.build()
    }
    private val session = WenkuSession(client.cookieJar)

    suspend fun get(path: String, retryOnRateLimit: Boolean = true): Document {
        val url = origin.resolve(path) ?: throw WenkuAccessException(AccessFailure.STRUCTURE, "页面地址无效")
        checkNetwork(url)
        if (!retryOnRateLimit && policy.isCoolingDown()) {
            diagnostics?.result("throttle", AccessFailure.RATE_LIMITED.name)
            throw WenkuAccessException(AccessFailure.RATE_LIMITED, "网站正在限流，请稍后再检查")
        }
        return policy.page(url, null, retryOnRateLimit) { load(url, retryOnRateLimit = retryOnRateLimit) }
    }

    suspend fun searchForm(keyword: String, body: RequestBody): Document {
        val url = origin.newBuilder().encodedPath(WenkuBrowse.SEARCH_FORM).build()
        checkNetwork(url)
        return policy.page(url, keyword) { load(url, body) }
    }

    suspend fun <T> illustration(request: Request, read: (Response) -> T): T {
        require(request.url.hasWenkuOrigin(origin))
        checkNetwork(request.url)
        return policy.exchange(searchResults = false, retryOnRateLimit = false) {
            checkNetwork(request.url)
            withResponse(request.newBuilder().headers(headers).header("Accept", "image/*").build()) { response ->
                if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
                    WenkuErrors.checkStatus(response.code, true)
                }
                if (response.code == 429) policy.limited(response.header("Retry-After"), response.header("Date"))
                read(response)
            }
        }
    }

    private suspend fun load(start: HttpUrl, form: RequestBody? = null, retryOnRateLimit: Boolean = true): Document {
        var url = start
        var body = form
        var stage = "redirect"
        try {
            repeat(6) { count ->
                stage = "redirect"
                if (!url.hasWenkuOrigin(origin)) {
                    throw WenkuAccessException(AccessFailure.STRUCTURE, "页面跳转到未验证的站点")
                }
                stage = "throttle"
                val document = policy.exchange(searchResults = url.encodedPath == WenkuBrowse.SEARCH, retryOnRateLimit = retryOnRateLimit) {
                    checkNetwork(url)
                    stage = "session"
                    val names = session.cookieNames(url)
                    val hadLoginCookie = WenkuSession.LOGIN_COOKIE in names
                    diagnostics?.session(url, names)
                    stage = "request"
                    val request = Request.Builder().url(url).headers(headers)
                    val currentBody = body
                    if (currentBody == null) {
                        request.cacheControl(CacheControl.FORCE_NETWORK)
                    } else {
                        request.post(currentBody)
                    }
                    withResponse(request.build(), recoverChallenge = currentBody == null) { response ->
                        val challengeHeader = response.header("cf-mitigated")?.equals("challenge", true) == true
                        diagnostics?.response(url, response.code, challengeHeader)
                        stage = "response"
                        if (challengeHeader) WenkuErrors.checkStatus(response.code, true)
                        if (response.code == 429) policy.limited(response.header("Retry-After"), response.header("Date"))
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
                            return@withResponse null
                        }
                        if (response.code != 403 && response.code != 503) WenkuErrors.checkStatus(response.code, false)
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
                        if (WenkuErrors.isChallenge(document)) WenkuErrors.checkStatus(response.code, true)
                        if (response.isSuccessful) WenkuErrors.checkPage(document, hadLoginCookie)
                        if (WenkuErrors.isRateLimitNotice(document)) policy.limited(response.header("Retry-After"), response.header("Date"))
                        WenkuErrors.checkStatus(response.code, false)
                        diagnostics?.acceptedHtml(WenkuErrors.showsSignedIn(document, origin))
                        document
                    }
                }
                if (document != null) return document
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            checkNetwork(url)
            diagnostics?.transport(
                when (e) {
                    is UnknownHostException -> "DNS"
                    is SocketTimeoutException -> "TIMEOUT"
                    is ConnectException -> "CONNECT"
                    is SSLException -> "TLS"
                    else -> "IO"
                },
            )
            diagnostics?.result(stage, AccessFailure.NETWORK.name)
            throw WenkuAccessException(AccessFailure.NETWORK, "网络连接失败或超时，请检查网络后重试")
        } catch (e: WenkuAccessException) {
            diagnostics?.result(stage, e.failure.name)
            throw e
        }
        throw WenkuAccessException(AccessFailure.STRUCTURE, "页面跳转次数过多")
    }

    private fun checkNetwork(url: HttpUrl) {
        if (hasNetwork() != false) return
        diagnostics?.let { trace ->
            trace.transport("OFFLINE")
            if (url.hasWenkuOrigin(origin)) {
                runCatching { session.cookieNames(url) }.getOrNull()?.let { trace.session(url, it) }
            }
        }
        throw WenkuAccessException(AccessFailure.OFFLINE, "设备未连接网络，联网后可以重试")
    }

    private suspend fun <T> withResponse(request: Request, recoverChallenge: Boolean = false, read: (Response) -> T): T {
        executeRequest?.let { return it(request).use(read) }
        val recoveryClient = htmlRecoveryClient?.takeIf { recoverChallenge }
        if (recoveryClient == null) return wenkuReadResponse(client.newCall(request), read, awaitCall)

        val attempt = ChallengeAttempt()
        val tagged = request.newBuilder().tag(ChallengeAttempt::class.java, attempt).build()
        try {
            return wenkuReadResponse(recoveryClient.newCall(tagged), read, awaitCall)
        } catch (error: IOException) {
            if (!attempt.challenged) throw error
            currentCoroutineContext().ensureActive()
            checkNetwork(request.url)
            diagnostics?.result("hostWebView", "NEEDS_MANUAL_VERIFICATION")
            WenkuErrors.challengeRequired()
        }
    }

    private class ChallengeAttempt {
        @Volatile var challenged = false
    }

    companion object {
        private const val MAX_HTML_BYTES = 2L * 1024 * 1024
    }
}

internal suspend fun <T> wenkuReadResponse(call: Call, read: (Response) -> T, awaitCall: suspend (Call) -> Response = { it.await() }): T = coroutineScope {
    val finished = AtomicBoolean()
    // The host await() cancels up to response delivery. Keep cancellation active while reading the body.
    val cancelCall = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) call.cancel()
        }
    }
    try {
        awaitCall(call).use(read)
    } finally {
        finished.set(true)
        cancelCall.cancel()
    }
}

internal fun String.siteUrl(): HttpUrl = toHttpUrl()

internal fun HttpUrl.hasWenkuOrigin(origin: HttpUrl): Boolean = isHttps && host == origin.host && port == origin.port &&
    username.isEmpty() && password.isEmpty()
