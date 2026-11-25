package yomu.search

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import kotlin.time.Duration.Companion.seconds

/** Installs the extensions a restored library needs, such as one coming from Mihon, without asking for each. */
class MissingSourcesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var getFavorites: GetFavorites

    @Inject lateinit var sourceManager: SourceManager

    @Inject lateinit var extensionManager: ExtensionManager

    @Inject lateinit var sourcePreferences: SourcePreferences

    @Inject lateinit var searchPack: SearchPack

    override suspend fun doWork(): Result {
        graph.inject(this)
        val missing = getFavorites.await().map { it.source }.toSet()
            .filter { it != LocalSource.ID && sourceManager.get(it) == null }
            .toMutableSet()
        if (missing.isEmpty()) return repaired()

        // Search-only copies install without a download.
        missing.toList().forEach { id -> if (searchPack.install(id) != null) missing -= id }
        if (missing.isEmpty()) return repaired()

        extensionManager.findAvailableExtensions()
        val available = withTimeoutOrNull(LIST_TIMEOUT) {
            extensionManager.availableExtensionsFlow.first { it.isNotEmpty() }
        }.orEmpty()
        available.filter { extension -> extension.sources.any { it.id in missing } }
            .distinctBy { it.pkgName }
            .forEach { extension ->
                if (extension.contentWarning !in sourcePreferences.enabledContentWarnings.get()) {
                    sourcePreferences.enabledContentWarnings.getAndSet { it + extension.contentWarning }
                }
                val result = extensionManager.installExtension(extension).first(InstallStep::isCompleted)
                if (result == InstallStep.Installed) {
                    val languages = extension.sources.filter { it.id in missing }.map { it.lang }
                    sourcePreferences.enabledLanguages.getAndSet { it + languages }
                }
            }
        return repaired()
    }

    /** What's still missing now has no extension at all: the repair finds those series elsewhere. */
    private fun repaired(): Result {
        LibraryRepairWorker.runOnce(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "YomuMissingSources"
        private val LIST_TIMEOUT = 30.seconds

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<MissingSourcesWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            context.workManager.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
