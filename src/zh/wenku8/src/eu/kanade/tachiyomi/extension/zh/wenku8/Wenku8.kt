package eu.kanade.tachiyomi.extension.zh.wenku8

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source

@Source
abstract class Wenku8 : HttpSource() {
    override val supportsLatest = false

    override suspend fun getPopularManga(page: Int): MangasPage = notImplemented()

    override suspend fun getLatestUpdates(page: Int): MangasPage = notImplemented()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = notImplemented()

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = notImplemented()

    override suspend fun getPageList(chapter: SChapter): List<Page> = notImplemented()

    override suspend fun getImageUrl(page: Page): String = notImplemented()

    private fun notImplemented(): Nothing = throw UnsupportedOperationException("轻小说文库尚未实现")
}
