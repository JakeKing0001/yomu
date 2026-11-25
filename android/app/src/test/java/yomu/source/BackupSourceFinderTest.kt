package yomu.source

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import yomu.search.SearchPack

class BackupSourceFinderTest {

    private val current = Manga.create().copy(id = 1, source = 10, title = "One Piece")
    private val other = Manga.create().copy(id = 2, source = 20, title = "One Piece", url = "/one-piece")
    private val chapter = Chapter.create().copy(id = 100, mangaId = 1, chapterNumber = 12.0, url = "/c12")
    private val sameChapter = Chapter.create().copy(id = 200, mangaId = 2, chapterNumber = 12.0, url = "/o12")

    private val sourceManager = mockk<SourceManager>()
    private val getChapters = mockk<GetChaptersByMangaId>()
    private val source = mockk<HttpSource>(relaxed = true)

    private val finder = run {
        val preferences = mockk<SourcePreferences>()
        val languages = mockk<Preference<Set<String>>>()
        every { languages.get() } returns setOf("it")
        every { preferences.enabledLanguages } returns languages
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(1) } returns current
        coEvery { getManga.await(2) } returns other
        val getChapter = mockk<GetChapter>()
        coEvery { getChapter.await(100L) } returns chapter
        val toLocal = mockk<NetworkToLocalManga>()
        coEvery { toLocal.invoke(any<Manga>()) } returns other
        val update = mockk<UpdateMangaFromRemote>()
        coEvery { update.invoke(any(), any(), any(), any(), any(), any()) } returns Result.success(mockk())

        val currentSource = mockk<HttpSource>(relaxed = true)
        every { currentSource.id } returns 10
        every { currentSource.lang } returns "it"
        every { source.id } returns 20
        every { source.lang } returns "it"
        every { source.name } returns "Riserva"
        every { source.getFilterList() } returns FilterList()
        coEvery { source.getSearchManga(any(), any(), any()) } returns MangasPage(
            listOf(
                SManga.create().apply {
                    title = "One Piece"
                    url = "/one-piece"
                },
            ),
            false,
        )
        coEvery { sourceManager.get(10) } returns currentSource
        coEvery { sourceManager.get(20) } returns source
        coEvery { sourceManager.getOnlineSources() } returns listOf(currentSource, source)

        BackupSourceFinder(
            sourceManager,
            preferences,
            mockk<ExtensionManager>(),
            getManga,
            getChapter,
            getChapters,
            toLocal,
            update,
            mockk<MigrateMangaUseCase>(),
            mockk<SearchPack> {
                every { entries() } returns emptyList()
                coEvery { load(any()) } returns emptyList()
            },
        )
    }

    private fun find() = runBlocking { finder.find(1, 100) {} }

    @Test
    fun `finds the same chapter on another source`() {
        coEvery { getChapters.await(2) } returns listOf(sameChapter)
        coEvery { source.getPageList(any()) } returns listOf(Page(0))

        val hit = find()

        assertEquals(200L, hit?.chapter?.id)
        assertEquals("Riserva", hit?.sourceName)
    }

    @Test
    fun `a source without that chapter number is no use`() {
        coEvery { getChapters.await(2) } returns listOf(sameChapter.copy(chapterNumber = 13.0))

        assertNull(find())
    }

    @Test
    fun `a chapter listed without pages is no use`() {
        coEvery { getChapters.await(2) } returns listOf(sameChapter)
        coEvery { source.getPageList(any()) } returns emptyList()

        assertNull(find())
    }
}
