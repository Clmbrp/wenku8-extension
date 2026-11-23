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
internal object WenkuTextRenderer {
    class Chapter(private val heading: StaticLayout, private val body: StaticLayout, private val pages: List<WenkuLinePage>) {
        val pageCount: Int get() = pages.size

        fun png(index: Int): ByteArray {
            val page = pages.getOrNull(index) ?: throw IOException("阅读页不存在，请重新打开章节")
            val headingHeight = if (index == 0) heading.height + HEADING_GAP else 0
            val bodyTop = MARGIN_Y + headingHeight
            val height = bodyTop + page.height + MARGIN_Y
            if (height !in 1..MAX_HEIGHT) throw IOException("阅读页超出图片尺寸限制")
            val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                if (index == 0) {
                    canvas.save()
                    canvas.translate(MARGIN_X.toFloat(), MARGIN_Y.toFloat())
                    heading.draw(canvas)
                    canvas.restore()
                }
                canvas.save()
                canvas.clipRect(0, bodyTop, WIDTH, bodyTop + page.height)
                canvas.translate(MARGIN_X.toFloat(), (bodyTop - body.getLineTop(page.start)).toFloat())
                body.draw(canvas)
                canvas.restore()

                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("阅读页 PNG 编码失败")
                return output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun prepare(text: WenkuChapterText): Chapter {
        val heading = layout(text.title, 52f, heading = true)
        val body = layout(text.body, 42f, heading = false)
        val lineTops = IntArray(body.lineCount + 1) { body.getLineTop(it) }
        val available = MAX_HEIGHT - MARGIN_Y * 2
        val pages = wenkuPaginate(lineTops, available - heading.height - HEADING_GAP, available)
        return Chapter(heading, body, pages)
    }

    private fun layout(text: String, size: Float, heading: Boolean): StaticLayout {
        val paint = TextPaint().apply {
            color = Color.BLACK
            textSize = size
            typeface = if (heading) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            isAntiAlias = true
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, WIDTH - MARGIN_X * 2)
            .setAlignment(if (heading) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .setLineSpacing(4f, 1.35f)
            .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }
            .build()
    }

    private const val WIDTH = 1_000
    private const val MAX_HEIGHT = 2_000
    private const val MARGIN_X = 62
    private const val MARGIN_Y = 52
    private const val HEADING_GAP = 32
}
