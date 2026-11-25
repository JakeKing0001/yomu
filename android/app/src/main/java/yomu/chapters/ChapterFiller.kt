package yomu.chapters

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Completes a series with chapters from other sources: the gaps in its numbering, newer chapters its
 * source doesn't have yet, or every chapter when its source lists none.
 */
@Inject
@SingleIn(AppScope::class)
class ChapterFiller(
    private val smartSearch: SmartSearch,
    private val searchPack: SearchPack,
    private val sourceManager: SourceManager,
    private val chapterRepository: ChapterRepository,
    private val preferences: YomuPreferences,
) {

    private val running = ConcurrentHashMap.newKeySet<Long>()

    /** How many chapters were added; it looks again for a series only after a while. */
    suspend fun fill(manga: Manga): Int = withContext(Dispatchers.IO) {
        if (manga.source == 0L || !running.add(manga.id)) return@withContext 0
        try {
            if (!isDue(manga.id)) return@withContext 0
            markTried(manga.id)
            withTimeoutOrNull(BUDGET) { borrow(manga) } ?: 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Couldn't complete the chapters of ${manga.title}" }
            0
        } finally {
            running.remove(manga.id)
        }
    }

    private suspend fun borrow(manga: Manga): Int {
        val existing = chapterRepository.getChapterByMangaId(manga.id)
        val have = existing.filter { it.isRecognizedNumber }.map { it.chapterNumber }.toMutableList()
        val added = ArrayList<Chapter>()
        val now = Clock.System.now()

        for (hit in smartSearch.allMatches(manga.title).filter { it.source.id != manga.source }.take(MAX_SOURCES)) {
            val offered = chaptersOf(hit.source, hit.manga.first()) ?: continue
            if (!BorrowedChapter.isNumberedLikeTheSeries(offered.map { it.first })) continue
            val missing = BorrowedChapter.missing(have, offered)
            if (missing.isEmpty()) continue
            // Reading needs the source installed, not just loaded for searching.
            if (sourceManager.get(hit.source.id) == null && searchPack.install(hit.source.id) == null) continue
            missing.forEach { (number, chapter) ->
                have += number
                added += chapter.copy(
                    id = -1,
                    mangaId = manga.id,
                    url = BorrowedChapter.url(hit.source.id, chapter.url),
                    scanlator = hit.source.name,
                    chapterNumber = number,
                    read = false,
                    bookmark = false,
                    lastPageRead = 0,
                    dateFetch = now,
                )
            }
        }
        if (added.isEmpty()) return 0

        val ordered = added.sortedByDescending(Chapter::chapterNumber)
            .mapIndexed { index, chapter -> chapter.copy(sourceOrder = (existing.size + index).toLong()) }
        chapterRepository.updateFromRemote(removedIds = emptyList(), added = ordered, updated = emptyList())
        return ordered.size
    }

    private suspend fun chaptersOf(source: CatalogueSource, found: Manga): List<Pair<Double, Chapter>>? {
        return try {
            val listed = withTimeoutOrNull(SOURCE_TIMEOUT) {
                source.getMangaUpdate(found.toSManga(), emptyList(), fetchDetails = true, fetchChapters = true).chapters
            } ?: return null
            val chapters = listed.map { Chapter.create().copyFromSChapter(it) }
            ChapterRecognition.parseChapterNumbers(found.title, chapters).zip(chapters)
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
        const val MAX_SOURCES = 6
        val RETRY_AFTER = 12.hours
        val BUDGET = 4.minutes
        val SOURCE_TIMEOUT = 20.seconds
    }
}
