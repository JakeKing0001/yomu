package yomu.search

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.extension.model.ContentWarning
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import yomu.reader.YomuPreferences
import yomu.web.WebSource
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

enum class SmartStep { Idle, Titles, Italian, PreparingOthers, OtherLanguages, Web, Done }

@Immutable
data class SearchHit(val source: CatalogueSource, val manga: List<Manga>, val isStrong: Boolean, val tier: Int)

@Immutable
data class SmartSearchState(
    val query: String? = null,
    val alternatives: List<String> = emptyList(),
    val step: SmartStep = SmartStep.Idle,
    val done: Int = 0,
    val total: Int = 0,
    val hits: List<SearchHit> = emptyList(),
    val packMissing: Boolean = false,
) {
    val showWebFallback: Boolean get() = step == SmartStep.Done && hits.none(SearchHit::isStrong)
}

/**
 * Searches Italian sources first and other languages only when the series or its chapters aren't
 * there. Sources that fail, time out or list a series without chapters are left out.
 */
@Inject
class SmartSearch(
    private val sourceManager: SourceManager,
    private val searchPack: SearchPack,
    private val titleExpander: TitleExpander,
    private val networkToLocalManga: NetworkToLocalManga,
    private val webSource: WebSource,
    private val extensionManager: ExtensionManager,
    preferences: YomuPreferences,
) {

    private val health = SourceHealth(preferences.sourceHealth)

    /** The first source that really has the series with chapters, for replacing one that's gone. */
    suspend fun firstMatch(query: String): SearchHit? {
        var state = SmartSearchState()
        run(query, stopAtFirst = true, everywhere = false) { change -> state = change(state) }
        return state.hits.firstOrNull(SearchHit::isStrong)
    }

    /** Every source found with the series and its chapters, in the order a reader would want them. */
    suspend fun allMatches(query: String): List<SearchHit> {
        var state = SmartSearchState()
        run(query, everywhere = true) { change -> state = change(state) }
        return state.hits.filter(SearchHit::isStrong)
    }

    suspend fun run(
        query: String,
        stopAtFirst: Boolean = false,
        everywhere: Boolean = true,
        update: ((SmartSearchState) -> SmartSearchState) -> Unit,
    ) {
        update {
            SmartSearchState(query = query, step = SmartStep.Titles, packMissing = searchPack.entries().isEmpty())
        }
        val titles = titleExpander.expand(query)
        update { it.copy(alternatives = titles.filter { it != query }, step = SmartStep.Italian) }

        val adult = adultSources()
        val installed = sourceManager.getOnlineSources().filterIsInstance<CatalogueSource>()
            .filter { it.id !in adult }
        val packEntries = searchPack.entries().filterNot(PackEntry::isAdult)
        val italian = (
            installed.filter { it.lang == ITALIAN } +
                searchPack.load(packEntries.filter { entry -> entry.sources.any { it.lang == ITALIAN } })
                    .filter { it.lang == ITALIAN }
            ).distinctBy(CatalogueSource::id)
        val firstOnly = if (stopAtFirst) 1 else null
        val inItalian =
            searchTier(italian, titles.take(ITALIAN_TITLES), tier = 1, stopAfter = firstOnly, update = update)
        if (inItalian > 0 && !everywhere) {
            update { it.copy(step = SmartStep.Done) }
            return
        }

        update { it.copy(step = SmartStep.PreparingOthers) }
        val others = (
            installed.filter { it.lang in OTHER_LANGUAGES } +
                searchPack.load(
                    packEntries.filter { entry ->
                        entry.sources.any { it.lang in OTHER_LANGUAGES }
                    },
                )
                    .filter { it.lang in OTHER_LANGUAGES }
            )
            .distinctBy(CatalogueSource::id)
            .sortedBy { OTHER_LANGUAGES.indexOf(it.lang) }
        update { it.copy(step = SmartStep.OtherLanguages) }
        coroutineScope {
            // The web takes longest, so it starts now and is only shown if no extension has the series.
            val fromWeb = if (inItalian == 0) async { search(webSource, titles.take(WEB_TITLES), tier = 3) } else null
            val elsewhere = searchTier(
                others,
                titles.take(OTHER_TITLES),
                tier = 2,
                stopAfter = firstOnly ?: ENOUGH_ELSEWHERE,
                update = update,
            )
            if (elsewhere == 0 && inItalian == 0) {
                update { it.copy(step = SmartStep.Web, done = 0, total = 1) }
                fromWeb?.await()?.let { hit ->
                    update { state -> state.copy(hits = (state.hits + hit).sortedWith(HIT_ORDER)) }
                }
            } else {
                fromWeb?.cancel()
            }
        }
        update { it.copy(step = SmartStep.Done) }
    }

    private suspend fun searchTier(
        sources: List<CatalogueSource>,
        titles: List<String>,
        tier: Int,
        stopAfter: Int? = null,
        update: ((SmartSearchState) -> SmartSearchState) -> Unit,
    ): Int {
        val usable = sources.filter { health.isUsable(it.id) }
        update { it.copy(done = 0, total = usable.size) }
        val strong = AtomicInteger()
        val gate = Semaphore(PARALLEL_SOURCES)
        coroutineScope {
            usable.forEach { source ->
                launch {
                    gate.withPermit {
                        if (stopAfter == null || strong.get() < stopAfter) {
                            val hit = search(source, titles, tier)
                            // Only a real match is worth showing, never whatever a source returns for any query.
                            if (hit != null && hit.isStrong) {
                                strong.incrementAndGet()
                                update { state -> state.copy(hits = (state.hits + hit).sortedWith(HIT_ORDER)) }
                            }
                        }
                        update { it.copy(done = it.done + 1) }
                    }
                }
            }
        }
        return strong.get()
    }

    private suspend fun search(source: CatalogueSource, titles: List<String>, tier: Int): SearchHit? {
        return try {
            val found = withTimeoutOrNull(if (source is WebSource) WEB_TIMEOUT else SEARCH_TIMEOUT) {
                titles.flatMap { title -> source.getSearchManga(1, title, source.getFilterList()).mangas }
                    .distinctBy(SManga::url)
            }
            if (found == null) {
                health.failed(source.id)
                return null
            }
            health.worked(source.id)
            // Many sources answer any query with their whole catalogue; only titles close to the search count.
            val ranked = found.map { it to TitleMatch.score(it.title, titles) }
                .filter { TitleMatch.isStrong(it.second) }
                .sortedByDescending { it.second }
                .take(CHECKED_PER_SOURCE)
            // The web source checked its pages already, and putting a whole series together takes long.
            val withChapters = if (source is WebSource) {
                ranked
            } else {
                ranked.filter { (manga, _) ->
                    isReadable(source, manga)
                }
            }
            if (withChapters.isEmpty()) return null
            val manga = networkToLocalManga(withChapters.map { (manga, _) -> manga.toDomainManga(source.id) })
            SearchHit(source, manga, TitleMatch.isStrong(withChapters.first().second), tier)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            health.failed(source.id)
            null
        }
    }

    /** Has chapters, and isn't adult: catalogues like MangaDex hold both kinds of series. */
    private suspend fun isReadable(source: CatalogueSource, manga: SManga): Boolean {
        return try {
            withTimeoutOrNull(CHAPTERS_TIMEOUT) {
                // The same request the series page makes: some sources only filter chapters by language with details.
                val update = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
                update.chapters.isNotEmpty() && !isAdultGenre(update.manga.genre ?: manga.genre)
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun adultSources(): Set<Long> {
        return extensionManager.getLoadedExtensions()
            .filter { it.contentWarning == ContentWarning.NSFW }
            .flatMapTo(HashSet()) { extension -> extension.sources.map { it.id } }
    }

    companion object {
        private val ADULT_GENRE = Regex("""\b(hentai|adult|smut|erotica|pornographic|18\+)""", RegexOption.IGNORE_CASE)

        fun isAdultGenre(genre: String?): Boolean = genre != null && ADULT_GENRE.containsMatchIn(genre)

        private const val ITALIAN = "it"
        private const val ITALIAN_TITLES = 3
        private const val OTHER_TITLES = 2
        private const val WEB_TITLES = 1
        private const val PARALLEL_SOURCES = 40
        private const val CHECKED_PER_SOURCE = 3
        private const val ENOUGH_ELSEWHERE = 6
        private val SEARCH_TIMEOUT = 8.seconds
        private val WEB_TIMEOUT = 60.seconds
        private val CHAPTERS_TIMEOUT = 15.seconds

        /** Languages with plenty of releases; a reader can translate them in Studio. */
        private val OTHER_LANGUAGES =
            listOf("en", "es", "es-419", "fr", "pt-BR", "pt", "de", "ja", "ko", "zh", "zh-Hans")

        private val HIT_ORDER =
            compareBy<SearchHit>({ !it.isStrong }, { it.tier }, { OTHER_LANGUAGES.indexOf(it.source.lang) })
    }
}
