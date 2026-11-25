package yomu.update

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

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var updater: YomuUpdater

    @Inject lateinit var preferences: YomuPreferences

    override suspend fun doWork(): Result {
        graph.inject(this)
        if (!preferences.autoUpdate.get()) return Result.success()
        return try {
            val release = updater.newer() ?: return Result.success()
            val apk = updater.download(release)
            // Installing replaces the running app, so it waits until the reader has left it.
            if (!updater.isInForeground()) updater.install(apk)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Update check failed" }
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "YomuUpdate"
        private const val ONCE = "YomuUpdateNow"
        private val constraints = Constraints(requiredNetworkType = NetworkType.CONNECTED)

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(
                3,
                TimeUnit.HOURS,
            ).setConstraints(constraints).build()
            context.workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(constraints).build()
            context.workManager.enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
