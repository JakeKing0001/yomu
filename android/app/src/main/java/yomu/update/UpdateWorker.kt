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
import androidx.work.workDataOf
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
        val now = inputData.getBoolean(KEY_NOW, false)
        if (!now && !preferences.autoUpdate.get()) return Result.success()
        return try {
            val apk = updater.prepare() ?: return Result.success()
            // Installing replaces the running app, so it waits until the reader has left it, unless asked.
            if (now || !updater.isInForeground()) updater.install(apk)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Update check failed" }
            updater.failed()
            if (now) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "YomuUpdate"
        private const val ONCE = "YomuUpdateNow"
        private const val KEY_NOW = "now"
        private val constraints = Constraints(requiredNetworkType = NetworkType.CONNECTED)

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(
                3,
                TimeUnit.HOURS,
            ).setConstraints(constraints).build()
            context.workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * A check already running is kept: replacing it would start a large download over each time the
         * app is left. [now] installs as soon as it's downloaded, even with the app open.
         */
        fun runOnce(context: Context, now: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_NOW to now))
                .build()
            val policy = if (now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            context.workManager.enqueueUniqueWork(ONCE, policy, request)
        }
    }
}
