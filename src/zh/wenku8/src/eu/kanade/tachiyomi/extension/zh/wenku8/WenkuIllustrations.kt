package eu.kanade.tachiyomi.extension.zh.wenku8

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal class WenkuIllustrations(
    client: OkHttpClient,
    private val pages: WenkuPages,
    private val origin: HttpUrl,
    private val executeExternal: (suspend (Request) -> Response)? = null,
) {
    private val anonymous = client.newBuilder()
        .dispatcher(Dispatcher())
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .dns(
            Dns { host ->
                client.dns.lookup(host).filter(::publicImageAddress)
                    .ifEmpty { throw UnknownHostException("插图域名未解析到公共地址") }
            },
        )
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(rawUrl: String): ByteArray {
        var url = rawUrl.toHttpUrlOrNull() ?: throw IOException("插图地址无效")
        repeat(4) {
            if (!url.isPublicImageUrl()) throw IOException("插图地址不符合 HTTPS 访问策略")
            val request = Request.Builder().url(url).header("Accept", "image/*").build()
            val result = if (url.hasWenkuOrigin(origin)) {
                pages.illustration(request, ::read)
            } else {
                executeExternal?.invoke(request)?.use(::read) ?: wenkuReadResponse(anonymous.newCall(request), ::read)
            }
            when (result) {
                is ImageResponse.Bytes -> return result.data
                is ImageResponse.Redirect -> url = result.url
            }
        }
        throw IOException("插图跳转次数过多")
    }

    private fun read(response: Response): ImageResponse {
        if (response.code in listOf(301, 302, 303, 307, 308)) {
            val target = response.header("Location")?.let(response.request.url::resolve)
                ?: throw IOException("插图跳转地址无效")
            return ImageResponse.Redirect(target)
        }
        if (!response.isSuccessful) throw IOException("插图加载失败（HTTP ${response.code}）")
        val type = response.body.contentType()
        if (type != null && type.type != "image" && type.toString().substringBefore(';') != "application/octet-stream") {
            throw IOException("插图服务器返回了非图片内容")
        }
        val source = response.body.source()
        if (response.body.contentLength() > MAX_DOWNLOAD_BYTES || source.request(MAX_DOWNLOAD_BYTES + 1)) {
            throw IOException("插图文件超出大小限制")
        }
        val data = source.readByteArray()
        if (data.isEmpty()) throw IOException("插图响应为空")
        return ImageResponse.Bytes(data)
    }

    private sealed interface ImageResponse {
        data class Bytes(val data: ByteArray) : ImageResponse
        data class Redirect(val url: HttpUrl) : ImageResponse
    }

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 12L * 1_024 * 1_024
    }
}

private fun HttpUrl.isPublicImageUrl(): Boolean = isHttps && port == 443 && username.isEmpty() && password.isEmpty() &&
    '.' in host && host.any(Char::isLetter) && ':' !in host && !host.trimEnd('.').endsWith(".localhost") &&
    !host.trimEnd('.').endsWith(".local") && !host.trimEnd('.').endsWith(".internal") && host != WenkuPageRef.HOST

private fun publicImageAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) {
        return false
    }
    val bytes = address.address
    val first = bytes[0].toInt() and 255
    if (address is Inet6Address) return first and 0xE0 == 0x20
    return first in 1..223 && (first != 100 || bytes[1].toInt() and 0xC0 != 0x40)
}

internal data class WenkuImageSize(val width: Int, val height: Int)

private const val ILLUSTRATION_SIDE_MARGIN = 62

internal fun wenkuFitImage(width: Int, height: Int, maxWidth: Int, maxHeight: Int): WenkuImageSize {
    if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) throw IOException("插图尺寸无效")
    val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
    return WenkuImageSize((width * scale).roundToInt().coerceIn(1, maxWidth), (height * scale).roundToInt().coerceIn(1, maxHeight))
}

internal fun wenkuImageSampleSize(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) throw IOException("插图尺寸无效")
    var sample = 1
    while (true) {
        val reducedWidth = (width.toLong() + sample - 1) / sample
        val reducedHeight = (height.toLong() + sample - 1) / sample
        if (reducedWidth <= 4_096 && reducedHeight <= 4_096 && reducedWidth * reducedHeight <= 4_000_000) return sample
        sample *= 2
    }
}

/** Frames one illustration on demand, following the Apache-2.0 BiliNovel App page design. */
internal fun wenkuIllustrationPng(data: ByteArray, title: String, renderer: WenkuTextRenderer): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    val style = renderer.style
    val heading = renderer.heading(title)
    // Untitled figures keep just a narrow white edge. Title spacing matches the text reader.
    val margin = if (heading == null) 12 else renderer.titleMargin
    val top = margin + renderer.headingHeight(heading)
    val size = wenkuFitImage(bounds.outWidth, bounds.outHeight, style.width - ILLUSTRATION_SIDE_MARGIN * 2, style.maxHeight - top - margin)
    val options = BitmapFactory.Options().apply {
        inSampleSize = wenkuImageSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val original = BitmapFactory.decodeByteArray(data, 0, data.size, options)
        ?: throw IOException("无法解码插图")
    try {
        val bitmap = Bitmap.createBitmap(style.width, top + size.height + margin, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(style.backgroundColor)
            renderer.drawHeading(canvas, heading)
            val left = (style.width - size.width) / 2
            canvas.drawBitmap(original, null, Rect(left, top, left + size.width, top + size.height), Paint(Paint.FILTER_BITMAP_FLAG))
            return wenkuEncodePng(bitmap)
        } finally {
            bitmap.recycle()
        }
    } finally {
        original.recycle()
    }
}
