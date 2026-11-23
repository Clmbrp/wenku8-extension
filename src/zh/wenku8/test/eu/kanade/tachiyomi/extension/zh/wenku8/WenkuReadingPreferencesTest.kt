package eu.kanade.tachiyomi.extension.zh.wenku8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class WenkuReadingPreferencesTest {
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
