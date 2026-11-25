package yomu.chapters

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import yomu.reader.YomuPreferences
import yomu.search.SearchPack
import yomu.search.SmartSearch
import yomu.web.WebSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Completes a series with chapters from other sources: the gaps in its numbering, newer chapters its
 * source doesn't have yet, or every chapter when its source lists none. Sources with more chapters lend
 * first, and the web is tried for what none of them has.
 */
@Inject
@SingleIn(AppScope::class)
class ChapterFiller(
    private val smartSearch: SmartSearch,
    private val searchPack: SearchPack,
    private val sourceManager: SourceManager,
    private val chapterRepository: ChapterRepository,
    private val webSource: WebSource,
    private val preferences: YomuPreferences,
) {

    private val _filling = MutableStateFlow(emptySet<Long>())

    /** The series being completed right now. */
    val filling: StateFlow<Set<Long>> = _filling.asStateFlow()

    /** How many chapters were added. Unless [force]d, it looks again for a series only after a while. */
    suspend fun fill(manga: Manga, force: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (manga.source == 0L || manga.id in _filling.value) return@withContext 0
        _filling.update { it + manga.id }
        try {
            if (!force && !isDue(manga.id)) return@withContext 0
            markTried(manga.id)
            withTimeoutOrNull(BUDGET) { borrow(manga) } ?: 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Couldn't complete the chapters of ${manga.title}" }
            0
        } finally {
            _filling.update { it - manga.id }
        }
    }

    private class Lender(val source: CatalogueSource, val chapters: List<Pair<Double, Chapter>>)

    private suspend fun borrow(manga: Manga): Int {
        val existing = chapterRepository.getChapterByMangaId(manga.id)
        val have = existing.filter { it.isRecognizedNumber }.map { it.chapterNumber }.toMutableList()
        val added = ArrayList<Chapter>()
        val now = Clock.System.now()

        val take: (Lender) -> Unit = { lender ->
            BorrowedChapter.missing(have, lender.chapters).forEach { (number, chapter) ->
                have += number
                added += chapter.copy(
                    id = -1,
                    mangaId = manga.id,
                    url = BorrowedChapter.url(lender.source.id, chapter.url),
                    scanlator = lender.source.name,
                    chapterNumber = number,
                    read = false,
                    bookmark = false,
                    lastPageRead = 0,
                    dateFetch = now,
                )
            }
        }

        // Italian sources first, then those with most chapters, so a series is mostly read from one place.
        lenders(manga)
            .sortedWith(
                compareByDescending<Lender> { it.source.lang == ITALIAN }
                    .thenByDescending { it.chapters.distinctBy { (number, _) -> number }.size },
            )
            .forEach { lender ->
                if (BorrowedChapter.missing(have, lender.chapters).isEmpty()) return@forEach
                // Reading needs the source installed, not just loaded for searching.
                if (sourceManager.get(lender.source.id) != null || searchPack.install(lender.source.id) != null) {
                    take(lender)
                }
            }
        if (manga.source != WebSource.ID && (have.isEmpty() || BorrowedChapter.gaps(have) > 0)) {
            fromWeb(manga.title)?.let(take)
        }
        if (added.isEmpty()) return 0

        val ordered = added.sortedByDescending(Chapter::chapterNumber)
            .mapIndexed { index, chapter -> chapter.copy(sourceOrder = (existing.size + index).toLong()) }
        chapterRepository.updateFromRemote(removedIds = emptyList(), added = ordered, updated = emptyList())
        return ordered.size
    }

    private suspend fun lenders(manga: Manga): List<Lender> = coroutineScope {
        val gate = Semaphore(PARALLEL_SOURCES)
        smartSearch.allMatches(manga.title)
            .filter { it.source.id != manga.source && it.source.id != WebSource.ID }
            .take(MAX_SOURCES)
            .map { hit ->
                async {
                    gate.withPermit {
                        val found = hit.manga.first()
                        chaptersOf(hit.source, found.toSManga(), found.title)
                            ?.takeIf { offered -> BorrowedChapter.isNumberedLikeTheSeries(offered.map { it.first }) }
                            ?.let { Lender(hit.source, it) }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun fromWeb(title: String): Lender? {
        val series = try {
            webSource.getSearchManga(1, title, FilterList()).mangas
                .firstOrNull { WebSource.WebSeries.parse(it.url) != null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG, e) { "Nothing on the web for $title" }
            null
        } ?: return null
        return chaptersOf(webSource, series, title)?.let { Lender(webSource, it) }
    }

    private suspend fun chaptersOf(
        source: CatalogueSource,
        found: SManga,
        title: String,
    ): List<Pair<Double, Chapter>>? {
        return try {
            val timeout = if (source is WebSource) WEB_TIMEOUT else SOURCE_TIMEOUT
            val listed = withTimeoutOrNull(timeout) {
                source.getMangaUpdate(found, emptyList(), fetchDetails = true, fetchChapters = true).chapters
            } ?: return null
            val chapters = listed.map { Chapter.create().copyFromSChapter(it) }
            ChapterRecognition.parseChapterNumbers(title, chapters).zip(chapters)
                .mapNotNull { (numbering, chapter) ->
                    numbering.chapter?.let(ChapterRecognition::toDouble)?.let { it to chapter }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG, e) { "No chapters to borrow from ${source.name}" }
            null
        }
    }

    private fun isDue(mangaId: Long): Boolean {
        val last = preferences.fillTried.get().firstOrNull { it.startsWith("$mangaId:") }
            ?.substringAfter(':')?.toLongOrNull() ?: return true
        return System.currentTimeMillis() - last > RETRY_AFTER.inWholeMilliseconds
    }

    private fun markTried(mangaId: Long) {
        val now = System.currentTimeMillis()
        val kept = preferences.fillTried.get().filterNot { it.startsWith("$mangaId:") }
            // Old entries go, so the set stays small.
            .filter { (it.substringAfter(':').toLongOrNull() ?: 0) > now - RETRY_AFTER.inWholeMilliseconds }
        preferences.fillTried.set(kept.toSet() + "$mangaId:$now")
    }

    private companion object {
        const val ITALIAN = "it"
        const val MAX_SOURCES = 6
        const val PARALLEL_SOURCES = 3
        val RETRY_AFTER = 12.hours
        val BUDGET = 5.minutes
        val SOURCE_TIMEOUT = 20.seconds
        val WEB_TIMEOUT = 2.minutes
    }
}
