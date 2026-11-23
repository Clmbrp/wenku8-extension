package eu.kanade.tachiyomi.extension.zh.wenku8

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LineHeightSpan
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
internal class WenkuTextRenderer(internal val style: WenkuReadingStyle = WenkuReadingStyle(), legacy: Boolean = false) : WenkuPageGraphics {
    private val edgeMargin = if (legacy) 52 else 20
    internal val titleMargin = 52

    inner class Chapter(private val heading: StaticLayout?, private val body: StaticLayout, private val pages: List<WenkuLinePage>) : WenkuTextPages {
        override val pageCount: Int get() = pages.size

        override fun png(index: Int): ByteArray {
            val page = pages.getOrNull(index) ?: throw IOException("阅读页不存在，请重新打开章节")
            val pageHeading = heading.takeIf { index == 0 }
            val bodyTop = topMargin(pageHeading) + headingHeight(pageHeading)
            val height = bodyTop + page.height + edgeMargin
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

    override fun prepare(text: WenkuChapterText): Chapter {
        val heading = heading(text.title)
        val body = layout(wenkuSpacedParagraphs(text.body, style.paragraphGap), style.bodySize, heading = false)
        val lineTops = IntArray(body.lineCount + 1) { body.getLineTop(it) }
        val available = style.maxHeight - edgeMargin * 2
        val firstHeight = style.maxHeight - topMargin(heading) - headingHeight(heading) - edgeMargin
        val pages = wenkuPaginate(lineTops, firstHeight, available)
        return Chapter(heading, body, pages)
    }

    override fun illustration(data: ByteArray, title: String): ByteArray = wenkuIllustrationPng(data, title, this)

    fun heading(title: String): StaticLayout? = title.takeIf(String::isNotBlank)?.let { layout(it, style.headingSize, heading = true) }

    fun headingHeight(heading: StaticLayout?): Int = heading?.let { it.height + style.headingGap } ?: 0

    private fun topMargin(heading: StaticLayout?): Int = if (heading == null) edgeMargin else titleMargin

    fun drawHeading(canvas: Canvas, heading: StaticLayout?) {
        if (heading == null) return
        canvas.save()
        canvas.translate(style.horizontalMargin.toFloat(), titleMargin.toFloat())
        heading.draw(canvas)
        canvas.restore()
    }

    private fun layout(text: CharSequence, size: Int, heading: Boolean): StaticLayout {
        val paint = TextPaint().apply {
            color = this@WenkuTextRenderer.style.textColor
            textSize = size.toFloat()
            typeface = if (heading) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            isAntiAlias = true
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, style.width - style.horizontalMargin * 2)
            .setAlignment(if (heading) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .setLineSpacing(4f, style.lineSpacingPercent / 100f)
            .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }
            .build()
    }
}

internal fun wenkuParagraphBreaks(text: String): List<Int> = (1 until text.length).filter { text[it] == '\n' && text[it - 1] == '\n' }

private fun wenkuSpacedParagraphs(text: String, gap: Int): CharSequence {
    if (gap == 0) return text
    // Only the empty line between paragraphs changes height. StaticLayout retains every character.
    val spaced = SpannableString(text)
    for (offset in wenkuParagraphBreaks(text)) {
        val space = LineHeightSpan { _, start, _, _, _, metrics: Paint.FontMetricsInt ->
            if (start == offset) {
                metrics.top = -gap
                metrics.ascent = -gap
                metrics.descent = 0
                metrics.bottom = 0
            }
        }
        spaced.setSpan(space, offset, offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return spaced
}

internal fun wenkuEncodePng(bitmap: Bitmap): ByteArray {
    val output = ByteArrayOutputStream()
    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("阅读页 PNG 编码失败")
    return output.toByteArray()
}
