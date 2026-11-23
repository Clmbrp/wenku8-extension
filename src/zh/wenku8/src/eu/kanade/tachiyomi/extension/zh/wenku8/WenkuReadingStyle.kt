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
) {
    val width get() = 1_000
    val maxHeight get() = 2_000
    val headingGap get() = 32
    val backgroundColor get() = if (dark) paper.dark else paper.light
    val textColor get() = if (dark) ink.dark else ink.light

    fun stamp(): String = listOf(bodySize, headingSize, lineSpacingPercent, paragraphGap, horizontalMargin, paper.name, ink.name, if (dark) 1 else 0)
        .joinToString("-")

    companion object {
        val BODY_SIZES = listOf(30, 34, 38, 42, 46, 50, 56, 62, 68, 72)
        val HEADING_SIZES = listOf(36, 42, 48, 52, 58, 64, 72, 80, 88)
        val LINE_SPACING = listOf(115, 135, 155, 175)
        val PARAGRAPH_GAPS = listOf(18, 0, 72)
        val SIDE_MARGINS = listOf(30, 46, 62, 80, 100)

        fun fromStamp(stamp: String): WenkuReadingStyle? {
            val parts = stamp.split('-')
            if (parts.size != 8) return null
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
            return fromPreferences(settings, dark).takeIf { it.stamp() == stamp }
        }

        fun fromPreferences(settings: Map<String, *>, dark: Boolean): WenkuReadingStyle {
            fun choice(key: String, values: List<Int>, default: Int) = (settings[WENKU_STYLE_PREFIX + key] as? String)?.toIntOrNull()?.takeIf(values::contains) ?: default

            return WenkuReadingStyle(
                bodySize = choice("body_size", BODY_SIZES, 42),
                headingSize = choice("heading_size", HEADING_SIZES, 52),
                lineSpacingPercent = choice("line_spacing", LINE_SPACING, 135),
                paragraphGap = choice("paragraph_gap", PARAGRAPH_GAPS, 0),
                horizontalMargin = choice("side_margin", SIDE_MARGINS, 62),
                paper = WenkuPaper.entries.firstOrNull { it.name == settings[WENKU_STYLE_PREFIX + "paper"] } ?: WenkuPaper.WHITE,
                ink = WenkuInk.entries.firstOrNull { it.name == settings[WENKU_STYLE_PREFIX + "ink"] } ?: WenkuInk.STANDARD,
                dark = dark,
            )
        }
    }
}

internal const val WENKU_STYLE_PREFIX = "wenku8_reader_"
