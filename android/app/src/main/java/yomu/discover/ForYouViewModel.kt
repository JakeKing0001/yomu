package yomu.discover

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.manga.interactor.UpdateManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import yomu.reader.YomuPreferences

@Immutable
data class ForYouEntry(val manga: Manga, val because: String?)

@Immutable
data class ForYouState(
    val recommendations: List<ForYouEntry> = emptyList(),
    val results: List<Manga> = emptyList(),
    val isBuilding: Boolean = false,
    val indexed: Int = 0,
    val total: Int = 0,
    val hasSources: Boolean = true,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ForYouViewModel(
    private val catalog: DiscoverCatalog,
    private val preferences: YomuPreferences,
    private val updateManga: UpdateManga,
    mangaRepository: MangaRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** Cards leave the deck right away, before the library and preferences catch up. */
    private val handled = MutableStateFlow(emptySet<Long>())

    @OptIn(FlowPreview::class)
    val state: StateFlow<ForYouState> = combine(
        catalog.state,
        mangaRepository.getLibraryMangaAsFlow().map { library -> library.map { it.manga } },
        preferences.dismissedManga.changes(),
        handled,
        _query.debounce(SEARCH_DEBOUNCE_MS),
    ) { catalogState, library, dismissed, handledIds, query ->
        val dismissedIds = dismissed.mapNotNullTo(HashSet(), String::toLongOrNull)
        val rejected = catalogState.items.filter { it.id in dismissedIds }.map(Manga::toTaste)
        val byId = catalogState.items.associateBy(Manga::id)
        val ranked = TasteEngine.rank(
            candidates = catalogState.items.filter { it.id !in handledIds }.map(Manga::toTaste),
            liked = library.map(Manga::toTaste),
            disliked = rejected,
        )
        ForYouState(
            recommendations = ranked.mapNotNull { rec ->
                byId[rec.item.id]?.let { ForYouEntry(it, rec.because) }
            },
            results = if (query.isBlank()) {
                emptyList()
            } else {
                MemorySearch.search(query, catalogState.items + library.filter { it.id !in byId }) {
                    SearchFields(it.title, it.genre.orEmpty(), it.description)
                }
            },
            isBuilding = catalogState.isBuilding,
            indexed = catalogState.indexed,
            total = catalogState.total,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ForYouState())

    init {
        catalog.ensureBuilt()
    }

    fun search(text: String) {
        _query.value = text
    }

    fun like(manga: Manga) {
        handled.update { it + manga.id }
        viewModelScope.launch(Dispatchers.IO) { updateManga.awaitUpdateFavorite(manga.id, true) }
    }

    fun dismiss(manga: Manga) {
        handled.update { it + manga.id }
        preferences.dismissedManga.getAndSet { it + manga.id.toString() }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 220L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

private fun Manga.toTaste() = TasteItem(id, title, genre.orEmpty(), description)
