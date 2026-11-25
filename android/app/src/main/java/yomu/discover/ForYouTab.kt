package yomu.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.manga.components.MangaCover
import mihon.core.navigation.DiscoverRoute
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.MangaRoute
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import yomu.reader.YomuIcons

private val CoverShape = RoundedCornerShape(12.dp)

@Composable
fun forYouTab(): TabContent {
    return TabContent(
        titleRes = MR.strings.yomu_for_you,
        content = { contentPadding, _ -> ForYouContent(contentPadding) },
    )
}

@Composable
private fun ForYouContent(contentPadding: PaddingValues) {
    val navigator = LocalNavigator.current
    val viewModel = metroViewModel<ForYouViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val layoutDirection = LocalLayoutDirection.current
    val isSearching = query.isNotBlank()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection) + 14.dp,
            end = contentPadding.calculateEndPadding(layoutDirection) + 14.dp,
            top = contentPadding.calculateTopPadding() + 6.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
            MemorySearchField(query = query, onChange = viewModel::search)
        }

        if (isSearching) {
            if (state.results.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "noResults") {
                    Text(
                        text = stringResource(MR.strings.yomu_memory_search_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 18.dp),
                    )
                }
            }
            items(state.results, key = { "result-${it.id}" }) { manga ->
                CoverCell(manga = manga, caption = null) { navigator.push(MangaRoute(manga.id, fromSource = true)) }
            }
            return@LazyVerticalGrid
        }

        if (state.recommendations.size >= 3) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "deck") {
                DeckCard(
                    covers = state.recommendations.take(3).map(ForYouEntry::manga),
                    onClick = { navigator.push(DiscoverRoute) },
                )
            }
        }

        if (state.isBuilding) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "building") {
                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(
                        text = stringResource(MR.strings.yomu_for_you_building),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.total > 0) {
                        LinearProgressIndicator(
                            progress = { state.indexed / state.total.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }
        }

        if (state.recommendations.isEmpty() && !state.isBuilding) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                Text(
                    text = stringResource(MR.strings.yomu_for_you_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 18.dp),
                )
            }
        } else if (state.recommendations.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
                Text(
                    text = stringResource(MR.strings.yomu_for_you_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }

        items(state.recommendations, key = { "rec-${it.manga.id}" }) { entry ->
            CoverCell(
                manga = entry.manga,
                caption = entry.because?.let { stringResource(MR.strings.yomu_for_you_because, it) },
            ) { navigator.push(MangaRoute(entry.manga.id, fromSource = true)) }
        }
    }
}

@Composable
private fun MemorySearchField(query: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MaterialSymbols.Rounded.Search, contentDescription = null, tint = colors.primary)
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(MR.strings.yomu_memory_search_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Close,
                contentDescription = stringResource(MR.strings.action_reset),
                tint = colors.onSurface,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onChange("") },
            )
        }
    }
}

@Composable
private fun DeckCard(covers: List<Manga>, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .height(128.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(MR.strings.yomu_deck_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(MR.strings.yomu_deck_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface.copy(alpha = 0.78f),
            )
        }
        Box(modifier = Modifier.size(width = 112.dp, height = 104.dp), contentAlignment = Alignment.Center) {
            covers.asReversed().forEachIndexed { index, manga ->
                val position = covers.lastIndex - index
                MangaCover.Book(
                    data = manga,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(92.dp)
                        .offset(x = (-24).dp * (position - 1))
                        .rotate((position - 1) * -9f),
                )
            }
        }
    }
}

@Composable
private fun CoverCell(manga: Manga, caption: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(CoverShape)
            .clickable(onClick = onClick),
    ) {
        MangaCover.Book(data = manga, shape = CoverShape, modifier = Modifier.fillMaxWidth())
        Text(
            text = manga.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp),
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}
