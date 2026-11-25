package yomu.search

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.browse.components.GlobalSearchCardRow
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.GlobalSearchToolbar
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.core.navigation.BrowseSourceRoute
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.MangaRoute
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import yomu.web.WebSource

@AssistedInject
class SmartSearchViewModel(
    @Assisted initialQuery: String,
    private val smartSearch: SmartSearch,
    private val searchPack: SearchPack,
    private val getManga: GetManga,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(initialQuery: String): SmartSearchViewModel
    }

    private val _state = MutableStateFlow(SmartSearchState(query = initialQuery))
    val state: StateFlow<SmartSearchState> = _state.asStateFlow()

    private val _opening = MutableStateFlow(false)
    val opening: StateFlow<Boolean> = _opening.asStateFlow()

    private var job: Job? = null

    init {
        if (initialQuery.isNotBlank()) search()
    }

    fun updateQuery(query: String?) {
        _state.update { it.copy(query = query) }
    }

    fun search() {
        val query = _state.value.query?.trim().orEmpty()
        if (query.isEmpty()) return
        job?.cancel()
        // Off the main thread: cancelling a source's request closes its connection, which Android
        // doesn't allow there.
        job = viewModelScope.launch(Dispatchers.IO) {
            smartSearch.run(query) { change -> _state.update(change) }
        }
    }

    /** A result from a search-only source installs it first, so the series opens like any other. */
    fun ensureSource(source: Source, onReady: (Boolean) -> Unit) {
        viewModelScope.launch {
            _opening.value = true
            val ready = searchPack.install(source.id) != null
            _opening.value = false
            onReady(ready)
        }
    }

    @Composable
    fun manga(initial: Manga): androidx.compose.runtime.State<Manga> {
        return produceState(initialValue = initial) {
            getManga.subscribe(initial.url, initial.source).filterNotNull().collect { value = it }
        }
    }

    override fun onCleared() {
        searchPack.release()
    }
}

@Composable
fun SmartSearchScreen(searchQuery: String) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val viewModel = assistedMetroViewModel<SmartSearchViewModel, SmartSearchViewModel.Factory> {
        create(initialQuery = searchQuery)
    }
    val state by viewModel.state.collectAsState()
    val opening by viewModel.opening.collectAsState()

    val open: (Source, () -> Unit) -> Unit = { source, then ->
        viewModel.ensureSource(source) { ready ->
            if (ready) then() else context.toast(MR.strings.yomu_smart_source_error)
        }
    }
    var fromWeb by remember { mutableStateOf<Manga?>(null) }
    val openManga: (Source, Manga) -> Unit = { source, manga ->
        if (source.id == WebSource.ID) {
            fromWeb = manga
        } else {
            open(source) { navigator.push(MangaRoute(manga.id, true)) }
        }
    }
    fromWeb?.let { manga ->
        WebSeriesDialog(
            manga = manga,
            onConfirm = {
                fromWeb = null
                navigator.push(MangaRoute(manga.id, true))
            },
            onDismiss = { fromWeb = null },
        )
    }

    Scaffold(
        topBar = { scrollBehavior ->
            GlobalSearchToolbar(
                searchQuery = state.query,
                progress = state.done,
                total = state.total,
                navigateUp = navigator::pop,
                onChangeSearchQuery = viewModel::updateQuery,
                onSearch = { viewModel.search() },
                hideSourceFilter = true,
                sourceFilter = SourceFilter.All,
                onChangeSearchFilter = {},
                onlyShowHasResults = true,
                onToggleResults = {},
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item(key = "status") { SearchStatus(state, opening) }
            items(state.hits, key = { "hit-${it.source.id}" }) { hit ->
                GlobalSearchResultItem(
                    title = hit.source.name,
                    subtitle = LocaleHelper.getLocalizedDisplayName(hit.source.lang),
                    onClick = { open(hit.source) { navigator.push(BrowseSourceRoute(hit.source.id, state.query)) } },
                    modifier = Modifier.animateItem(),
                ) {
                    GlobalSearchCardRow(
                        titles = hit.manga,
                        getManga = { viewModel.manga(it) },
                        onClick = { manga -> openManga(hit.source, manga) },
                        onLongClick = { manga -> openManga(hit.source, manga) },
                    )
                }
            }
            if (state.showWebFallback) {
                item(key = "web") {
                    WebFallback(state.alternatives.firstOrNull() ?: state.query.orEmpty())
                }
            }
        }
    }
}

@Composable
private fun SearchStatus(state: SmartSearchState, opening: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.alternatives.isNotEmpty()) {
            Text(
                text = stringResource(
                    MR.strings.yomu_smart_alternatives,
                    state.alternatives.joinToString(", ") { "«$it»" },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val step = when {
            opening -> stringResource(MR.strings.yomu_smart_opening)
            state.step == SmartStep.Titles -> stringResource(MR.strings.yomu_smart_titles)
            state.step == SmartStep.Italian -> stringResource(MR.strings.yomu_smart_italian, state.done, state.total)
            state.step == SmartStep.PreparingOthers -> stringResource(MR.strings.yomu_smart_preparing)
            state.step == SmartStep.OtherLanguages ->
                stringResource(MR.strings.yomu_smart_others, state.done, state.total)
            state.step == SmartStep.Web -> stringResource(MR.strings.yomu_smart_web_searching)
            else -> null
        }
        if (step != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.packMissing) {
            Text(
                text = stringResource(MR.strings.yomu_smart_pack_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The series is only put together from the web pages once the reader asks for it, since that takes a while. */
@Composable
private fun WebSeriesDialog(manga: Manga, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val sites = WebSource.WebSeries.parse(manga.url)?.sites?.size ?: 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.yomu_web_series_title)) },
        text = { Text(stringResource(MR.strings.yomu_web_series_text, manga.title, sites)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(MR.strings.yomu_web_series_yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun WebFallback(title: String) {
    val context = LocalContext.current
    val openSearch: (String) -> Unit = { query ->
        val uri = Uri.parse("https://duckduckgo.com/").buildUpon().appendQueryParameter("q", query).build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(MR.strings.yomu_smart_not_found),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text = stringResource(MR.strings.yomu_smart_not_found_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { openSearch("$title manga") }, modifier = Modifier.weight(1f)) {
                Text(stringResource(MR.strings.yomu_smart_web))
            }
            OutlinedButton(onClick = { openSearch("site:t.me $title manga") }, modifier = Modifier.weight(1f)) {
                Text(stringResource(MR.strings.yomu_smart_telegram))
            }
        }
    }
}
