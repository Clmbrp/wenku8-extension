package eu.kanade.tachiyomi.extension.zh.wenku8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class WenkuReadingPreferencesTest {
    @Test
    fun `three font presets and one custom option preserve old body and heading values`() {
        assertEquals(listOf("小号 · 34 px", "标准 · 42 px", "大号 · 56 px"), wenkuFontSizeLabels(WenkuReadingStyle.BODY_SIZES))
        assertEquals(listOf("小号 · 42 px", "标准 · 52 px", "大号 · 72 px"), wenkuFontSizeLabels(WenkuReadingStyle.HEADING_SIZES))
        for ((sizes, saved) in listOf(WenkuReadingStyle.BODY_SIZES to "65", WenkuReadingStyle.HEADING_SIZES to "88")) {
            val labels = wenkuFontSizeLabels(sizes)
            val presets = sizes.map(Int::toString).zip(labels).toMap()
            assertEquals(labels + "自定义…", wenkuReadingOptions(presets, sizes[1].toString(), numeric = true, color = false, unit = " px").values.toList())
            val custom = wenkuReadingOptions(presets, saved, numeric = true, color = false, unit = " px")
            assertEquals(labels + "自定义 · $saved px", custom.values.toList())
            assertEquals(saved, custom.keys.last())
        }
        val saved = mapOf("wenku8_reader_body_size" to "65", "wenku8_reader_heading_size" to "88")
        val style = WenkuReadingStyle.fromPreferences(saved, dark = false)
        assertEquals(65, style.bodySize)
        assertEquals(88, style.headingSize)
    }

    @Test
    fun `AndroidX summary formatting preserves preset and custom line spacing labels`() {
        for (label in listOf("紧凑 · 115%", "标准 · 135%", "宽松 · 155%", "很宽松 · 175%", "自定义 · 143%")) {
            assertEquals(label, String.format(Locale.ROOT, wenkuLiteralListSummary(label)!!, label))
        }
    }

    @Test
    fun `literal summaries preserve plain text and percent placeholder shaped text`() {
        for (label in listOf("#FAFAF8 · 浅色", "正文 42 px", "%s 与 %% 都按原样显示")) {
            assertEquals(label, String.format(Locale.ROOT, wenkuLiteralListSummary(label)!!, "选项"))
        }
        assertNull(wenkuLiteralListSummary(null))
    }
}
