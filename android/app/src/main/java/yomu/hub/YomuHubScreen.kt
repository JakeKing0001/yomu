package yomu.hub

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.PanelsRoute
import mihon.core.navigation.YearRoute
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import yomu.offline.OfflineReadyWorker
import yomu.presentation.glass.groupedCard
import yomu.reader.YomuIcons
import yomu.reader.YomuPreferences
import yomu.search.LibraryRepairWorker
import yomu.search.SearchPack
import yomu.search.SearchPackWorker
import yomu.sync.LibrarySync
import yomu.sync.LibrarySyncWorker
import yomu.update.UpdateWorker
import yomu.update.YomuUpdater

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class YomuHubViewModel(
    val preferences: YomuPreferences,
    private val librarySync: LibrarySync,
    searchPack: SearchPack,
    val updater: YomuUpdater,
) : ViewModel() {

    val packStatus = searchPack.status

    var isSyncing by mutableStateOf(false)
        private set

    fun syncNow(onResult: (StringResource) -> Unit) {
        if (isSyncing) return
        isSyncing = true
        viewModelScope.launch {
            val message = try {
                librarySync.sync()
                MR.strings.yomu_sync_done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Sync failed" }
                MR.strings.yomu_sync_error
            }
            isSyncing = false
            onResult(message)
        }
    }
}

@Composable
fun YomuHubScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val viewModel = metroViewModel<YomuHubViewModel>()
    val preferences = viewModel.preferences

    val volumeView by preferences.volumeView.changes().collectAsState(preferences.volumeView.get())
    val welcomeBack by preferences.welcomeBack.changes().collectAsState(preferences.welcomeBack.get())
    val offlineReady by preferences.offlineReady.changes().collectAsState(preferences.offlineReady.get())
    val offlineChapters by preferences.offlineChapters.changes().collectAsState(preferences.offlineChapters.get())
    val syncFile by preferences.syncFile.changes().collectAsState(preferences.syncFile.get())
    val syncLastAt by preferences.syncLastAt.changes().collectAsState(preferences.syncLastAt.get())
    val searchPackOn by preferences.searchPack.changes().collectAsState(preferences.searchPack.get())
    val pack by viewModel.packStatus.collectAsState()
    val autoUpdate by preferences.autoUpdate.changes().collectAsState(preferences.autoUpdate.get())
    val replaced by preferences.repairReplaced.changes().collectAsState(preferences.repairReplaced.get())
    val covers by preferences.repairCovers.changes().collectAsState(preferences.repairCovers.get())

    val useSyncFile: (Uri?) -> Unit = useSyncFile@{ uri ->
        if (uri == null) return@useSyncFile
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        preferences.syncFile.set(uri.toString())
        preferences.syncKnown.set(emptySet())
        LibrarySyncWorker.schedule(context)
        viewModel.syncNow { context.toast(it) }
    }
    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
        useSyncFile,
    )
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), useSyncFile)

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.yomu_hub_title),
                navigateUp = navigator::pop,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(vertical = 8.dp),
        ) {
            HubLink(
                YomuIcons.Bookmark,
                stringResource(MR.strings.yomu_panels_title),
                stringResource(MR.strings.yomu_panels_summary),
                first = true,
            ) { navigator.push(PanelsRoute) }
            HubLink(
                YomuIcons.Year,
                stringResource(MR.strings.yomu_year_title),
                stringResource(MR.strings.yomu_year_summary),
                last = true,
            ) { navigator.push(YearRoute) }

            Spacer(Modifier.height(16.dp))
            HubSwitch(
                YomuIcons.Volumes,
                stringResource(MR.strings.yomu_hub_volumes),
                stringResource(MR.strings.yomu_hub_volumes_summary),
                volumeView,
                first = true,
            ) { preferences.volumeView.set(it) }
            HubSwitch(
                YomuIcons.Memory,
                stringResource(MR.strings.yomu_hub_welcome),
                stringResource(MR.strings.yomu_hub_welcome_summary),
                welcomeBack,
                last = true,
            ) { preferences.welcomeBack.set(it) }

            Spacer(Modifier.height(16.dp))
            HubSwitch(
                YomuIcons.Offline,
                stringResource(MR.strings.yomu_offline_title),
                stringResource(MR.strings.yomu_offline_summary, offlineChapters),
                offlineReady,
                first = true,
                last = !offlineReady,
            ) { enabled ->
                preferences.offlineReady.set(enabled)
                if (enabled) {
                    OfflineReadyWorker.schedule(context)
                    OfflineReadyWorker.runOnce(context)
                } else {
                    OfflineReadyWorker.cancel(context)
                }
            }
            if (offlineReady) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .groupedCard(first = false, last = true)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(MR.strings.yomu_offline_count),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1, 3, 5, 10).forEach { count ->
                            val selected = count == offlineChapters
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                ),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .clickable {
                                        preferences.offlineChapters.set(count)
                                        OfflineReadyWorker.runOnce(context)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HubSwitch(
                YomuIcons.Swap,
                stringResource(MR.strings.yomu_pack_title),
                when {
                    !searchPackOn -> stringResource(MR.strings.yomu_pack_off)
                    pack.isUpdating -> stringResource(MR.strings.yomu_pack_updating, pack.ready, pack.wanted)
                    pack.ready == 0 -> stringResource(MR.strings.yomu_pack_waiting)
                    else -> stringResource(MR.strings.yomu_pack_ready, pack.ready, pack.bytes / (1024 * 1024))
                },
                searchPackOn,
                first = true,
                last = false,
            ) { enabled ->
                preferences.searchPack.set(enabled)
                if (enabled) {
                    SearchPackWorker.schedule(context)
                    SearchPackWorker.runOnce(context)
                } else {
                    SearchPackWorker.cancel(context)
                }
            }

            HubLink(
                YomuIcons.Studio,
                stringResource(MR.strings.yomu_repair_title),
                stringResource(MR.strings.yomu_repair_summary, replaced, covers),
                last = true,
            ) {
                preferences.repairTried.set(emptySet())
                LibraryRepairWorker.runOnce(context, androidx.work.ExistingWorkPolicy.REPLACE)
                context.toast(MR.strings.yomu_repair_started)
            }

            Spacer(Modifier.height(16.dp))
            HubSwitch(
                YomuIcons.Offline,
                stringResource(MR.strings.yomu_update_title),
                stringResource(MR.strings.yomu_update_summary, viewModel.updater.installedVersion),
                autoUpdate,
                first = true,
                last = true,
            ) { enabled ->
                preferences.autoUpdate.set(enabled)
                if (enabled) {
                    UpdateWorker.schedule(context)
                    UpdateWorker.runOnce(context)
                }
            }

            Spacer(Modifier.height(16.dp))
            if (syncFile.isEmpty()) {
                HubLink(
                    YomuIcons.Cloud,
                    stringResource(MR.strings.yomu_sync_create),
                    stringResource(MR.strings.yomu_sync_create_summary),
                    first = true,
                ) { createFile.launch("yomu-sync.json") }
                HubLink(
                    YomuIcons.Swap,
                    stringResource(MR.strings.yomu_sync_open),
                    stringResource(MR.strings.yomu_sync_open_summary),
                    last = true,
                ) { openFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .groupedCard(first = true, last = false)
                        .clickable { viewModel.syncNow { context.toast(it) } }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(YomuIcons.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(MR.strings.yomu_sync_now),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            text = if (syncLastAt > 0) {
                                stringResource(
                                    MR.strings.yomu_sync_last,
                                    DateUtils.getRelativeTimeSpanString(syncLastAt).toString(),
                                )
                            } else {
                                stringResource(MR.strings.yomu_sync_never)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (viewModel.isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                HubLink(
                    YomuIcons.Stop,
                    stringResource(MR.strings.yomu_sync_disconnect),
                    stringResource(MR.strings.yomu_sync_disconnect_summary),
                    last = true,
                ) {
                    preferences.syncFile.set("")
                    preferences.syncKnown.set(emptySet())
                    LibrarySyncWorker.cancel(context)
                }
            }
        }
    }
}

@Composable
private fun HubLink(
    icon: ImageVector,
    title: String,
    subtitle: String,
    first: Boolean = false,
    last: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .groupedCard(first = first, last = last)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        HubText(title, subtitle, Modifier.weight(1f))
    }
}

@Composable
private fun HubSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    first: Boolean = false,
    last: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .groupedCard(first = first, last = last)
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        HubText(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HubText(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
        )
    }
}
