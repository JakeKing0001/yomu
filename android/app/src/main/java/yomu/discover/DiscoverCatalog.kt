package yomu.discover

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import yomu.reader.YomuPreferences
import kotlin.time.Duration.Companion.days

data class CatalogState(
    val items: List<Manga> = emptyList(),
    val isBuilding: Boolean = false,
    val indexed: Int = 0,
    val total: Int = 0,
)

/** A small local index of the pinned sources, so recommendations and search work without asking them again. */
@Inject
@SingleIn(AppScope::class)
class DiscoverCatalog(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val sourcePreferences: SourcePreferences,
    private val yomuPreferences: YomuPreferences,
    private val sourceManager: SourceManager,
    private val mangaRepository: MangaRepository,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
) {

    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private var job: Job? = null

    fun ensureBuilt() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                if (_state.value.items.isEmpty()) loadSaved()
                val age = System.currentTimeMillis() - yomuPreferences.catalogUpdatedAt.get()
                if (_state.value.items.isEmpty() || age > REFRESH_EVERY.inWholeMilliseconds) rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to build the discovery catalogue" }
            } finally {
                _state.update { it.copy(isBuilding = false) }
            }
        }
    }

    private suspend fun loadSaved() {
        val items = yomuPreferences.catalogIds.get()
            .split(',')
            .mapNotNull(String::toLongOrNull)
            .mapNotNull { id -> runCatching { mangaRepository.getMangaById(id) }.getOrNull() }
        _state.update { it.copy(items = items) }
    }

    private suspend fun rebuild() {
        val sources = sourcePreferences.pinnedSources.get()
            .mapNotNull(String::toLongOrNull)
            .mapNotNull { sourceManager.get(it) as? CatalogueSource }
        if (sources.isEmpty()) return

        _state.update { it.copy(isBuilding = true, indexed = 0, total = 0) }
        val found = LinkedHashMap<Long, Manga>()
        for (source in sources) {
            for (page in 1..POPULAR_PAGES) {
                val listing = runCatching { source.getPopularManga(page) }.getOrNull() ?: break
                networkToLocalManga(listing.mangas.map { it.toDomainManga(source.id) })
                    .forEach { found.putIfAbsent(it.id, it) }
                if (!listing.hasNextPage || found.size >= MAX_ITEMS) break
                delay(REQUEST_GAP_MS)
            }
        }
        if (found.isEmpty()) return

        val items = found.values.take(MAX_ITEMS)
        _state.update { it.copy(items = items, total = items.size, indexed = items.count(Manga::initialized)) }

        val limiter = Semaphore(CONCURRENT_DETAILS)
        coroutineScope {
            items.filterNot(Manga::initialized).map { manga ->
                async {
                    limiter.withPermit {
                        updateMangaFromRemote(manga, fetchDetails = true)
                        val fresh = runCatching { mangaRepository.getMangaById(manga.id) }.getOrDefault(manga)
                        _state.update { state ->
                            state.copy(
                                items = state.items.map { if (it.id == fresh.id) fresh else it },
                                indexed = state.indexed + 1,
                            )
                        }
                        delay(REQUEST_GAP_MS)
                    }
                }
            }.awaitAll()
        }
        yomuPreferences.catalogIds.set(items.joinToString(",") { it.id.toString() })
        yomuPreferences.catalogUpdatedAt.set(System.currentTimeMillis())
    }

    private companion object {
        const val POPULAR_PAGES = 4
        const val MAX_ITEMS = 72
        const val CONCURRENT_DETAILS = 2
        const val REQUEST_GAP_MS = 350L
        val REFRESH_EVERY = 3.days
    }
}
