package eu.kanade.tachiyomi.extension.zh.wenku8

import okhttp3.HttpUrl
import okhttp3.Request

/** Redacted phase-2 trace. The source only enables this in Debug builds. */
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

    private fun HttpUrl.forTrace(): String {
        val knownHost = host == "www.wenku8.net" || host == "wenku8.net"
        val safeHost = if (knownHost) host else "<external>"
        val safePath = if (knownHost && encodedPath in SAFE_PATHS) encodedPath else "/<redacted>"
        return "$scheme://$safeHost$safePath"
    }

    companion object {
        const val TAG = "Wenku8Access"
        private val SAFE_PATHS = setOf("/", "/index.php", "/top.php", "/login.php")
    }
}
