package yomu.volumes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.manga.ChapterList
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import yomu.reader.YomuPreferences

@Stable
class VolumeListState(private val preferences: YomuPreferences) {
    var isVolumeView by mutableStateOf(preferences.volumeView.get())
        private set
    var labels by mutableStateOf<Map<Long, String>>(emptyMap())
    var isLoading by mutableStateOf(false)
    var hasLoaded by mutableStateOf(false)

    /** Survives a chapter list refresh, which would otherwise close what the reader opened. */
    private val opened = mutableStateMapOf<String, Boolean>()

    val isShowingVolumes: Boolean get() = isVolumeView && labels.isNotEmpty()

    fun showVolumes(show: Boolean) {
        isVolumeView = show
        preferences.volumeView.set(show)
    }

    /** Follows the same option changed from Yomu's features while this screen stays in the back stack. */
    suspend fun followPreference() {
        preferences.volumeView.changes().collect { isVolumeView = it }
    }

    fun groups(items: List<ChapterList.Item>): List<VolumeGroup<ChapterList.Item>> {
        return VolumeParser.group(items) { labels[it.id] }
    }

    fun isOpen(group: VolumeGroup<ChapterList.Item>, groups: List<VolumeGroup<ChapterList.Item>>): Boolean {
        return opened[keyOf(group)] ?: (group === defaultOpen(groups))
    }

    fun toggle(group: VolumeGroup<ChapterList.Item>, groups: List<VolumeGroup<ChapterList.Item>>) {
        opened[keyOf(group)] = !isOpen(group, groups)
    }

    /** The volume holding the next chapter to read. */
    private fun defaultOpen(groups: List<VolumeGroup<ChapterList.Item>>): VolumeGroup<ChapterList.Item>? {
        val next = groups.flatMap { it.items }.filter { !it.chapter.read }.minByOrNull { it.chapter.chapterNumber }
        return groups.firstOrNull { next in it.items } ?: groups.firstOrNull()
    }

    fun keyOf(group: VolumeGroup<*>): String = group.label ?: NO_VOLUME

    private companion object {
        const val NO_VOLUME = "none"
    }
}

@Composable
fun rememberVolumeListState(manga: Manga, items: List<ChapterList.Item>): VolumeListState {
    val graph: AppGraph = LocalContext.current.metroGraph()
    val state = remember(manga.id) { VolumeListState(graph.yomuPreferences) }
    val chapterIds = remember(items) { items.map { it.id } }
    LaunchedEffect(state) { state.followPreference() }
    LaunchedEffect(manga.id, chapterIds, state.isVolumeView) {
        if (!state.isVolumeView || items.isEmpty()) return@LaunchedEffect
        state.isLoading = true
        state.labels = graph.volumeRepository.labels(manga, items.map { it.chapter })
        state.isLoading = false
        state.hasLoaded = true
    }
    return state
}

@Composable
fun ChapterViewToggle(state: VolumeListState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
    ) {
        ToggleLabel(stringResource(MR.strings.chapters), !state.isVolumeView) { state.showVolumes(false) }
        ToggleLabel(stringResource(MR.strings.yomu_volumes), state.isVolumeView) { state.showVolumes(true) }
    }
}

@Composable
private fun ToggleLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun VolumeStatus(state: VolumeListState, modifier: Modifier = Modifier) {
    when {
        !state.isVolumeView -> Unit
        state.isLoading -> Row(
            modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(MR.strings.yomu_volumes_loading), style = MaterialTheme.typography.bodySmall)
        }
        state.hasLoaded && state.labels.isEmpty() -> Text(
            text = stringResource(MR.strings.yomu_volumes_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun VolumeHeader(
    group: VolumeGroup<ChapterList.Item>,
    isOpen: Boolean,
    onToggle: () -> Unit,
    onDownload: (() -> Unit)?,
    onMarkRead: (Boolean) -> Unit,
) {
    val read = group.items.count { it.chapter.read }
    val total = group.items.size
    val downloaded = group.items.count { it.isDownloaded }
    val rotation by animateFloatAsState(if (isOpen) 180f else 0f, label = "volumeArrow")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.label?.let { label ->
                        if (label.first().isDigit()) stringResource(MR.strings.yomu_volume_number, label) else label
                    } ?: stringResource(MR.strings.yomu_volume_none),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (downloaded > 0) {
                        stringResource(MR.strings.yomu_volume_progress_downloaded, read, total, downloaded)
                    } else {
                        stringResource(MR.strings.yomu_volume_progress, read, total)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDownload != null && downloaded < total) {
                VolumeAction(
                    icon = { Icon(MaterialSymbols.Rounded.Download, stringResource(MR.strings.yomu_volume_download)) },
                    onClick = onDownload,
                )
            }
            VolumeAction(
                icon = {
                    Icon(
                        MaterialSymbols.Rounded.Check,
                        stringResource(
                            if (read == total) MR.strings.action_mark_as_unread else MR.strings.action_mark_as_read,
                        ),
                        tint = if (read == total) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                onClick = { onMarkRead(read != total) },
            )
            Icon(
                MaterialSymbols.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { read / total.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 10.dp)
                .height(3.dp),
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
    }
}

@Composable
private fun VolumeAction(icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
    }
}
