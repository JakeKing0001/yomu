package yomu.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AdaptiveSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderSummarySheet(
    client: RecapClient,
    memory: ReadingMemory,
    mangaId: Long,
    chapterNumber: Double,
    pageIndex: Int,
    catchUp: suspend (onProgress: (Int, Int) -> Unit) -> Unit,
    volumeLabels: suspend () -> Map<String, String>,
    onDismissRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var volumes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) { volumes = volumeLabels() }
    var recap by remember { mutableStateOf<Recap?>(null) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    var progress by remember { mutableStateOf<RecapProgress?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val start: () -> Unit = {
        isLoading = true
        error = null
        progress = null
        scope.launch {
            try {
                catchUp { done, total -> progress = RecapProgress.Reading(done, total) }
                val chapters = memory.chaptersUpTo(mangaId, chapterNumber, pageIndex)
                recap = client.recap(mangaId, chapters) { progress = it }
            } catch (e: RecapClient.RecapException) {
                error = e.reason
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Recap failed" }
                error = MR.strings.yomu_summary_error_generic
            } finally {
                isLoading = false
            }
        }
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(
                    text = stringResource(MR.strings.yomu_summary_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = stringResource(
                        MR.strings.yomu_summary_scope,
                        RecapText.chapterLabel(chapterNumber),
                        pageIndex + 1,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val shown = recap
            when {
                shown != null -> RecapContent(shown, volumes)
                isLoading -> RecapWorking(progress)
                else -> {
                    Text(
                        text = stringResource(MR.strings.yomu_summary_privacy),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f),
                    )
                    error?.let {
                        Text(
                            text = stringResource(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = start,
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        Text(stringResource(MR.strings.yomu_summary_generate))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecapWorking(progress: RecapProgress?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        AnimatedContent(
            targetState = progress,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "recapProgress",
        ) { step ->
            Text(
                text = when (step) {
                    is RecapProgress.Reading ->
                        stringResource(MR.strings.yomu_summary_progress_reading, step.done, step.total)
                    is RecapProgress.Picking ->
                        stringResource(MR.strings.yomu_summary_progress_picking, step.done, step.total)
                    RecapProgress.Writing -> stringResource(MR.strings.yomu_summary_progress_writing)
                    RecapProgress.Checking -> stringResource(MR.strings.yomu_summary_progress_checking)
                    is RecapProgress.Waiting -> stringResource(MR.strings.yomu_summary_progress_waiting, step.seconds)
                    null -> stringResource(MR.strings.yomu_summary_progress_writing)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecapContent(recap: Recap, volumes: Map<String, String>) {
    val colors = MaterialTheme.colorScheme

    if (recap.chapters.isEmpty()) {
        Text(
            text = stringResource(MR.strings.yomu_summary_only_moments),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface.copy(alpha = 0.84f),
        )
    }

    Column {
        recap.chapters.forEachIndexed { index, chapter ->
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Column(
                    modifier = Modifier
                        .width(18.dp)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(colors.primary),
                    )
                    if (index < recap.chapters.lastIndex) {
                        Box(
                            modifier = Modifier
                                .padding(vertical = 3.dp)
                                .width(1.5.dp)
                                .weight(1f)
                                .background(colors.primary.copy(alpha = 0.34f)),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.padding(bottom = 14.dp)) {
                    Text(
                        text = volumes[RecapText.chapterLabel(chapter.number)]?.let { volume ->
                            stringResource(
                                MR.strings.yomu_summary_volume_chapter,
                                volume,
                                RecapText.chapterLabel(chapter.number),
                            )
                        } ?: stringResource(MR.strings.yomu_summary_chapter, RecapText.chapterLabel(chapter.number)),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = colors.primary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = chapter.sentences.joinToString(" "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurface,
                    )
                }
            }
        }
    }

    if (recap.characters.isNotEmpty()) {
        SectionLabel(stringResource(MR.strings.yomu_summary_characters))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            recap.characters.forEach { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.surfaceContainerHighest)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (recap.lastLines.isNotEmpty()) {
        SectionLabel(stringResource(MR.strings.yomu_summary_left_off))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceContainerHigh),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(colors.primary),
            )
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                recap.lastLines.forEach { line ->
                    Text(
                        text = "«$line»",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = colors.onSurface.copy(alpha = 0.92f),
                    )
                }
            }
        }
    }

    if (recap.chapters.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Check,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (recap.removed > 0) {
                    stringResource(MR.strings.yomu_summary_checked_removed, recap.removed)
                } else {
                    stringResource(MR.strings.yomu_summary_checked)
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface.copy(alpha = 0.76f),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
    )
}
