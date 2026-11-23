package eu.kanade.tachiyomi.extension.zh.wenku8

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal val WENKU_CHAPTER_PATH = Regex("/novel/\\d+/\\d+/\\d+\\.htm")

internal data class WenkuTextKey(val chapterPath: String, val revision: String, val version: Int = 1)

internal data class WenkuRenderKey(val chapter: WenkuTextKey, val style: WenkuReadingStyle?)

internal data class WenkuPageRef(val key: WenkuTextKey, val index: Int, val style: WenkuReadingStyle? = null) {
    val renderKey get() = WenkuRenderKey(key, style)

    fun url(): String {
        val version = style?.let { "v3/${it.stamp()}/${key.version}" } ?: "v${key.version}"
        return "$ORIGIN$version${key.chapterPath}/${key.revision}/$index.png"
    }

    companion object {
        const val HOST = "wenku8-reader.invalid"
        private const val ORIGIN = "https://$HOST/"
        private val ROOT = ORIGIN.toHttpUrl()
        private val PATH = Regex("/(?:v|v3/([A-Z0-9-]{1,64})/)([12])(/novel/\\d+/\\d+/\\d+\\.htm)/([a-f0-9]{64})/(\\d+)\\.png")

        fun parse(url: HttpUrl): WenkuPageRef? {
            if (!url.hasWenkuOrigin(ROOT) || url.query != null || url.fragment != null) return null
            val match = PATH.matchEntire(url.encodedPath) ?: return null
            val (stamp, version, chapter, revision, index) = match.destructured
            val style = if (stamp.isEmpty()) null else WenkuReadingStyle.fromStamp(stamp) ?: return null
            return WenkuPageRef(WenkuTextKey(chapter, revision, version.toInt()), index.toIntOrNull() ?: return null, style)
        }
    }
}

internal data class WenkuCachedChapter(val key: WenkuTextKey, val content: WenkuChapterContent)

/** Bounded reader cache, separate from Mihon's reading history and image cache. */
internal class WenkuTextCache(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val legacyDirectory: File? = null,
) {
    private data class Parsed(val chapter: WenkuCachedChapter, val length: Long, val modified: Long, val original: File)

    private val parsed = WenkuMemoryCache<WenkuTextKey, Parsed>(6, 1_024 * 1_024) { it.chapter.content.payloadWeight() }
    private var lastMaintenance: Long? = null

    @Synchronized
    fun fresh(chapterPath: String, allowExpired: Boolean = false): WenkuCachedChapter? {
        maintain()
        val prefix = filePrefix(chapterPath)
        return entries().asSequence().filter { it.name.startsWith(prefix) }
            .filter { allowExpired || now() - it.lastModified() in 0..FRESH_MILLIS }
            .sortedByDescending(File::lastModified)
            .firstNotNullOfOrNull { file ->
                val version = if (file.extension == "json") 2 else 1
                readFile(WenkuTextKey(chapterPath, file.nameWithoutExtension.removePrefix(prefix), version), file)
            }
    }

    @Synchronized
    fun read(key: WenkuTextKey): WenkuCachedChapter? {
        maintain()
        if (!REVISION.matches(key.revision)) return null
        return readFile(key, File(directory, fileName(key)))
            ?: legacyDirectory?.takeIf { key.version == 1 }?.let { readFile(key, File(it, fileName(key))) }
    }

    @Synchronized
    fun hasSnapshot(chapterPath: String): Boolean {
        val prefix = filePrefix(chapterPath)
        return entries().any { it.name.startsWith(prefix) }
    }

    private fun readFile(key: WenkuTextKey, file: File): WenkuCachedChapter? {
        try {
            val length = file.length()
            val modified = file.lastModified()
            val cached = parsed[key]
            if (cached?.original == file) {
                if (cached.length == length && cached.modified == modified) return cached.chapter
                parsed.remove(key)
            }
            if (length !in 1L..MAX_ENTRY_BYTES) return null
            val bytes = file.readBytes()
            if (digest(bytes) != key.revision) return null
            val data = bytes.toString(Charsets.UTF_8)
            val content = if (key.version == 2) {
                decodeChapter(data)
            } else {
                val title = data.substringBefore('\n')
                val body = data.substringAfter('\n', "")
                if (title.isBlank() || body.isBlank()) return null
                WenkuChapterContent(title, listOf(WenkuContentBlock.TextBlock(body)))
            } ?: return null
            return WenkuCachedChapter(key, content).also { parsed.put(key, Parsed(it, length, modified, file)) }
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    fun remember(chapterPath: String, text: WenkuChapterText): WenkuCachedChapter = remember(chapterPath, WenkuChapterContent(text.title, listOf(WenkuContentBlock.TextBlock(text.body))))

    fun legacySnapshot(chapterPath: String, content: WenkuChapterContent): WenkuCachedChapter {
        val text = content.textForReader()
        val snapshot = WenkuChapterContent(text.title, listOf(WenkuContentBlock.TextBlock(text.body)))
        // A text-only recovery for an old URL must not replace the mixed layout on the next open.
        return WenkuCachedChapter(WenkuTextKey(chapterPath, digest(encodeText(text))), snapshot)
    }

    @Synchronized
    fun remember(chapterPath: String, content: WenkuChapterContent): WenkuCachedChapter {
        maintain()
        val mixed = content.blocks.any { it is WenkuContentBlock.ImageBlock }
        val stored = if (mixed) {
            content
        } else {
            val text = content.textForReader()
            WenkuChapterContent(text.title, listOf(WenkuContentBlock.TextBlock(text.body)))
        }
        val bytes = if (mixed) {
            encodeChapter(stored).encodeToByteArray()
        } else {
            encodeText(stored.textForReader())
        }
        val key = WenkuTextKey(chapterPath, digest(bytes), if (mixed) 2 else 1)
        val chapter = WenkuCachedChapter(key, stored)
        if (bytes.size <= MAX_ENTRY_BYTES) {
            try {
                directory.mkdirs()
                val target = File(directory, fileName(key))
                if (readFile(key, target) == null || !target.setLastModified(now())) {
                    val pending = File.createTempFile("pending-", ".tmp", directory)
                    try {
                        pending.writeBytes(bytes)
                        Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        pending.delete()
                    }
                }
                parsed.put(key, Parsed(chapter, target.length(), target.lastModified(), target))
                prune(keepFirst = target)
            } catch (_: IOException) {
                // Reading can still use the in-memory layout when app cache storage is unavailable.
            }
        }
        return chapter
    }

    private fun encodeText(text: WenkuChapterText): ByteArray = "${text.title}\n${text.body}".encodeToByteArray()

    private fun encodeChapter(content: WenkuChapterContent): String = JsonObject(
        mapOf(
            "title" to JsonPrimitive(content.title),
            "blocks" to JsonArray(
                content.blocks.map {
                    when (it) {
                        is WenkuContentBlock.TextBlock -> JsonObject(mapOf("text" to JsonPrimitive(it.text)))
                        is WenkuContentBlock.ImageBlock -> JsonObject(mapOf("image" to JsonPrimitive(it.url)))
                    }
                },
            ),
        ),
    ).toString()

    private fun decodeChapter(data: String): WenkuChapterContent? {
        val chapter = Json.parseToJsonElement(data) as? JsonObject ?: return null
        val title = (chapter["title"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) ?: return null
        val blocks = (chapter["blocks"] as? JsonArray)?.map { node ->
            val block = node as? JsonObject ?: return null
            when (val field = block.keys.singleOrNull()) {
                "text" -> WenkuContentBlock.TextBlock((block[field] as? JsonPrimitive)?.content ?: return null)
                "image" -> WenkuContentBlock.ImageBlock((block[field] as? JsonPrimitive)?.content ?: return null)
                else -> return null
            }
        } ?: return null
        return WenkuChapterContent(title, blocks).takeIf { blocks.any { it is WenkuContentBlock.ImageBlock } }
    }

    private fun maintain() {
        val time = now()
        val last = lastMaintenance
        if (last != null && time - last in 0 until MAINTENANCE_INTERVAL) return
        lastMaintenance = time
        directory.listFiles { file -> PENDING_FILE.matches(file.name) && file.isFile }?.forEach { file ->
            if (file.lastModified() > 0L && time - file.lastModified() >= ORPHAN_AGE) file.delete()
        }
        prune()
    }

    private fun prune(keepFirst: File? = null) {
        var keptBytes = 0L
        val files = entries().sortedWith(compareByDescending<File> { it == keepFirst }.thenByDescending(File::lastModified))
        var keptEntries = 0
        for (file in files) {
            val length = file.length()
            if (keptEntries < MAX_ENTRIES && length in 1L..MAX_ENTRY_BYTES && length <= MAX_CACHE_BYTES - keptBytes) {
                keptBytes += length
                keptEntries++
            } else {
                file.delete()
            }
        }
    }

    private fun entries(): Array<out File> = directory.listFiles { file -> file.isFile && CACHE_FILE.matches(file.name) }.orEmpty()

    private fun fileName(key: WenkuTextKey): String = "${filePrefix(key.chapterPath)}${key.revision}.${if (key.version == 2) "json" else "txt"}"

    private fun filePrefix(path: String): String {
        require(WENKU_CHAPTER_PATH.matches(path))
        return path.removePrefix("/novel/").removeSuffix(".htm").replace('/', '-') + "-"
    }

    private fun digest(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

    companion object {
        private const val FRESH_MILLIS = 20 * 60 * 1_000L
        private const val MAINTENANCE_INTERVAL = 60 * 60 * 1_000L
        private const val ORPHAN_AGE = 24 * MAINTENANCE_INTERVAL
        private const val MAX_ENTRY_BYTES = 4 * 1_024 * 1_024L
        private const val MAX_ENTRIES = 24
        private const val MAX_CACHE_BYTES = 16 * 1_024 * 1_024L
        private val REVISION = Regex("[a-f0-9]{64}")
        private val CACHE_FILE = Regex("\\d+-\\d+-\\d+-[a-f0-9]{64}\\.(txt|json)")
        private val PENDING_FILE = Regex("pending-\\d+\\.tmp")
    }
}
