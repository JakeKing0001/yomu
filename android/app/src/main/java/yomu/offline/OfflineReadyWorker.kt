package yomu.offline

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.util.system.workManager
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.GetFavorites
import yomu.reader.YomuPreferences
import java.util.concurrent.TimeUnit

/** On Wi-Fi, keeps the next chapters of the series being followed ready to read offline. */
class OfflineReadyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var preferences: YomuPreferences

    @Inject lateinit var getFavorites: GetFavorites

    @Inject lateinit var getHistory: GetHistory

    @Inject lateinit var getNextChapters: GetNextChapters

    @Inject lateinit var downloadManager: DownloadManager

    override suspend fun doWork(): Result {
        graph.inject(this)
        if (!preferences.offlineReady.get()) return Result.success()

        val count = preferences.offlineChapters.get()
        val oldestRead = System.currentTimeMillis() - ACTIVE_WINDOW_MS
        val queued = downloadManager.getQueuedDownloadsByChapterId().keys
        getFavorites.await().forEach { manga ->
            val lastRead = getHistory.await(manga.id).mapNotNull { it.readAt?.toEpochMilliseconds() }.maxOrNull()
            if (lastRead == null || lastRead < oldestRead) return@forEach

            val next = getNextChapters.await(manga.id, onlyUnread = true)
            val downloaded = downloadManager.getDownloadedChapterIds(next, manga)
            val missing = chaptersToFetch(next, downloaded, queued, count)
            if (missing.isNotEmpty()) downloadManager.downloadChapters(manga, missing)
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "YomuOfflineReady"
        private const val ONCE = "YomuOfflineReadyNow"
        private const val ACTIVE_WINDOW_MS = 60L * 24 * 60 * 60 * 1000

        private val constraints = Constraints(
            requiredNetworkType = NetworkType.UNMETERED,
            requiresBatteryNotLow = true,
            requiresStorageNotLow = true,
        )

        /** Already-downloaded chapters count toward the target, so finishing one fetches just one more. */
        fun chaptersToFetch(
            unread: List<Chapter>,
            downloaded: Set<Long>,
            queued: Set<Long>,
            count: Int,
        ): List<Chapter> {
            return unread.take(count).filter { it.id !in downloaded && it.id !in queued }
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OfflineReadyWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            context.workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<OfflineReadyWorker>().setConstraints(constraints).build()
            context.workManager.enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            context.workManager.cancelUniqueWork(PERIODIC)
            context.workManager.cancelUniqueWork(ONCE)
        }
    }
}
