package yomu.search

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
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.util.system.logcat
import yomu.reader.YomuPreferences
import java.util.concurrent.TimeUnit

/** Keeps the search sources up to date. The first download is about 60 MB, later ones only what changed. */
class SearchPackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var searchPack: SearchPack

    @Inject lateinit var preferences: YomuPreferences

    override suspend fun doWork(): Result {
        graph.inject(this)
        if (!preferences.searchPack.get()) return Result.success()
        return try {
            searchPack.refresh()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Search sources update failed" }
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "YomuSearchPack"
        private const val ONCE = "YomuSearchPackNow"

        private val constraints = Constraints(
            requiredNetworkType = NetworkType.CONNECTED,
            requiresBatteryNotLow = true,
            requiresStorageNotLow = true,
        )

        fun schedule(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<SearchPackWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            context.workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SearchPackWorker>().setConstraints(constraints).build()
            context.workManager.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            context.workManager.cancelUniqueWork(PERIODIC)
            context.workManager.cancelUniqueWork(ONCE)
        }
    }
}
