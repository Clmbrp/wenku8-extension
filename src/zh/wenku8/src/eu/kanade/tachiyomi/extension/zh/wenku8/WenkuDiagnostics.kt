package eu.kanade.tachiyomi.extension.zh.wenku8

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Redacted trace. The source only enables this in Debug builds. */
internal class WenkuDiagnostics(private val log: (String) -> Unit) {
    fun session(url: HttpUrl, hasLoginCookie: Boolean) {
        log("session url=${url.forTrace()} loginCookieVisible=$hasLoginCookie")
    }

    // A network interceptor sees the request after OkHttp has applied its CookieJar.
    fun sent(request: Request) {
        val hasLoginCookie = request.headers("Cookie").any { header ->
            header.split(';').any { it.substringBefore('=', "").trim() == WenkuSession.LOGIN_COOKIE }
        }
        log("request url=${request.url.forTrace()} loginCookieSent=$hasLoginCookie")
    }

    fun response(url: HttpUrl, status: Int, challengeHeader: Boolean) {
        log("response url=${url.forTrace()} status=$status challengeHeader=$challengeHeader")
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

    companion object {
        const val TAG = "Wenku8Access"
        private val SAFE_PATHS = setOf("/", "/index.php", "/top.php", "/login.php", "/so.php", "/modules/article/search.php", "/modules/article/toplist.php")
    }
}
