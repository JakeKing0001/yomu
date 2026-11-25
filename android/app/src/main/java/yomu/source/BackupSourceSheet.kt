package yomu.source

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import yomu.presentation.glass.groupedCard

@Composable
fun BackupSourceSheet(
    finder: BackupSourceFinder,
    mangaId: Long,
    chapterId: Long,
    isChapterBroken: Boolean,
    onOpen: (BackupHit) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var isSearching by remember { mutableStateOf(true) }
    var currentSource by remember { mutableStateOf<String?>(null) }
    var hit by remember { mutableStateOf<BackupHit?>(null) }
    var suggestions by remember { mutableStateOf<List<Extension.Available>>(emptyList()) }
    var isMoving by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        isSearching = true
        hit = finder.find(mangaId, chapterId) { currentSource = it }
        if (hit == null) suggestions = finder.installable(mangaId)
        isSearching = false
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(MR.strings.yomu_backup_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            if (isChapterBroken) {
                Text(
                    text = stringResource(MR.strings.yomu_backup_broken),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val found = hit
            when {
                isSearching -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = currentSource?.let { stringResource(MR.strings.yomu_backup_searching, it) }
                            ?: stringResource(MR.strings.yomu_backup_searching_start),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                found != null -> {
                    Text(
                        text = stringResource(MR.strings.yomu_backup_found, found.sourceName),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { onOpen(found) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(MR.strings.yomu_backup_open))
                    }
                    OutlinedButton(
                        enabled = !isMoving,
                        onClick = {
                            isMoving = true
                            scope.launch {
                                finder.moveSeries(mangaId, found)
                                onOpen(found)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(MR.strings.yomu_backup_move))
                    }
                }
                else -> {
                    Text(
                        text = stringResource(
                            if (suggestions.isEmpty()) {
                                MR.strings.yomu_backup_none
                            } else {
                                MR.strings.yomu_backup_try_new
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    suggestions.forEachIndexed { index, extension ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .groupedCard(first = index == 0, last = index == suggestions.lastIndex)
                                .clickable(enabled = installing == null) {
                                    installing = extension.pkgName
                                    scope.launch {
                                        finder.install(extension)
                                        installing = null
                                        attempt++
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = extension.name.removePrefix("Tachiyomi: "),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (installing == extension.pkgName) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text(
                                    text = stringResource(MR.strings.yomu_backup_install),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
