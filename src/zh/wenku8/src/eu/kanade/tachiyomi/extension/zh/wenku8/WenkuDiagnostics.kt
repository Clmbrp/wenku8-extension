package eu.kanade.tachiyomi.extension.zh.wenku8

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Redacted trace. The source only enables this in Debug builds. */
internal class WenkuDiagnostics(
    private val webViewCookies: ((HttpUrl) -> Set<String>?)? = null,
    private val log: (String) -> Unit,
) {
    private var lastTransport: String? = null

    fun session(url: HttpUrl, names: Set<String>) {
        val webView = webViewCookies?.invoke(url)
        val agreement = webView?.let { it == names }
        log("session url=${url.forTrace()} jarNames=${names.forTrace()} webViewNames=${webView?.forTrace() ?: "unknown"} cookieNamesMatch=$agreement")
    }

    // A network interceptor sees the request after OkHttp has applied its CookieJar.
    fun sent(request: Request, expectedUserAgent: String? = null) {
        val names = WenkuSession.cookieNamesFromHeaders(request.headers("Cookie"))
        val userAgentMatches = expectedUserAgent?.let { it == request.header("User-Agent") }
        log("request url=${request.url.forTrace()} sentNames=${names.forTrace()} sourceUserAgentMatches=$userAgentMatches")
    }

    fun response(url: HttpUrl, status: Int, challengeHeader: Boolean) {
        transport("HTTP")
        log("response url=${url.forTrace()} status=$status challengeHeader=$challengeHeader")
    }

    @Synchronized
    fun transport(state: String) {
        if (state == lastTransport) return
        log("transport from=${lastTransport ?: "UNKNOWN"} to=$state")
        lastTransport = state
    }

    fun acceptedHtml(showsSignedIn: Boolean) {
        log("result stage=page kind=HTML siteLogin=${if (showsSignedIn) "SIGNED_IN" else "UNCONFIRMED"}")
    }

    fun redirect(url: HttpUrl) {
        log("redirect target=${url.forTrace()}")
    }

    fun result(stage: String, kind: String) {
        log("result stage=$stage kind=$kind")
    }

    fun search(page: Int, kind: String, usedNextLink: Boolean) {
        log("search page=$page kind=$kind usedNextLink=$usedNextLink")
    }

    fun cover(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!WenkuCover.isKnownUrl(url)) return chain.proceed(request)
        val address = "${url.scheme}://${url.host}${url.encodedPath}"
        return try {
            val response = chain.proceed(request)
            val finalUrl = response.request.url
            val finalTarget = if (WenkuCover.isKnownUrl(finalUrl)) finalUrl.scheme else "<other>"
            val isImage = response.body.contentType()?.type == "image"
            log("cover url=$address status=${response.code} image=$isImage finalTarget=$finalTarget")
            response
        } catch (e: IOException) {
            log("cover url=$address kind=NETWORK")
            throw e
        }
    }

    private fun HttpUrl.forTrace(): String {
        val knownHost = host == "www.wenku8.net" || host == "wenku8.net"
        val safeHost = if (knownHost) host else "<external>"
        val safePath = if (knownHost && encodedPath in SAFE_PATHS) encodedPath else "/<redacted>"
        return "$scheme://$safeHost$safePath"
    }

    private fun Set<String>.forTrace(): String = sorted().joinToString(separator = ",").ifEmpty { "none" }

    companion object {
        const val TAG = "Wenku8Access"
        private val SAFE_PATHS = setOf("/", "/index.php", "/top.php", "/login.php", "/so.php", "/modules/article/search.php", "/modules/article/toplist.php")
    }
}
