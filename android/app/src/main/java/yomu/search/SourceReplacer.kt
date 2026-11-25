package yomu.search

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.model.toSManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.migration.usecases.MigrateMangaUseCase
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import yomu.reader.YomuPreferences
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Moves a series whose source is gone to another source with the same series, keeping what was read. */
@Inject
@SingleIn(AppScope::class)
class SourceReplacer(
    private val sourceManager: SourceManager,
    private val smartSearch: SmartSearch,
    private val searchPack: SearchPack,
    private val migrateManga: MigrateMangaUseCase,
    private val getManga: GetManga,
    private val preferences: YomuPreferences,
) {

    /** The series as it is now kept, in its new source, or null when it stays where it was. */
    suspend fun replace(manga: Manga): Manga? {
        // Wait for the extension installer first: the source may simply not be installed yet.
        if (searchPack.owner(manga.source) != null && searchPack.install(manga.source) != null) return null
        val hits = withTimeoutOrNull(SEARCH_BUDGET) { smartSearch.nearestMatches(manga.title) }
            ?.filter { it.source.id != manga.source }
            ?.take(CANDIDATES)
            .orEmpty()
        // The source with most chapters, so the series reads from as few places as possible.
        val target = hits.map { it.manga.first() to chapterCount(it) }
            .filter { (_, count) -> count > 0 }
            .maxByOrNull { (_, count) -> count }
            ?.first ?: return null
        if (sourceManager.get(target.source) == null && searchPack.install(target.source) == null) return null
        migrateManga(manga, getManga.await(target.id) ?: target, replace = true)
        preferences.repairReplaced.set(preferences.repairReplaced.get() + 1)
        return getManga.await(target.id)
    }

    private suspend fun chapterCount(hit: SearchHit): Int {
        return try {
            withTimeoutOrNull(COUNT_TIMEOUT) {
                hit.source.getMangaUpdate(
                    hit.manga.first().toSManga(),
                    emptyList(),
                    fetchDetails = true,
                    fetchChapters = true,
                )
                    .chapters.size
            } ?: 0
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
    }

    private companion object {
        const val CANDIDATES = 5
        val SEARCH_BUDGET = 3.minutes
        val COUNT_TIMEOUT = 20.seconds
    }
}
