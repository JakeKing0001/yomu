package yomu.sync

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
import java.util.concurrent.TimeUnit

class LibrarySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var librarySync: LibrarySync

    override suspend fun doWork(): Result {
        graph.inject(this)
        return try {
            librarySync.sync()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Library sync failed" }
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val PERIODIC = "YomuLibrarySync"
        private const val ONCE = "YomuLibrarySyncNow"
        private const val MAX_RETRIES = 3
        private val constraints = Constraints(requiredNetworkType = NetworkType.CONNECTED)

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            context.workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>().setConstraints(constraints).build()
            context.workManager.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            context.workManager.cancelUniqueWork(PERIODIC)
        }
    }
}
