package eu.kanade.tachiyomi.extension.zh.wenku8

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun wenkuReadingStyle(preferences: SharedPreferences, context: Context): WenkuReadingStyle {
    val values = preferences.all
    val night = WenkuNight.entries.firstOrNull { it.name == values[WENKU_STYLE_PREFIX + "night"] } ?: WenkuNight.OFF
    val dark = if (night == WenkuNight.FOLLOW) {
        val system = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        night.resolve(mihonNightMode(context), system)
    } else {
        night.resolve(null, false)
    }
    return WenkuReadingStyle.fromPreferences(values, dark)
}

private fun mihonNightMode(context: Context): Boolean? {
    val saved = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        .all["pref_theme_mode_key"]
    when (saved) {
        "DARK" -> return true
        "LIGHT" -> return false
        "SYSTEM" -> return null
    }
    // Mirrors BiliNovel's AppCompat fallback on hosts that don't expose Mihon's theme key.
    val mode = runCatching {
        val delegate = Class.forName("androidx.appcompat.app.AppCompatDelegate")
        runCatching { delegate.getMethod("getDefaultNightMode").invoke(null) as Int }.getOrElse {
            delegate.getDeclaredField("sDefaultNightMode").apply { isAccessible = true }.getInt(null)
        }
    }.getOrNull()
    return when (mode) {
        1 -> false
        2 -> true
        else -> null
    }
}

internal fun wenkuReadingPreferences(screen: PreferenceScreen, preferences: SharedPreferences, refresh: () -> WenkuPageListRefreshResult) {
    val context = screen.context
    val choices = mutableListOf<Pair<ListPreference, String>>()
    val saved = preferences.all

    fun list(name: String, label: String, values: List<String>, labels: List<String>, default: String, hint: String = "") {
        val preferenceKey = WENKU_STYLE_PREFIX + name
        if (preferenceKey in saved && saved[preferenceKey] !in values) {
            preferences.edit().putString(preferenceKey, default).apply()
        }
        val item = ListPreference(context).apply {
            key = preferenceKey
            title = label
            entries = labels.toTypedArray()
            entryValues = values.toTypedArray()
            setDefaultValue(default)
            summary = if (hint.isEmpty()) "%s" else "%s\n$hint"
            setOnPreferenceChangeListener { _, value -> value in values }
        }
        choices += item to default
        screen.addPreference(item)
    }

    fun numbers(name: String, label: String, values: List<Int>, default: Int, labels: List<String> = values.map { "$it px" }) = list(name, label, values.map(Int::toString), labels, default.toString())

    val defaults = WenkuReadingStyle()
    numbers("body_size", "正文字号", WenkuReadingStyle.BODY_SIZES, defaults.bodySize)
    numbers("heading_size", "标题字号", WenkuReadingStyle.HEADING_SIZES, defaults.headingSize)
    numbers("line_spacing", "行距", WenkuReadingStyle.LINE_SPACING, defaults.lineSpacingPercent, listOf("紧凑 · 115%", "标准 · 135%", "宽松 · 155%", "很宽松 · 175%"))
    numbers("paragraph_gap", "段落间距", WenkuReadingStyle.PARAGRAPH_GAPS, defaults.paragraphGap, listOf("紧凑", "标准（保留原空行）", "宽松"))
    numbers("side_margin", "左右页边距", WenkuReadingStyle.SIDE_MARGINS, defaults.horizontalMargin)
    list("paper", "背景配色", WenkuPaper.entries.map { it.name }, WenkuPaper.entries.map { it.label }, defaults.paper.name, "由深色模式决定使用哪一种背景。")
    list("ink", "正文颜色", WenkuInk.entries.map { it.name }, WenkuInk.entries.map { it.label }, defaults.ink.name)
    list("night", "深色模式", WenkuNight.entries.map { it.name }, WenkuNight.entries.map { it.label }, WenkuNight.OFF.name)
    screen.addPreference(
        plainPreference(context).apply {
            title = "应用到已读章节"
            summary = "退出阅读器后，点此重新分页，再打开章节。切换 Mihon 主题后也可点此更新。"
            setOnPreferenceClickListener {
                setEnabled(false)
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val result = withContext(Dispatchers.IO) { refresh() }
                        val message = when {
                            result.incompatible -> "当前 Mihon 的页列表无法刷新，新章节将使用所选样式"
                            result.failed > 0 -> "已刷新 ${result.updated} 个章节，还有 ${result.failed} 个待刷新，请退出阅读器再试"
                            result.updated > 0 -> "已刷新 ${result.updated} 个章节，返回阅读器即可查看"
                            else -> "已保存样式，打开章节即可"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    } finally {
                        setEnabled(true)
                    }
                }
                true
            }
        },
    )
    screen.addPreference(
        plainPreference(context).apply {
            title = "恢复默认阅读样式"
            summary = "恢复字号、间距、颜色和深色模式。再点「应用到已读章节」可重排已读页。"
            setOnPreferenceClickListener {
                for ((item, value) in choices) item.value = value
                true
            }
        },
    )
}

// tachiyomix's compile stub omits the real AndroidX constructor.
private fun plainPreference(context: Context): Preference = Preference::class.java.getConstructor(Context::class.java).newInstance(context)
