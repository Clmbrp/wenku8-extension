package eu.kanade.tachiyomi.extension.zh.wenku8

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
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
    val choices = mutableListOf<ReadingPreference>()
    val typography by lazy { wenkuPreferenceGroup(screen, "文字排版") }
    val colors by lazy { wenkuPreferenceGroup(screen, "颜色与主题") }
    val storage by lazy { wenkuPreferenceGroup(screen, "缓存与下载") }

    fun list(
        name: String,
        label: String,
        presets: Map<String, String>,
        default: String,
        add: (Preference) -> Unit,
        numberRange: IntRange? = null,
        unit: String = "",
        color: Boolean = false,
    ) {
        val preferenceKey = WENKU_STYLE_PREFIX + name
        val saved = preferences.all[preferenceKey]
        val stored = saved as? String
        val value = when {
            stored != null && stored in presets -> stored
            numberRange != null -> wenkuInteger(stored, numberRange)?.toString() ?: default
            else -> default
        }
        if (saved != null && saved != value) preferences.edit().putString(preferenceKey, value).apply()

        val item = ListPreference(context).apply {
            key = preferenceKey
            title = label
            setDefaultValue(default)
        }

        fun sync(newValue: String) {
            val options = presets.toMutableMap()
            if (numberRange != null && newValue !in options) options[newValue] = "自定义 · $newValue$unit"
            if (numberRange != null || color) options[CUSTOM_ENTRY] = "自定义…"
            item.entryValues = options.keys.toTypedArray()
            item.entries = options.values.toTypedArray()
            val style = if (color) wenkuReadingStyle(preferences, context) else null
            val custom = when (name) {
                "paper" -> style?.customPaper
                "ink" -> style?.customInk
                else -> null
            }
            val summary = if (custom != null && style != null) {
                val effective = if (name == "paper") style.backgroundColor else style.textColor
                "#${custom.hexRgb()} · ${if (style.dark) "深色" else "浅色"}" +
                    if (custom != effective) "（当前文字显示为 #${effective.hexRgb()}）" else ""
            } else {
                options[newValue]
            }
            item.summary = wenkuLiteralListSummary(summary)
        }

        val reset = {
            if (color) {
                preferences.edit()
                    .remove(wenkuColorPreferenceKey(name, dark = false))
                    .remove(wenkuColorPreferenceKey(name, dark = true)).apply()
            }
            item.value = default
            sync(default)
        }
        choices += ReadingPreference(reset) { sync(item.value ?: default) }
        item.setOnPreferenceChangeListener { _, newValue ->
            val selected = newValue as? String ?: return@setOnPreferenceChangeListener false
            when {
                selected == CUSTOM_ENTRY && numberRange != null -> {
                    inputValue(
                        context,
                        label,
                        item.value ?: default,
                        "输入 ${numberRange.first}–${numberRange.last} 的整数$unit。" +
                            if (name == "paragraph_gap") " 0 保留网页空行。" else "",
                        InputType.TYPE_CLASS_NUMBER,
                    ) { raw ->
                        val parsed = wenkuInteger(raw, numberRange)?.toString()
                            ?: return@inputValue "请输入 ${numberRange.first}–${numberRange.last} 的整数"
                        item.value = parsed
                        sync(parsed)
                        null
                    }
                    false
                }
                selected == CUSTOM_ENTRY && color -> {
                    val style = wenkuReadingStyle(preferences, context)
                    val current = if (name == "paper") style.backgroundColor else style.textColor
                    inputValue(
                        context,
                        "$label · ${if (style.dark) "深色" else "浅色"}",
                        "#${current.hexRgb()}",
                        "输入 #RRGGBB，例如 #FAFAF8。自定义颜色仅用于当前的浅色或深色主题。",
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                    ) { raw ->
                        val parsed = wenkuHexColor(raw) ?: return@inputValue "请输入 6 位十六进制颜色，例如 #FAFAF8"
                        if (name == "ink" && wenkuColorContrast(parsed.toRgb(), style.backgroundColor) < WENKU_MIN_CONTRAST) {
                            return@inputValue "文字与背景的对比度太低，请选择更浅或更深的颜色"
                        }
                        preferences.edit().putString(wenkuColorPreferenceKey(name, style.dark), parsed).apply()
                        choices.forEach { it.sync() }
                        null
                    }
                    false
                }
                selected in presets || (numberRange != null && wenkuInteger(selected, numberRange) != null) -> {
                    if (color) {
                        val dark = wenkuReadingStyle(preferences, context).dark
                        preferences.edit().remove(wenkuColorPreferenceKey(name, dark)).apply()
                    }
                    item.value = selected
                    choices.forEach { it.sync() }
                    false
                }
                else -> false
            }
        }
        add(item)
        sync(value)
    }

    fun numbers(name: String, label: String, presets: List<Int>, default: Int, range: IntRange, unit: String, labels: List<String> = presets.map { "$it$unit" }) = list(name, label, presets.map(Int::toString).zip(labels).toMap(), default.toString(), typography, range, unit)

    val defaults = WenkuReadingStyle()
    numbers("body_size", "正文字号", WenkuReadingStyle.BODY_SIZES, defaults.bodySize, WenkuReadingStyle.BODY_RANGE, " px")
    numbers("heading_size", "标题字号", WenkuReadingStyle.HEADING_SIZES, defaults.headingSize, WenkuReadingStyle.HEADING_RANGE, " px")
    numbers("line_spacing", "行距", WenkuReadingStyle.LINE_SPACING, defaults.lineSpacingPercent, WenkuReadingStyle.LINE_RANGE, "%", listOf("紧凑 · 115%", "标准 · 135%", "宽松 · 155%", "很宽松 · 175%"))
    numbers("paragraph_gap", "段落间距", WenkuReadingStyle.PARAGRAPH_GAPS, defaults.paragraphGap, WenkuReadingStyle.PARAGRAPH_RANGE, " px", listOf("紧凑 · 18 px", "标准（保留原空行）", "宽松 · 72 px"))
    numbers("side_margin", "左右页边距", WenkuReadingStyle.SIDE_MARGINS, defaults.horizontalMargin, WenkuReadingStyle.MARGIN_RANGE, " px")
    list("paper", "背景颜色", WenkuPaper.entries.associate { it.name to it.label }, defaults.paper.name, colors, color = true)
    list("ink", "正文颜色", WenkuInk.entries.associate { it.name to it.label }, defaults.ink.name, colors, color = true)
    list("night", "深色模式", WenkuNight.entries.associate { it.name to it.label }, WenkuNight.OFF.name, colors)
    storage(
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
    storage(
        plainPreference(context).apply {
            title = "恢复默认阅读样式"
            summary = "恢复字号、间距、颜色和深色模式。再点「应用到已读章节」可重排已读页。"
            setOnPreferenceClickListener {
                choices.forEach { it.reset() }
                choices.forEach { it.sync() }
                true
            }
        },
    )
    storage(
        plainPreference(context).apply {
            title = "离线下载"
            summary = "使用 Mihon 章节下载。下载期间请保持样式一致；已下载页面保留下载时的样式。"
            setEnabled(false)
        },
    )
}

private const val CUSTOM_ENTRY = "custom"
private data class ReadingPreference(val reset: () -> Unit, val sync: () -> Unit)

// ListPreference passes the summary to String.format when binding the row.
internal fun wenkuLiteralListSummary(text: String?): String? = text?.replace("%", "%%")

private fun inputValue(context: Context, title: String, initial: String, hint: String, type: Int, save: (String) -> String?) {
    val inset = (24 * context.resources.displayMetrics.density).toInt()
    val edit = EditText(context).apply {
        inputType = type
        isSingleLine = true
        setText(initial)
        selectAll()
    }
    val container = FrameLayout(context).apply {
        setPadding(inset, 0, inset, 0)
        addView(edit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(hint)
        .setView(container)
        .setNegativeButton("取消", null)
        .setPositiveButton("保存", null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val error = save(edit.text.toString())
            if (error == null) dialog.dismiss() else edit.error = error
        }
    }
    edit.requestFocus()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    dialog.show()
}

/** PreferenceCategory is available in the host, but absent from the tachiyomix compile stub. */
internal fun wenkuPreferenceGroup(screen: PreferenceScreen, title: String): (Preference) -> Unit {
    val category = runCatching {
        val type = Class.forName("androidx.preference.PreferenceCategory").asSubclass(Preference::class.java)
        val item = type.getConstructor(Context::class.java).newInstance(screen.context)
        val add = type.getMethod("addPreference", Preference::class.java)
        item.title = title
        item to add
    }.getOrNull()
    if (category != null && screen.addPreference(category.first)) {
        return { preference -> category.second.invoke(category.first, preference) }
    }
    screen.addPreference(
        plainPreference(screen.context).apply {
            this.title = title
            setEnabled(false)
        },
    )
    return { screen.addPreference(it) }
}

// tachiyomix's compile stub omits the real AndroidX constructor.
private fun plainPreference(context: Context): Preference = Preference::class.java.getConstructor(Context::class.java).newInstance(context)
