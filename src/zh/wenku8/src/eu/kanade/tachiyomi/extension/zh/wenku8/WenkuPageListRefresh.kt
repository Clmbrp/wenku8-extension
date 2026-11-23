package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

internal data class WenkuPageListRefreshResult(val updated: Int = 0, val failed: Int = 0, val incompatible: Boolean = false)

private val cacheEntryName = Regex("[a-f0-9]{32}\\.0")
private const val MAX_PAGE_LIST_BYTES = 4L * 1024 * 1024

/**
 * Mihon's source API cannot refresh a saved page list. Explicit refresh evicts only verified Wenku8
 * list values in Mihon's known DiskLruCache format. A missing value is a regular miss for the host.
 */
internal fun wenkuRefreshPageLists(
    appCache: File,
    style: WenkuReadingStyle,
    onEvicted: (String) -> Unit,
): WenkuPageListRefreshResult {
    val cache = File(appCache, "chapter_disk_cache")
    if (!cache.exists()) return WenkuPageListRefreshResult()
    val journal = File(cache, "journal")
    if (!Files.isDirectory(cache.toPath(), NOFOLLOW_LINKS) || !Files.isRegularFile(journal.toPath(), NOFOLLOW_LINKS)) {
        return WenkuPageListRefreshResult(incompatible = true)
    }
    val format = runCatching { journal.useLines { it.take(4).toList() } }.getOrNull()
    if (format != listOf("libcore.io.DiskLruCache", "1", "1", "1")) return WenkuPageListRefreshResult(incompatible = true)
    val files = cache.listFiles() ?: return WenkuPageListRefreshResult(incompatible = true)
    var updated = 0
    var failed = 0
    for (file in files) {
        if (!cacheEntryName.matches(file.name)) continue
        try {
            val path = file.toPath()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isRegularFile || attributes.size() !in 1..MAX_PAGE_LIST_BYTES) continue
            val first = file.inputStream().buffered().use { input ->
                input.mark(1)
                if (input.read() != '['.code) {
                    null
                } else {
                    input.reset()
                    wenkuCachedPageList(input.reader(Charsets.UTF_8).readText())
                }
            } ?: continue
            if (first.style == style) continue
            val current = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!current.isRegularFile || current.lastModifiedTime() != attributes.lastModifiedTime() ||
                current.size() != attributes.size() || current.fileKey() != attributes.fileKey() ||
                File(cache, "${file.name}.tmp").exists()
            ) {
                failed++
            } else if (Files.deleteIfExists(path)) {
                onEvicted(first.key.chapterPath)
                updated++
            }
        } catch (_: IOException) {
            // The host may have evicted this entry in the meantime.
            if (file.exists()) failed++
        }
    }
    return WenkuPageListRefreshResult(updated, failed)
}

internal fun wenkuCachedPageList(value: String): WenkuPageRef? {
    val entries = try {
        Json.parseToJsonElement(value) as? JsonArray
    } catch (_: IllegalArgumentException) {
        return null
    } ?: return null
    var first: WenkuPageRef? = null
    for ((index, entry) in entries.withIndex()) {
        val fields = entry as? JsonObject ?: return null
        val url = (fields["url"] as? JsonPrimitive)?.content?.toHttpUrlOrNull() ?: return null
        val page = WenkuPageRef.parse(url) ?: return null
        val image = fields["imageUrl"]
        if (page.index != index || (image != null && image != JsonNull && (image as? JsonPrimitive)?.content != page.url())) {
            return null
        }
        if (first != null && first.renderKey != page.renderKey) return null
        if (index == 0) first = page
    }
    return first
}
