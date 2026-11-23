package eu.kanade.tachiyomi.extension.zh.wenku8

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal val WENKU_CHAPTER_PATH = Regex("/novel/\\d+/\\d+/\\d+\\.htm")

internal data class WenkuTextKey(val chapterPath: String, val revision: String)

internal data class WenkuPageRef(val key: WenkuTextKey, val index: Int) {
    fun url(): String = "$ORIGIN$VERSION${key.chapterPath}/${key.revision}/$index.png"

    companion object {
        const val HOST = "wenku8-reader.invalid"
        private const val ORIGIN = "https://$HOST/"
        private const val VERSION = "v1"
        private val ROOT = ORIGIN.toHttpUrl()
        private val PATH = Regex("/$VERSION(/novel/\\d+/\\d+/\\d+\\.htm)/([a-f0-9]{64})/(\\d+)\\.png")

        fun parse(url: HttpUrl): WenkuPageRef? {
            if (!url.hasWenkuOrigin(ROOT) || url.query != null || url.fragment != null) return null
            val match = PATH.matchEntire(url.encodedPath) ?: return null
            val (chapter, revision, index) = match.destructured
            return WenkuPageRef(WenkuTextKey(chapter, revision), index.toIntOrNull() ?: return null)
        }
    }
}

internal data class WenkuCachedText(val key: WenkuTextKey, val text: WenkuChapterText)

/** Bounded reader cache, separate from Mihon's reading history and image cache. */
internal class WenkuTextCache(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun fresh(chapterPath: String): WenkuCachedText? {
        val prefix = filePrefix(chapterPath)
        return entries().asSequence().filter { it.name.startsWith(prefix) }
            .filter { now() - it.lastModified() in 0..FRESH_MILLIS }
            .sortedByDescending(File::lastModified)
            .firstNotNullOfOrNull { file -> read(WenkuTextKey(chapterPath, file.name.removePrefix(prefix).removeSuffix(".txt"))) }
    }

    @Synchronized
    fun read(key: WenkuTextKey): WenkuCachedText? {
        if (!REVISION.matches(key.revision)) return null
        val file = file(key)
        try {
            if (file.length() !in 1L..MAX_ENTRY_BYTES) return null
            val bytes = file.readBytes()
            if (digest(bytes) != key.revision) return null
            val data = bytes.toString(Charsets.UTF_8)
            val title = data.substringBefore('\n')
            val body = data.substringAfter('\n', "")
            if (title.isBlank() || body.isBlank()) return null
            return WenkuCachedText(key, WenkuChapterText(title, body))
        } catch (_: IOException) {
            return null
        }
    }

    @Synchronized
    fun remember(chapterPath: String, text: WenkuChapterText): WenkuCachedText {
        val bytes = "${text.title}\n${text.body}".encodeToByteArray()
        val key = WenkuTextKey(chapterPath, digest(bytes))
        if (bytes.size <= MAX_ENTRY_BYTES) {
            try {
                directory.mkdirs()
                val pending = File.createTempFile("pending-", ".tmp", directory)
                try {
                    pending.writeBytes(bytes)
                    Files.move(pending.toPath(), file(key).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    pending.delete()
                }
                prune()
            } catch (_: IOException) {
                // Reading can still use the in-memory layout when app cache storage is unavailable.
            }
        }
        return WenkuCachedText(key, text)
    }

    private fun prune() {
        var keptBytes = 0L
        entries().sortedByDescending(File::lastModified).forEachIndexed { index, file ->
            keptBytes += file.length()
            if (index >= MAX_ENTRIES || keptBytes > MAX_CACHE_BYTES) file.delete()
        }
    }

    private fun entries(): Array<out File> = directory.listFiles { file -> file.isFile && CACHE_FILE.matches(file.name) }.orEmpty()

    private fun file(key: WenkuTextKey) = File(directory, "${filePrefix(key.chapterPath)}${key.revision}.txt")

    private fun filePrefix(path: String): String {
        require(WENKU_CHAPTER_PATH.matches(path))
        return path.removePrefix("/novel/").removeSuffix(".htm").replace('/', '-') + "-"
    }

    private fun digest(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

    companion object {
        private const val FRESH_MILLIS = 20 * 60 * 1_000L
        private const val MAX_ENTRY_BYTES = 4 * 1_024 * 1_024L
        private const val MAX_ENTRIES = 24
        private const val MAX_CACHE_BYTES = 16 * 1_024 * 1_024L
        private val REVISION = Regex("[a-f0-9]{64}")
        private val CACHE_FILE = Regex("\\d+-\\d+-\\d+-[a-f0-9]{64}\\.txt")
    }
}
