package eu.kanade.tachiyomi.extension.zh.wenku8

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.io.IOException

internal data class WenkuLinePage(val start: Int, val end: Int, val height: Int)

internal data class WenkuReadingStyle(
    val width: Int = 1_000,
    val maxHeight: Int = 2_000,
    val horizontalMargin: Int = 62,
    val verticalMargin: Int = 52,
    val headingGap: Int = 32,
    val headingSize: Float = 52f,
    val bodySize: Float = 42f,
    val lineSpacing: Float = 1.35f,
    val backgroundColor: Int = Color.WHITE,
    val textColor: Int = Color.BLACK,
)

/** Line tops include the bottom of the final line. Each line belongs to exactly one page. */
internal fun wenkuPaginate(lineTops: IntArray, firstHeight: Int, pageHeight: Int): List<WenkuLinePage> {
    val pages = mutableListOf<WenkuLinePage>()
    var start = 0
    while (start < lineTops.lastIndex) {
        val available = if (pages.isEmpty()) firstHeight else pageHeight
        var end = start
        while (end < lineTops.lastIndex && lineTops[end + 1] - lineTops[start] <= available) {
            end++
        }
        if (end == start) throw IOException("章节文字超出阅读页的排版范围")
        pages += WenkuLinePage(start, end, lineTops[end] - lineTops[start])
        start = end
    }
    if (pages.isEmpty()) throw IOException("章节没有可渲染的文字")
    return pages
}

/**
 * StaticLayout and Canvas drawing adapted from the Apache-2.0 BiliNovel TextInterceptor.
 * Keep the original paragraph layout and clip at line boundaries instead of laying out slices again.
 */
internal class WenkuTextRenderer(internal val style: WenkuReadingStyle = WenkuReadingStyle()) {
    inner class Chapter(private val heading: StaticLayout?, private val body: StaticLayout, private val pages: List<WenkuLinePage>) {
        val pageCount: Int get() = pages.size

        fun png(index: Int): ByteArray {
            val page = pages.getOrNull(index) ?: throw IOException("阅读页不存在，请重新打开章节")
            val pageHeading = heading.takeIf { index == 0 }
            val bodyTop = style.verticalMargin + headingHeight(pageHeading)
            val height = bodyTop + page.height + style.verticalMargin
            if (height !in 1..style.maxHeight) throw IOException("阅读页超出图片尺寸限制")
            val bitmap = Bitmap.createBitmap(style.width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(style.backgroundColor)
                drawHeading(canvas, pageHeading)
                canvas.save()
                canvas.clipRect(0, bodyTop, style.width, bodyTop + page.height)
                canvas.translate(style.horizontalMargin.toFloat(), (bodyTop - body.getLineTop(page.start)).toFloat())
                body.draw(canvas)
                canvas.restore()

                return wenkuEncodePng(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun prepare(text: WenkuChapterText): Chapter {
        val heading = heading(text.title)
        val body = layout(text.body, style.bodySize, heading = false)
        val lineTops = IntArray(body.lineCount + 1) { body.getLineTop(it) }
        val available = style.maxHeight - style.verticalMargin * 2
        val pages = wenkuPaginate(lineTops, available - headingHeight(heading), available)
        return Chapter(heading, body, pages)
    }

    fun heading(title: String): StaticLayout? = title.takeIf(String::isNotBlank)?.let { layout(it, style.headingSize, heading = true) }

    fun headingHeight(heading: StaticLayout?): Int = heading?.let { it.height + style.headingGap } ?: 0

    fun drawHeading(canvas: Canvas, heading: StaticLayout?) {
        if (heading == null) return
        canvas.save()
        canvas.translate(style.horizontalMargin.toFloat(), style.verticalMargin.toFloat())
        heading.draw(canvas)
        canvas.restore()
    }

    private fun layout(text: String, size: Float, heading: Boolean): StaticLayout {
        val paint = TextPaint().apply {
            color = this@WenkuTextRenderer.style.textColor
            textSize = size
            typeface = if (heading) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            isAntiAlias = true
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, style.width - style.horizontalMargin * 2)
            .setAlignment(if (heading) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .setLineSpacing(4f, style.lineSpacing)
            .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }
            .build()
    }
}

internal fun wenkuEncodePng(bitmap: Bitmap): ByteArray {
    val output = ByteArrayOutputStream()
    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("阅读页 PNG 编码失败")
    return output.toByteArray()
}
