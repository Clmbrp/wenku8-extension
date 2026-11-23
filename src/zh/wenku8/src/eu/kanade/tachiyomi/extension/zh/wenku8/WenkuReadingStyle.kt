package eu.kanade.tachiyomi.extension.zh.wenku8

internal enum class WenkuPaper(val label: String, val light: Int, val dark: Int) {
    WHITE("白色 / 近黑", 0xFFFFFFFF.toInt(), 0xFF141414.toInt()),
    IVORY("米白 / 暖深色", 0xFFFAF3E4.toInt(), 0xFF241F1A.toInt()),
    GRAY("浅灰 / 深灰", 0xFFEFF1F4.toInt(), 0xFF252A31.toInt()),
}

internal enum class WenkuInk(val label: String, val light: Int, val dark: Int) {
    STANDARD("标准", 0xFF000000.toInt(), 0xFFF1F1F1.toInt()),
    SOFT("柔和", 0xFF41464C.toInt(), 0xFFCDD3DB.toInt()),
    WARM("暖调", 0xFF523727.toInt(), 0xFFF0D8BE.toInt()),
}

internal enum class WenkuNight(val label: String) {
    FOLLOW("跟随 Mihon"),
    ON("始终开启"),
    OFF("始终关闭"),
    ;

    fun resolve(app: Boolean?, system: Boolean): Boolean = when (this) {
        FOLLOW -> app ?: system
        ON -> true
        OFF -> false
    }
}

internal data class WenkuReadingStyle(
    val bodySize: Int = 42,
    val headingSize: Int = 52,
    val lineSpacingPercent: Int = 135,
    val paragraphGap: Int = 0,
    val horizontalMargin: Int = 62,
    val paper: WenkuPaper = WenkuPaper.WHITE,
    val ink: WenkuInk = WenkuInk.STANDARD,
    val dark: Boolean = false,
    val customPaper: Int? = null,
    val customInk: Int? = null,
) {
    val width get() = 1_000
    val maxHeight get() = 2_000
    val headingGap get() = 32
    val backgroundColor get() = customPaper ?: if (dark) paper.dark else paper.light
    val textColor: Int
        get() {
            val preferred = customInk ?: if (dark) ink.dark else ink.light
            val background = backgroundColor
            if (wenkuColorContrast(preferred, background) >= WENKU_MIN_CONTRAST) return preferred
            return if (wenkuColorContrast(WHITE_RGB, background) > wenkuColorContrast(BLACK_RGB, background)) WHITE_RGB else BLACK_RGB
        }

    fun stamp(): String = listOf(bodySize, headingSize, lineSpacingPercent, paragraphGap, horizontalMargin, paper.name, ink.name, if (dark) 1 else 0)
        .joinToString("-") + if (customPaper == null && customInk == null) {
        ""
    } else {
        "-${customPaper?.hexRgb() ?: "X"}-${customInk?.hexRgb() ?: "X"}"
    }

    companion object {
        val BODY_SIZES = listOf(30, 34, 38, 42, 46, 50, 56, 62, 68, 72)
        val HEADING_SIZES = listOf(36, 42, 48, 52, 58, 64, 72, 80, 88)
        val LINE_SPACING = listOf(115, 135, 155, 175)
        val PARAGRAPH_GAPS = listOf(18, 0, 72)
        val SIDE_MARGINS = listOf(30, 46, 62, 80, 100)
        val BODY_RANGE = 30..72
        val HEADING_RANGE = 36..88
        val LINE_RANGE = 110..180
        val PARAGRAPH_RANGE = 0..96
        val MARGIN_RANGE = 20..120

        fun fromStamp(stamp: String): WenkuReadingStyle? {
            val parts = stamp.split('-')
            if (parts.size != 8 && parts.size != 10) return null
            val dark = when (parts[7]) {
                "0" -> false
                "1" -> true
                else -> return null
            }
            val settings = mapOf(
                "body_size" to parts[0],
                "heading_size" to parts[1],
                "line_spacing" to parts[2],
                "paragraph_gap" to parts[3],
                "side_margin" to parts[4],
                "paper" to parts[5],
                "ink" to parts[6],
            ).mapKeys { (key, _) -> WENKU_STYLE_PREFIX + key }
            val style = fromPreferences(settings, dark).copy(
                customPaper = parts.getOrNull(8)?.let { wenkuHexColor("#$it")?.toRgb() },
                customInk = parts.getOrNull(9)?.let { wenkuHexColor("#$it")?.toRgb() },
            )
            return style.takeIf { it.stamp() == stamp }
        }

        fun fromPreferences(settings: Map<String, *>, dark: Boolean): WenkuReadingStyle {
            fun choice(key: String, range: IntRange, default: Int) = wenkuInteger(settings[WENKU_STYLE_PREFIX + key] as? String, range) ?: default
            fun customColor(key: String) = wenkuHexColor(settings[wenkuColorPreferenceKey(key, dark)] as? String)?.toRgb()

            return WenkuReadingStyle(
                bodySize = choice("body_size", BODY_RANGE, 42),
                headingSize = choice("heading_size", HEADING_RANGE, 52),
                lineSpacingPercent = choice("line_spacing", LINE_RANGE, 135),
                paragraphGap = choice("paragraph_gap", PARAGRAPH_RANGE, 0),
                horizontalMargin = choice("side_margin", MARGIN_RANGE, 62),
                paper = WenkuPaper.entries.firstOrNull { it.name == settings[WENKU_STYLE_PREFIX + "paper"] } ?: WenkuPaper.WHITE,
                ink = WenkuInk.entries.firstOrNull { it.name == settings[WENKU_STYLE_PREFIX + "ink"] } ?: WenkuInk.STANDARD,
                dark = dark,
                customPaper = customColor("paper"),
                customInk = customColor("ink"),
            )
        }
    }
}

internal const val WENKU_STYLE_PREFIX = "wenku8_reader_"

internal fun wenkuInteger(input: String?, range: IntRange): Int? = input?.trim()?.takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }
    ?.toIntOrNull()?.takeIf(range::contains)

private val HEX_RGB = Regex("#[0-9a-fA-F]{6}")
internal fun wenkuHexColor(input: String?): String? = input?.trim()?.takeIf(HEX_RGB::matches)?.uppercase()

internal fun wenkuColorPreferenceKey(name: String, dark: Boolean): String = WENKU_STYLE_PREFIX + name + if (dark) "_custom_dark" else "_custom_light"

internal fun String.toRgb(): Int = 0xFF000000.toInt() or substring(1).toInt(16)
internal fun Int.hexRgb(): String = (this and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

internal const val WENKU_MIN_CONTRAST = 4.5
private const val WHITE_RGB = -1
private const val BLACK_RGB = 0xFF000000.toInt()

internal fun wenkuColorContrast(first: Int, second: Int): Double {
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = (color ushr shift and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
    }
    val a = luminance(first)
    val b = luminance(second)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
