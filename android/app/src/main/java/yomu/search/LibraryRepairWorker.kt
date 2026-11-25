package yomu.search

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import yomu.reader.YomuPreferences
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Fixes a restored library by itself: a series whose source no longer exists moves to another source
 * with the same chapters, keeping what was read, and a cover that won't load is found again.
 */
class LibraryRepairWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var getFavorites: GetFavorites

    @Inject lateinit var getManga: GetManga

    @Inject lateinit var sourceManager: SourceManager

    @Inject lateinit var smartSearch: SmartSearch

    @Inject lateinit var searchPack: SearchPack

    @Inject lateinit var migrateManga: MigrateMangaUseCase

    @Inject lateinit var updateMangaFromRemote: UpdateMangaFromRemote

    @Inject lateinit var updateManga: UpdateManga

    @Inject lateinit var coverCache: CoverCache

    @Inject lateinit var network: NetworkHelper

    @Inject lateinit var titleExpander: TitleExpander

    @Inject lateinit var preferences: YomuPreferences

    override suspend fun doWork(): Result {
        graph.inject(this)
        val deadline = System.currentTimeMillis() + RUN_BUDGET.inWholeMilliseconds
        val tried = preferences.repairTried.get().toMutableSet()
        var unfinished = false

        for (manga in getFavorites.await()) {
            if (System.currentTimeMillis() > deadline) {
                unfinished = true
                break
            }
            if (manga.source == LocalSource.ID || "${manga.id}" in tried) continue
            try {
                if (sourceManager.get(manga.source) == null) {
                    replaceSource(manga)
                } else {
                    repairCover(manga)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Couldn't repair ${manga.title}" }
            }
            // One attempt per series: what can't be fixed now is left for the reader to migrate.
            tried += "${manga.id}"
            preferences.repairTried.set(tried)
        }
        // A worker may only run for minutes, so a large library is repaired over several runs.
        if (unfinished) runOnce(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    private suspend fun replaceSource(manga: Manga) {
        // Wait for the extension installer first: the source may simply not be installed yet.
        if (searchPack.owner(manga.source) != null && searchPack.install(manga.source) != null) return
        val hit = withTimeoutOrNull(SEARCH_BUDGET) { smartSearch.firstMatch(manga.title) } ?: return
        val target = hit.manga.first()
        if (sourceManager.get(target.source) == null && searchPack.install(target.source) == null) return
        migrateManga(manga, getManga.await(target.id) ?: target, replace = true)
        preferences.repairReplaced.set(preferences.repairReplaced.get() + 1)
    }

    private suspend fun repairCover(manga: Manga) {
        if (coverCache.hasCustomCover(manga.id) || coverCache.getCoverFile(manga.thumbnailUrl)?.exists() == true) return
        val source = sourceManager.get(manga.source) as? HttpSource
        if (loads(manga.thumbnailUrl, source)) return

        // Sites move their images, and a backup keeps the old address.
        if (source != null) {
            updateMangaFromRemote(source, manga, fetchDetails = true).getOrNull()
            val refreshed = getManga.await(manga.id)
            if (refreshed != null && refreshed.thumbnailUrl != manga.thumbnailUrl &&
                loads(refreshed.thumbnailUrl, source)
            ) {
                return
            }
        }
        val cover = titleExpander.coverOf(manga.title) ?: return
        network.client.newCall(GET(cover)).await().use { response ->
            if (!response.isSuccessful) return
            coverCache.setCustomCoverToCache(manga, response.body.byteStream())
        }
        updateManga.awaitUpdateCoverLastModified(manga.id)
        preferences.repairCovers.set(preferences.repairCovers.get() + 1)
    }

    private suspend fun loads(url: String?, source: HttpSource?): Boolean {
        if (url.isNullOrBlank() || !url.startsWith("http")) return false
        return try {
            val client = source?.client ?: network.client
            val request = GET(url, source?.headers ?: GET(url).headers)
            withTimeoutOrNull(COVER_TIMEOUT) {
                client.newCall(request).await().use { response ->
                    response.isSuccessful && response.body.contentType()?.type == "image"
                }
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val NAME = "YomuLibraryRepair"
        private val RUN_BUDGET = 8.minutes
        private val SEARCH_BUDGET = 3.minutes
        private val COVER_TIMEOUT = 15.seconds

        fun runOnce(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val request = OneTimeWorkRequestBuilder<LibraryRepairWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            context.workManager.enqueueUniqueWork(NAME, policy, request)
        }
    }
}
